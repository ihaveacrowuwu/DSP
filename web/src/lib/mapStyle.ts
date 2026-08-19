/**
 * The reef map's style — every layer, colour and size expression MapLibre needs.
 *
 * This lives outside the view for two reasons.
 *
 * First, MapLibre paints on a canvas and cannot read CSS custom properties, so
 * every colour has to be handed to it as a literal string. Reading the design
 * tokens at runtime, rather than duplicating hex values, is what keeps the map in
 * step with the rest of the interface when the colour scheme changes — but it
 * means the whole style depends on the DOM. Taking a `TokenReader` as a parameter
 * instead of reaching for `document` makes that dependency explicit and lets the
 * expressions be evaluated in a test, which is the only way to check them without
 * a GPU: a malformed paint expression is not a type error, it is a layer that
 * silently fails to appear.
 *
 * Second, a repaint has to reapply exactly what the layers were created with. The
 * previous version listed the properties to update by hand in a second function,
 * which is a drift waiting to happen — add a layer, forget the repaint line, and
 * the map half-changes theme. `repaint()` below re-derives the layers and replays
 * their paint, so there is nothing to keep in sync.
 */
import type {
  ExpressionSpecification,
  LayerSpecification,
  LngLatBoundsLike,
  Map as MapLibreMap,
  StyleSpecification,
} from 'maplibre-gl'

export type TokenReader = (name: string) => string

/** Reads a design token off the document. The default reader in the browser. */
export function cssToken(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/** The whole archipelago, so the first view answers "how are the reefs?". */
export const MALDIVES_BOUNDS: LngLatBoundsLike = [
  [71.6, -1.4],
  [74.4, 7.6],
]

/**
 * There is only geography for the Maldives, so zooming out past the archipelago
 * shows nothing but ocean. A floor is the fix; `maxBounds` is not, and was tried
 * first. When the bounds are narrower than the viewport MapLibre resolves the
 * conflict by zooming *in* until they fill it — so fencing the camera to a
 * 6-degree-wide country makes the whole country unviewable on a wide monitor,
 * which is the opposite of the intent. The floor plus the "Whole archipelago"
 * button covers the same ground with no dependence on window shape.
 */
export const MIN_ZOOM = 5
export const MAX_ZOOM = 15

/** Where the bundled Natural Earth geography is served from. */
export const BASEMAP_URL = `${import.meta.env.BASE_URL}basemap/maldives.json`

/**
 * Severity ramp: living tissue -> pale -> bone. The progression mirrors the
 * phenomenon, so the legend needs no explaining to a marine scientist. -1 marks
 * "not yet assessed" and paints neutral, never a point on the scale.
 */
export function severityColour(read: TokenReader): ExpressionSpecification {
  return [
    'case',
    ['<', ['get', 'severity'], 0],
    read('--ink-4'),
    [
      'interpolate',
      ['linear'],
      ['get', 'severity'],
      0,
      read('--reef'),
      0.35,
      read('--reef-pale'),
      0.7,
      read('--bone'),
      1,
      read('--bone-dim'),
    ],
  ]
}

/** One rung of the radius ladder: how big a marker is for 1, 50 and 500 sightings. */
function byCount(one: number, fifty: number, many: number): ExpressionSpecification {
  return ['interpolate', ['linear'], ['get', 'count'], 1, one, 50, fifty, 500, many]
}

/**
 * Marker radius, in pixels, as a function of zoom first and cluster size second.
 *
 * Both variables matter and the earlier expression used only the count. A cluster
 * standing for 400 sightings should be bigger than one standing for three — but
 * not at national zoom, where the old ladder topped out at 15 px, wide enough to
 * cover an entire atoll and hide the geography it sits on. So the count ramp is
 * re-declared at four zoom levels: small and crisp with the whole country on
 * screen, generous once you are over a single reef.
 */
export function coreRadius(): ExpressionSpecification {
  return [
    'interpolate',
    ['linear'],
    ['zoom'],
    MIN_ZOOM,
    byCount(2.2, 4, 6),
    8,
    byCount(3.4, 6.5, 9.5),
    11,
    byCount(5, 9.5, 13.5),
    14,
    byCount(7, 12, 16),
  ]
}

/** The halo is proportional to the core, roughly two and a half times its size. */
export function haloRadius(): ExpressionSpecification {
  return [
    'interpolate',
    ['linear'],
    ['zoom'],
    MIN_ZOOM,
    byCount(5, 9, 14),
    8,
    byCount(9, 17, 25),
    11,
    byCount(14, 26, 36),
    14,
    byCount(20, 32, 44),
  ]
}

/**
 * The style the map is constructed with: a ground colour, and the optional raster
 * basemap.
 *
 * Defined here rather than fetched from a style server. Two reasons: the project
 * forbids depending on external services, and a style that fails to load would
 * stop MapLibre firing `load`, which would silently prevent our own data layers
 * from ever being added. With a local style the sighting data always renders.
 */
export function baseStyle(
  read: TokenReader,
  tileUrl: string,
  tileAttribution: string,
): StyleSpecification {
  const style: StyleSpecification = {
    version: 8,
    sources: {},
    layers: [
      // The abyss. Everything else in the geography is a shallower patch painted
      // on top of it, so nothing has a straight edge where the source data was
      // clipped.
      { id: 'ground', type: 'background', paint: { 'background-color': read('--sea-abyss') } },
    ],
  }

  if (tileUrl) {
    style.sources.basemap = {
      type: 'raster',
      tiles: [tileUrl],
      tileSize: 256,
      attribution: tileAttribution,
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

/** The `kind` values in the bundled geography, bottom of the stack upward. */
export const SEAFLOOR_SOURCE = 'seafloor'

/**
 * The bundled geography, drawn bottom up: the 1000 m slope, the shallow atoll
 * platforms and their edge, the reef rims, then the islands.
 *
 * The two fills are skipped when a raster basemap is configured, because two
 * basemaps stacked is worse than either. The line work is not: a reef rim drawn
 * over satellite imagery is exactly the annotation the imagery lacks.
 */
export function basemapLayers(read: TokenReader, hasRaster: boolean): LayerSpecification[] {
  const layers: LayerSpecification[] = []

  if (!hasRaster) {
    layers.push(
      {
        id: 'sea-slope',
        type: 'fill',
        source: SEAFLOOR_SOURCE,
        filter: ['==', ['get', 'kind'], 'slope'],
        paint: { 'fill-color': read('--sea-slope') },
      },
      {
        id: 'sea-shelf',
        type: 'fill',
        source: SEAFLOOR_SOURCE,
        filter: ['==', ['get', 'kind'], 'shelf'],
        paint: { 'fill-color': read('--sea-shelf') },
      },
    )
  }

  layers.push(
    {
      // The platform edge, its own line layer rather than `fill-outline-color`,
      // which cannot be given a width and disappears at low zoom.
      id: 'sea-shelf-edge',
      type: 'line',
      source: SEAFLOOR_SOURCE,
      filter: ['==', ['get', 'kind'], 'shelf'],
      paint: {
        'line-color': read('--sea-shelf-line'),
        'line-width': ['interpolate', ['linear'], ['zoom'], MIN_ZOOM, 0.6, 10, 1.4],
      },
    },
    {
      id: 'sea-reef',
      type: 'line',
      source: SEAFLOOR_SOURCE,
      filter: ['==', ['get', 'kind'], 'reef'],
      paint: {
        'line-color': read('--sea-reef'),
        'line-width': ['interpolate', ['linear'], ['zoom'], MIN_ZOOM, 0.5, 10, 2],
        // Hidden at national zoom, where the rims sit on the platform edge and
        // only thicken it.
        'line-opacity': ['interpolate', ['linear'], ['zoom'], MIN_ZOOM, 0, 7, 0.9],
      },
    },
    {
      id: 'sea-land',
      type: 'fill',
      source: SEAFLOOR_SOURCE,
      filter: ['==', ['get', 'kind'], 'island'],
      paint: { 'fill-color': read('--sea-land') },
    },
  )

  return layers
}

export const SIGHTINGS_SOURCE = 'sightings'
export const CORE_LAYER = 'sightings-core'

/** The sighting markers: a blurred halo and the core dot on top of it. */
export function sightingLayers(read: TokenReader): LayerSpecification[] {
  return [
    {
      // The halo makes a run of bleached reefs read as a region rather than a row
      // of specks. It fades in with zoom: at national zoom the halos merge into
      // one wash that hides the atolls underneath them.
      id: 'sightings-halo',
      type: 'circle',
      source: SIGHTINGS_SOURCE,
      paint: {
        'circle-radius': haloRadius(),
        'circle-color': severityColour(read),
        'circle-opacity': [
          'interpolate',
          ['linear'],
          ['zoom'],
          MIN_ZOOM,
          0.07,
          7.5,
          0.16,
          11,
          0.22,
        ],
        'circle-blur': 0.8,
      },
    },
    {
      id: CORE_LAYER,
      type: 'circle',
      source: SIGHTINGS_SOURCE,
      // Worse condition draws on top. Bleaching is the finding; it must never end
      // up hidden under a healthy marker that happened to be drawn later.
      layout: { 'circle-sort-key': ['get', 'severity'] },
      paint: {
        'circle-radius': coreRadius(),
        'circle-color': severityColour(read),
        // Slightly translucent so overlapping markers darken rather than hide one
        // another — the only cue that two clusters are stacked.
        'circle-opacity': 0.92,
        // A hairline separating touching markers, suppressed at national zoom
        // where a 1 px ring on a 2 px dot is most of the dot. It takes the
        // platform colour because that is what is behind almost every sighting.
        'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 6.5, 0, 8.5, 1, 12, 1.4],
        'circle-stroke-color': read('--sea-shelf'),
      },
    },
  ]
}

/**
 * Reapplies every paint property from the current palette. Re-deriving the layers
 * rather than listing properties by hand is what stops a new layer from being
 * left behind on a theme change.
 */
export function repaint(instance: MapLibreMap, read: TokenReader, hasRaster: boolean) {
  instance.setPaintProperty('ground', 'background-color', read('--sea-abyss'))

  for (const layer of [...basemapLayers(read, hasRaster), ...sightingLayers(read)]) {
    if (!instance.getLayer(layer.id)) continue
    for (const [property, value] of Object.entries(layer.paint ?? {})) {
      instance.setPaintProperty(layer.id, property, value)
    }
  }
}
