import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ConditionChip from './ConditionChip.vue'
import type { Condition, SightingStatus } from '@/lib/api'

/**
 * NFR13: "ML-only labels shall be visually distinct from expert-verified labels in
 * every interface that shows a condition." Its stated verification method is a UI
 * review checklist — a human looking at screenshots — which is the weakest kind of
 * evidence for a requirement about a mistake that matters.
 *
 * The mistake is specific: reading a model's guess as a marine biologist's finding.
 * The dashboard is where researchers act on that distinction, so it is worth an
 * assertion rather than an eyeball.
 *
 * **Colour is deliberately not asserted.** NFR13 says "not colour alone", so testing
 * the colour would be testing the thing that must not be load-bearing. These tests
 * check what survives a greyscale print and a colour-blind reader: the word, and the
 * class that carries border and marker style.
 */

function chip(props: {
  condition?: Condition
  status: SightingStatus
  verified: boolean
  severity?: number
}) {
  return mount(ConditionChip, { props })
}

describe('provenance is carried by more than colour (NFR13)', () => {
  it('says "expert" when a human decided', () => {
    const wrapper = chip({ condition: 'bleached', status: 'verified', verified: true })
    expect(wrapper.text()).toContain('expert')
    expect(wrapper.text()).not.toContain('model')
  })

  it('says "model" when only the classifier has', () => {
    const wrapper = chip({
      condition: 'bleached',
      status: 'awaiting_verification',
      verified: false,
    })
    expect(wrapper.text()).toContain('model')
    expect(wrapper.text()).not.toContain('expert')
  })

  it('marks the two with different classes, so shape and border can differ', () => {
    const expert = chip({ condition: 'healthy', status: 'verified', verified: true })
    const model = chip({ condition: 'healthy', status: 'awaiting_verification', verified: false })

    expect(expert.classes()).toContain('chip-verified')
    expect(model.classes()).toContain('chip-predicted')
    // The distinction must not rest on the condition class, which is the colour.
    expect(expert.classes()).toContain('chip-healthy')
    expect(model.classes()).toContain('chip-healthy')
  })

  it('is distinguishable with every colour class stripped', () => {
    // The greyscale test, made concrete: remove the classes that carry hue and the
    // two chips must still differ.
    const colourClasses = ['chip-healthy', 'chip-bleached']
    const strip = (classes: string[]) => classes.filter((c) => !colourClasses.includes(c))

    const expert = strip(chip({ condition: 'bleached', status: 'verified', verified: true }).classes())
    const model = strip(
      chip({ condition: 'bleached', status: 'awaiting_verification', verified: false }).classes(),
    )
    expect(expert).not.toEqual(model)
  })

  it('never labels an unassessed sighting with a provenance it does not have', () => {
    const wrapper = chip({ status: 'pending_photos', verified: false })
    expect(wrapper.text()).not.toContain('model')
    expect(wrapper.text()).not.toContain('expert')
  })

  it('drops provenance on a rejected sighting, which is a decision about the photograph', () => {
    const wrapper = chip({ condition: 'bleached', status: 'rejected', verified: true })
    expect(wrapper.text()).toBe('Rejected')
    expect(wrapper.text()).not.toContain('expert')
  })
})

describe('what the chip says', () => {
  it.each([
    ['pending_photos', 'Awaiting photos'],
    ['processing', 'Analysing'],
    ['rejected', 'Rejected'],
  ] as const)('%s reads as "%s"', (status, expected) => {
    expect(chip({ status, verified: false }).text()).toBe(expected)
  })

  it('reads "Unassessed" when there is no condition yet', () => {
    expect(chip({ status: 'awaiting_verification', verified: false }).text()).toBe('Unassessed')
  })

  it('paints an unassessed sighting off the condition scale entirely', () => {
    const wrapper = chip({ status: 'awaiting_verification', verified: false })
    expect(wrapper.classes()).toContain('chip-pending')
    expect(wrapper.classes()).not.toContain('chip-healthy')
    expect(wrapper.classes()).not.toContain('chip-bleached')
  })

  it('names the condition and its extent as a percentage', () => {
    const wrapper = chip({
      condition: 'bleached',
      status: 'verified',
      verified: true,
      severity: 0.62,
    })
    expect(wrapper.text()).toContain('Bleached')
    expect(wrapper.text()).toContain('62%')
  })

  it('omits the percentage when severity is unknown, rather than showing 0%', () => {
    // "Bleached 0%" would read as "not bleached", which is the opposite of unknown.
    const wrapper = chip({ condition: 'bleached', status: 'verified', verified: true })
    expect(wrapper.text()).toContain('Bleached')
    expect(wrapper.text()).not.toContain('%')
  })

  it('shows 0% when severity really is zero', () => {
    const wrapper = chip({
      condition: 'healthy',
      status: 'verified',
      verified: true,
      severity: 0,
    })
    expect(wrapper.text()).toContain('0%')
  })

  it('rounds severity rather than truncating it', () => {
    expect(
      chip({ condition: 'bleached', status: 'verified', verified: true, severity: 0.675 }).text(),
    ).toContain('68%')
    expect(
      chip({ condition: 'bleached', status: 'verified', verified: true, severity: 0.674 }).text(),
    ).toContain('67%')
  })
})
