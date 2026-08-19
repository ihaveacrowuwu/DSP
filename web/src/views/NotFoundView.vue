<script setup lang="ts">
// Dead ends point somewhere useful rather than apologising. The second sentence
// is there because in this system a missing record often means a real thing
// happened to it — rejection during review — not that the URL was mistyped.
</script>

<template>
  <div class="page">
    <div class="state">
      <span class="eyebrow">Not found</span>
      <h2>That page does not exist</h2>
      <p>
        The link may be out of date, or the record may have been rejected during review
        and removed from the set.
      </p>
      <RouterLink class="btn btn-secondary" :to="{ name: 'sightings' }">
        Go to sightings
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
.state {
  gap: 0.625rem;
}

.state .btn {
  margin-top: 0.5rem;
}
</style>
