/**
 * FR6: "a verification queue where a researcher can confirm, correct, or reject
 * each sighting, with every decision audit-logged."
 *
 * The API tests already prove the queue's ordering and that a decision is written
 * to the audit log. What nothing covered until now is the screen in between: that
 * the buttons send the decision they name, that a correction sends the label the
 * reviewer picked rather than the one the model guessed, and that the keyboard
 * path - the one a reviewer doing fifty of these actually uses - agrees with the
 * buttons.
 *
 * The assertion that matters most is `correct to healthy` sending
 * `{decision: 'corrected', label: 'healthy'}`. A view that posted the model's own
 * label there would record a researcher agreeing with a model they had just
 * overruled, and every downstream figure - the trend chart, the CSV export, any
 * future training set - would inherit it silently. Nothing else in the system can
 * catch that: the API is behaving correctly by storing what it was sent.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { photo, prediction, sighting } from './fixtures'

// vi.hoisted, because vi.mock's factory is lifted above every other statement in
// the file - a plain `const` above it is still in its temporal dead zone when the
// factory runs, and the module fails to mock at all.
const { verificationQueue, getSighting, verify } = vi.hoisted(() => ({
  verificationQueue: vi.fn(),
  getSighting: vi.fn(),
  verify: vi.fn(),
}))

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return {
    ...actual,
    api: { ...actual.api, verificationQueue, getSighting, verify },
    // The real one calls URL.createObjectURL on a fetched blob; the view only
    // ever puts the result in an <img src>, so a string is the whole contract.
    fetchPhotoObjectUrl: vi.fn(async () => 'blob:photo-1'),
  }
})

import QueueView from './QueueView.vue'

/**
 * A standing-in server rather than a fixed response, because the view tops the
 * queue up after a decision. A stub that kept returning the pre-decision page
 * would let the item just graded reappear, and the test would be asserting
 * against a server that cannot exist.
 */
function stubQueue(items = [sighting()], photos = [photo()]) {
  let remaining = [...items]

  verificationQueue.mockImplementation(async () => ({
    items: [...remaining],
    total: remaining.length,
    limit: 25,
    offset: 0,
  }))
  getSighting.mockImplementation(async (id: string) => ({
    sighting: items.find((item) => item.id === id) ?? items[0],
    photos,
    verifications: [],
  }))
  verify.mockImplementation(async (id: string) => {
    remaining = remaining.filter((item) => item.id !== id)
    return { id: 'verif-1' }
  })
}

// Every mount registers a window keydown listener, so a wrapper left mounted
// keeps grading photographs in the next test. They are all unmounted in
// afterEach; the leak this prevents is the same class of bug the view had.
const mounted: ReturnType<typeof mount>[] = []

async function open() {
  const wrapper = mount(QueueView, {
    attachTo: document.body,
    // No router is installed, and this screen only links out to the full record.
    // Stubbing it keeps the link in the tree without pulling vue-router in.
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  mounted.push(wrapper)
  // Two awaits: the queue resolves, then the watcher on `current` fires and the
  // detail resolves. One flush leaves the photograph and prediction unrendered.
  await flushPromises()
  await flushPromises()
  return wrapper
}

/** The buttons carry both a word and a shortcut key, so match on the word. */
function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper
    .findAll('button')
    .find((candidate) => candidate.text().toLowerCase().includes(text.toLowerCase()))
  if (!found) throw new Error(`no button matching "${text}"`)
  return found
}

beforeEach(() => {
  URL.revokeObjectURL = vi.fn()
  stubQueue()
})

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('the decision a researcher makes is the decision that is sent (FR6)', () => {
  it('confirms with the model label the reviewer was shown', async () => {
    const wrapper = await open()

    await button(wrapper, 'Confirm').trigger('click')
    await flushPromises()

    expect(verify).toHaveBeenCalledWith('sighting-1', {
      decision: 'confirmed',
      label: 'bleached',
      rejectReason: undefined,
    })
  })

  it('corrects with the label the reviewer picked, not the one the model guessed', async () => {
    const wrapper = await open()

    // The fixture's model says bleached. The reviewer overrules it.
    await button(wrapper, 'Healthy').trigger('click')
    await flushPromises()

    expect(verify).toHaveBeenCalledWith('sighting-1', {
      decision: 'corrected',
      label: 'healthy',
      rejectReason: undefined,
    })
  })

  it('rejects with the reason chosen, and only after the reason is asked for', async () => {
    const wrapper = await open()

    // The first press opens the reason panel; it must not decide anything itself.
    await button(wrapper, 'Reject photograph').trigger('click')
    expect(verify).not.toHaveBeenCalled()

    await button(wrapper, 'Reject').trigger('click')
    await flushPromises()

    expect(verify).toHaveBeenCalledWith('sighting-1', {
      decision: 'rejected',
      label: undefined,
      rejectReason: 'blurry',
    })
  })

  it('advances to the next sighting once a decision is recorded', async () => {
    stubQueue(
      [sighting(), sighting({ id: 'sighting-2', capturedAt: '2026-08-22T06:00:00Z' })],
      [photo()],
    )
    const wrapper = await open()
    expect(wrapper.text()).toContain('2 awaiting')

    await button(wrapper, 'Confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('1 awaiting')
    // The detail for the next item is fetched without another interaction.
    expect(getSighting).toHaveBeenCalledWith('sighting-2')
  })

  it('does not send a second decision while one is in flight', async () => {
    let release = () => {}
    verify.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = () => resolve({ id: 'verif-1' })
        }),
    )
    const wrapper = await open()

    await button(wrapper, 'Confirm').trigger('click')
    await button(wrapper, 'Confirm').trigger('click')
    expect(verify).toHaveBeenCalledTimes(1)

    release()
    await flushPromises()
  })

  it('says so when the decision could not be recorded, rather than advancing', async () => {
    const { ApiError } = await import('@/lib/api')
    verify.mockRejectedValue(new ApiError(409, 'conflict', 'Already verified by someone else.'))
    const wrapper = await open()

    await button(wrapper, 'Confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Already verified by someone else.')
    expect(wrapper.text()).toContain('1 awaiting')
  })
})

describe('the keyboard path agrees with the buttons', () => {
  // Dispatched at an element, never at `window`. With nothing focused a browser
  // targets document.body and the event bubbles to the window listener; firing
  // at the window directly would hand the handler a target with no getAttribute,
  // which is a situation the browser never produces.
  async function press(key: string, target: EventTarget = document.body) {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
    await flushPromises()
  }

  it('C confirms, H and B correct', async () => {
    await open()

    await press('c')
    expect(verify).toHaveBeenLastCalledWith(
      'sighting-1',
      expect.objectContaining({ decision: 'confirmed', label: 'bleached' }),
    )
  })

  it('H corrects to healthy', async () => {
    await open()

    await press('h')
    expect(verify).toHaveBeenLastCalledWith(
      'sighting-1',
      expect.objectContaining({ decision: 'corrected', label: 'healthy' }),
    )
  })

  it('B corrects to bleached', async () => {
    await open()

    await press('b')
    expect(verify).toHaveBeenLastCalledWith(
      'sighting-1',
      expect.objectContaining({ decision: 'corrected', label: 'bleached' }),
    )
  })

  it('ignores a shortcut typed into a text field', async () => {
    await open()
    const input = document.createElement('input')
    document.body.appendChild(input)

    await press('b', input)

    // Someone typing "bleached" into a comment must not grade the photograph.
    expect(verify).not.toHaveBeenCalled()
  })

  it('stops listening once the screen is left', async () => {
    const wrapper = await open()
    wrapper.unmount()

    await press('c')

    expect(verify).not.toHaveBeenCalled()
  })
})

describe('the model is presented as a claim, not a verdict (FR8, NFR13)', () => {
  it('shows the model version that produced the assessment', async () => {
    const wrapper = await open()
    expect(wrapper.text()).toContain('effnetb0-0.1.0')
  })

  it('shows the confidence and the lattice tally beside the extent', async () => {
    const wrapper = await open()

    // 9 of 25 cells bleached in the fixture: the reviewer can check the number
    // against the grid without counting squares.
    expect(wrapper.text()).toContain('9/25')
    expect(wrapper.text()).toContain('36%')
  })

  it('draws the patch lattice, and lets it be taken off the photograph', async () => {
    const wrapper = await open()
    expect(wrapper.findComponent({ name: 'PatchLattice' }).exists()).toBe(true)

    await button(wrapper, 'Hide model grid').trigger('click')

    expect(wrapper.findComponent({ name: 'PatchLattice' }).exists()).toBe(false)
  })

  it('names the resolution, and flags one too low to judge', async () => {
    stubQueue([sighting()], [photo({ width: 320, height: 240 })])
    const wrapper = await open()

    expect(wrapper.text()).toContain('320')
    expect(wrapper.text()).toContain('low resolution')
  })

  it('says the model has not run rather than showing an empty assessment', async () => {
    stubQueue([sighting()], [photo({ prediction: undefined })])
    const wrapper = await open()

    expect(wrapper.text()).toContain('No model result yet')
  })

  it('offers no confirm action when there is nothing to confirm', async () => {
    stubQueue([sighting()], [photo({ prediction: undefined })])
    const wrapper = await open()

    // Confirming an assessment that does not exist would write a researcher's
    // agreement with nothing at all.
    expect(button(wrapper, 'Confirm').attributes('disabled')).toBeDefined()
  })
})

describe('the empty and failed states', () => {
  it('says the queue is clear rather than showing an empty frame', async () => {
    stubQueue([])
    const wrapper = await open()

    expect(wrapper.text()).toContain('The queue is clear')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('surfaces a failure to load as an alert', async () => {
    const { ApiError } = await import('@/lib/api')
    verificationQueue.mockRejectedValue(new ApiError(503, 'unavailable', 'Database is down.'))
    const wrapper = await open()

    expect(wrapper.find('[role="alert"]').text()).toContain('Database is down.')
  })
})

describe('each photograph is fetched once', () => {
  /*
   * This started as a check that the detail is re-read when the queue advances
   * and found the opposite problem: opening the screen fetched it twice.
   * onMounted awaited loadCurrentDetail() after loadQueue(), and loading the
   * queue had already moved `current` off null and triggered the watcher. Two
   * concurrent runs, two image downloads, and the object URL that lost the race
   * was overwritten without URL.revokeObjectURL - leaking one blob per mount.
   */
  it('reads the detail once on open, not once per trigger', async () => {
    stubQueue(
      [sighting(), sighting({ id: 'sighting-2' })],
      [photo({ prediction: prediction({ label: 'healthy' }) })],
    )
    await open()

    expect(getSighting).toHaveBeenCalledTimes(1)
    expect(getSighting).toHaveBeenCalledWith('sighting-1')
  })

  it('revokes the previous object URL before replacing it', async () => {
    const revoke = vi.fn()
    URL.revokeObjectURL = revoke
    stubQueue([sighting(), sighting({ id: 'sighting-2' })], [photo()])
    const wrapper = await open()

    await button(wrapper, 'Confirm').trigger('click')
    await flushPromises()

    expect(revoke).toHaveBeenCalledWith('blob:photo-1')
  })
})
