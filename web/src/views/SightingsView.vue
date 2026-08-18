<script setup lang="ts">
/**
 * Sighting records. Contributors see their own submissions here; researchers see
 * everything and can export the filtered set.
 */
import { computed, onMounted, ref, watch } from 'vue'

import ConditionChip from '@/components/ConditionChip.vue'
import { ApiError, api, type Condition, type Sighting } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const sightings = ref<Sighting[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)

const condition = ref<Condition | ''>('')
const verifiedOnly = ref(false)
const page = ref(0)
const pageSize = 25

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

const exportUrl = computed(() =>
  api.exportCsvUrl({
    condition: condition.value || undefined,
    verified: verifiedOnly.value || undefined,
  }),
)

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await api.listSightings({
      condition: condition.value || undefined,
      verified: verifiedOnly.value || undefined,
      limit: pageSize,
      offset: page.value * pageSize,
    })
    sightings.value = result.items
    total.value = result.total
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not load sightings.'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([condition, verifiedOnly], () => {
  page.value = 0
  void load()
})
watch(page, load)
</script>

<template>
  <header class="head">
    <div>
      <span class="eyebrow">Sightings / {{ total }} records</span>
      <h1>{{ auth.canVerify ? 'All sightings' : 'Your sightings' }}</h1>
    </div>

    <div class="controls">
      <div class="field inline">
        <label for="condition">Condition</label>
        <select id="condition" v-model="condition">
          <option value="">Any</option>
          <option value="healthy">Healthy</option>
          <option value="bleached">Bleached</option>
        </select>
      </div>

      <label class="toggle">
        <input v-model="verifiedOnly" type="checkbox" />
        Expert-verified only
      </label>

      <a v-if="auth.canVerify" class="btn" :href="exportUrl" download> Export CSV </a>
    </div>
  </header>

  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <div v-if="loading" class="state">Loading sightings…</div>

  <div v-else-if="!sightings.length" class="state empty">
    <h2>Nothing recorded yet</h2>
    <p v-if="auth.canVerify">
      Once contributors sync their first sightings, they will appear here.
    </p>
    <p v-else>
      Capture a sighting in the Muraka mobile app. It uploads as soon as you have signal.
    </p>
  </div>

  <div v-else class="table-wrap">
    <table>
      <thead>
        <tr>
          <th scope="col">Captured</th>
          <th scope="col">Position</th>
          <th scope="col">Depth</th>
          <th scope="col">Site</th>
          <th v-if="auth.canVerify" scope="col">Contributor</th>
          <th scope="col">Condition</th>
          <th scope="col"><span class="sr-only">Open</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="sighting in sightings" :key="sighting.id">
          <td class="readout">{{ new Date(sighting.capturedAt).toLocaleDateString() }}</td>
          <td class="readout dim">
            {{ sighting.location.lat.toFixed(3) }}, {{ sighting.location.lon.toFixed(3) }}
          </td>
          <td class="readout dim">
            {{ sighting.depthM !== undefined ? `${sighting.depthM.toFixed(1)} m` : '—' }}
          </td>
          <td>{{ sighting.siteName ?? '—' }}</td>
          <td v-if="auth.canVerify">{{ sighting.contributorName }}</td>
          <td>
            <ConditionChip
              :condition="sighting.condition"
              :status="sighting.status"
              :verified="sighting.verified"
              :severity="sighting.severity"
            />
          </td>
          <td class="row-action">
            <RouterLink :to="{ name: 'sighting', params: { id: sighting.id } }">Open</RouterLink>
          </td>
        </tr>
      </tbody>
    </table>

    <nav v-if="pageCount > 1" class="pager" aria-label="Pagination">
      <button type="button" class="btn" :disabled="page === 0" @click="page -= 1">Previous</button>
      <span class="readout page-count">Page {{ page + 1 }} of {{ pageCount }}</span>
      <button type="button" class="btn" :disabled="page + 1 >= pageCount" @click="page += 1">
        Next
      </button>
    </nav>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem 2rem;
  align-items: flex-end;
  justify-content: space-between;
  padding: 1.5rem clamp(1.25rem, 3vw, 2rem) 1.25rem;
  border-bottom: 1px solid var(--hairline);
}

.head h1 {
  margin-top: 0.375rem;
  font-size: var(--step-2);
}

.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem;
}

.field.inline {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.toggle {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: var(--step--1);
  color: var(--ink-muted);
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

.state.empty h2 {
  color: var(--ink);
  margin-bottom: 0.5rem;
}

.table-wrap {
  padding: clamp(1.25rem, 3vw, 2rem);
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  min-width: 44rem;
}

th {
  text-align: left;
  padding: 0 0.75rem 0.5rem;
  font-family: var(--font-mono);
  font-size: var(--step--1);
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-faint);
  border-bottom: 1px solid var(--hairline);
}

td {
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid var(--hairline);
  font-size: var(--step-0);
}

tbody tr:hover {
  background: var(--shelf);
}

.dim {
  color: var(--ink-muted);
  font-size: var(--step--1);
}

.row-action {
  text-align: right;
}

.pager {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-top: 1.25rem;
}

.page-count {
  font-size: var(--step--1);
  color: var(--ink-muted);
}
</style>
