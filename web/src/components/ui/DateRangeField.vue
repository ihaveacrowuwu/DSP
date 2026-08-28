<script setup lang="ts">
/**
 * Date range picker - one control for the "from" and "to" filter that every
 * data screen in this system uses.
 *
 * It is one control rather than two date fields on purpose. A range has an
 * invariant (the end cannot precede the start) and two independent inputs cannot
 * express it: they let you type an impossible range and only complain afterwards.
 * Here the second click closes the range, clicking before the start moves the
 * start instead, and the shaded span shows what you are about to ask for before
 * you ask.
 *
 * Presets sit above the calendar because most reef questions are relative - "the
 * last month", "this year" - and paging a calendar back to find them is work the
 * interface can do instead.
 */
import { computed, ref } from 'vue'

import CalendarPanel from '@/components/ui/CalendarPanel.vue'
import Icon from '@/components/ui/Icon.vue'
import { useAnchoredPanel } from '@/composables/useAnchoredPanel'
import { formatDay, shiftDays, todayISO } from '@/lib/dates'
import { iconCalendar, iconClose } from '@/lib/icons'

withDefaults(
  defineProps<{
    /** Accessible name; the trigger has no visible label of its own. */
    ariaLabel?: string
  }>(),
  { ariaLabel: 'Date range' },
)

const from = defineModel<string>('from', { required: true })
const to = defineModel<string>('to', { required: true })

const triggerRef = ref<HTMLButtonElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const calendarRef = ref<InstanceType<typeof CalendarPanel> | null>(null)

const panel = useAnchoredPanel({
  anchorRef: triggerRef,
  panelRef,
  minWidthRem: 19,
  estimatedHeight: 340,
})

const label = computed(() => {
  if (from.value && to.value) return `${formatDay(from.value)} - ${formatDay(to.value)}`
  if (from.value) return `From ${formatDay(from.value)}`
  if (to.value) return `Until ${formatDay(to.value)}`
  return 'Any dates'
})

const hasRange = computed(() => Boolean(from.value || to.value))

/**
 * Three clicks-worth of state in one handler: an empty or complete range starts a
 * new one, a day before the open start moves the start rather than rejecting the
 * click, and anything after it closes the range.
 */
function onPick(iso: string) {
  if (!from.value || (from.value && to.value)) {
    from.value = iso
    to.value = ''
    return
  }
  if (iso < from.value) {
    from.value = iso
    return
  }
  to.value = iso
  panel.hide(true)
}

function applyPreset(days: number) {
  const end = todayISO()
  from.value = shiftDays(end, -days)
  to.value = end
  panel.hide(true)
}

function clear() {
  from.value = ''
  to.value = ''
}

async function open() {
  await panel.show()
  calendarRef.value?.focusCursor()
}

function onPanelKey(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    panel.hide(true)
  }
}
</script>

<template>
  <div class="wrap">
    <button
      ref="triggerRef"
      type="button"
      class="trigger"
      :class="{ 'is-open': panel.open.value, 'is-set': hasRange }"
      :aria-label="ariaLabel"
      aria-haspopup="dialog"
      :aria-expanded="panel.open.value"
      @click="panel.open.value ? panel.hide() : open()"
    >
      <Icon :path="iconCalendar" :size="1.05" />
      <span class="trigger-label readout">{{ label }}</span>
    </button>

    <!-- Clearing is a separate target so it never costs a trip through the
         calendar, and it only exists when there is something to clear. -->
    <button
      v-if="hasRange"
      type="button"
      class="btn btn-ghost btn-icon clear"
      data-tip="Clear dates"
      aria-label="Clear date range"
      @click="clear"
    >
      <Icon :path="iconClose" :size="0.95" />
    </button>
  </div>

  <Teleport to="body">
    <div
      v-if="panel.open.value"
      ref="panelRef"
      class="panel"
      :style="panel.style.value"
      role="dialog"
      aria-modal="false"
      :aria-label="ariaLabel"
      @keydown="onPanelKey"
    >
      <div class="presets">
        <button type="button" class="preset row-hover" @click="applyPreset(29)">30 days</button>
        <button type="button" class="preset row-hover" @click="applyPreset(89)">3 months</button>
        <button type="button" class="preset row-hover" @click="applyPreset(364)">12 months</button>
        <button
          type="button"
          class="preset row-hover"
          :disabled="!hasRange"
          @click="clear"
        >
          All time
        </button>
      </div>

      <CalendarPanel
        ref="calendarRef"
        :start="from || null"
        :end="to || null"
        :max="todayISO()"
        @pick="onPick"
      />

      <p class="hint">
        {{
          from && !to
            ? 'Pick the end of the range, or an earlier day to move the start.'
            : 'Pick a day to start a range. Future days are not selectable.'
        }}
      </p>
    </div>
  </Teleport>
</template>

<style scoped>
.wrap {
  display: inline-flex;
  align-items: center;
  gap: 0.125rem;
}

.trigger {
  display: inline-flex;
  align-items: center;
  gap: 0.4375rem;
  padding: 0.4375rem 0.6875rem;
  min-height: 2rem;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--surface-2);
  color: var(--ink-2);
  font-size: var(--step--1);
  font-weight: 600;
  white-space: nowrap;
  box-shadow: var(--sheen);
  transition: scale var(--dur) var(--ease-spring), border-color var(--dur-fast) linear,
    background-color var(--dur-fast) linear, box-shadow var(--dur-fast) linear,
    color var(--dur-fast) linear;
}

.trigger:hover:not(.is-open) {
  scale: 1.02;
  background: var(--surface-3);
  border-color: var(--line-strong);
  color: var(--ink);
}

.trigger.is-set {
  color: var(--ink);
  border-color: color-mix(in srgb, var(--reef) 34%, transparent);
}

.trigger.is-open {
  border-color: var(--reef);
  box-shadow: 0 0 0 3px var(--reef-wash);
  color: var(--ink);
}

.trigger-label {
  font-size: var(--step--1);
}

.clear {
  color: var(--ink-4);
}

/* -- panel ---------------------------------------------------------------- */

.panel {
  position: fixed;
  z-index: var(--z-menu);
  display: grid;
  gap: 0.625rem;
  padding: 0.75rem;
  overflow-y: auto;
  /* Glass, matching the select menu - the calendar inside it inherits this
     surface rather than painting its own. */
  background: var(--chrome);
  backdrop-filter: blur(var(--blur-lg));
  -webkit-backdrop-filter: blur(var(--blur-lg));
  border: 1px solid var(--line-strong);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-float), var(--sheen);
  animation: pop-in var(--dur-fast) var(--ease-spring);
}

/* Two by two rather than a flex row: four presets do not fit across the calendar's
   width, and a row that wraps one item onto its own line looks like a mistake. */
.presets {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.25rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--line);
}

.preset {
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--ink-3);
  font-family: var(--font-mono);
  font-size: var(--step--2);
  letter-spacing: 0.04em;
}

.preset:hover:not(:disabled) {
  border-color: var(--line-strong);
  color: var(--ink);
}

.preset:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.hint {
  max-width: 17.5rem;
  color: var(--ink-4);
  font-size: var(--step--2);
  line-height: 1.4;
}
</style>
