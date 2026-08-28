<script setup lang="ts">
/**
 * The only checkbox in the app.
 *
 * A native checkbox renders differently in every browser and ignores the theme
 * entirely - under the light scheme it stays a blue-grey Windows control on a
 * chart-paper panel. So the box is drawn, and a real input is laid over it at
 * zero opacity: the control keeps its keyboard behaviour, its focus ring and its
 * label association, and only the appearance is ours.
 */
import Icon from '@/components/ui/Icon.vue'
import { iconCheck } from '@/lib/icons'

const model = defineModel<boolean>({ required: true })

defineProps<{ disabled?: boolean; ariaLabel?: string }>()
</script>

<template>
  <span class="box" :class="{ 'is-on': model, 'is-disabled': disabled }">
    <input
      v-model="model"
      class="native"
      type="checkbox"
      :disabled="disabled"
      :aria-label="ariaLabel"
    />
    <Icon v-if="model" :path="iconCheck" :size="0.8" />
  </span>
</template>

<style scoped>
.box {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.0625rem;
  height: 1.0625rem;
  flex-shrink: 0;
  border: 1.5px solid var(--line-strong);
  /* Not --r-xs: at 7px on a 17px box the corners meet and it reads as a circle,
     which is a radio button's shape and the wrong promise to make. */
  border-radius: 5px;
  background: var(--field-bg);
  color: var(--accent-ink);
  vertical-align: -0.15em;
  transition: background-color var(--dur-fast) linear, border-color var(--dur-fast) linear,
    scale var(--dur) var(--ease-spring);
}

.box:hover:not(.is-disabled) {
  scale: 1.12;
  border-color: var(--ink-3);
}

.box:active:not(.is-disabled) {
  scale: 0.92;
}

.box.is-on {
  background: var(--reef);
  border-color: var(--reef);
}

.box.is-disabled {
  opacity: 0.45;
}

.box:focus-within {
  outline: 2px solid var(--reef);
  outline-offset: 2px;
}

.native {
  position: absolute;
  inset: 0;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}

.native:disabled {
  cursor: not-allowed;
}
</style>
