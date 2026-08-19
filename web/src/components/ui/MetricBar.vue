<script setup lang="ts">
/**
 * One measured quantity as a labelled bar: bleached extent, model confidence,
 * the share of a month's sightings that came back bleached.
 *
 * The bar exists because a percentage alone does not answer "compared to what".
 * Sitting in a track of fixed width, 62% is instantly readable as "most of it",
 * and two bars stacked are comparable at a glance in a way two numbers are not.
 * The number stays, in mono, because the exact value is still the data.
 *
 * Fill colour carries meaning, never decoration: reef for living tissue, bone for
 * bleached extent, rust for a failure count.
 */
const props = withDefaults(
  defineProps<{
    label: string
    /** Fraction from 0 to 1; values outside are clamped rather than overflowing. */
    value: number
    /** What to print at the end of the row. Defaults to a whole percentage. */
    display?: string
    tone?: 'reef' | 'bone' | 'rust' | 'verified' | 'neutral'
    /** Widen the label column when labels are long, e.g. model stage names. */
    wideLabel?: boolean
  }>(),
  { tone: 'reef' },
)

const TONES: Record<string, string> = {
  reef: 'var(--reef)',
  bone: 'var(--bone)',
  rust: 'var(--rust)',
  verified: 'var(--verified)',
  neutral: 'var(--ink-3)',
}

const clamped = () => Math.min(Math.max(props.value, 0), 1)
const text = () => props.display ?? `${Math.round(clamped() * 100)}%`
</script>

<template>
  <div class="metric" :class="{ 'is-wide': props.wideLabel }">
    <span class="metric-label">{{ props.label }}</span>
    <span class="track">
      <span
        class="fill"
        :style="{ width: `${clamped() * 100}%`, background: TONES[props.tone] }"
      />
    </span>
    <span class="value readout">{{ text() }}</span>
  </div>
</template>

<style scoped>
.metric {
  display: grid;
  grid-template-columns: 6rem minmax(2.5rem, 1fr) 3rem;
  align-items: center;
  gap: 0.5rem;
}

.is-wide {
  grid-template-columns: 9rem minmax(2.5rem, 1fr) 3.5rem;
}

.metric-label {
  font-family: var(--font-mono);
  font-size: var(--step--2);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.track {
  height: 0.4375rem;
  border-radius: var(--r-pill);
  background: var(--surface-2);
  overflow: hidden;
}

.fill {
  display: block;
  height: 100%;
  min-width: 2px;
  border-radius: var(--r-pill);
  /* The only transition here: a value arriving from the API grows into place, so
     a re-fetch reads as an update rather than a redraw. */
  transition: width var(--dur-slow) var(--ease-spring);
}

.value {
  font-size: var(--step--1);
  color: var(--ink-2);
  text-align: right;
}
</style>
