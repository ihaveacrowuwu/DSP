/**
 * FR7 ("map with clustering, a condition heatmap layer, and filters") and FR12
 * ("chart coral-condition trends over time for a selected area or site").
 *
 * `lib/mapStyle.ts` already has 14 tests over the layer definitions and the
 * basemap, so what is missing is not the style: it is the wiring between the
 * controls a researcher touches and the query the server receives. That wiring
 * is where a filter silently stops applying, and a map that shows unfiltered
 * data under a heading claiming a filter is applied is worse than one that
 * fails outright - a researcher reads a number off it and never learns it was
 * the wrong number.
 *
 * MapLibre itself is replaced by the fake below. It needs a WebGL context no
 * headless DOM provides, and none of the behaviour under test is MapLibre's:
 * these tests assert what the view *asks* for and what it *renders*, and treat
 * the map as the source of a bounding box and a zoom level, which is all the
 * data path uses it for.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { mapPoint, trendBucket } from './fixtures'

const { mapPoints, trends, fakeMap } = vi.hoisted(() => {
  // Registered handlers, so a test can fire `moveend` the way a pan does.
  const handlers: Record<string, ((event?: unknown) => void)[]> = {}

  const fakeMap = {
    handlers,
    setDataCalls: [] as unknown[],
    fitBoundsCalls: [] as unknown[],
    zoom: 6,
    on(event: string, layerOrFn: unknown, maybeFn?: unknown) {
      const fn = (typeof layerOrFn === 'function' ? layerOrFn : maybeFn) as () => void
      ;(handlers[event] ??= []).push(fn)
    },
    once(event: string, fn: () => void) {
      ;(handlers[event] ??= []).push(fn)
    },
    emit(event: string, payload?: unknown) {
      for (const fn of handlers[event] ?? []) fn(payload)
    },
    // `loaded()` false forces the view down its `once('load')` path, which is
    // the one a real browser takes; a test that only ever exercised the
    // synchronous branch would not cover how the layers actually get added.
    loaded: () => false,
    getBounds: () => ({
      getWest: () => 72.5,
      getSouth: () => 3.5,
      getEast: () => 73.5,
      getNorth: () => 4.5,
    }),
    getZoom() {
      return fakeMap.zoom
    },
    getCanvas: () => ({ style: {} }),
    getContainer: () => document.createElement('div'),
    getLayer: () => ({}),
    getSource: () => ({ setData: (data: unknown) => fakeMap.setDataCalls.push(data) }),
    addSource: vi.fn(),
    addLayer: vi.fn(),
    addControl: vi.fn(),
    easeTo: vi.fn(),
    fitBounds: (...args: unknown[]) => fakeMap.fitBoundsCalls.push(args),
    remove: vi.fn(),
    reset() {
      for (const key of Object.keys(handlers)) delete handlers[key]
      fakeMap.setDataCalls.length = 0
      fakeMap.fitBoundsCalls.length = 0
      fakeMap.zoom = 6
    },
  }

  return { mapPoints: vi.fn(), trends: vi.fn(), fakeMap }
})

vi.mock('maplibre-gl', () => {
  class Marker {
    setLngLat() {
      return this
    }
    addTo() {
      return this
    }
    remove() {}
  }
  class Popup {
    setLngLat() {
      return this
    }
    setHTML() {
      return this
    }
    addTo() {
      return this
    }
    remove() {}
  }
  return {
    default: {
      Map: vi.fn(() => fakeMap),
      Marker,
      Popup,
      NavigationControl: class {},
      ScaleControl: class {},
    },
    Marker,
    Popup,
  }
})

vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}))

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, api: { ...actual.api, mapPoints, trends } }
})

import ReefMapView from './ReefMapView.vue'

const mounted: ReturnType<typeof mount>[] = []

async function open() {
  const wrapper = mount(ReefMapView, { attachTo: document.body })
  mounted.push(wrapper)
  // The view defers its first load until MapLibre reports the style is ready.
  fakeMap.emit('load')
  await flushPromises()
  await flushPromises()
  return wrapper
}

/** The debounce on moveend is 250 ms of real timer, so tests drive it directly. */
async function pan() {
  vi.useFakeTimers()
  fakeMap.emit('moveend')
  vi.advanceTimersByTime(300)
  vi.useRealTimers()
  await flushPromises()
  await flushPromises()
}

beforeEach(() => {
  fakeMap.reset()
  mapPoints.mockResolvedValue({ points: [mapPoint()], zoom: 6, clustered: false })
  trends.mockResolvedValue({ buckets: [trendBucket()] })
  global.fetch = vi.fn(async () => ({
    ok: true,
    json: async () => ({ type: 'FeatureCollection', features: [] }),
  })) as unknown as typeof fetch
})

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('the viewport is what gets queried (FR7)', () => {
  it('asks for the bounding box the map is actually showing', async () => {
    await open()

    expect(mapPoints).toHaveBeenCalledWith(
      expect.objectContaining({ bbox: '72.5000,3.5000,73.5000,4.5000', zoom: 6 }),
    )
  })

  it('sends the zoom, so the server can decide whether to cluster', async () => {
    fakeMap.zoom = 11
    await open()

    expect(mapPoints).toHaveBeenCalledWith(expect.objectContaining({ zoom: 11 }))
  })

  it('re-queries after a pan, and coalesces a burst into one request', async () => {
    await open()
    mapPoints.mockClear()

    vi.useFakeTimers()
    fakeMap.emit('moveend')
    fakeMap.emit('moveend')
    fakeMap.emit('moveend')
    vi.advanceTimersByTime(300)
    vi.useRealTimers()
    await flushPromises()

    // Panning fires moveend per gesture; three in a burst must cost one request.
    expect(mapPoints).toHaveBeenCalledTimes(1)
  })

  it('says whether the markers are grouped or individual', async () => {
    mapPoints.mockResolvedValue({ points: [mapPoint()], zoom: 6, clustered: true })
    const wrapper = await open()

    expect(wrapper.text()).toContain('Grouped by area at this zoom')
    expect(wrapper.text()).not.toContain('Each marker is one sighting')
  })

  it('hands the returned points to the map source as GeoJSON', async () => {
    await open()

    const [data] = fakeMap.setDataCalls.slice(-1) as [{ type: string; features: unknown[] }]
    expect(data.type).toBe('FeatureCollection')
    expect(data.features).toHaveLength(1)
  })
})

describe('a filter applies to the markers and the trend alike (FR7, FR12)', () => {
  it('applies the verified-only filter to both queries', async () => {
    const wrapper = await open()
    mapPoints.mockClear()
    trends.mockClear()

    await wrapper.findComponent({ name: 'CheckBox' }).vm.$emit('update:modelValue', true)
    await pan()

    // Both, not one. A trend chart drawn from unfiltered data under a heading
    // that says a filter is applied is the failure this asserts against.
    expect(mapPoints).toHaveBeenCalledWith(expect.objectContaining({ verified: true }))
    expect(trends).toHaveBeenCalledWith(expect.objectContaining({ verified: true }))
  })

  it('sends no verified flag at all when the filter is off', async () => {
    await open()

    // `verified: false` would mean "show me unverified only" to the API.
    expect(mapPoints).toHaveBeenCalledWith(expect.objectContaining({ verified: undefined }))
  })

  it('applies the capture-date range to both queries', async () => {
    const wrapper = await open()
    mapPoints.mockClear()
    trends.mockClear()

    const range = wrapper.findComponent({ name: 'DateRangeField' })
    await range.vm.$emit('update:from', '2026-01-01')
    await range.vm.$emit('update:to', '2026-06-30')
    await pan()

    const expected = expect.objectContaining({ from: '2026-01-01', to: '2026-06-30' })
    expect(mapPoints).toHaveBeenCalledWith(expected)
    expect(trends).toHaveBeenCalledWith(expected)
  })

  it('tells the researcher when a filter is narrowing what they see', async () => {
    const wrapper = await open()

    await wrapper.findComponent({ name: 'CheckBox' }).vm.$emit('update:modelValue', true)
    await flushPromises()

    expect(wrapper.text()).toContain('Filters applied')
  })

  it('changes the trend interval without changing the markers', async () => {
    const wrapper = await open()
    trends.mockClear()

    await wrapper.findComponent({ name: 'SegmentedTabs' }).vm.$emit('update:modelValue', 'week')
    await pan()

    expect(trends).toHaveBeenCalledWith(expect.objectContaining({ bucket: 'week' }))
  })
})

describe('the trend chart reports what the buckets say (FR12)', () => {
  it('draws one bar per bucket', async () => {
    trends.mockResolvedValue({
      buckets: [
        trendBucket({ bucket: '2026-06-01' }),
        trendBucket({ bucket: '2026-07-01' }),
        trendBucket({ bucket: '2026-08-01' }),
      ],
    })
    const wrapper = await open()

    expect(wrapper.findAll('.trend .bar')).toHaveLength(3)
  })

  it('carries the bleached share as well as the volume', async () => {
    trends.mockResolvedValue({
      buckets: [trendBucket({ bucket: '2026-08-01', total: 40, bleached: 10, healthy: 30 })],
    })
    const wrapper = await open()

    // Height is volume, fill is share: one glyph, two variables, so the fill
    // must not silently become the volume again.
    const bar = wrapper.find('.trend .bar')
    expect(bar.attributes('style')).toContain('--share: 0.25')
    expect(bar.attributes('data-tip')).toContain('10 of 40 bleached')
  })

  it('says the window is empty rather than drawing an empty chart', async () => {
    trends.mockResolvedValue({ buckets: [] })
    const wrapper = await open()

    expect(wrapper.text()).toContain('No sightings in this window')
    expect(wrapper.find('.trend').exists()).toBe(false)
  })
})

describe('failures are visible, not silent', () => {
  it('reports a failed data load', async () => {
    const { ApiError } = await import('@/lib/api')
    mapPoints.mockRejectedValue(new ApiError(500, 'internal', 'Map query failed.'))
    const wrapper = await open()

    expect(wrapper.find('[role="alert"]').text()).toContain('Map query failed.')
  })

  it('reports a map that never initialised, instead of showing an empty canvas', async () => {
    const wrapper = await open()

    fakeMap.emit('error', { error: new Error('WebGL unavailable') })
    await flushPromises()

    // An empty map and "no sightings here" look identical; this is the
    // difference between them.
    expect(wrapper.find('[role="alert"]').text()).toContain('could not be initialised')
  })

  it('returns to the whole archipelago on request', async () => {
    const wrapper = await open()

    await wrapper.find('.head-actions button').trigger('click')

    expect(fakeMap.fitBoundsCalls).toHaveLength(1)
  })
})
