/**
 * Calendar arithmetic for the date picker.
 *
 * Dates are handled as plain `YYYY-MM-DD` strings, never as Date objects with a
 * time component. A sighting captured at 06:00 in Male belongs to that calendar
 * day everywhere, and passing a Date through `toISOString()` would shift it to
 * the previous day for anyone west of UTC - a filter that silently drops the
 * first day of the range. Where a Date is unavoidable it is constructed at local
 * midday, which no time zone offset can push into an adjacent day.
 */

export interface CalendarCell {
  iso: string
  day: number
  /** False for the leading and trailing days that pad the grid to whole weeks. */
  inMonth: boolean
}

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]

/** Monday-first, matching the calendar convention used across the Maldives. */
export const WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function toISO(year: number, month: number, day: number): string {
  return `${year}-${pad(month + 1)}-${pad(day)}`
}

/** Today in the viewer's own time zone, as an ISO day string. */
export function todayISO(): string {
  const now = new Date()
  return toISO(now.getFullYear(), now.getMonth(), now.getDate())
}

/** Splits `YYYY-MM-DD` into numbers; returns null for anything else. */
export function parseISO(iso: string | null | undefined): { y: number; m: number; d: number } | null {
  if (!iso) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return null
  return { y: Number(match[1]), m: Number(match[2]) - 1, d: Number(match[3]) }
}

export function monthLabel(year: number, month: number): string {
  return `${MONTHS[month]} ${year}`
}

/** Shifts a year/month pair by whole months, carrying across year boundaries. */
export function shiftMonth(year: number, month: number, by: number): { year: number; month: number } {
  const total = year * 12 + month + by
  return { year: Math.floor(total / 12), month: ((total % 12) + 12) % 12 }
}

/**
 * The grid for one month: whole weeks, Monday first, padded with the neighbouring
 * months' days so every row has seven cells and the grid never reflows between
 * months. Always six rows - a five-row month next to a six-row one would resize
 * the panel as the user pages through it.
 */
export function monthGrid(year: number, month: number): CalendarCell[] {
  const first = new Date(year, month, 1, 12)
  // getDay() is Sunday-first; rotate so Monday is 0.
  const lead = (first.getDay() + 6) % 7
  const cells: CalendarCell[] = []

  for (let index = 0; index < 42; index += 1) {
    const date = new Date(year, month, 1 - lead + index, 12)
    cells.push({
      iso: toISO(date.getFullYear(), date.getMonth(), date.getDate()),
      day: date.getDate(),
      inMonth: date.getMonth() === month,
    })
  }
  return cells
}

/** Moves an ISO day by whole days, letting Date handle month and leap lengths. */
export function shiftDays(iso: string, by: number): string {
  const parts = parseISO(iso)
  if (!parts) return iso
  const date = new Date(parts.y, parts.m, parts.d + by, 12)
  return toISO(date.getFullYear(), date.getMonth(), date.getDate())
}

/** ISO day strings sort chronologically as text, which is the point of the format. */
export function isBefore(a: string, b: string): boolean {
  return a < b
}

export function isWithin(iso: string, start: string, end: string): boolean {
  return iso > start && iso < end
}

/** Compact display form: "4 Mar 2026". Fixed-width enough for a trigger button. */
export function formatDay(iso: string): string {
  const parts = parseISO(iso)
  if (!parts) return ''
  return new Date(parts.y, parts.m, parts.d, 12).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}
