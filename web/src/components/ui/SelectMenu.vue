<script setup lang="ts">
/**
 * The only dropdown in the app. Single-select, because nothing in this system
 * filters by more than one value at a time.
 *
 * A native <select> is not used for the same reason as the checkbox: it cannot be
 * themed, and its popup ignores the surrounding design entirely. Positioning and
 * dismissal come from useAnchoredPanel, shared with the date picker.
 *
 * Keyboard support is the whole reason not to replace a native control lightly:
 * arrows move the highlight, Enter and Space commit, Escape cancels and returns
 * focus to the trigger.
 */
import { computed, ref } from 'vue'

import Icon from '@/components/ui/Icon.vue'
import { useAnchoredPanel } from '@/composables/useAnchoredPanel'
import { iconCheck, iconChevronDown } from '@/lib/icons'

export interface SelectOption {
  value: string
  label: string
  /** Secondary line under the label, for options that need a reason. */
  hint?: string
}

const props = withDefaults(
  defineProps<{
    options: SelectOption[]
    placeholder?: string
    /** Accessible name; required when there is no visible label element. */
    ariaLabel?: string
    disabled?: boolean
    /** 'sm' matches a compact toolbar row; 'md' matches a form field. */
    size?: 'sm' | 'md'
    /** Fill the available width instead of hugging the label. */
    block?: boolean
  }>(),
  { size: 'md' },
)

const model = defineModel<string>({ required: true })

const triggerRef = ref<HTMLButtonElement | null>(null)
const menuRef = ref<HTMLElement | null>(null)
const highlighted = ref(0)

const panel = useAnchoredPanel({ anchorRef: triggerRef, panelRef: menuRef, estimatedHeight: 200 })

const selected = computed(() => props.options.find((o) => o.value === model.value) ?? null)

async function show() {
  if (props.disabled) return
  highlighted.value = Math.max(
    props.options.findIndex((o) => o.value === model.value),
    0,
  )
  await panel.show()
  menuRef.value?.focus()
}

function choose(value: string) {
  model.value = value
  panel.hide(true)
}

function onTriggerKey(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'Enter') {
    event.preventDefault()
    void show()
  }
}

function onMenuKey(event: KeyboardEvent) {
  const count = props.options.length
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      highlighted.value = (highlighted.value + 1) % count
      break
    case 'ArrowUp':
      event.preventDefault()
      highlighted.value = (highlighted.value - 1 + count) % count
      break
    case 'Home':
      event.preventDefault()
      highlighted.value = 0
      break
    case 'End':
      event.preventDefault()
      highlighted.value = count - 1
      break
    case 'Enter':
    case ' ':
      event.preventDefault()
      choose(props.options[highlighted.value].value)
      break
    case 'Escape':
      event.preventDefault()
      panel.hide(true)
      break
    case 'Tab':
      panel.hide()
      break
  }
}
</script>

<template>
  <button
    ref="triggerRef"
    type="button"
    class="trigger"
    :class="[`trigger-${props.size}`, { 'is-open': panel.open.value, 'is-block': props.block }]"
    :disabled="props.disabled"
    :aria-label="props.ariaLabel"
    aria-haspopup="listbox"
    :aria-expanded="panel.open.value"
    @click="panel.open.value ? panel.hide() : show()"
    @keydown="onTriggerKey"
  >
    <span class="trigger-label" :class="{ 'is-placeholder': !selected }">
      {{ selected?.label ?? props.placeholder ?? 'Select…' }}
    </span>
    <Icon
      class="chevron"
      :class="{ 'is-open': panel.open.value }"
      :path="iconChevronDown"
      :size="1"
    />
  </button>

  <Teleport to="body">
    <div
      v-if="panel.open.value"
      ref="menuRef"
      class="menu"
      :style="panel.style.value"
      role="listbox"
      tabindex="-1"
      @keydown="onMenuKey"
    >
      <button
        v-for="(option, index) in props.options"
        :key="option.value"
        type="button"
        class="option row-hover"
        :class="{ 'is-highlighted': index === highlighted, 'is-selected': option.value === model }"
        role="option"
        :aria-selected="option.value === model"
        @click="choose(option.value)"
        @pointermove="highlighted = index"
      >
        <span class="option-text">
          <span class="option-label">{{ option.label }}</span>
          <span v-if="option.hint" class="option-hint">{{ option.hint }}</span>
        </span>
        <Icon v-if="option.value === model" :path="iconCheck" :size="0.95" />
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--surface-2);
  color: var(--ink);
  font-weight: 600;
  text-align: left;
  box-shadow: var(--sheen);
  transition: scale var(--dur) var(--ease-spring), border-color var(--dur-fast) linear,
    background-color var(--dur-fast) linear, box-shadow var(--dur-fast) linear;
}

.trigger-sm {
  padding: 0.25rem 0.5rem 0.25rem 0.625rem;
  min-height: 1.75rem;
  font-size: var(--step--2);
  border-radius: var(--r-sm);
}

.trigger-md {
  padding: 0.4375rem 0.5rem 0.4375rem 0.6875rem;
  min-height: 2rem;
  font-size: var(--step--1);
}

.is-block {
  width: 100%;
}

.trigger:hover:not(:disabled):not(.is-open) {
  scale: 1.02;
  background: var(--surface-3);
  border-color: var(--line-strong);
}

.trigger.is-open {
  border-color: var(--reef);
  box-shadow: 0 0 0 3px var(--reef-wash);
}

.trigger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.trigger-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-placeholder {
  color: var(--ink-4);
  font-weight: 500;
}

.chevron {
  color: var(--ink-3);
  transition: rotate var(--dur-fast) var(--ease-out);
}

.chevron.is-open {
  rotate: 180deg;
}

/* ── menu ───────────────────────────────────────────────────────────────── */

.menu {
  position: fixed;
  z-index: var(--z-menu);
  display: grid;
  gap: 1px;
  padding: 0.3125rem;
  overflow-y: auto;
  /* Glass, like every other floating surface. --chrome is the scrim that keeps
     option text legible over a moving map without going opaque. */
  background: var(--chrome);
  backdrop-filter: blur(var(--blur-lg));
  -webkit-backdrop-filter: blur(var(--blur-lg));
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-float), var(--sheen);
  animation: pop-in var(--dur-fast) var(--ease-spring);
  outline: none;
}

.option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.625rem;
  width: 100%;
  padding: 0.375rem 0.5rem;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
  font-size: var(--step--1);
  font-weight: 500;
  text-align: left;
}

.option.is-highlighted {
  color: var(--ink);
  background: var(--surface-2);
}

.option.is-selected {
  color: var(--reef);
  font-weight: 600;
}

.option-text {
  display: grid;
  gap: 0.0625rem;
  min-width: 0;
}

.option-label {
  white-space: nowrap;
}

.option-hint {
  font-size: var(--step--2);
  color: var(--ink-4);
}
</style>
