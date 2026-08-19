<script setup lang="ts">
/**
 * The patch lattice — this interface's signature element, and the whole of its
 * argument about machine learning.
 *
 * The model does not judge a photograph as a whole: it tiles it into a grid and
 * judges each cell. Drawing that lattice is drawing the model's actual reasoning,
 * which is why it appears at two sizes — as an overlay on the photograph, and as
 * a thumbnail glyph in list rows where it works like a sparkline. You read a
 * sighting's bleaching pattern without opening it.
 *
 * Cell fill encodes the label (reef teal / bone white); opacity encodes
 * confidence, so a hesitant model looks hesitant. That is deliberate: a
 * confident-looking wrong answer is the expensive failure mode in a system whose
 * whole point is that experts correct the model.
 */
import { computed } from 'vue'

import type { Patch } from '@/lib/api'

const props = withDefaults(
  defineProps<{
    patches: Patch[]
    grid: number
    /** 'glyph' for inline use in lists, 'overlay' for on top of a photograph. */
    variant?: 'glyph' | 'overlay'
    /** Play the cells in row by row, as if inference were sweeping the image. */
    animate?: boolean
  }>(),
  { variant: 'glyph', animate: false },
)

const cells = computed(() =>
  props.patches.map((patch) => ({
    row: patch.row,
    col: patch.col,
    label: patch.label,
    confidence: patch.confidence,
  })),
)

const bleachedCount = computed(() => cells.value.filter((c) => c.label === 'bleached').length)

const summary = computed(() =>
  cells.value.length
    ? `${bleachedCount.value} of ${cells.value.length} patches classified bleached`
    : 'No patch analysis available',
)

/**
 * Confidence maps to opacity over a floor, so even a 50%-sure cell stays visible.
 * The overlay range stops well short of solid: past roughly 0.7 the cells stop
 * annotating the photograph and start replacing it, and a reviewer cannot check
 * a judgement against coral they can no longer see. The glyph has no photograph
 * underneath, so it uses the full range.
 */
function cellStyle(cell: (typeof cells.value)[number]): Record<string, string> {
  const confidence = Math.min(Math.max(cell.confidence, 0), 1)
  const opacity =
    props.variant === 'glyph' ? 0.45 + confidence * 0.55 : 0.28 + confidence * 0.42
  return {
    gridRow: String(cell.row + 1),
    gridColumn: String(cell.col + 1),
    background: cell.label === 'bleached' ? 'var(--bone)' : 'var(--reef)',
    opacity: String(opacity),
    // Staggered by position, so the sweep runs left-to-right and top-to-bottom
    // rather than every cell appearing at once.
    animationDelay: props.animate ? `${cell.row * 38 + cell.col * 11}ms` : '0ms',
  }
}
</script>

<template>
  <div
    class="lattice"
    :class="[`lattice-${variant}`, { 'is-animated': animate }]"
    :style="{ '--grid': grid }"
    role="img"
    :aria-label="summary"
  >
    <span
      v-for="cell in cells"
      :key="`${cell.row}-${cell.col}`"
      class="cell"
      :style="cellStyle(cell)"
    />
  </div>
</template>

<style scoped>
.lattice {
  display: grid;
  grid-template-columns: repeat(var(--grid), 1fr);
  grid-template-rows: repeat(var(--grid), 1fr);
  aspect-ratio: 1;
}

.lattice-glyph {
  width: 2.125rem;
  gap: 1px;
  padding: 1px;
  border-radius: var(--r-xs);
  background: var(--surface--1);
  border: 1px solid var(--line);
  overflow: hidden;
}

.lattice-overlay {
  position: absolute;
  inset: 0;
  gap: 1px;
  /* Blend so the coral photograph stays readable through the judgements — the
     point is to annotate the image, not to replace it with a chart. */
  mix-blend-mode: hard-light;
}

.cell {
  display: block;
  border-radius: 1px;
}

.is-animated .cell {
  animation: cell-in 240ms var(--ease-spring) both;
}

@keyframes cell-in {
  from {
    opacity: 0;
    scale: 0.8;
  }
}
</style>
