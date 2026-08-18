<script setup lang="ts">
/**
 * Reef map — where reef condition is read across space and time.
 *
 * The style is defined locally rather than fetched from a style server. Two
 * reasons: the project forbids depending on external services, and a style that
 * fails to load would stop MapLibre firing `load`, which would silently prevent
 * our own data layers from ever being added. With a local style the sighting
 * data always renders, tiles or no tiles.
 *
 * There are also no text layers on the canvas. Symbol layers need a glyph server,
 * which would be another external dependency; counts are shown on hover instead.
 *
 * Clustering happens in SQL, so the payload stays small no matter how much data
 * exists: a national view of 10,000 sightings is around 30 features (NFR3).
 */
import { onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { FeatureCollection, Point as GeoJSONPoint } from 'geojson'
import maplibregl, {
  type LngLatBoundsLike,
  type Map as MapLibreMap,
  type StyleSpecification,
} from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'

import { ApiError, api, type MapPoint, type TrendBucket } from '@/lib/api'

const container = ref<HTMLDivElement | null>(null)
const map = shallowRef<MapLibreMap | null>(null)

const points = ref<MapPoint[]>([])
const clustered = ref(false)
const buckets = ref<TrendBucket[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const verifiedOnly = ref(false)
const from = ref('')
const to = ref('')

// The whole archipelago, so the first view answers "how are the reefs?".
const MALDIVES_BOUNDS: LngLatBoundsLike = [
  [71.6, -1.4],
  [74.4, 7.6],
]

const GROUND = '#061a23'

/**
 * Optional raster basemap. Empty by default: the map is self-contained and works
 * with no network at all, which also makes the demo demo safe. Set
 * VITE_MAP_TILE_URL to an XYZ template to lay tiles underneath the data.
 */
const TILE_URL = import.meta.env.VITE_MAP_TILE_URL ?? ''
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION ?? ''

function buildStyle(): StyleSpecification {
  const style: StyleSpecification = {
    version: 8,
    sources: {},
    layers: [{ id: 'ground', type: 'background', paint: { 'background-color': GROUND } }],
  }

  if (TILE_URL) {
    style.sources.basemap = {
      type: 'raster',
      tiles: [TILE_URL],
      tileSize: 256,
      attribution: TILE_ATTRIBUTION,
    }
    style.layers.push({
      id: 'basemap',
      type: 'raster',
      source: 'basemap',
      // Desaturated and dimmed so the condition colours stay the loudest thing.
      paint: { 'raster-opacity': 0.5, 'raster-saturation': -0.4, 'raster-brightness-max': 0.85 },
    })
  }
  return style
}

// Severity ramp: living tissue -> pale -> bone. The progression mirrors the
// phenomenon, so the legend needs no explaining to a marine scientist.
// -1 marks "not yet assessed" and paints neutral grey.
const severityColour: maplibregl.ExpressionSpecification = [
  'case',
  ['<', ['get', 'severity'], 0],
  '#5c7b87',
  [
    'interpolate',
    ['linear'],
    ['get', 'severity'],
    0,
    '#35c79a',
    0.35,
    '#9fd9c4',
    0.7,
    '#efe6d8',
    1,
    '#ffffff',
  ],
]

function toGeoJSON(items: MapPoint[]): FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: items.map((p) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
      properties: {
        count: p.count,
        severity: p.avgSeverity ?? -1,
        sightingId: p.sightingId ?? '',
      },
    })),
  }
}

function addLayers(instance: MapLibreMap) {
  instance.addSource('sightings', { type: 'geojson', data: toGeoJSON([]) })

  instance.addLayer({
    id: 'sightings-halo',
    type: 'circle',
    source: 'sightings',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['get', 'count'], 1, 8, 50, 22, 500, 38],
      'circle-color': severityColour,
      'circle-opacity': 0.2,
      'circle-blur': 0.7,
    },
  })

  instance.addLayer({
    id: 'sightings-core',
    type: 'circle',
    source: 'sightings',
    paint: {
      // Area grows with the number of sightings the marker stands for.
      'circle-radius': ['interpolate', ['linear'], ['get', 'count'], 1, 4, 50, 9, 500, 15],
      'circle-color': severityColour,
      'circle-stroke-width': 1,
      'circle-stroke-color': GROUND,
    },
  })
}

async function loadData() {
  const instance = map.value
  if (!instance) return

  loading.value = true
  error.value = null
  try {
    const bounds = instance.getBounds()
    const zoom = Math.round(instance.getZoom())
    const filters = {
      bbox: [
        bounds.getWest().toFixed(4),
        bounds.getSouth().toFixed(4),
        bounds.getEast().toFixed(4),
        bounds.getNorth().toFixed(4),
      ].join(','),
      zoom,
      verified: verifiedOnly.value || undefined,
      from: from.value || undefined,
      to: to.value || undefined,
    }

    const [mapResult, trendResult] = await Promise.all([
      api.mapPoints(filters),
      api.trends({ ...filters, bucket: 'month' }),
    ])

    points.value = mapResult.points
    clustered.value = mapResult.clustered
    buckets.value = trendResult.buckets

    const source = instance.getSource('sightings') as maplibregl.GeoJSONSource | undefined
    source?.setData(toGeoJSON(mapResult.points))
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not load map data.'
  } finally {
    loading.value = false
  }
}

let hoverPopup: maplibregl.Popup | null = null
// Tracks which feature the popup is describing, so it is rebuilt only when the
// cursor moves to a different marker rather than on every mousemove.
let hoveredKey = ''
let reloadTimer: number | undefined

// Panning fires moveend per gesture; coalesce bursts into one request.
function scheduleReload() {
  window.clearTimeout(reloadTimer)
  reloadTimer = window.setTimeout(() => void loadData(), 250)
}

onMounted(() => {
  if (!container.value) return

  const instance = new maplibregl.Map({
    container: container.value,
    style: buildStyle(),
    bounds: MALDIVES_BOUNDS,
    fitBoundsOptions: { padding: 56 },
    attributionControl: TILE_URL ? undefined : false,
  })
  map.value = instance

  instance.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
  instance.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-right')

  const start = () => {
    addLayers(instance)
    void loadData()
  }
  // `loaded()` covers the case where the style — which is local, so it resolves
  // synchronously — finished before this handler was attached. Without it the
  // data layers would never be added and the map would sit empty.
  if (instance.loaded()) {
    start()
  } else {
    instance.once('load', start)
  }

  instance.on('moveend', scheduleReload)

  // Counts live in a popup rather than a symbol layer, which would need a glyph
  // server — one more external dependency the project does not allow.
  instance.on('mousemove', 'sightings-core', (event) => {
    const feature = event.features?.[0]
    if (!feature) return

    const count = Number(feature.properties?.count ?? 1)
    const severity = Number(feature.properties?.severity ?? -1)
    const [lon, lat] = (feature.geometry as GeoJSONPoint).coordinates

    // Rebuilding the popup on every mousemove is wasted work, and re-adding it
    // under the cursor can fight with the layer's own hover detection.
    const key = `${lon},${lat},${count}`
    if (key === hoveredKey) return
    hoveredKey = key

    const extent =
      severity < 0 ? 'not yet assessed' : `${Math.round(severity * 100)}% bleached extent`
    const heading = count > 1 ? `${count} sightings` : '1 sighting'
    const hint = count > 1 ? 'Click to zoom in' : 'Click to open the record'

    instance.getCanvas().style.cursor = 'pointer'
    hoverPopup ??= new maplibregl.Popup({
      closeButton: false,
      closeOnClick: false,
      offset: 14,
      // The popup must never receive pointer events: anchored under the cursor
      // it would steal hover from the markers and flicker on and off.
      className: 'map-hover-popup',
    })
    hoverPopup
      .setLngLat([lon, lat])
      .setHTML(
        `<span class="popup-count">${heading}</span>` +
          `<span class="popup-extent">${extent}</span>` +
          `<span class="popup-hint">${hint}</span>`,
      )
      .addTo(instance)
  })

  instance.on('mouseleave', 'sightings-core', () => {
    instance.getCanvas().style.cursor = ''
    hoveredKey = ''
    hoverPopup?.remove()
  })

  instance.on('click', 'sightings-core', (event) => {
    const feature = event.features?.[0]
    const id = String(feature?.properties?.sightingId ?? '')
    // Clusters have no single record to open; zoom in to resolve them instead.
    if (!id) {
      instance.easeTo({ center: event.lngLat, zoom: Math.min(instance.getZoom() + 2, 14) })
      return
    }
    window.location.assign(`/sightings/${id}`)
  })
})

onUnmounted(() => {
  window.clearTimeout(reloadTimer)
  hoverPopup?.remove()
  map.value?.remove()
  map.value = null
})

watch([verifiedOnly, from, to], () => void loadData())

const totalInView = () => points.value.reduce((sum, p) => sum + p.count, 0)
const trendMax = () => Math.max(1, ...buckets.value.map((b) => b.total))
</script>

<template>
  <div class="map-page">
    <div ref="container" class="canvas" />

    <header class="overlay head panel">
      <span class="eyebrow">Reef map / {{ totalInView() }} sightings in view</span>
      <h1>Reef condition</h1>
      <p class="note">
        {{
          clustered
            ? 'Grouped by area at this zoom. Click a group to zoom in.'
            : 'Each marker is one sighting. Click to open its record.'
        }}
      </p>

      <div class="filters">
        <label class="toggle">
          <input v-model="verifiedOnly" type="checkbox" />
          Expert-verified only
        </label>
        <div class="dates">
          <label class="date">
            <span class="eyebrow">From</span>
            <input v-model="from" type="date" />
          </label>
          <label class="date">
            <span class="eyebrow">To</span>
            <input v-model="to" type="date" />
          </label>
        </div>
      </div>

      <p v-if="error" class="status error" role="alert">{{ error }}</p>
      <p v-else-if="loading" class="status readout">Loading…</p>
    </header>

    <aside class="overlay legend panel">
      <span class="eyebrow">Bleached extent</span>
      <div class="ramp">
        <span
          class="ramp-bar"
          role="img"
          aria-label="Colour scale from healthy coral tissue to fully bleached"
        />
        <div class="ramp-labels readout">
          <span>0%</span>
          <span>50%</span>
          <span>100%</span>
        </div>
      </div>

      <template v-if="buckets.length">
        <span class="eyebrow trend-title">Sightings by month</span>
        <div class="trend" role="img" aria-label="Monthly sighting volume and bleached share">
          <span
            v-for="bucket in buckets"
            :key="bucket.bucket"
            class="bar"
            :style="{
              height: `${(bucket.total / trendMax()) * 100}%`,
              '--share': bucket.total ? bucket.bleached / bucket.total : 0,
            }"
            :title="`${new Date(bucket.bucket).toLocaleDateString(undefined, {
              month: 'short',
              year: 'numeric',
            })}: ${bucket.bleached} of ${bucket.total} bleached`"
          />
        </div>
        <div class="trend-axis readout">
          <span>{{ new Date(buckets[0].bucket).getFullYear() }}</span>
          <span>bar height = volume, fill = bleached</span>
        </div>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.map-page {
  position: relative;
  flex: 1;
  min-height: 0;
}

.canvas {
  position: absolute;
  inset: 0;
  background: var(--abyss);
}

.overlay {
  position: absolute;
  z-index: 2;
  box-shadow: var(--shadow-panel);
  backdrop-filter: blur(8px);
  background: color-mix(in srgb, var(--shelf) 88%, transparent);
}

.head {
  top: 1rem;
  left: 1rem;
  width: min(23rem, calc(100% - 2rem));
  padding: 1rem;
  display: grid;
  gap: 0.5rem;
}

.head h1 {
  font-size: var(--step-2);
}

.note {
  color: var(--ink-muted);
  font-size: var(--step--1);
}

.filters {
  display: grid;
  gap: 0.625rem;
  margin-top: 0.375rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--hairline);
}

.toggle {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: var(--step--1);
  color: var(--ink-muted);
}

.dates {
  display: flex;
  gap: 0.75rem;
}

.date {
  display: grid;
  gap: 0.25rem;
}

.date input {
  padding: 0.3125rem 0.375rem;
  border: 1px solid var(--shallow);
  border-radius: var(--radius-sm);
  background: var(--abyss);
  color: var(--ink);
  font: inherit;
  font-size: var(--step--1);
}

.status {
  font-size: var(--step--1);
  color: var(--ink-faint);
}

.status.error {
  color: var(--rust);
}

.legend {
  bottom: 1rem;
  left: 1rem;
  width: min(17rem, calc(100% - 2rem));
  padding: 0.875rem;
  display: grid;
  gap: 0.5rem;
}

.ramp {
  display: grid;
  gap: 0.25rem;
}

.ramp-bar {
  height: 0.5rem;
  border-radius: 2px;
  background: linear-gradient(90deg, #35c79a 0%, #9fd9c4 35%, #efe6d8 70%, #ffffff 100%);
}

.ramp-labels,
.trend-axis {
  display: flex;
  justify-content: space-between;
  font-size: 0.6875rem;
  color: var(--ink-faint);
}

.trend-title {
  margin-top: 0.5rem;
  padding-top: 0.625rem;
  border-top: 1px solid var(--hairline);
}

/* Bar height is volume, fill is the bleached share: one glyph carries both. */
.trend {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 3.25rem;
}

.bar {
  flex: 1;
  min-height: 2px;
  border-radius: 1px;
  background: linear-gradient(
    to top,
    var(--living) 0%,
    var(--living) calc((1 - var(--share)) * 100%),
    var(--bone) calc((1 - var(--share)) * 100%),
    var(--bone) 100%
  );
}

@media (max-width: 44rem) {
  .legend {
    display: none;
  }
}
</style>

<style>
/* Popup content is injected as raw HTML, so it cannot be scoped. */

/* The hover popup is a readout, never a target. Letting it take pointer events
   would break marker hover, because it sits directly under the cursor. */
.map-hover-popup {
  pointer-events: none;
}

.maplibregl-popup-content {
  display: grid;
  gap: 0.125rem;
  padding: 0.5rem 0.625rem;
  background: var(--shelf);
  border: 1px solid var(--shallow);
  border-radius: var(--radius-sm);
  font-family: var(--font-ui);
}

.maplibregl-popup-tip {
  border-top-color: var(--shelf) !important;
  border-bottom-color: var(--shelf) !important;
}

.popup-count {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--ink);
}

.popup-extent {
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  color: var(--ink-muted);
}

.popup-hint {
  font-size: 0.6875rem;
  color: var(--ink-faint);
}
</style>
