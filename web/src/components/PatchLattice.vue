<script setup lang="ts">
/**
 * The patch lattice — this interface's signature element.
 *
 * The model does not judge a photo as a whole: it tiles it into a grid and
 * judges each cell. Showing that lattice is showing the model's actual reasoning,
 * so it appears in two sizes: as an overlay on the photo, and as a thumbnail-sized
 * glyph in list rows where it works like a sparkline — you read a sighting's
 * bleaching pattern without opening it.
 *
 * Cell fill encodes label (living teal / bone white); opacity encodes confidence,
 * so a hesitant model looks hesitant.
 */
import { computed } from 'vue'
import type { Patch } from '@/lib/api'

const props = withDefaults(
  defineProps<{
    patches: Patch[]
    grid: number
    /** 'glyph' for inline use in lists, 'overlay' for on top of a photo. */
    variant?: 'glyph' | 'overlay'
    /** Animate cells in row by row, as if inference were sweeping the image. */
    animate?: boolean
  }>(),
  { variant: 'glyph', animate: false },
)

interface Cell {
  row: number
  col: number
  label: Patch['label']
  confidence: number
}

const cells = computed<Cell[]>(() =>
  props.patches.map((p) => ({
    row: p.row,
    col: p.col,
    label: p.label,
    confidence: p.confidence,
  })),
)

const bleachedCount = computed(() => cells.value.filter((c) => c.label === 'bleached').length)

const summary = computed(() =>
  cells.value.length
    ? `${bleachedCount.value} of ${cells.value.length} patches classified bleached`
    : 'No patch analysis available',
)

// Confidence maps to opacity over a floor, so even a 50%-sure cell stays visible.
function cellStyle(cell: Cell): Record<string, string> {
  const opacity = 0.35 + Math.min(Math.max(cell.confidence, 0), 1) * 0.55
  return {
    gridRow: String(cell.row + 1),
    gridColumn: String(cell.col + 1),
    background: cell.label === 'bleached' ? 'var(--bone)' : 'var(--living)',
    opacity: String(props.variant === 'glyph' ? Math.min(opacity + 0.1, 1) : opacity),
    animationDelay: props.animate ? `${cell.row * 40 + cell.col * 12}ms` : '0ms',
  }
}
</script>

<template>
  <div
    class="lattice"
    :class="[`lattice-${variant}`, { 'lattice-animate': animate }]"
    :style="{ '--grid': grid }"
    role="img"
    :aria-label="summary"
  >
    <span v-for="cell in cells" :key="`${cell.row}-${cell.col}`" class="cell" :style="cellStyle(cell)" />
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
  width: 2.25rem;
  gap: 1px;
  border-radius: 2px;
  overflow: hidden;
  background: var(--hairline);
}

.lattice-overlay {
  position: absolute;
  inset: 0;
  gap: 1px;
  /* Blend so the coral photograph stays readable through the judgements. */
  mix-blend-mode: hard-light;
}

.cell {
  display: block;
}

.lattice-animate .cell {
  animation: cell-in 220ms ease-out both;
}

@keyframes cell-in {
  from {
    opacity: 0;
    transform: scale(0.82);
  }
}
</style>
