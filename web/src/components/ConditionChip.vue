<script setup lang="ts">
/**
 * Condition badge. Expert-verified labels must never be mistaken for model
 * output (NFR13), so provenance is carried by shape as well as colour: verified
 * chips have a filled marker and solid border, model chips are dashed and say so.
 */
import { computed } from 'vue'
import type { Condition, SightingStatus } from '@/lib/api'

const props = defineProps<{
  condition?: Condition
  status: SightingStatus
  verified: boolean
  severity?: number
}>()

const classes = computed(() => {
  if (props.status === 'rejected') return ['chip', 'chip-rejected']
  if (!props.condition) return ['chip', 'chip-pending']
  return [
    'chip',
    props.condition === 'bleached' ? 'chip-bleached' : 'chip-healthy',
    props.verified ? 'chip-verified' : 'chip-predicted',
  ]
})

const label = computed(() => {
  if (props.status === 'rejected') return 'Rejected'
  if (props.status === 'pending_photos') return 'Awaiting photos'
  if (props.status === 'processing') return 'Analysing'
  if (!props.condition) return 'Unassessed'

  const name = props.condition === 'bleached' ? 'Bleached' : 'Healthy'
  const extent =
    props.severity !== undefined ? ` ${Math.round(props.severity * 100)}%` : ''
  return `${name}${extent}`
})

// Says who decided, in the interface's voice rather than jargon.
const provenance = computed(() => {
  if (!props.condition || props.status === 'rejected') return ''
  return props.verified ? 'expert' : 'model'
})
</script>

<template>
  <span :class="classes">
    {{ label }}
    <span v-if="provenance" class="provenance">{{ provenance }}</span>
  </span>
</template>

<style scoped>
/* "expert" / "model" rides inside the chip rather than beside it, so provenance
   travels with the label everywhere the chip is used. */
.provenance {
  font-size: var(--step--2);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  opacity: 0.72;
}
</style>
