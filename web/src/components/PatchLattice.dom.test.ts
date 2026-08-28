import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PatchLattice from './PatchLattice.vue'
import type { Patch } from '@/lib/api'

/**
 * The patch lattice is drawn three times in this project - here, in Compose on
 * Android and in UIKit on iOS - from one specification in
 * `mobile-shared/README.md`:
 *
 *   cell opacity, two different formulas: over a photograph use
 *   `0.28 + confidence x 0.42`, and for the small standalone glyph in a list row use
 *   `0.45 + confidence x 0.55`
 *
 * Three implementations of one formula is three chances to drift, and a drifted
 * lattice is not a visual nit: the overlay's ceiling exists so that past roughly 0.7
 * the cells annotate the photograph instead of replacing it. A reviewer who cannot
 * see the coral through the judgement cannot check the judgement. These constants are
 * therefore asserted numerically rather than described.
 */

const OVERLAY_FLOOR = 0.28
const OVERLAY_RANGE = 0.42
const GLYPH_FLOOR = 0.45
const GLYPH_RANGE = 0.55

function patch(row: number, col: number, label: Patch['label'], confidence: number): Patch {
  return { row, col, label, confidence } as Patch
}

function opacities(wrapper: ReturnType<typeof mount>): number[] {
  return wrapper.findAll('.cell').map((cell) => Number(cell.attributes('style')?.match(/opacity:\s*([\d.]+)/)?.[1]))
}

describe('the two opacity formulas (mobile-shared contract)', () => {
  it.each([0, 0.25, 0.5, 0.75, 1])(
    'overlay opacity at confidence %s is 0.28 + c × 0.42',
    (confidence) => {
      const wrapper = mount(PatchLattice, {
        props: { patches: [patch(0, 0, 'healthy', confidence)], grid: 1, variant: 'overlay' },
      })
      expect(opacities(wrapper)[0]).toBeCloseTo(OVERLAY_FLOOR + confidence * OVERLAY_RANGE, 6)
    },
  )

  it.each([0, 0.25, 0.5, 0.75, 1])(
    'glyph opacity at confidence %s is 0.45 + c × 0.55',
    (confidence) => {
      const wrapper = mount(PatchLattice, {
        props: { patches: [patch(0, 0, 'healthy', confidence)], grid: 1, variant: 'glyph' },
      })
      expect(opacities(wrapper)[0]).toBeCloseTo(GLYPH_FLOOR + confidence * GLYPH_RANGE, 6)
    },
  )

  it('defaults to the glyph formula, because that is the variant used in list rows', () => {
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', 1)], grid: 1 },
    })
    expect(opacities(wrapper)[0]).toBeCloseTo(GLYPH_FLOOR + GLYPH_RANGE, 6)
  })

  it('keeps the overlay clear of solid, so the photograph stays readable', () => {
    // The ceiling is the whole argument for two formulas rather than one.
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'bleached', 1)], grid: 1, variant: 'overlay' },
    })
    expect(opacities(wrapper)[0]).toBeCloseTo(0.7, 6)
    expect(opacities(wrapper)[0]).toBeLessThan(1)
  })

  it('keeps even a hesitant cell visible, so the grid never has holes in it', () => {
    const overlay = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', 0)], grid: 1, variant: 'overlay' },
    })
    const glyph = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', 0)], grid: 1, variant: 'glyph' },
    })
    expect(opacities(overlay)[0]).toBeGreaterThan(0)
    expect(opacities(glyph)[0]).toBeGreaterThan(0)
  })

  it('the glyph is more opaque than the overlay at every confidence', () => {
    // The glyph has no photograph underneath, so it uses the full range. If the two
    // formulas were ever swapped, this is what would catch it.
    for (const confidence of [0, 0.3, 0.6, 1]) {
      const overlay = OVERLAY_FLOOR + confidence * OVERLAY_RANGE
      const glyph = GLYPH_FLOOR + confidence * GLYPH_RANGE
      expect(glyph).toBeGreaterThan(overlay)
    }
  })

  it.each([
    [-0.5, 0],
    [1.5, 1],
  ])('clamps a confidence of %s to %s rather than producing an invalid opacity', (given, clamped) => {
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', given)], grid: 1, variant: 'overlay' },
    })
    expect(opacities(wrapper)[0]).toBeCloseTo(OVERLAY_FLOOR + clamped * OVERLAY_RANGE, 6)
  })
})

describe('geometry', () => {
  it('places cells one-based, because CSS grid lines are not array indices', () => {
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', 1), patch(2, 3, 'bleached', 1)], grid: 5 },
    })
    const styles = wrapper.findAll('.cell').map((cell) => cell.attributes('style') ?? '')
    expect(styles[0]).toContain('grid-row: 1')
    expect(styles[0]).toContain('grid-column: 1')
    expect(styles[1]).toContain('grid-row: 3')
    expect(styles[1]).toContain('grid-column: 4')
  })

  it('passes the grid size to CSS so the lattice matches the server tiling', () => {
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', 1)], grid: 7 },
    })
    expect(wrapper.attributes('style')).toContain('--grid: 7')
  })

  it('draws one cell per patch and no placeholders for missing ones', () => {
    // A partial grid must render partially rather than inventing cells the model
    // never judged.
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(0, 0, 'healthy', 1), patch(1, 1, 'healthy', 1)], grid: 5 },
    })
    expect(wrapper.findAll('.cell')).toHaveLength(2)
  })
})

describe('accessibility', () => {
  it('counts the bleached patches for a screen reader', () => {
    const wrapper = mount(PatchLattice, {
      props: {
        patches: [
          patch(0, 0, 'bleached', 0.9),
          patch(0, 1, 'healthy', 0.9),
          patch(1, 0, 'bleached', 0.9),
          patch(1, 1, 'healthy', 0.9),
        ],
        grid: 2,
      },
    })
    expect(wrapper.attributes('role')).toBe('img')
    expect(wrapper.attributes('aria-label')).toBe('2 of 4 patches classified bleached')
  })

  it('says there is no analysis rather than claiming zero bleached patches', () => {
    // "0 of 0 patches classified bleached" would read as a healthy reef.
    const wrapper = mount(PatchLattice, { props: { patches: [], grid: 5 } })
    expect(wrapper.attributes('aria-label')).toBe('No patch analysis available')
    expect(wrapper.findAll('.cell')).toHaveLength(0)
  })
})

describe('the animation is opt-in', () => {
  it('staggers by position when asked, so the sweep reads left to right', () => {
    const wrapper = mount(PatchLattice, {
      props: {
        patches: [patch(0, 0, 'healthy', 1), patch(1, 2, 'healthy', 1)],
        grid: 3,
        animate: true,
      },
    })
    const styles = wrapper.findAll('.cell').map((cell) => cell.attributes('style') ?? '')
    expect(styles[0]).toContain('animation-delay: 0ms')
    expect(styles[1]).toContain(`animation-delay: ${1 * 38 + 2 * 11}ms`)
    expect(wrapper.classes()).toContain('is-animated')
  })

  it('adds no delay and no class by default', () => {
    const wrapper = mount(PatchLattice, {
      props: { patches: [patch(2, 2, 'healthy', 1)], grid: 5 },
    })
    expect(wrapper.findAll('.cell')[0].attributes('style')).toContain('animation-delay: 0ms')
    expect(wrapper.classes()).not.toContain('is-animated')
  })
})
