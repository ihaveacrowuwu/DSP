<script setup lang="ts">
/**
 * The calendar grid. Built rather than borrowed, for two reasons: a native
 * `<input type="date">` cannot be themed and shows a different popup in every
 * browser, and no native control can shade a range across two months, which is
 * the only thing this system actually asks a calendar to do.
 *
 * It renders one month at a time as six fixed rows, so paging never resizes the
 * panel, and it knows nothing about how the range is stored - the parent owns the
 * start and end and this emits the day that was chosen.
 */
import { computed, nextTick, ref, watch } from 'vue'

import Icon from '@/components/ui/Icon.vue'
import { iconChevronLeft, iconChevronRight } from '@/lib/icons'
import {
  WEEKDAYS,
  formatDay,
  isWithin,
  monthGrid,
  monthLabel,
  parseISO,
  shiftDays,
  shiftMonth,
  todayISO,
} from '@/lib/dates'

const props = defineProps<{
  /** Range start, or the single selected day. */
  start: string | null
  /** Range end. Null while the range is half-open. */
  end: string | null
  /** Day the keyboard cursor and the visible month should open on. */
  focus?: string | null
  /** Inclusive bounds; days outside them are shown but not selectable. */
  min?: string | null
  max?: string | null
}>()

const emit = defineEmits<{ pick: [iso: string] }>()

const today = todayISO()

/** Which month is on screen. Seeded from the current selection, not from today. */
const initial = parseISO(props.start ?? props.focus ?? today) ?? parseISO(today)!
const year = ref(initial.y)
const month = ref(initial.m)

/** The day the arrow keys are on. Also what a fresh focus lands on. */
const cursor = ref(props.start ?? props.focus ?? today)
/** Day under the pointer, used to preview a half-open range before it closes. */
const hovered = ref<string | null>(null)
/** Which way the grid should fly in when the month changes. */
const direction = ref<'next' | 'prev'>('next')

const cells = computed(() => monthGrid(year.value, month.value))
const label = computed(() => monthLabel(year.value, month.value))
/** Changing this key is what replays the entrance animation. */
const monthKey = computed(() => `${year.value}-${month.value}`)

function outOfBounds(iso: string): boolean {
  if (props.min && iso < props.min) return true
  if (props.max && iso > props.max) return true
  return false
}

/**
 * The far edge of the range being previewed: the real end if there is one, the
 * hovered day while the range is still half-open. This is what lets someone see
 * the span they are about to select before committing to it.
 */
const previewEnd = computed(() => {
  if (props.end) return props.end
  if (props.start && hovered.value && hovered.value > props.start) return hovered.value
  return null
})

function classesFor(iso: string, inMonth: boolean) {
  const end = previewEnd.value
  return {
    'is-outside': !inMonth,
    'is-today': iso === today,
    'is-start': iso === props.start,
    'is-end': end !== null && iso === end,
    'is-inside': props.start !== null && end !== null && isWithin(iso, props.start, end),
    'is-provisional': props.start !== null && props.end === null && end !== null,
  }
}

function page(by: number) {
  direction.value = by > 0 ? 'next' : 'prev'
  const next = shiftMonth(year.value, month.value, by)
  year.value = next.year
  month.value = next.month
}

/** Brings a day into view, paging the month if it falls outside the current one. */
function reveal(iso: string) {
  const parts = parseISO(iso)
  if (!parts) return
  if (parts.y !== year.value || parts.m !== month.value) {
    direction.value =
      parts.y * 12 + parts.m > year.value * 12 + month.value ? 'next' : 'prev'
    year.value = parts.y
    month.value = parts.m
  }
}

/**
 * The wrapper, not the grid itself. A template ref on the transitioned grid is
 * unreliable: when the outgoing month finally unmounts it clears the ref that the
 * incoming month already claimed, leaving it null. The wrapper never unmounts.
 */
const frameRef = ref<HTMLElement | null>(null)

/** The day cell in the month currently on screen - the last grid in the frame. */
function cellFor(iso: string): HTMLButtonElement | null {
  const grids = frameRef.value?.querySelectorAll<HTMLElement>('[data-month-grid]') ?? []
  const live = grids[grids.length - 1]
  return live?.querySelector<HTMLButtonElement>(`[data-iso="${iso}"]`) ?? null
}

function moveCursor(by: number) {
  const next = shiftDays(cursor.value, by)
  cursor.value = next
  reveal(next)
  void nextTick(() => cellFor(next)?.focus())
}

function onKeydown(event: KeyboardEvent) {
  switch (event.key) {
    case 'ArrowLeft':
      event.preventDefault()
      moveCursor(-1)
      break
    case 'ArrowRight':
      event.preventDefault()
      moveCursor(1)
      break
    case 'ArrowUp':
      event.preventDefault()
      moveCursor(-7)
      break
    case 'ArrowDown':
      event.preventDefault()
      moveCursor(7)
      break
    case 'PageUp':
      event.preventDefault()
      page(-1)
      break
    case 'PageDown':
      event.preventDefault()
      page(1)
      break
  }
}

function choose(iso: string) {
  if (outOfBounds(iso)) return
  cursor.value = iso
  emit('pick', iso)
}

// Clearing the range from outside (the field's Clear action) should bring the
// calendar back to a sensible month rather than leaving it wherever it was.
watch(
  () => props.start,
  (value) => {
    if (value) reveal(value)
  },
)

defineExpose({
  /** Called by the field when the panel opens, so arrows work immediately. */
  focusCursor() {
    void nextTick(() => cellFor(cursor.value)?.focus())
  },
})
</script>

<template>
  <div class="calendar" @keydown="onKeydown">
    <header class="head">
      <button
        type="button"
        class="btn btn-ghost btn-icon"
        aria-label="Previous month"
        @click="page(-1)"
      >
        <Icon :path="iconChevronLeft" :size="1.1" />
      </button>
      <!-- aria-live so a screen reader hears the month when paging with arrows. -->
      <span class="month readout" aria-live="polite">{{ label }}</span>
      <button type="button" class="btn btn-ghost btn-icon" aria-label="Next month" @click="page(1)">
        <Icon :path="iconChevronRight" :size="1.1" />
      </button>
    </header>

    <div class="weekdays" aria-hidden="true">
      <span v-for="day in WEEKDAYS" :key="day">{{ day }}</span>
    </div>

    <div ref="frameRef" class="grid-frame">
      <Transition :name="`page-${direction}`">
        <div :key="monthKey" class="grid" data-month-grid :aria-label="label" role="group">
          <button
            v-for="cell in cells"
            :key="cell.iso"
            type="button"
            class="day"
            :class="classesFor(cell.iso, cell.inMonth)"
            :data-iso="cell.iso"
            :disabled="outOfBounds(cell.iso)"
            :tabindex="cell.iso === cursor ? 0 : -1"
            :aria-current="cell.iso === today ? 'date' : undefined"
            :aria-label="formatDay(cell.iso)"
            @click="choose(cell.iso)"
            @pointerenter="hovered = cell.iso"
            @pointerleave="hovered = null"
          >
            {{ cell.day }}
          </button>
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.calendar {
  display: grid;
  gap: 0.5rem;
  /* Seven 2rem columns plus gaps and padding - fixed so the panel never resizes
     between a month with five rows and one with six. */
  width: 17.5rem;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.25rem;
}

.month {
  font-size: var(--step--1);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink);
}

.weekdays,
.grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.weekdays span {
  padding-bottom: 0.125rem;
  text-align: center;
  font-family: var(--font-mono);
  font-size: var(--step--2);
  letter-spacing: 0.08em;
  color: var(--ink-4);
}

/* Holds the outgoing month absolutely while the incoming one animates, so the
   two never stack and push the footer down. */
.grid-frame {
  position: relative;
}

.day {
  display: grid;
  place-items: center;
  aspect-ratio: 1;
  padding: 0;
  border: 1px solid transparent;
  /* Rounded well past a square but not a circle: the range fills read as one
     continuous ribbon rather than a row of separate dots. */
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  transition: scale var(--dur) var(--ease-spring), background-color var(--dur-fast) linear,
    color var(--dur-fast) linear, border-color var(--dur-fast) linear;
}

.day:hover:not(:disabled) {
  scale: 1.14;
  background: var(--surface-2);
  color: var(--ink);
}

.day:active:not(:disabled) {
  scale: 0.94;
}

.day:disabled {
  color: var(--ink-4);
  opacity: 0.4;
  cursor: not-allowed;
}

.day.is-outside {
  color: var(--ink-4);
}

/* Today is marked by an outline, never a fill: a fill would compete with the
   selection and there is no way to tell which one you picked. */
.day.is-today {
  border-color: var(--line-strong);
}

.day.is-inside {
  background: var(--reef-wash);
  color: var(--ink);
  border-radius: 4px;
}

.day.is-start,
.day.is-end {
  background: var(--reef);
  border-color: var(--reef);
  color: var(--accent-ink);
  font-weight: 600;
}

/* While the range is half-open the far end is only a preview, so it is drawn
   hollow — you can see what you would get without it looking already chosen. */
.day.is-provisional.is-end {
  background: var(--reef-wash);
  border-color: var(--reef);
  color: var(--ink);
}

/* Month paging: the new grid arrives from the side you paged towards. */
.page-next-enter-active,
.page-prev-enter-active {
  transition: translate var(--dur) var(--ease-out), opacity var(--dur-fast) linear;
}

.page-next-leave-active,
.page-prev-leave-active {
  position: absolute;
  inset: 0;
  transition: opacity var(--dur-fast) linear;
}

.page-next-enter-from {
  translate: 18px 0;
  opacity: 0;
}

.page-prev-enter-from {
  translate: -18px 0;
  opacity: 0;
}

.page-next-leave-to,
.page-prev-leave-to {
  opacity: 0;
}
</style>
