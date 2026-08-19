<script setup lang="ts">
/**
 * Sighting records. Contributors see their own submissions here; researchers see
 * everything and can export the filtered set.
 *
 * The table is the point of this screen, so the filters above it are one row of
 * controls rather than a panel: condition as a segmented control (three choices,
 * always worth showing), verification as a switch, and dates as a single range
 * picker. Every filter is in the request, so the export downloads exactly the
 * rows on screen.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import ConditionChip from '@/components/ConditionChip.vue'
import CheckBox from '@/components/ui/CheckBox.vue'
import DateRangeField from '@/components/ui/DateRangeField.vue'
import SegmentedTabs from '@/components/ui/SegmentedTabs.vue'
import { ApiError, api, type Condition, type Sighting } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const sightings = ref<Sighting[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)

// Held as a plain string because the segmented control speaks strings; it is
// narrowed back to the API's union where the request is built.
const condition = ref('')
const verifiedOnly = ref(false)
const from = ref('')
const to = ref('')
const page = ref(0)
const pageSize = 25

const CONDITIONS = [
  { value: '', label: 'Any' },
  { value: 'healthy', label: 'Healthy' },
  { value: 'bleached', label: 'Bleached' },
]

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

/** Filters as the API wants them; shared by the list request and the export URL
    so a download can never disagree with what is on screen. */
const filters = computed(() => ({
  condition: (condition.value || undefined) as Condition | undefined,
  verified: verifiedOnly.value || undefined,
  from: from.value || undefined,
  to: to.value || undefined,
}))

const exportUrl = computed(() => api.exportCsvUrl(filters.value))

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await api.listSightings({
      ...filters.value,
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

// Any filter change invalidates the current offset, so paging restarts.
watch([condition, verifiedOnly, from, to], () => {
  page.value = 0
  void load()
})
watch(page, load)

/**
 * The whole row opens the record, since that is the only thing a row does. The
 * "Open" link stays: it is the keyboard target, it advertises that rows are
 * clickable, and a modified click on it opens a background tab like any link.
 *
 * Three things are deliberately not navigation: a click on a real control, a
 * modified click (the browser owns those), and a click that ends a text
 * selection — coordinates and site names in this table are there to be copied.
 */
function openRecord(sighting: Sighting, event: MouseEvent) {
  if (event.defaultPrevented) return
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  if ((event.target as HTMLElement | null)?.closest('a, button, input, select, label')) return
  if (window.getSelection()?.toString()) return

  void router.push({ name: 'sighting', params: { id: sighting.id } })
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <span class="eyebrow">Records / {{ total }} sightings</span>
        <h1>{{ auth.canVerify ? 'All sightings' : 'Your sightings' }}</h1>
      </div>

      <div class="toolbar">
        <SegmentedTabs
          v-model="condition"
          :options="CONDITIONS"
          ariaLabel="Filter by condition"
          size="sm"
        />

        <label class="check-row">
          <CheckBox v-model="verifiedOnly" ariaLabel="Show expert-verified sightings only" />
          Expert-verified only
        </label>

        <DateRangeField v-model:from="from" v-model:to="to" ariaLabel="Filter by capture date" />

        <a v-if="auth.canVerify" class="btn btn-secondary" :href="exportUrl" download>
          Export CSV
        </a>
      </div>
    </header>

    <p v-if="error" class="notice" role="alert">{{ error }}</p>

    <div v-if="loading" class="loading" aria-hidden="true">
      <span v-for="row in 6" :key="row" class="skeleton" />
      <span class="sr-only">Loading sightings…</span>
    </div>

    <div v-else-if="!sightings.length" class="state">
      <h2>Nothing matches</h2>
      <p v-if="condition || verifiedOnly || from || to">
        No sighting fits these filters. Widen the date range or clear the condition
        filter to see more.
      </p>
      <p v-else-if="auth.canVerify">
        Once contributors sync their first sightings, they will appear here.
      </p>
      <p v-else>
        Capture a sighting in the Muraka mobile app. It uploads as soon as you have signal.
      </p>
    </div>

    <template v-else>
      <div class="card table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th scope="col">Captured</th>
              <th scope="col">Position</th>
              <th scope="col">Depth</th>
              <th scope="col">Site</th>
              <th v-if="auth.canVerify" scope="col">Contributor</th>
              <th scope="col">Extent</th>
              <th scope="col">Condition</th>
              <th scope="col"><span class="sr-only">Open record</span></th>
            </tr>
          </thead>
          <tbody>
            <!-- Every cell's content sits in a .cell-body so the hover growth happens
                 inside each column instead of scaling the row's box. -->
            <tr
              v-for="sighting in sightings"
              :key="sighting.id"
              data-row-link
              @click="openRecord(sighting, $event)"
            >
              <td>
                <span class="cell-body readout">{{ formatDate(sighting.capturedAt) }}</span>
              </td>
              <td>
                <span class="cell-body cell-num">
                  {{ sighting.location.lat.toFixed(3) }}, {{ sighting.location.lon.toFixed(3) }}
                </span>
              </td>
              <td>
                <span class="cell-body cell-num">
                  {{ sighting.depthM !== undefined ? `${sighting.depthM.toFixed(1)} m` : '—' }}
                </span>
              </td>
              <td><span class="cell-body">{{ sighting.siteName ?? '—' }}</span></td>
              <td v-if="auth.canVerify">
                <span class="cell-body">{{ sighting.contributorName }}</span>
              </td>
              <!-- Bleached extent as a bar, so a column of rows is scannable for
                   severity without reading every number. -->
              <td>
                <span v-if="sighting.severity !== undefined" class="cell-body extent">
                  <span class="extent-track">
                    <span class="extent-fill" :style="{ width: `${sighting.severity * 100}%` }" />
                  </span>
                  <span class="extent-value readout">
                    {{ Math.round(sighting.severity * 100) }}%
                  </span>
                </span>
                <span v-else class="cell-body faint">—</span>
              </td>
              <td>
                <span class="cell-body">
                  <ConditionChip
                    :condition="sighting.condition"
                    :status="sighting.status"
                    :verified="sighting.verified"
                    :severity="sighting.severity"
                  />
                </span>
              </td>
              <td class="cell-right">
                <RouterLink
                  class="cell-body open"
                  :to="{ name: 'sighting', params: { id: sighting.id } }"
                >
                  Open
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <nav v-if="pageCount > 1" class="pager" aria-label="Pagination">
        <button
          type="button"
          class="btn btn-secondary"
          :disabled="page === 0"
          @click="page -= 1"
        >
          Previous
        </button>
        <span class="readout small muted">Page {{ page + 1 }} of {{ pageCount }}</span>
        <button
          type="button"
          class="btn btn-secondary"
          :disabled="page + 1 >= pageCount"
          @click="page += 1"
        >
          Next
        </button>
      </nav>
    </template>
  </div>
</template>

<style scoped>
.loading {
  display: grid;
  gap: 0.5rem;
}

.loading .skeleton {
  height: 2.5rem;
}

/* Doubles as the cell body, so it keeps inline-block's shrink-to-fit while still
   laying its bar and value out in a row. */
.extent {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  vertical-align: middle;
}

.extent-track {
  width: 3.5rem;
  height: 0.3125rem;
  border-radius: var(--r-pill);
  background: var(--surface-2);
  overflow: hidden;
}

.extent-fill {
  display: block;
  height: 100%;
  min-width: 2px;
  border-radius: var(--r-pill);
  background: var(--bone);
}

.extent-value {
  font-size: var(--step--2);
  color: var(--ink-3);
}

.open {
  font-size: var(--step--1);
  font-weight: 600;
  text-decoration: none;
}

.pager {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  margin-top: 1rem;
}
</style>
