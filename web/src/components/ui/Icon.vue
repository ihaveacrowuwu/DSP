<script setup lang="ts">
/**
 * The only icon component. Renders a Material Design Icons path from
 * `@/lib/icons` at a size expressed in ems, so an icon beside text scales with
 * that text instead of needing a pixel value per call site.
 *
 * `currentColor` is not overridable on purpose: an icon is a glyph in a run of
 * text or a button, and it should inherit whatever colour that context has.
 */
const props = withDefaults(
  defineProps<{
    /** A path string re-exported from `@/lib/icons`. */
    path: string
    /** Box size in ems relative to the surrounding text. */
    size?: number
  }>(),
  { size: 1.15 },
)
</script>

<template>
  <svg
    class="icon"
    :style="{ width: `${props.size}em`, height: `${props.size}em` }"
    viewBox="0 0 24 24"
    aria-hidden="true"
    focusable="false"
  >
    <path :d="props.path" fill="currentColor" />
  </svg>
</template>

<style scoped>
.icon {
  flex-shrink: 0;
  /* Nudge the optical centre onto the text baseline's midline; MDI's 24px grid
     sits slightly high next to 14px type. */
  vertical-align: -0.14em;
}
</style>
