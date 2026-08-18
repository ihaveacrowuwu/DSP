<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref<'sign-in' | 'register'>('sign-in')
const email = ref('')
const password = ref('')
const displayName = ref('')

async function submit() {
  const ok =
    mode.value === 'sign-in'
      ? await auth.signIn(email.value, password.value)
      : await auth.register(email.value, password.value, displayName.value)

  if (ok) {
    const next = typeof route.query.next === 'string' ? route.query.next : null
    void router.push(next ?? { name: 'sightings' })
  }
}
</script>

<template>
  <div class="page">
    <section class="intro">
      <span class="eyebrow">Maldives reef monitoring</span>
      <h1>
        Reefs change faster<br />
        than surveys can reach them.
      </h1>
      <p class="lede">
        Divers photograph reefs every day. Muraka turns those photographs into structured
        condition data: a model grades each photo patch by patch, and marine researchers
        confirm or correct every result before it counts.
      </p>

      <dl class="mechanic">
        <div>
          <dt class="eyebrow">Contributors</dt>
          <dd>Capture a sighting underwater, offline. It syncs when you surface.</dd>
        </div>
        <div>
          <dt class="eyebrow">Model</dt>
          <dd>Tiles each photo into a grid and reports bleached extent, not a verdict.</dd>
        </div>
        <div>
          <dt class="eyebrow">Researchers</dt>
          <dd>Review lowest-confidence results first. Expert labels always win.</dd>
        </div>
      </dl>
    </section>

    <section class="panel form-panel">
      <h2>{{ mode === 'sign-in' ? 'Sign in' : 'Create an account' }}</h2>
      <p class="form-note">
        {{
          mode === 'sign-in'
            ? 'Use the account your supervisor or administrator issued.'
            : 'New accounts start as contributors. An administrator grants review access.'
        }}
      </p>

      <form @submit.prevent="submit">
        <div v-if="mode === 'register'" class="field">
          <label for="displayName">Display name</label>
          <input
            id="displayName"
            v-model="displayName"
            autocomplete="name"
            required
            placeholder="How you appear on your sightings"
          />
        </div>

        <div class="field">
          <label for="email">Email</label>
          <input id="email" v-model="email" type="email" autocomplete="email" required />
        </div>

        <div class="field">
          <label for="password">Password</label>
          <input
            id="password"
            v-model="password"
            type="password"
            :autocomplete="mode === 'sign-in' ? 'current-password' : 'new-password'"
            required
            minlength="10"
          />
          <span v-if="mode === 'register'" class="hint">At least 10 characters.</span>
        </div>

        <p v-if="auth.error" class="error" role="alert">{{ auth.error }}</p>

        <button type="submit" class="btn btn-primary submit" :disabled="auth.loading">
          {{ auth.loading ? 'Working…' : mode === 'sign-in' ? 'Sign in' : 'Create account' }}
        </button>
      </form>

      <button
        type="button"
        class="btn btn-ghost switch"
        @click="mode = mode === 'sign-in' ? 'register' : 'sign-in'"
      >
        {{ mode === 'sign-in' ? 'Create an account instead' : 'I already have an account' }}
      </button>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(20rem, 26rem);
  gap: clamp(2rem, 6vw, 6rem);
  align-items: center;
  min-height: 100vh;
  padding: clamp(1.5rem, 5vw, 4.5rem);
  max-width: 84rem;
  margin: 0 auto;
}

.intro h1 {
  margin: 0.75rem 0 1.25rem;
  font-size: clamp(1.875rem, 4.2vw, 3rem);
  letter-spacing: -0.025em;
}

.lede {
  max-width: 46ch;
  color: var(--ink-muted);
  font-size: var(--step-1);
  line-height: 1.6;
}

/* Three roles, three steps — the sequence is real, so it earns dividers. */
.mechanic {
  margin: 2.5rem 0 0;
  display: grid;
  gap: 1px;
  background: var(--hairline);
  border-block: 1px solid var(--hairline);
}

.mechanic > div {
  display: grid;
  grid-template-columns: 9rem 1fr;
  gap: 1rem;
  padding: 0.875rem 0;
  background: var(--abyss);
}

.mechanic dt {
  padding-top: 0.125rem;
}

.mechanic dd {
  margin: 0;
  color: var(--ink-muted);
}

.form-panel {
  padding: 1.75rem;
  box-shadow: var(--shadow-panel);
}

.form-note {
  margin-top: 0.5rem;
  color: var(--ink-muted);
  font-size: var(--step--1);
}

form {
  display: grid;
  gap: 1rem;
  margin-top: 1.5rem;
}

.hint {
  color: var(--ink-faint);
  font-size: var(--step--1);
}

.error {
  padding: 0.5rem 0.625rem;
  border-left: 2px solid var(--rust);
  background: color-mix(in srgb, var(--rust) 12%, transparent);
  color: var(--ink);
  font-size: var(--step--1);
}

.submit {
  margin-top: 0.25rem;
}

.switch {
  margin-top: 1rem;
  padding-inline: 0;
  font-size: var(--step--1);
}

@media (max-width: 60rem) {
  .page {
    grid-template-columns: 1fr;
    align-content: center;
  }

  .mechanic {
    margin-top: 1.5rem;
  }

  .mechanic > div {
    grid-template-columns: 1fr;
    gap: 0.25rem;
  }
}
</style>
