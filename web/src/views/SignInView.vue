<script setup lang="ts">
/**
 * Sign-in, and the only page in the system a stranger sees.
 *
 * It explains the mechanism before asking for credentials, because "citizen
 * science coral monitoring" means nothing until someone knows what the three
 * parties actually do. The three-row breakdown is the product in fifty words.
 *
 * Signing in and registering are one form with a segmented switch rather than two
 * pages: the fields are almost identical, and a page navigation between them
 * would throw away whatever had already been typed.
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import SegmentedTabs from '@/components/ui/SegmentedTabs.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref('sign-in')
const email = ref('')
const password = ref('')
const displayName = ref('')

const MODES = [
  { value: 'sign-in', label: 'Sign in' },
  { value: 'register', label: 'Create account' },
]

const registering = computed(() => mode.value === 'register')

async function submit() {
  const ok = registering.value
    ? await auth.register(email.value, password.value, displayName.value)
    : await auth.signIn(email.value, password.value)

  if (ok) {
    const next = typeof route.query.next === 'string' ? route.query.next : null
    void router.push(next ?? { name: 'sightings' })
  }
}
</script>

<template>
  <div class="split">
    <section class="intro">
      <div class="brand">
        <span class="mark" aria-hidden="true">
          <span
            v-for="cell in 9"
            :key="cell"
            class="mark-cell"
            :class="{ 'is-bleached': cell === 3 || cell === 5 || cell === 8 }"
          />
        </span>
        <span class="eyebrow">Maldives reef monitoring</span>
      </div>

      <h1>
        Reefs change faster<br />
        than surveys can reach them.
      </h1>

      <p class="lede">
        Divers photograph reefs every day. Muraka turns those photographs into
        structured condition data: a model grades each photograph patch by patch, and
        marine researchers confirm or correct every result before it counts.
      </p>

      <!-- Three roles, three steps. The sequence is real, so it earns dividers. -->
      <dl class="mechanic">
        <div>
          <dt class="eyebrow">Contributors</dt>
          <dd>Capture a sighting underwater, offline. It syncs when you surface.</dd>
        </div>
        <div>
          <dt class="eyebrow">The model</dt>
          <dd>Tiles each photograph into a grid and reports bleached extent, not a verdict.</dd>
        </div>
        <div>
          <dt class="eyebrow">Researchers</dt>
          <dd>Review the least confident results first. Expert labels always win.</dd>
        </div>
      </dl>
    </section>

    <section class="card card-pad form-card">
      <SegmentedTabs v-model="mode" :options="MODES" ariaLabel="Sign in or register" equal />

      <p class="form-note">
        {{
          registering
            ? 'New accounts start as contributors. An administrator grants review access.'
            : 'Use the account your supervisor or administrator issued.'
        }}
      </p>

      <form @submit.prevent="submit">
        <div v-if="registering" class="field">
          <label for="displayName">Display name</label>
          <input
            id="displayName"
            v-model="displayName"
            class="input"
            autocomplete="name"
            required
            placeholder="How you appear on your sightings"
          />
        </div>

        <div class="field">
          <label for="email">Email</label>
          <input
            id="email"
            v-model="email"
            class="input"
            type="email"
            autocomplete="email"
            required
          />
        </div>

        <div class="field">
          <label for="password">Password</label>
          <input
            id="password"
            v-model="password"
            class="input"
            type="password"
            :autocomplete="registering ? 'new-password' : 'current-password'"
            required
            minlength="10"
          />
          <span v-if="registering" class="hint">At least 10 characters.</span>
        </div>

        <p v-if="auth.error" class="notice" role="alert">{{ auth.error }}</p>

        <button type="submit" class="btn btn-primary btn-block submit" :disabled="auth.loading">
          {{ auth.loading ? 'Working…' : registering ? 'Create account' : 'Sign in' }}
        </button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.split {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(19rem, 24rem);
  gap: clamp(2rem, 6vw, 5rem);
  align-items: center;
  min-height: 100vh;
  max-width: 80rem;
  margin: 0 auto;
  padding: clamp(1.5rem, 5vw, 4rem);
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-bottom: 1rem;
}

/* The same nine-square mark the rail carries, at the one size where it can be
   read as what it is: a photograph tiled into patches, two of them bleached. */
.mark {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 2px;
  width: 1.625rem;
  aspect-ratio: 1;
  padding: 2px;
  border-radius: var(--r-xs);
  background: var(--surface--1);
  border: 1px solid var(--line);
}

.mark-cell {
  border-radius: 1px;
  background: var(--reef);
}

.mark-cell.is-bleached {
  background: var(--bone);
}

.intro h1 {
  font-size: clamp(1.875rem, 4vw, 2.875rem);
  letter-spacing: -0.03em;
}

.lede {
  max-width: 46ch;
  margin-top: 1.125rem;
  color: var(--ink-2);
  font-size: var(--step-1);
  line-height: 1.6;
}

.mechanic {
  margin-top: 2.25rem;
  display: grid;
  gap: 1px;
  background: var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.mechanic > div {
  display: grid;
  grid-template-columns: 9rem minmax(0, 1fr);
  gap: 1rem;
  padding: 0.875rem;
  /* A recessed fill rather than a flat colour, so the hairline dividers read
     against the page's gradient instead of sitting on an opaque slab. */
  background: var(--surface--1);
}

.mechanic dt {
  padding-top: 0.0625rem;
}

.mechanic dd {
  color: var(--ink-2);
  font-size: var(--step--1);
}

.form-card {
  display: grid;
  gap: 0.875rem;
  box-shadow: var(--shadow-float), var(--sheen);
}

.form-note {
  color: var(--ink-3);
  font-size: var(--step--1);
}

form {
  display: grid;
  gap: 0.875rem;
  margin-top: 0.25rem;
}

.hint {
  color: var(--ink-4);
  font-size: var(--step--2);
}

.submit {
  margin-top: 0.25rem;
  min-height: 2.375rem;
}

@media (max-width: 60rem) {
  .split {
    grid-template-columns: 1fr;
    align-content: center;
  }

  .mechanic {
    margin-top: 1.5rem;
  }

  .mechanic > div {
    grid-template-columns: 1fr;
    gap: 0.25rem;
    padding: 0.75rem 0.875rem;
  }
}
</style>
