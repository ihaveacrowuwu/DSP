<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'

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
  <div v-if="!auth.isAuthenticated" class="bare">
    <RouterView />
  </div>

  <div v-else class="shell">
    <nav class="rail" aria-label="Main">
      <div class="brand">
        <span class="mark" aria-hidden="true">
          <span v-for="i in 9" :key="i" :class="['mark-cell', { bleached: i === 3 || i === 6 || i === 7 }]" />
        </span>
        <span class="brand-text">
          <strong>Muraka</strong>
          <span class="eyebrow">Reef condition</span>
        </span>
      </div>

      <ul class="nav">
        <li v-if="auth.canVerify">
          <RouterLink :to="{ name: 'reefs' }">Reef map</RouterLink>
        </li>
        <li v-if="auth.canVerify">
          <RouterLink :to="{ name: 'queue' }">Review queue</RouterLink>
        </li>
        <li>
          <RouterLink :to="{ name: 'sightings' }">Sightings</RouterLink>
        </li>
        <li v-if="auth.isAdmin">
          <RouterLink :to="{ name: 'operations' }">Operations</RouterLink>
        </li>
      </ul>

      <div class="account">
        <span class="account-name">{{ auth.user?.displayName }}</span>
        <span class="eyebrow">{{ auth.user?.role }}</span>
        <button type="button" class="btn btn-ghost sign-out" @click="signOut">Sign out</button>
      </div>
    </nav>

    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.bare {
  min-height: 100vh;
}

.shell {
  display: grid;
  grid-template-columns: var(--rail-width) 1fr;
  min-height: 100vh;
}

.rail {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 1.25rem 1rem;
  background: var(--shelf);
  border-right: 1px solid var(--hairline);
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.625rem;
}

/* The brand mark is a 3x3 patch lattice: the product's core idea at logo size. */
.mark {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  width: 1.5rem;
  aspect-ratio: 1;
  border-radius: 2px;
  overflow: hidden;
  background: var(--hairline);
  flex-shrink: 0;
}

.mark-cell {
  background: var(--living);
}

.mark-cell.bleached {
  background: var(--bone);
}

.brand-text {
  display: grid;
  line-height: 1.25;
}

.brand-text strong {
  font-size: var(--step-1);
  letter-spacing: -0.01em;
}

.nav {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 2px;
}

.nav a {
  display: block;
  padding: 0.4375rem 0.625rem;
  border-radius: var(--radius-sm);
  color: var(--ink-muted);
  text-decoration: none;
  border-left: 2px solid transparent;
  transition: background var(--transition), color var(--transition);
}

.nav a:hover {
  background: var(--shelf-raised);
  color: var(--ink);
}

.nav a.router-link-active {
  background: var(--shelf-raised);
  border-left-color: var(--living);
  color: var(--ink);
  font-weight: 500;
}

.account {
  margin-top: auto;
  display: grid;
  gap: 0.125rem;
  padding-top: 1rem;
  border-top: 1px solid var(--hairline);
}

.account-name {
  font-weight: 500;
}

.sign-out {
  justify-self: start;
  margin-top: 0.5rem;
  padding-inline: 0;
}

.content {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

@media (max-width: 55rem) {
  .shell {
    grid-template-columns: 1fr;
  }

  .rail {
    flex-direction: row;
    align-items: center;
    gap: 1rem;
    border-right: none;
    border-bottom: 1px solid var(--hairline);
    overflow-x: auto;
  }

  .nav {
    grid-auto-flow: column;
    gap: 0.25rem;
  }

  .nav a {
    border-left: none;
    border-bottom: 2px solid transparent;
    white-space: nowrap;
  }

  .nav a.router-link-active {
    border-left-color: transparent;
    border-bottom-color: var(--living);
  }

  .account {
    margin-top: 0;
    padding-top: 0;
    border-top: none;
    margin-left: auto;
  }

  .account .eyebrow,
  .account-name {
    display: none;
  }

  .sign-out {
    margin-top: 0;
  }
}
</style>
