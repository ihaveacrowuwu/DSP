<script setup lang="ts">
/**
 * Application shell.
 *
 * There is no layout grid here on purpose. The rail floats over the content and
 * the content fills the viewport underneath it, so the map can pan and zoom
 * across the full width while text screens keep clear of the rail with padding
 * (see `.page`). A grid column for the rail would crop the map to a rectangle
 * beside it and make the reef stop at a hard edge.
 */
import { onMounted } from 'vue'
import { RouterView, useRouter } from 'vue-router'

import AppSidebar from '@/components/ui/AppSidebar.vue'
import TooltipLayer from '@/components/ui/TooltipLayer.vue'
import { setUnauthorizedHandler } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

onMounted(() => {
  // A refresh failure anywhere in the app lands the user back at sign-in.
  setUnauthorizedHandler(() => {
    void router.push({ name: 'sign-in' })
  })
})

async function signOut() {
  await auth.signOut()
  void router.push({ name: 'sign-in' })
}
</script>

<template>
  <!-- Signed out there is no navigation to show, so the view owns the viewport. -->
  <div v-if="!auth.isAuthenticated" class="bare">
    <RouterView />
  </div>

  <div v-else class="shell">
    <AppSidebar @sign-out="signOut" />
    <main class="content">
      <RouterView />
    </main>
  </div>

  <TooltipLayer />
</template>

<style scoped>
.bare,
.shell {
  min-height: 100vh;
}

/* The scroll container is the full viewport width, not the area beside the rail,
   so a map or a wide table extends under the floating rail rather than being
   boxed in next to it. */
.content {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* On narrow screens the rail lies along the bottom edge, so content clears it
   there instead of on the left. */
@media (max-width: 55rem) {
  .content {
    padding-bottom: 3.75rem;
  }
}
</style>
