/**
 * The low-resolution threshold is a judgement call sitting in a constant, which
 * is exactly the kind of thing that gets nudged later without anyone noticing
 * what it was for. These tests pin it to the two resolutions that actually reach
 * the dashboard: the 224 px dataset crops the seeder attaches, and the real
 * photographs, which are an order of magnitude larger.
 */
import { describe, expect, it } from 'vitest'

import { dimensions, isLowResolution, lowResolutionNote } from './photos'

describe('isLowResolution', () => {
  it('flags the 224px dataset crops', () => {
    expect(isLowResolution(224, 224)).toBe(true)
  })

  it('does not flag real photographs', () => {
    expect(isLowResolution(900, 700)).toBe(false)
    expect(isLowResolution(1024, 768)).toBe(false)
  })

  it('judges on the shorter side, because the frame is square', () => {
    // A wide, short photograph is cropped to its height. 4000 px of width buys
    // nothing when only the centre 300 px square is on screen.
    expect(isLowResolution(4000, 300)).toBe(true)
  })
})

describe('lowResolutionNote', () => {
  it('names the actual resolution, because how low it is changes the conclusion', () => {
    expect(lowResolutionNote(224, 224)).toContain('224×224')
  })

  it('says what to do about it, not just that it is a problem', () => {
    expect(lowResolutionNote(224, 224)).toMatch(/reject/i)
  })
})

describe('dimensions', () => {
  it('reads as a resolution, not a multiplication', () => {
    expect(dimensions(900, 700)).toBe('900×700')
  })
})
