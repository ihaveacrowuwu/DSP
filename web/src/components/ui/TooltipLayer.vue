<script setup lang="ts">
/**
 * One tooltip for the whole app, mounted once by App.vue.
 *
 * Any element that needs a hover label carries `data-tip="..."`, and optionally
 * `data-tip-side="right|top"` (default right, which is what the collapsed nav
 * rail wants). Native `title` is never used: it renders unstyled, ignores the
 * theme, and appears on a delay the browser owns.
 *
 * A single delegated listener handles every tooltip in the app, so adding one to
 * a new element costs an attribute and no wiring. Focus shows it too — the rail
 * is keyboard-navigable and icon-only, so without that a keyboard user would
 * have nothing to read.
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const text = ref('')
const visible = ref(false)
const x = ref(0)
const y = ref(0)
const side = ref<'right' | 'top'>('right')

const GAP = 10
const MARGIN = 8

function show(target: HTMLElement) {
  const label = target.dataset.tip
  if (!label) return

  const box = target.getBoundingClientRect()
  side.value = target.dataset.tipSide === 'top' ? 'top' : 'right'
  text.value = label

  if (side.value === 'top') {
    x.value = box.left + box.width / 2
    y.value = Math.max(MARGIN, box.top - GAP)
  } else {
    x.value = box.right + GAP
    y.value = box.top + box.height / 2
  }
  visible.value = true
}

function hide() {
  visible.value = false
}

function onOver(event: Event) {
  const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-tip]')
  if (target) show(target)
  else if (visible.value) hide()
}

// Scrolling or resizing invalidates a measured position, and a tooltip that
// stays behind while its anchor moves is worse than no tooltip.
function onDisplace() {
  if (visible.value) hide()
}

onMounted(() => {
  document.addEventListener('pointerover', onOver, true)
  // The pointer leaving the window fires no further pointerover, so without this
  // the last tooltip would stay on screen after the cursor is gone.
  document.addEventListener('pointerleave', hide)
  document.addEventListener('pointerdown', hide, true)
  document.addEventListener('focusin', onOver, true)
  document.addEventListener('focusout', hide, true)
  window.addEventListener('scroll', onDisplace, true)
  window.addEventListener('resize', onDisplace)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerover', onOver, true)
  document.removeEventListener('pointerleave', hide)
  document.removeEventListener('pointerdown', hide, true)
  document.removeEventListener('focusin', onOver, true)
  document.removeEventListener('focusout', hide, true)
  window.removeEventListener('scroll', onDisplace, true)
  window.removeEventListener('resize', onDisplace)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="text"
      class="tip"
      :class="[`tip-${side}`, { 'is-visible': visible }]"
      :style="{ left: `${x}px`, top: `${y}px` }"
      role="tooltip"
      aria-hidden="true"
    >
      {{ text }}
    </div>
  </Teleport>
</template>

<style scoped>
.tip {
  position: fixed;
  z-index: var(--z-tooltip);
  max-width: 15rem;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-sm);
  background: var(--chrome);
  backdrop-filter: blur(var(--blur-lg));
  -webkit-backdrop-filter: blur(var(--blur-lg));
  box-shadow: var(--shadow-2), var(--sheen);
  color: var(--ink);
  font-size: var(--step--1);
  font-weight: 500;
  line-height: 1.35;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity var(--dur-micro) linear, scale var(--dur-fast) var(--ease-spring);
}

.tip-right {
  translate: 0 -50%;
  transform-origin: left center;
  scale: 0.94;
}

.tip-top {
  translate: -50% -100%;
  transform-origin: center bottom;
  scale: 0.94;
}

.tip.is-visible {
  opacity: 1;
  scale: 1;
}
</style>
