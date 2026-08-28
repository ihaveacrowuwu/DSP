<script setup lang="ts">
/**
 * Operations - the administrator's view of people, models and the inference
 * pipeline.
 *
 * This screen answers two questions and nothing else: "is it working?" and "who
 * can do what?". The pipeline block is deliberately the loudest thing on it,
 * because a stalled queue is the failure that quietly stops the whole system
 * while every other screen keeps looking fine.
 *
 * Suspending an account confirms in place rather than in a dialog. A modal for one
 * destructive button is more chrome than the action deserves; making the button
 * itself ask is the same protection with none of the machinery.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'

import MetricBar from '@/components/ui/MetricBar.vue'
import SelectMenu from '@/components/ui/SelectMenu.vue'
import { ApiError, api, type ModelVersion, type QueueDepth, type Role, type User } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const users = ref<User[]>([])
const models = ref<ModelVersion[]>([])
const queue = ref<QueueDepth | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)
/** Id of the account whose suspension is awaiting a second click. */
const confirming = ref<string | null>(null)

let poll: number | undefined

const ROLE_OPTIONS = [
  { value: 'contributor', label: 'Contributor', hint: 'Submits sightings' },
  { value: 'researcher', label: 'Researcher', hint: 'Reviews and verifies' },
  { value: 'admin', label: 'Admin', hint: 'Full access, including this screen' },
]

/**
 * Failures as a share of everything the pipeline has attempted. A raw count of
 * three failures means nothing without knowing whether it processed ten jobs or
 * ten thousand.
 */
const failureShare = computed(() => {
  const depth = queue.value
  if (!depth) return 0
  const attempted = depth.done + depth.failed
  return attempted ? depth.failed / attempted : 0
})

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

async function changeRole(user: User, role: string) {
  error.value = null
  try {
    await api.setUserRole(user.id, role as Role)
    user.role = role as Role
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not change that role.'
    // The optimistic update is now suspect, so re-read rather than guess.
    await loadAll()
  }
}

async function setStatus(user: User, next: 'active' | 'banned') {
  error.value = null
  confirming.value = null
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
    models.value.forEach((entry) => {
      entry.isActive = entry.version === model.version
    })
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not activate that model.'
  }
}

onMounted(async () => {
  await loadAll()
  // The queue is the live signal an administrator watches; refresh it gently and
  // on its own, so a slow user list never delays it.
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
  <div class="page">
    <header class="page-head">
      <div>
        <span class="eyebrow">Operations</span>
        <h1>People, models and the pipeline</h1>
      </div>
      <p class="page-head-note">
        Everything on this screen is live. The pipeline figures refresh every few
        seconds.
      </p>
    </header>

    <p v-if="error" class="notice" role="alert">{{ error }}</p>
    <div v-if="loading" class="state">Loading…</div>

    <div v-else class="grid">
      <section class="section">
        <span class="eyebrow">Inference pipeline</span>

        <div v-if="queue" class="counters">
          <div class="figure-block">
            <span class="figure">{{ queue.queued }}</span>
            <span class="figure-label">waiting</span>
          </div>
          <div class="figure-block">
            <span class="figure">{{ queue.running }}</span>
            <span class="figure-label">in progress</span>
          </div>
          <div class="figure-block">
            <span class="figure" :class="{ 'figure-alarm': queue.failed > 0 }">
              {{ queue.failed }}
            </span>
            <span class="figure-label">failed</span>
          </div>
          <div class="figure-block">
            <span class="figure">{{ queue.done }}</span>
            <span class="figure-label">completed</span>
          </div>
        </div>

        <MetricBar
          v-if="queue"
          label="Failure rate"
          :value="failureShare"
          :tone="failureShare > 0.05 ? 'rust' : 'reef'"
          :display="`${(failureShare * 100).toFixed(1)}%`"
        />
        <p class="hint">
          Failures are retried by the worker. A rate that keeps climbing means the ML
          service needs attention, not the queue.
        </p>
      </section>

      <section class="section">
        <span class="eyebrow">Model versions</span>
        <div class="table-wrap">
          <table class="data-table">
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
                  <span v-if="model.isActive" class="serving">serving</span>
                </td>
                <td class="muted">{{ model.task.replace('_', ' ') }}</td>
                <td class="cell-num">
                  {{ model.trainedAt ? new Date(model.trainedAt).toLocaleDateString() : '—' }}
                </td>
                <td class="cell-right">
                  <button
                    v-if="!model.isActive"
                    type="button"
                    class="btn btn-secondary btn-sm"
                    @click="activate(model)"
                  >
                    Serve this
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="hint">
          Activating records which version future predictions cite. Restart the ML
          service to load a new artefact.
        </p>
      </section>

      <section class="section wide">
        <span class="eyebrow">Accounts / {{ users.length }}</span>
        <div class="table-wrap">
          <table class="data-table">
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
                <td class="muted">{{ user.email }}</td>
                <td>
                  <!-- Your own role is not editable here: an admin who demoted
                       themselves would be locked out of the only screen that
                       could undo it. -->
                  <SelectMenu
                    :model-value="user.role"
                    :options="ROLE_OPTIONS"
                    :disabled="user.id === auth.user?.id"
                    size="sm"
                    :ariaLabel="`Role for ${user.displayName}`"
                    @update:model-value="changeRole(user, $event)"
                  />
                </td>
                <td>
                  <span
                    class="chip"
                    :class="user.status === 'banned' ? 'chip-rejected' : 'chip-healthy'"
                  >
                    {{ user.status }}
                  </span>
                </td>
                <td class="cell-right">
                  <template v-if="user.status === 'banned'">
                    <button
                      type="button"
                      class="btn btn-secondary btn-sm"
                      :disabled="user.id === auth.user?.id"
                      @click="setStatus(user, 'active')"
                    >
                      Restore access
                    </button>
                  </template>

                  <template v-else-if="confirming === user.id">
                    <span class="confirm">
                      <button
                        type="button"
                        class="btn btn-danger btn-sm"
                        @click="setStatus(user, 'banned')"
                      >
                        Confirm suspend
                      </button>
                      <button
                        type="button"
                        class="btn btn-ghost btn-sm"
                        @click="confirming = null"
                      >
                        Cancel
                      </button>
                    </span>
                  </template>

                  <template v-else>
                    <button
                      type="button"
                      class="btn btn-danger btn-sm"
                      :disabled="user.id === auth.user?.id"
                      @click="confirming = user.id"
                    >
                      Suspend
                    </button>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="hint">
          A suspended account keeps its sightings; it simply cannot sign in or submit
          more.
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(23rem, 100%), 1fr));
  gap: 0.875rem;
  align-items: start;
}

.wide {
  grid-column: 1 / -1;
}

.counters {
  display: flex;
  flex-wrap: wrap;
  gap: 1.25rem 1.75rem;
}

.serving {
  margin-left: 0.5rem;
  padding: 0.0625rem 0.375rem;
  border-radius: var(--r-pill);
  background: var(--reef);
  color: var(--accent-ink);
  font-family: var(--font-ui);
  font-size: var(--step--2);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.confirm {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  /* The two-step control appears in place, so it should not shove the column
     wider as it does. */
  animation: pop-in var(--dur-fast) var(--ease-spring);
}

.hint {
  color: var(--ink-4);
  font-size: var(--step--2);
  line-height: 1.45;
}
</style>
