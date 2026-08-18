<script setup lang="ts">
// Empty and error states point somewhere useful rather than apologising.
</script>

<template>
  <div class="page">
    <span class="eyebrow">Not found</span>
    <h1>That page does not exist</h1>
    <p>The link may be out of date, or the record may have been rejected during review.</p>
    <RouterLink class="btn" :to="{ name: 'sightings' }">Go to sightings</RouterLink>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 0.75rem;
  justify-items: start;
  padding: clamp(2rem, 8vh, 6rem) clamp(1.25rem, 3vw, 2rem);
  max-width: 44ch;
}

p {
  color: var(--ink-muted);
}

.btn {
  margin-top: 0.5rem;
  text-decoration: none;
}
</style>
