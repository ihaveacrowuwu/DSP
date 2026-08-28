<script setup lang="ts">
/**
 * The only segmented control. Every tab bar, filter toggle and mode switch in the
 * app goes through it - building one inline out of buttons would mean
 * reimplementing the pill, and two pills that move differently look like two
 * different products.
 *
 * It shares its motion with the nav rail (usePillMotion), so the horizontal pill
 * here and the vertical one there are recognisably the same object. Use it for
 * two to four short choices; anything longer belongs in a SelectMenu.
 */
import { computed, ref, watch } from 'vue'

import { usePillMotion } from '@/composables/usePillMotion'

export interface SegmentOption {
  value: string
  label: string
}

const props = withDefaults(
  defineProps<{
    options: SegmentOption[]
    /** Accessible name for the group. */
    ariaLabel: string
    size?: 'sm' | 'md'
    /** Divide the container width evenly instead of sizing to the labels. */
    equal?: boolean
  }>(),
  { size: 'md' },
)

const model = defineModel<string>({ required: true })

const trackRef = ref<HTMLElement | null>(null)
const pillRef = ref<HTMLElement | null>(null)
const activeId = computed(() => model.value)

const pill = usePillMotion({
  activeId,
  containerRef: trackRef,
  orientation: 'horizontal',
  // Needed for the keyframe restart; there is no spotlight overlay here, so the
  // clip half of the composable stays inert.
  pillRef,
  radius: 10,
})

watch(() => props.options, () => pill.remeasure(), { deep: true })
</script>

<template>
  <div
    ref="trackRef"
    class="track"
    :class="[`track-${props.size}`, { 'is-equal': props.equal }]"
    role="tablist"
    :aria-label="props.ariaLabel"
  >
    <span
      v-show="pill.placed.value"
      ref="pillRef"
      class="pill"
      :style="pill.style.value"
      aria-hidden="true"
      @animationend="pill.onAnimationEnd()"
    />
    <button
      v-for="option in props.options"
      :key="option.value"
      type="button"
      class="tab"
      :class="{ 'is-active': option.value === model }"
      :data-pill-id="option.value"
      role="tab"
      :aria-selected="option.value === model"
      @click="model = option.value"
    >
      {{ option.label }}
    </button>
  </div>
</template>

<style scoped>
.track {
  position: relative;
  display: inline-flex;
  align-items: stretch;
  width: fit-content;
  padding: 3px;
  background: var(--surface--1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  box-shadow: var(--sheen);
}

.is-equal {
  width: 100%;
}

.is-equal .tab {
  flex: 1;
}

.tab {
  position: relative;
  z-index: 1;
  border: none;
  background: transparent;
  border-radius: var(--r-sm);
  color: var(--ink-3);
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
  transition: scale var(--dur) var(--ease-spring), color var(--dur-fast) linear;
}

.track-sm .tab {
  padding: 0.1875rem 0.625rem;
  font-size: var(--step--2);
}

.track-md .tab {
  padding: 0.3125rem 0.75rem;
  font-size: var(--step--1);
}

.tab:hover:not(.is-active) {
  color: var(--ink);
  scale: 1.05;
}

.tab:active:not(.is-active) {
  scale: 0.97;
}

/* Snapping rather than easing back to 1, for the same reason as the rail: the tab
   you just clicked becomes active while still hovered, and easing the hover grow
   away leaves the label shrinking under a pill that has already arrived. */
.tab.is-active {
  color: var(--ink);
  scale: 1;
  transition-duration: 0s;
}

.pill {
  position: absolute;
  top: 3px;
  bottom: 3px;
  z-index: 0;
  border-radius: var(--r-sm);
  background: var(--surface-3);
  border: 1px solid var(--line-strong);
  box-shadow: var(--sheen), var(--shadow-1);
  pointer-events: none;
  will-change: transform;
  transition: left var(--pill-travel-dur, 0.24s) var(--ease-travel),
    width var(--dur) var(--ease-out);
}
</style>
