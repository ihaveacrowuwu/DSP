<script setup lang="ts">
/**
 * Reef map — where reef condition is read across space and time.
 *
 * The map fills the whole viewport and runs underneath the floating rail, because
 * it is the content rather than a widget on a page: panning should not stop at a
 * panel edge. The controls and the legend float over it as frosted cards, inset
 * far enough from the left to clear the rail.
 *
 * ── The basemap ────────────────────────────────────────────────────────────
 * The geography is a local vector file, `public/basemap/maldives.json`, built by
 * `scripts/build_basemap.py` from Natural Earth 10m (public domain). It carries
 * four kinds of geometry — the 1000 m slope, the shallow atoll platforms, the
 * reef rims, and the islands — plus the twenty administrative atolls as label
 * anchors.
 *
 * This replaced an earlier design where the basemap was an optional raster tile
 * URL, blank by default. The default is what shipped, so in practice the map was
 * markers floating on a flat colour: correctly placed, and impossible to read,
 * because a coordinate means nothing without a coastline next to it. Bundling the
 * geography fixes that without breaking the rule that nothing may depend on a
 * third-party service — the whole country is 67 KB of GeoJSON, needs no tile
 * server, no glyph server and no network, and cannot fail during a demo.
 *
 * A raster basemap is still supported via VITE_MAP_TILE_URL for anyone who wants
 * imagery. When one is configured the local *fills* stand down, because two
 * basemaps stacked is worse than either; the reef rims and islands stay on as
 * annotation over the imagery.
 *
 * Place names are DOM markers, not a symbol layer. Symbol layers need a glyph
 * server — one more external dependency — whereas an absolutely-positioned span
 * gets the app's own typeface for free. There are twenty-one of them, so the cost
 * is nil.
 *
 * ── The markers ────────────────────────────────────────────────────────────
 * Clustering happens in SQL, so the payload stays small no matter how much data
 * exists: a national view of 10,000 sightings is on the order of 150 features
 * (NFR3). Every layer, colour and size expression lives in `lib/mapStyle.ts` —
 * see that file's header for why a paint expression is worth keeping testable.
 */
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { FeatureCollection, Point as GeoJSONPoint } from 'geojson'
import maplibregl, { type Map as MapLibreMap } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'

import CheckBox from '@/components/ui/CheckBox.vue'
import DateRangeField from '@/components/ui/DateRangeField.vue'
import SegmentedTabs from '@/components/ui/SegmentedTabs.vue'
import { useTheme } from '@/composables/useTheme'
import { ApiError, api, type MapPoint, type TrendBucket } from '@/lib/api'
import {
  BASEMAP_URL,
  CORE_LAYER,
  MALDIVES_BOUNDS,
  MAX_ZOOM,
  MIN_ZOOM,
  SEAFLOOR_SOURCE,
  SIGHTINGS_SOURCE,
  baseStyle,
  basemapLayers,
  cssToken,
  repaint,
  sightingLayers,
} from '@/lib/mapStyle'

const theme = useTheme()

const container = ref<HTMLDivElement | null>(null)
const map = shallowRef<MapLibreMap | null>(null)

const points = ref<MapPoint[]>([])
const clustered = ref(false)
const buckets = ref<TrendBucket[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const basemapFailed = ref(false)

const verifiedOnly = ref(false)
const from = ref('')
const to = ref('')
const bucket = ref('month')

const BUCKETS = [
  { value: 'day', label: 'Day' },
  { value: 'week', label: 'Week' },
  { value: 'month', label: 'Month' },
]

/**
 * Optional raster basemap under the sighting data. Empty by default: the bundled
 * vector geography already makes the map readable offline, and imagery is a
 * preference rather than a requirement. Set VITE_MAP_TILE_URL to a keyless XYZ
 * template to lay tiles underneath instead.
 */
const TILE_URL = import.meta.env.VITE_MAP_TILE_URL ?? ''
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION ?? ''

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

/**
 * Atoll and capital names, as DOM markers. See the file header for why these are
 * not a symbol layer.
 *
 * Which of the two labels shows is decided in CSS from a data attribute on the
 * container, so a zoom gesture toggles one attribute instead of touching
 * twenty-one elements.
 */
const labelMarkers: maplibregl.Marker[] = []

function addLabels(instance: MapLibreMap, data: FeatureCollection) {
  for (const feature of data.features) {
    const kind = feature.properties?.kind
    if (kind !== 'atoll' && kind !== 'capital') continue

    const element = document.createElement('span')
    element.className = kind === 'capital' ? 'map-label is-capital' : 'map-label'
    if (kind === 'atoll') {
      // textContent per span rather than innerHTML: these strings come from a
      // fetched file, and a label is never worth an HTML sink.
      const code = document.createElement('span')
      code.className = 'label-code'
      code.textContent = String(feature.properties?.code ?? '')
      const name = document.createElement('span')
      name.className = 'label-name'
      name.textContent = String(feature.properties?.name ?? '')
      element.append(code, name)
    } else {
      element.textContent = String(feature.properties?.name ?? '')
    }

    const [lon, lat] = (feature.geometry as GeoJSONPoint).coordinates
    // Nudged apart vertically. Kaafu's centroid and Malé are barely a kilometre
    // apart, so on the anchor itself the two labels sit on top of each other.
    const offset: [number, number] = kind === 'capital' ? [0, 9] : [0, -9]
    labelMarkers.push(
      new maplibregl.Marker({ element, offset }).setLngLat([lon, lat]).addTo(instance),
    )
  }
}

/**
 * How much of a place name to show, from the zoom.
 *
 * Three states rather than two. `code` is the national view, where twenty full
 * atoll names would overlap into a stripe. `name` is the regional view they were
 * written for. `local` drops them entirely: once the viewport is inside a single
 * atoll, its label is pinned to an arbitrary centroid that may be nowhere near
 * what is on screen, and saying "Kaafu" over one channel of it is noise.
 */
function syncLabelDetail(instance: MapLibreMap) {
  const zoom = instance.getZoom()
  instance.getContainer().dataset.labels = zoom < 6.6 ? 'code' : zoom > 9.5 ? 'local' : 'name'
}

/**
 * Fetches the bundled geography and slots it under the sighting markers.
 *
 * This runs after the sighting layers rather than before, so the markers are on
 * the map the moment the style is ready instead of waiting on a network round
 * trip. The geography is inserted underneath them by naming the halo layer as the
 * insertion point.
 */
async function loadBasemap(instance: MapLibreMap) {
  try {
    const response = await fetch(BASEMAP_URL)
    if (!response.ok) throw new Error(`basemap ${response.status}`)
    const data = (await response.json()) as FeatureCollection

    instance.addSource(SEAFLOOR_SOURCE, { type: 'geojson', data })
    for (const layer of basemapLayers(cssToken, Boolean(TILE_URL))) {
      instance.addLayer(layer, 'sightings-halo')
    }

    addLabels(instance, data)
    syncLabelDetail(instance)
  } catch {
    // Deliberately not surfaced as an error. The geography is context; the
    // sightings are the data, and they are already drawn. A missing basemap is
    // worth a line in the legend, not a banner over the map.
    basemapFailed.value = true
  }
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
      api.trends({ ...filters, bucket: bucket.value as 'day' | 'week' | 'month' }),
    ])

    points.value = mapResult.points
    clustered.value = mapResult.clustered
    buckets.value = trendResult.buckets

    const source = instance.getSource(SIGHTINGS_SOURCE) as maplibregl.GeoJSONSource | undefined
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

/** Re-reads the palette and repaints. Called when the colour scheme changes. */
function applyTheme() {
  const instance = map.value
  if (!instance || !instance.getLayer(CORE_LAYER)) return
  repaint(instance, cssToken, Boolean(TILE_URL))
}

function fitArchipelago() {
  map.value?.fitBounds(MALDIVES_BOUNDS, { padding: 64, duration: 700 })
}

const schemeWatcher = window.matchMedia('(prefers-color-scheme: light)')

onMounted(() => {
  if (!container.value) return

  const instance = new maplibregl.Map({
    container: container.value,
    style: baseStyle(cssToken, TILE_URL, TILE_ATTRIBUTION),
    bounds: MALDIVES_BOUNDS,
    fitBoundsOptions: { padding: 64 },
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    attributionControl: TILE_URL ? undefined : false,
  })
  map.value = instance

  instance.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
  instance.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-right')

  const start = () => {
    instance.addSource(SIGHTINGS_SOURCE, { type: 'geojson', data: toGeoJSON([]) })
    for (const layer of sightingLayers(cssToken)) instance.addLayer(layer)
    void loadBasemap(instance)
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
  instance.on('zoom', () => syncLabelDetail(instance))

  // Counts live in a popup rather than a symbol layer, which would need a glyph
  // server — one more external dependency the project does not allow.
  instance.on('mousemove', CORE_LAYER, (event) => {
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

  instance.on('mouseleave', CORE_LAYER, () => {
    instance.getCanvas().style.cursor = ''
    hoveredKey = ''
    hoverPopup?.remove()
  })

  instance.on('click', CORE_LAYER, (event) => {
    const feature = event.features?.[0]
    const id = String(feature?.properties?.sightingId ?? '')
    // Clusters have no single record to open; zoom in to resolve them instead.
    if (!id) {
      instance.easeTo({ center: event.lngLat, zoom: Math.min(instance.getZoom() + 2, 14) })
      return
    }
    window.location.assign(`/sightings/${id}`)
  })

  schemeWatcher.addEventListener('change', applyTheme)
})

onUnmounted(() => {
  window.clearTimeout(reloadTimer)
  schemeWatcher.removeEventListener('change', applyTheme)
  hoverPopup?.remove()
  for (const marker of labelMarkers) marker.remove()
  labelMarkers.length = 0
  map.value?.remove()
  map.value = null
})

watch([verifiedOnly, from, to, bucket], () => void loadData())
// A pinned scheme change repaints the canvas; the media query above covers the
// 'system' case, which does not touch this ref.
watch(theme.choice, () => applyTheme())

const totalInView = computed(() => points.value.reduce((sum, point) => sum + point.count, 0))
const trendMax = computed(() => Math.max(1, ...buckets.value.map((b) => b.total)))
const filtered = computed(() => verifiedOnly.value || Boolean(from.value) || Boolean(to.value))

function bucketLabel(iso: string): string {
  const date = new Date(iso)
  if (bucket.value === 'month') {
    return date.toLocaleDateString(undefined, { month: 'short', year: 'numeric' })
  }
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}
</script>

<template>
  <div class="map-page">
    <div ref="container" class="canvas" data-labels="code" />

    <header class="overlay head card">
      <div class="head-top">
        <div>
          <span class="eyebrow">Reef map / {{ totalInView }} in view</span>
          <h1>Reef condition</h1>
        </div>
        <span v-if="loading" class="pulse" aria-hidden="true" />
      </div>

      <p class="note">
        {{
          clustered
            ? 'Grouped by area at this zoom. Click a group to zoom in.'
            : 'Each marker is one sighting. Click to open its record.'
        }}
      </p>

      <div class="filters">
        <label class="check-row">
          <CheckBox v-model="verifiedOnly" ariaLabel="Show expert-verified sightings only" />
          Expert-verified only
        </label>
        <DateRangeField v-model:from="from" v-model:to="to" ariaLabel="Filter by capture date" />
      </div>

      <div class="head-actions">
        <button type="button" class="btn btn-ghost btn-sm" @click="fitArchipelago">
          Whole archipelago
        </button>
      </div>

      <p v-if="error" class="notice" role="alert">{{ error }}</p>
      <p v-else-if="filtered" class="applied readout">Filters applied to markers and trend.</p>
    </header>

    <aside class="overlay legend card">
      <span class="eyebrow">Bleached extent</span>
      <div class="ramp">
        <span
          class="ramp-bar"
          role="img"
          aria-label="Colour scale from living coral tissue to fully bleached skeleton"
        />
        <div class="ramp-labels readout">
          <span>0%</span>
          <span>50%</span>
          <span>100%</span>
        </div>
      </div>

      <!-- The basemap needs a key too: a viewer has to know that a pale shape is
           a reef platform and not a measurement. -->
      <div class="keys">
        <span class="key"><span class="swatch is-shelf" />Atoll platform</span>
        <span class="key"><span class="swatch is-reef" />Reef rim</span>
        <span class="key"><span class="swatch is-land" />Island</span>
      </div>

      <div class="trend-head">
        <span class="eyebrow">Timeline</span>
        <SegmentedTabs v-model="bucket" :options="BUCKETS" ariaLabel="Trend interval" size="sm" />
      </div>

      <template v-if="buckets.length">
        <!-- One glyph, two variables: bar height is how many sightings arrived,
             the bone-coloured portion is how many came back bleached. -->
        <div class="trend" role="img" aria-label="Sighting volume and bleached share over time">
          <span
            v-for="entry in buckets"
            :key="entry.bucket"
            class="bar"
            :style="{
              height: `${(entry.total / trendMax) * 100}%`,
              '--share': entry.total ? entry.bleached / entry.total : 0,
            }"
            :data-tip="`${bucketLabel(entry.bucket)}: ${entry.bleached} of ${entry.total} bleached`"
            data-tip-side="top"
          />
        </div>
        <div class="trend-axis readout">
          <span>{{ bucketLabel(buckets[0].bucket) }}</span>
          <span>{{ bucketLabel(buckets[buckets.length - 1].bucket) }}</span>
        </div>
        <p class="axis-note">Height is volume, fill is the bleached share.</p>
      </template>
      <p v-else class="axis-note">No sightings in this window.</p>

      <p class="credit">
        {{
          basemapFailed
            ? 'Geography could not be loaded — markers only.'
            : 'Geography: Natural Earth 10m, public domain.'
        }}
      </p>
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
  background: var(--sea-abyss);
}

.overlay {
  position: absolute;
  z-index: var(--z-overlay);
  /* Chrome rather than a card tint: these float over map tiles whose brightness
     is not ours to predict. */
  background: var(--chrome);
  box-shadow: var(--shadow-float), var(--sheen);
  backdrop-filter: blur(var(--blur-lg));
  -webkit-backdrop-filter: blur(var(--blur-lg));
}

/* Inset from the rail rather than from the viewport: the map runs underneath the
   rail, but nothing readable should. */
.head {
  top: var(--rail-gap);
  left: var(--content-inset);
  width: min(24rem, calc(100% - var(--content-inset) - 1rem));
  padding: 1rem;
  display: grid;
  gap: 0.5rem;
}

.head-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.head h1 {
  font-size: var(--step-2);
}

/* A quiet in-flight marker. The map has no room for a spinner and no need for
   one — this only has to say "numbers are still moving". */
.pulse {
  width: 0.5rem;
  height: 0.5rem;
  margin-top: 0.5rem;
  border-radius: 50%;
  background: var(--reef);
  animation: pulse-dot 1.1s var(--ease-out) infinite;
}

@keyframes pulse-dot {
  0%,
  100% {
    opacity: 0.25;
    scale: 0.8;
  }
  50% {
    opacity: 1;
    scale: 1;
  }
}

.note {
  color: var(--ink-3);
  font-size: var(--step--1);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.625rem;
  margin-top: 0.25rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--line);
}

.head-actions {
  display: flex;
  justify-content: flex-start;
}

.applied {
  font-size: var(--step--2);
  color: var(--reef);
}

.legend {
  bottom: var(--rail-gap);
  left: var(--content-inset);
  width: min(19rem, calc(100% - var(--content-inset) - 1rem));
  padding: 0.875rem;
  display: grid;
  gap: 0.5rem;
}

.ramp {
  display: grid;
  gap: 0.25rem;
}

/* Built from the same tokens the map markers read, so the legend cannot drift
   out of step with the data it explains. */
.ramp-bar {
  height: 0.5rem;
  border-radius: var(--r-pill);
  background: linear-gradient(
    90deg,
    var(--reef) 0%,
    var(--reef-pale) 35%,
    var(--bone) 70%,
    var(--bone-dim) 100%
  );
}

.ramp-labels,
.trend-axis {
  display: flex;
  justify-content: space-between;
  font-size: var(--step--2);
  color: var(--ink-4);
}

.keys {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.75rem;
  margin-top: 0.125rem;
  font-size: var(--step--2);
  color: var(--ink-3);
}

.key {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
}

.swatch {
  width: 0.625rem;
  height: 0.625rem;
  border-radius: var(--r-xs);
}

.swatch.is-shelf {
  background: var(--sea-shelf);
  border: 1px solid var(--sea-shelf-line);
}

/* A rim is a line on the map, so it is a line in the legend. */
.swatch.is-reef {
  height: 0;
  border-top: 2px solid var(--sea-reef);
  border-radius: 0;
}

.swatch.is-land {
  background: var(--sea-land);
}

.trend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding-top: 0.625rem;
  border-top: 1px solid var(--line);
}

.trend {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 3.5rem;
}

.bar {
  flex: 1;
  min-height: 2px;
  border-radius: 2px;
  background: linear-gradient(
    to top,
    var(--reef) 0%,
    var(--reef) calc((1 - var(--share)) * 100%),
    var(--bone) calc((1 - var(--share)) * 100%),
    var(--bone) 100%
  );
  transition: height var(--dur-slow) var(--ease-spring), scale var(--dur) var(--ease-spring);
}

.bar:hover {
  scale: 1.12;
}

.axis-note {
  color: var(--ink-4);
  font-size: var(--step--2);
}

.credit {
  margin-top: 0.25rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--line);
  color: var(--ink-4);
  font-size: var(--step--2);
}

/* Below this width the two overlays would cover the map they annotate, so the
   legend stands down and the header narrows. */
@media (max-width: 44rem) {
  .legend {
    display: none;
  }

  .head {
    left: 0.625rem;
    width: calc(100% - 1.25rem);
  }
}
</style>

<style>
/* Popup content and marker elements are built as raw DOM by MapLibre, outside
   the component's style scope, so these rules cannot be scoped. */

/* Place names. A marker element sits directly under the cursor wherever it lands,
   so it must not take pointer events — it would block panning and steal hover
   from the markers it labels. */
.map-label {
  pointer-events: none;
  white-space: nowrap;
  font-family: var(--font-ui);
  font-size: var(--step--2);
  letter-spacing: 0.06em;
  color: var(--ink-2);
  /* A halo instead of a plate: over the platform fills a boxed label would read
     as a marker, and there is no room for twenty-one boxes. */
  text-shadow: 0 1px 2px var(--sea-abyss), 0 0 6px var(--sea-abyss);
}

.map-label.is-capital {
  color: var(--ink);
  font-weight: 600;
  letter-spacing: 0.02em;
}

/* Which of the two atoll labels is shown comes from the container, so zooming
   toggles one attribute rather than twenty-one elements. */
.map-label .label-name {
  display: inline;
}

.map-label .label-code {
  display: none;
}

[data-labels='code'] .map-label .label-name {
  display: none;
}

[data-labels='code'] .map-label .label-code {
  display: inline;
}

/* Inside a single atoll, only the capital still means anything. */
[data-labels='local'] .map-label:not(.is-capital) {
  display: none;
}

.map-hover-popup {
  pointer-events: none;
}

.maplibregl-popup-content {
  display: grid;
  gap: 0.125rem;
  padding: 0.5rem 0.6875rem;
  background: var(--chrome);
  backdrop-filter: blur(var(--blur-lg));
  -webkit-backdrop-filter: blur(var(--blur-lg));
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-2), var(--sheen);
  font-family: var(--font-ui);
}

/* MapLibre builds the tip from border triangles, which cannot be glass — it
   would be an opaque wedge hanging off a translucent panel. The popup is offset
   above the marker under the cursor, so what it refers to was never in doubt. */
.maplibregl-popup-tip {
  display: none;
}

.popup-count {
  font-size: var(--step--1);
  font-weight: 600;
  color: var(--ink);
}

.popup-extent {
  font-family: var(--font-mono);
  font-size: var(--step--2);
  color: var(--ink-2);
}

.popup-hint {
  font-size: var(--step--2);
  color: var(--ink-4);
}

/* MapLibre's own controls ship with a light chrome that fights both schemes;
   restyle them to the app's surfaces rather than leaving two design languages
   on one screen. */
.maplibregl-ctrl-group {
  background: var(--surface-1) !important;
  border: 1px solid var(--line) !important;
  border-radius: var(--r-md) !important;
  box-shadow: var(--shadow-2) !important;
  backdrop-filter: blur(var(--blur));
  -webkit-backdrop-filter: blur(var(--blur));
  overflow: hidden;
}

.maplibregl-ctrl-group button + button {
  border-top: 1px solid var(--line) !important;
}

.maplibregl-ctrl-group button .maplibregl-ctrl-icon {
  /* The control icons are baked-in dark SVGs; invert them for the dark scheme so
     they read as light glyphs without shipping replacements. */
  filter: invert(1) opacity(0.75);
}

:root[data-theme='light'] .maplibregl-ctrl-group button .maplibregl-ctrl-icon {
  filter: none;
}

@media (prefers-color-scheme: light) {
  :root:not([data-theme='dark']) .maplibregl-ctrl-group button .maplibregl-ctrl-icon {
    filter: none;
  }
}

.maplibregl-ctrl-scale {
  background: var(--surface-1) !important;
  border-color: var(--line) !important;
  border-radius: var(--r-xs);
  color: var(--ink-2) !important;
  font-family: var(--font-mono);
  font-size: var(--step--2) !important;
}
</style>
