import { describe, expect, it } from 'vitest'

import {
  formatDay,
  isBefore,
  isWithin,
  monthGrid,
  monthLabel,
  parseISO,
  shiftDays,
  shiftMonth,
  toISO,
  todayISO,
  WEEKDAYS,
} from './dates'

/**
 * The date picker feeds the map and queue filters (FR7), and its own header warns
 * about the bug that makes calendar code worth testing: pass a Date through
 * `toISOString()` west of UTC and the day shifts backwards, silently dropping the
 * first day of a filtered range. Nothing tested that the warning had been heeded.
 *
 * These run under whatever time zone the machine is in. Where a test depends on the
 * offset it says so, rather than pinning TZ and proving the code works in one place.
 */
describe('ISO day strings', () => {
  it('pads months and days, because 2026-3-4 is not a date', () => {
    expect(toISO(2026, 2, 4)).toBe('2026-03-04')
  })

  it('takes a zero-based month, matching Date', () => {
    expect(toISO(2026, 0, 1)).toBe('2026-01-01')
    expect(toISO(2026, 11, 31)).toBe('2026-12-31')
  })

  it('round-trips through parseISO', () => {
    const parsed = parseISO('2026-08-21')
    expect(parsed).toEqual({ y: 2026, m: 7, d: 21 })
    expect(toISO(parsed!.y, parsed!.m, parsed!.d)).toBe('2026-08-21')
  })

  it.each([
    ['', 'empty'],
    ['2026-8-21', 'unpadded'],
    ['21-08-2026', 'day first'],
    ['2026/08/21', 'slashes'],
    ['2026-08-21T06:00:00Z', 'a timestamp rather than a day'],
    ['not a date', 'prose'],
  ])('rejects %s (%s) rather than guessing', (input) => {
    expect(parseISO(input)).toBeNull()
  })

  it('treats null and undefined as absent, not as an error', () => {
    expect(parseISO(null)).toBeNull()
    expect(parseISO(undefined)).toBeNull()
  })
})

describe('the time-zone hazard the module was written to avoid', () => {
  /**
   * This is the actual defect being guarded against. In any negative-offset zone
   * `new Date(2026, 0, 1).toISOString()` is 2025-12-31, so a picker built that way
   * hands the API the wrong day. `todayISO` and `monthGrid` must agree with the
   * *local* calendar instead.
   */
  it('todayISO agrees with the local calendar, not with UTC', () => {
    const now = new Date()
    const local = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(
      now.getDate(),
    ).padStart(2, '0')}`
    expect(todayISO()).toBe(local)
  })

  it('a midnight boundary does not move a day', () => {
    // Every cell is built at local midday precisely so no offset can push it into
    // an adjacent day. If the implementation used midnight this would fail for
    // roughly half the world.
    const grid = monthGrid(2026, 0)
    const january = grid.filter((cell) => cell.inMonth)
    expect(january[0].iso).toBe('2026-01-01')
    expect(january[january.length - 1].iso).toBe('2026-01-31')
  })
})

describe('the month grid', () => {
  it('is always six whole weeks, so the panel never resizes as you page through it', () => {
    for (const [year, month] of [
      [2026, 1], // February, 28 days, and 2026 is not a leap year
      [2026, 7],
      [2027, 1],
      [2024, 1], // February in a leap year
    ] as const) {
      expect(monthGrid(year, month)).toHaveLength(42)
    }
  })

  it('starts on a Monday, matching the weekday header', () => {
    expect(WEEKDAYS[0]).toBe('Mo')
    // 1 August 2026 is a Saturday, so the grid must lead with the preceding Monday.
    const grid = monthGrid(2026, 7)
    expect(grid[0].iso).toBe('2026-07-27')
    expect(new Date(2026, 6, 27, 12).getDay()).toBe(1)
  })

  it('pads with neighbouring days and marks them out of month', () => {
    const grid = monthGrid(2026, 7)
    expect(grid[0].inMonth).toBe(false)
    expect(grid.filter((cell) => cell.inMonth)).toHaveLength(31)
    expect(grid.filter((cell) => !cell.inMonth).length).toBe(42 - 31)
  })

  it('gives February its leap day in a leap year and not otherwise', () => {
    const leap = monthGrid(2024, 1).filter((cell) => cell.inMonth)
    const common = monthGrid(2026, 1).filter((cell) => cell.inMonth)
    expect(leap).toHaveLength(29)
    expect(leap[leap.length - 1].iso).toBe('2024-02-29')
    expect(common).toHaveLength(28)
    expect(common[common.length - 1].iso).toBe('2026-02-28')
  })

  it('has no duplicate or missing days across the six rows', () => {
    const grid = monthGrid(2026, 7)
    const unique = new Set(grid.map((cell) => cell.iso))
    expect(unique.size).toBe(42)
  })
})

describe('shifting', () => {
  it('carries months across a year boundary in both directions', () => {
    expect(shiftMonth(2026, 11, 1)).toEqual({ year: 2027, month: 0 })
    expect(shiftMonth(2026, 0, -1)).toEqual({ year: 2025, month: 11 })
  })

  it('handles shifts larger than a year', () => {
    expect(shiftMonth(2026, 5, 24)).toEqual({ year: 2028, month: 5 })
    expect(shiftMonth(2026, 5, -18)).toEqual({ year: 2024, month: 11 })
  })

  it('shifts days across months, years and a leap day', () => {
    expect(shiftDays('2026-01-31', 1)).toBe('2026-02-01')
    expect(shiftDays('2026-03-01', -1)).toBe('2026-02-28')
    expect(shiftDays('2024-02-28', 1)).toBe('2024-02-29')
    expect(shiftDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(shiftDays('2026-01-01', -1)).toBe('2025-12-31')
  })

  it('returns the input unchanged when it is not a day string', () => {
    expect(shiftDays('nonsense', 3)).toBe('nonsense')
  })
})

describe('comparison', () => {
  it('orders ISO day strings as text, which is the point of the format', () => {
    expect(isBefore('2026-01-01', '2026-01-02')).toBe(true)
    expect(isBefore('2026-01-02', '2026-01-01')).toBe(false)
    expect(isBefore('2025-12-31', '2026-01-01')).toBe(true)
    // Not before itself: a range of one day must not read as inverted.
    expect(isBefore('2026-01-01', '2026-01-01')).toBe(false)
  })

  it('isWithin is exclusive at both ends', () => {
    expect(isWithin('2026-01-15', '2026-01-01', '2026-01-31')).toBe(true)
    expect(isWithin('2026-01-01', '2026-01-01', '2026-01-31')).toBe(false)
    expect(isWithin('2026-01-31', '2026-01-01', '2026-01-31')).toBe(false)
  })
})

describe('display', () => {
  it('names the month and year', () => {
    expect(monthLabel(2026, 7)).toBe('August 2026')
    expect(monthLabel(2026, 0)).toBe('January 2026')
  })

  it('formats a day without shifting it', () => {
    // The locale is the machine's, so the assertion is on the parts rather than the
    // exact arrangement — what must not happen is the 21st rendering as the 20th.
    const formatted = formatDay('2026-08-21')
    expect(formatted).toContain('21')
    expect(formatted).toContain('2026')
  })

  it('returns empty for an unparseable day rather than "Invalid Date"', () => {
    expect(formatDay('2026-8-21')).toBe('')
    expect(formatDay('')).toBe('')
  })
})
