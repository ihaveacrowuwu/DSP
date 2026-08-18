<script setup lang="ts">
/**
 * Operations — the administrator's view of people, models and the inference
 * pipeline. Deliberately plain: this screen exists to answer "is it working?"
 * and "who can do what?".
 */
import { onMounted, onUnmounted, ref } from 'vue'

import { ApiError, api, type ModelVersion, type QueueDepth, type Role, type User } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const users = ref<User[]>([])
const models = ref<ModelVersion[]>([])
const queue = ref<QueueDepth | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)

let poll: number | undefined

const roles: Role[] = ['contributor', 'researcher', 'admin']

async function loadAll() {
  try {
    const [userResult, modelResult, queueResult] = await Promise.all([
      api.users(),
      api.models(),
      api.queueDepth(),
    ])
    users.value = userResult.items
    models.value = modelResult.items
    queue.value = queueResult
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not load operations data.'
  } finally {
    loading.value = false
  }
}

async function changeRole(user: User, role: Role) {
  error.value = null
  try {
    await api.setUserRole(user.id, role)
    user.role = role
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not change that role.'
    await loadAll()
  }
}

async function toggleBan(user: User) {
  const next = user.status === 'banned' ? 'active' : 'banned'
  error.value = null
  try {
    await api.setUserStatus(user.id, next)
    user.status = next
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not update that account.'
  }
}

async function activate(model: ModelVersion) {
  error.value = null
  try {
    await api.activateModel(model.version)
    models.value.forEach((m) => (m.isActive = m.version === model.version))
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not activate that model.'
  }
}

onMounted(async () => {
  await loadAll()
  // The queue is the live signal an administrator watches; refresh it gently.
  poll = window.setInterval(async () => {
    try {
      queue.value = await api.queueDepth()
    } catch {
      // Transient failures resolve on the next tick.
    }
  }, 5000)
})

onUnmounted(() => {
  if (poll) window.clearInterval(poll)
})
</script>

<template>
  <header class="head">
    <div>
      <span class="eyebrow">Operations</span>
      <h1>People, models and the pipeline</h1>
    </div>
  </header>

  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div v-if="loading" class="state">Loading…</div>

  <div v-else class="grid">
    <section class="panel block">
      <span class="eyebrow">Inference pipeline</span>
      <div v-if="queue" class="counters">
        <div>
          <span class="readout figure">{{ queue.queued }}</span>
          <span class="figure-label">waiting</span>
        </div>
        <div>
          <span class="readout figure">{{ queue.running }}</span>
          <span class="figure-label">in progress</span>
        </div>
        <div>
          <span class="readout figure" :class="{ alarm: queue.failed > 0 }">{{ queue.failed }}</span>
          <span class="figure-label">failed</span>
        </div>
        <div>
          <span class="readout figure">{{ queue.done }}</span>
          <span class="figure-label">completed</span>
        </div>
      </div>
      <p class="hint">Refreshes every few seconds.</p>
    </section>

    <section class="panel block">
      <span class="eyebrow">Model versions</span>
      <table>
        <thead>
          <tr>
            <th scope="col">Version</th>
            <th scope="col">Task</th>
            <th scope="col">Trained</th>
            <th scope="col"><span class="sr-only">Activate</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="model in models" :key="model.id">
            <td class="readout">
              {{ model.version }}
              <span v-if="model.isActive" class="active-tag">serving</span>
            </td>
            <td class="dim">{{ model.task.replace('_', ' ') }}</td>
            <td class="readout dim">
              {{ model.trainedAt ? new Date(model.trainedAt).toLocaleDateString() : '—' }}
            </td>
            <td class="row-action">
              <button
                v-if="!model.isActive"
                type="button"
                class="btn"
                @click="activate(model)"
              >
                Serve this
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="hint">
        Activating records which version future predictions cite. Restart the ML service to load a
        new artefact.
      </p>
    </section>

    <section class="panel block wide">
      <span class="eyebrow">Accounts / {{ users.length }}</span>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Email</th>
            <th scope="col">Role</th>
            <th scope="col">Status</th>
            <th scope="col"><span class="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>{{ user.displayName }}</td>
            <td class="dim">{{ user.email }}</td>
            <td>
              <select
                :value="user.role"
                :disabled="user.id === auth.user?.id"
                @change="changeRole(user, ($event.target as HTMLSelectElement).value as Role)"
              >
                <option v-for="role in roles" :key="role" :value="role">{{ role }}</option>
              </select>
            </td>
            <td>
              <span class="chip" :class="user.status === 'banned' ? 'chip-rejected' : 'chip-healthy'">
                {{ user.status }}
              </span>
            </td>
            <td class="row-action">
              <button
                type="button"
                class="btn"
                :class="{ 'btn-danger': user.status !== 'banned' }"
                :disabled="user.id === auth.user?.id"
                @click="toggleBan(user)"
              >
                {{ user.status === 'banned' ? 'Restore access' : 'Suspend' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.head {
  padding: 1.5rem clamp(1.25rem, 3vw, 2rem) 1.25rem;
  border-bottom: 1px solid var(--hairline);
}

.head h1 {
  margin-top: 0.375rem;
  font-size: var(--step-2);
}

.error {
  margin: 1rem clamp(1.25rem, 3vw, 2rem) 0;
  padding: 0.5rem 0.75rem;
  border-left: 2px solid var(--rust);
  background: color-mix(in srgb, var(--rust) 12%, transparent);
  font-size: var(--step--1);
}

.state {
  padding: 4rem clamp(1.25rem, 3vw, 2rem);
  color: var(--ink-muted);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(24rem, 100%), 1fr));
  gap: 1rem;
  padding: clamp(1.25rem, 3vw, 2rem);
  align-items: start;
}

.block {
  padding: 1rem;
  display: grid;
  gap: 0.75rem;
  overflow-x: auto;
}

.wide {
  grid-column: 1 / -1;
}

.counters {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
}

.counters > div {
  display: grid;
  gap: 0.125rem;
}

.figure {
  font-size: var(--step-3);
  font-weight: 600;
  line-height: 1;
}

.figure.alarm {
  color: var(--rust);
}

.figure-label {
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-faint);
}

table {
  width: 100%;
  border-collapse: collapse;
}

th {
  text-align: left;
  padding: 0 0.5rem 0.375rem;
  font-family: var(--font-mono);
  font-size: var(--step--1);
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-faint);
  border-bottom: 1px solid var(--hairline);
}

td {
  padding: 0.5rem;
  border-bottom: 1px solid var(--hairline);
  font-size: var(--step--1);
}

select {
  padding: 0.25rem 0.375rem;
  border: 1px solid var(--shallow);
  border-radius: var(--radius-sm);
  background: var(--abyss);
  color: var(--ink);
  font: inherit;
  font-size: var(--step--1);
}

.dim {
  color: var(--ink-muted);
}

.row-action {
  text-align: right;
}

.active-tag {
  margin-left: 0.5rem;
  padding: 0.0625rem 0.375rem;
  border-radius: 999px;
  background: var(--living);
  color: #04231c;
  font-family: var(--font-ui);
  font-size: 0.625rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.hint {
  color: var(--ink-faint);
  font-size: var(--step--1);
}
</style>
