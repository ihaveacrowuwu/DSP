/**
 * FR8: "full provenance for each sighting: contributor, timestamps, ML label +
 * confidence + model version, and verification history."
 *
 * Provenance is the claim this project makes about its own data. A researcher
 * downloading the CSV has to be able to answer "who said this, and on what
 * basis" for any row, and this screen is where that question is answered in
 * full. Each field named in FR8 is asserted separately rather than through one
 * snapshot, so a field that stops rendering fails the assertion that names it
 * instead of failing an opaque diff.
 *
 * Two behaviours here are not cosmetic. A corrected sighting must still show
 * what the model originally claimed - if the record silently adopted the
 * expert's label everywhere, the disagreement between model and expert would be
 * unrecoverable, and that disagreement is the only evidence the project has
 * about how well the model is doing in the field. And a sighting no expert has
 * touched must say so, because reading a model's guess as a finding is exactly
 * the mistake NFR13 exists to prevent.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { photo, prediction, sighting, verification } from './fixtures'

const { getSighting, fetchPhotoObjectUrl } = vi.hoisted(() => ({
  getSighting: vi.fn(),
  fetchPhotoObjectUrl: vi.fn(),
}))

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, api: { ...actual.api, getSighting }, fetchPhotoObjectUrl }
})

// Only useRoute is needed: the view reads one param and renders RouterLinks,
// which are stubbed below. Installing a real router would add a routing table
// this test has no opinion about.
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'sighting-1' } }) }))

import SightingDetailView from './SightingDetailView.vue'

const mounted: ReturnType<typeof mount>[] = []

function stubRecord({
  record = sighting(),
  photos = [photo()],
  verifications = [] as ReturnType<typeof verification>[],
} = {}) {
  getSighting.mockResolvedValue({ sighting: record, photos, verifications })
}

async function open() {
  const wrapper = mount(SightingDetailView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  URL.revokeObjectURL = vi.fn()
  fetchPhotoObjectUrl.mockImplementation(async (id: string) => `blob:${id}`)
  stubRecord()
})

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
  vi.clearAllMocks()
})

describe('every field FR8 names is on the page', () => {
  it('names the contributor', async () => {
    expect((await open()).text()).toContain('Aishath Reef')
  })

  it('shows both timestamps, captured and received', async () => {
    const wrapper = await open()

    // Two distinct instants: when the diver took it, and when the server got it.
    // Collapsing them would hide a sighting that sat in an outbox for a week.
    expect(wrapper.text()).toContain(new Date('2026-08-20T08:30:00Z').toLocaleString())
    expect(wrapper.text()).toContain(new Date('2026-08-20T08:58:00Z').toLocaleString())
  })

  it('shows the model label, its confidence and the version that produced it', async () => {
    const wrapper = await open()

    expect(wrapper.text()).toContain('bleached')
    expect(wrapper.text()).toContain('42%')
    expect(wrapper.text()).toContain('effnetb0-0.1.0')
  })

  it('shows how the position was obtained, and how good the fix was', async () => {
    const wrapper = await open()

    expect(wrapper.text()).toContain('GPS')
    expect(wrapper.text()).toContain('±8 m')
  })

  it('distinguishes a dropped pin from a GPS fix', async () => {
    stubRecord({ record: sighting({ locationSource: 'manual_pin' }) })
    const wrapper = await open()

    expect(wrapper.text()).toContain('Dropped pin')
  })

  it("keeps the diver's own call separate from the model's", async () => {
    stubRecord({ record: sighting({ selfAssessedCondition: 'healthy' }) })
    const wrapper = await open()

    expect(wrapper.text()).toContain("Diver's call")
    expect(wrapper.text()).toContain('healthy')
  })

  it('quotes the note the contributor wrote', async () => {
    expect((await open()).text()).toContain('Visibility poor on the north side.')
  })
})

describe('the review history is an audit trail, not a status field', () => {
  it('says plainly when no expert has reviewed the sighting', async () => {
    const wrapper = await open()

    expect(wrapper.text()).toContain('No expert has reviewed this sighting yet')
    expect(wrapper.text()).toContain('model output only')
  })

  it('names who decided, what they decided, and when', async () => {
    stubRecord({ verifications: [verification()] })
    const wrapper = await open()

    expect(wrapper.text()).toContain('Dr Hassan')
    expect(wrapper.text()).toContain('corrected the model')
    expect(wrapper.text()).toContain('as healthy')
  })

  it("keeps the model's original claim visible after it was overruled", async () => {
    // The record is now healthy by expert decision, but the model said bleached.
    // Losing that disagreement would destroy the only field evidence the project
    // has about model accuracy.
    stubRecord({
      record: sighting({ condition: 'healthy', verified: true, status: 'verified' }),
      photos: [photo({ prediction: prediction({ label: 'bleached' }) })],
      verifications: [verification()],
    })
    const wrapper = await open()

    expect(wrapper.text()).toContain('corrected the model')
    // The assessment panel still reports what the classifier claimed.
    expect(wrapper.find('.assessment').text()).toContain('bleached')
  })

  it('shows a rejection with its reason in plain words', async () => {
    stubRecord({
      verifications: [
        verification({ decision: 'rejected', label: undefined, rejectReason: 'not_coral' }),
      ],
    })
    const wrapper = await open()

    expect(wrapper.text()).toContain('rejected the photograph')
    // The enum's underscore is the database's spelling, not the interface's.
    expect(wrapper.text()).toContain('not coral')
    expect(wrapper.text()).not.toContain('not_coral')
  })

  it('lists every decision, not just the most recent', async () => {
    stubRecord({
      verifications: [
        verification({ id: 'v1', verifierName: 'Dr Hassan', decision: 'corrected' }),
        verification({ id: 'v2', verifierName: 'Dr Waheed', decision: 'confirmed' }),
      ],
    })
    const wrapper = await open()

    expect(wrapper.findAll('.history li')).toHaveLength(2)
    expect(wrapper.text()).toContain('Dr Waheed')
  })
})

describe('what the model has and has not done', () => {
  it('says the grader has not reached the photograph yet', async () => {
    stubRecord({ photos: [photo({ prediction: undefined })] })
    const wrapper = await open()

    expect(wrapper.text()).toContain('Awaiting model analysis')
  })

  it('reports the lattice tally alongside the extent score', async () => {
    const wrapper = await open()

    // The two can disagree - a high extent off few cells - and the tally is what
    // makes that visible without counting squares.
    expect(wrapper.text()).toContain('9/25')
  })

  it('takes the lattice off the photograph on request', async () => {
    const wrapper = await open()
    expect(wrapper.findComponent({ name: 'PatchLattice' }).exists()).toBe(true)

    await wrapper.find('.section-head button').trigger('click')

    expect(wrapper.findComponent({ name: 'PatchLattice' }).exists()).toBe(false)
  })

  it('prints the resolution caveat in full rather than hiding it behind a hover', async () => {
    stubRecord({ photos: [photo({ width: 320, height: 240 })] })
    const wrapper = await open()

    expect(wrapper.find('.low-res').exists()).toBe(true)
  })
})

describe('the record survives its parts failing', () => {
  it('renders the record when one image cannot be fetched', async () => {
    fetchPhotoObjectUrl.mockRejectedValue(new Error('storage unreachable'))
    const wrapper = await open()

    // The provenance is the point of the page; a missing JPEG must not blank it.
    expect(wrapper.text()).toContain('Aishath Reef')
    expect(wrapper.text()).toContain('Image unavailable')
  })

  it('keeps the readable images when only one of two fails', async () => {
    fetchPhotoObjectUrl.mockImplementation(async (id: string) => {
      if (id === 'photo-2') throw new Error('storage unreachable')
      return `blob:${id}`
    })
    stubRecord({ photos: [photo(), photo({ id: 'photo-2', sightingId: 'sighting-1' })] })
    const wrapper = await open()

    expect(wrapper.findAll('img')).toHaveLength(1)
    expect(wrapper.text()).toContain('Image unavailable')
  })

  it('offers a way back when the record itself cannot be loaded', async () => {
    const { ApiError } = await import('@/lib/api')
    getSighting.mockRejectedValue(new ApiError(404, 'not_found', 'No such sighting.'))
    const wrapper = await open()

    expect(wrapper.find('[role="alert"]').text()).toContain('No such sighting.')
    expect(wrapper.text()).toContain('Back to sightings')
  })

  it('says a sighting has no photographs rather than showing an empty frame', async () => {
    stubRecord({ photos: [] })
    const wrapper = await open()

    expect(wrapper.text()).toContain('No photographs were attached')
    expect(wrapper.find('.frame').exists()).toBe(false)
  })

  it('releases every object URL when the page is left', async () => {
    const revoke = vi.fn()
    URL.revokeObjectURL = revoke
    stubRecord({ photos: [photo(), photo({ id: 'photo-2' })] })
    const wrapper = await open()

    wrapper.unmount()

    expect(revoke).toHaveBeenCalledWith('blob:photo-1')
    expect(revoke).toHaveBeenCalledWith('blob:photo-2')
  })
})
