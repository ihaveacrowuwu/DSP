<script setup lang="ts">
/**
 * Review queue — the screen researchers spend their time in.
 *
 * Designed for bursts of keyboard work: lowest-confidence sighting first, one
 * key per decision, next item prefetched so the queue never stalls.
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import ConditionChip from '@/components/ConditionChip.vue'
import PatchLattice from '@/components/PatchLattice.vue'
import {
  ApiError,
  api,
  fetchPhotoObjectUrl,
  type Condition,
  type Photo,
  type RejectReason,
  type Sighting,
} from '@/lib/api'

const queue = ref<Sighting[]>([])
const total = ref(0)
const loading = ref(true)
const submitting = ref(false)
const error = ref<string | null>(null)

const photos = ref<Photo[]>([])
const imageUrl = ref<string | null>(null)
const showLattice = ref(true)
const rejecting = ref(false)
const rejectReason = ref<RejectReason>('blurry')

const current = computed(() => queue.value[0] ?? null)
const activePhoto = computed(() => photos.value[0] ?? null)
const prediction = computed(() => activePhoto.value?.prediction ?? null)

const rejectReasons: { value: RejectReason; label: string }[] = [
  { value: 'blurry', label: 'Too blurred to assess' },
  { value: 'not_coral', label: 'Not a coral photograph' },
  { value: 'duplicate', label: 'Duplicate submission' },
  { value: 'spam', label: 'Spam' },
  { value: 'other', label: 'Other' },
]

async function loadQueue() {
  loading.value = true
  error.value = null
  try {
    const page = await api.verificationQueue(25, 0)
    queue.value = page.items
    total.value = page.total
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not load the review queue.'
  } finally {
    loading.value = false
  }
}

function releaseImage() {
  if (imageUrl.value) {
    URL.revokeObjectURL(imageUrl.value)
    imageUrl.value = null
  }
}

async function loadCurrentDetail() {
  releaseImage()
  photos.value = []
  if (!current.value) return

  try {
    const detail = await api.getSighting(current.value.id)
    photos.value = detail.photos
    if (detail.photos.length) {
      imageUrl.value = await fetchPhotoObjectUrl(detail.photos[0].id)
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not load this sighting.'
  }
}

async function decide(
  decision: 'confirmed' | 'corrected' | 'rejected',
  label?: Condition,
  reason?: RejectReason,
) {
  const sighting = current.value
  if (!sighting || submitting.value) return

  submitting.value = true
  error.value = null
  try {
    await api.verify(sighting.id, { decision, label, rejectReason: reason })
    queue.value = queue.value.slice(1)
    total.value = Math.max(0, total.value - 1)
    rejecting.value = false

    // Top up before the queue empties so review never pauses on a fetch.
    if (queue.value.length <= 3) {
      const page = await api.verificationQueue(25, 0)
      const seen = new Set(queue.value.map((s) => s.id))
      queue.value = [...queue.value, ...page.items.filter((s) => !seen.has(s.id))]
      total.value = page.total
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not record that decision.'
  } finally {
    submitting.value = false
  }
}

function confirmModel() {
  const label = prediction.value?.label ?? current.value?.condition
  if (label) void decide('confirmed', label)
}

function correctTo(label: Condition) {
  void decide('corrected', label)
}

function onKey(event: KeyboardEvent) {
  if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return

  switch (event.key.toLowerCase()) {
    case 'c':
      confirmModel()
      break
    case 'h':
      correctTo('healthy')
      break
    case 'b':
      correctTo('bleached')
      break
    case 'r':
      rejecting.value = !rejecting.value
      break
    case 'l':
      showLattice.value = !showLattice.value
      break
    case 'escape':
      rejecting.value = false
      break
  }
}

watch(current, () => void loadCurrentDetail())

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  await loadQueue()
  await loadCurrentDetail()
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  releaseImage()
})

function formatCoord(value: number, positive: string, negative: string): string {
  return `${Math.abs(value).toFixed(4)}° ${value >= 0 ? positive : negative}`
}
</script>

<template>
  <header class="head">
    <div>
      <span class="eyebrow">Review queue / {{ total }} awaiting</span>
      <h1>Confirm or correct the model</h1>
    </div>
    <p class="head-note">
      Lowest-confidence sightings come first, so your time goes where the model is weakest.
    </p>
  </header>

  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <div v-if="loading" class="state">Loading the queue…</div>

  <div v-else-if="!current" class="state empty">
    <h2>The queue is clear</h2>
    <p>Every synced sighting has been reviewed. New submissions will appear here automatically.</p>
  </div>

  <div v-else class="review">
    <figure class="plate">
      <div class="frame">
        <img v-if="imageUrl" :src="imageUrl" :alt="`Reef photograph submitted on ${current.capturedAt}`" />
        <div v-else class="frame-empty">
          {{ current.photoCount ? 'Loading photograph…' : 'This sighting has no photograph.' }}
        </div>

        <PatchLattice
          v-if="showLattice && prediction?.patches?.length"
          :patches="prediction.patches"
          :grid="prediction.patchGrid"
          variant="overlay"
          animate
        />
      </div>

      <figcaption>
        <button type="button" class="btn btn-ghost" @click="showLattice = !showLattice">
          {{ showLattice ? 'Hide model grid' : 'Show model grid' }}
          <kbd>L</kbd>
        </button>
        <span v-if="prediction" class="readout scale">
          <span class="swatch living" /> healthy
          <span class="swatch bone" /> bleached
          <span class="scale-note">cell opacity = confidence</span>
        </span>
      </figcaption>
    </figure>

    <aside class="inspector">
      <section class="assessment panel">
        <span class="eyebrow">Model assessment</span>
        <p v-if="prediction" class="verdict">
          <span class="readout severity">{{ Math.round(prediction.severity * 100) }}%</span>
          <span class="verdict-text">
            bleached extent — {{ prediction.label }} at
            {{ Math.round(prediction.confidence * 100) }}% confidence
          </span>
        </p>
        <p v-else class="verdict-text muted">No model result yet for this sighting.</p>

        <dl v-if="prediction" class="meta">
          <div>
            <dt>Grid</dt>
            <dd class="readout">{{ prediction.patchGrid }}×{{ prediction.patchGrid }}</dd>
          </div>
          <div>
            <dt>Model</dt>
            <dd class="readout">{{ prediction.modelVersion }}</dd>
          </div>
          <div v-if="prediction.inferenceMs">
            <dt>Inference</dt>
            <dd class="readout">{{ prediction.inferenceMs }} ms</dd>
          </div>
        </dl>
      </section>

      <section class="panel details">
        <span class="eyebrow">Sighting</span>
        <dl class="meta">
          <div>
            <dt>Captured</dt>
            <dd class="readout">{{ new Date(current.capturedAt).toLocaleString() }}</dd>
          </div>
          <div>
            <dt>Position</dt>
            <dd class="readout">
              {{ formatCoord(current.location.lat, 'N', 'S') }},
              {{ formatCoord(current.location.lon, 'E', 'W') }}
            </dd>
          </div>
          <div v-if="current.depthM !== undefined">
            <dt>Depth</dt>
            <dd class="readout">{{ current.depthM.toFixed(1) }} m</dd>
          </div>
          <div v-if="current.siteName">
            <dt>Site</dt>
            <dd>{{ current.siteName }}</dd>
          </div>
          <div>
            <dt>Contributor</dt>
            <dd>{{ current.contributorName }}</dd>
          </div>
          <div>
            <dt>Fix</dt>
            <dd>{{ current.locationSource === 'gps' ? 'GPS' : 'Dropped pin' }}</dd>
          </div>
        </dl>
        <p v-if="current.note" class="note">“{{ current.note }}”</p>
        <RouterLink class="detail-link" :to="{ name: 'sighting', params: { id: current.id } }">
          Open full record
        </RouterLink>
      </section>

      <section class="decide">
        <span class="eyebrow">Your decision</span>

        <div v-if="!rejecting" class="actions">
          <button
            type="button"
            class="btn btn-primary"
            :disabled="submitting || !prediction"
            @click="confirmModel"
          >
            Confirm {{ prediction?.label ?? 'assessment' }} <kbd>C</kbd>
          </button>

          <div class="correct">
            <span class="correct-label">Correct to</span>
            <button type="button" class="btn" :disabled="submitting" @click="correctTo('healthy')">
              Healthy <kbd>H</kbd>
            </button>
            <button type="button" class="btn" :disabled="submitting" @click="correctTo('bleached')">
              Bleached <kbd>B</kbd>
            </button>
          </div>

          <button type="button" class="btn btn-danger" :disabled="submitting" @click="rejecting = true">
            Reject photograph <kbd>R</kbd>
          </button>
        </div>

        <div v-else class="reject-form">
          <div class="field">
            <label for="reason">Why is it unusable?</label>
            <select id="reason" v-model="rejectReason">
              <option v-for="option in rejectReasons" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </div>
          <div class="reject-actions">
            <button
              type="button"
              class="btn btn-danger"
              :disabled="submitting"
              @click="decide('rejected', undefined, rejectReason)"
            >
              Reject
            </button>
            <button type="button" class="btn btn-ghost" @click="rejecting = false">Cancel</button>
          </div>
        </div>
      </section>

      <section v-if="queue.length > 1" class="upnext">
        <span class="eyebrow">Next in queue</span>
        <ul>
          <li v-for="sighting in queue.slice(1, 5)" :key="sighting.id">
            <PatchLattice
              v-if="sighting.severity !== undefined"
              :patches="[]"
              :grid="5"
              class="upnext-glyph"
            />
            <span class="readout upnext-date">
              {{ new Date(sighting.capturedAt).toLocaleDateString() }}
            </span>
            <ConditionChip
              :condition="sighting.condition"
              :status="sighting.status"
              :verified="sighting.verified"
              :severity="sighting.severity"
            />
          </li>
        </ul>
      </section>
    </aside>
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

.head-note {
  max-width: 34ch;
  color: var(--ink-muted);
  font-size: var(--step--1);
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

.review {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(21rem, 26rem);
  gap: clamp(1rem, 2.5vw, 2rem);
  padding: clamp(1.25rem, 3vw, 2rem);
  align-items: start;
}

.plate {
  margin: 0;
  display: grid;
  gap: 0.75rem;
}

.frame {
  position: relative;
  aspect-ratio: 1;
  max-height: 68vh;
  background: var(--shelf);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  overflow: hidden;
}

.frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.frame-empty {
  display: grid;
  place-items: center;
  height: 100%;
  color: var(--ink-faint);
  font-size: var(--step--1);
}

figcaption {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1rem;
  align-items: center;
  justify-content: space-between;
}

.scale {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: var(--step--1);
  color: var(--ink-muted);
}

.swatch {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 2px;
  display: inline-block;
}

.swatch.living {
  background: var(--living);
}

.swatch.bone {
  background: var(--bone);
  margin-left: 0.5rem;
}

.scale-note {
  margin-left: 0.5rem;
  color: var(--ink-faint);
}

.inspector {
  display: grid;
  gap: 0.875rem;
  position: sticky;
  top: 1rem;
}

.assessment,
.details {
  padding: 1rem;
  display: grid;
  gap: 0.75rem;
}

.verdict {
  display: flex;
  align-items: baseline;
  gap: 0.625rem;
  flex-wrap: wrap;
}

.severity {
  font-size: var(--step-4);
  font-weight: 600;
  line-height: 1;
  color: var(--bone);
}

.verdict-text {
  color: var(--ink-muted);
  font-size: var(--step--1);
  max-width: 22ch;
}

.verdict-text.muted {
  color: var(--ink-faint);
}

.meta {
  margin: 0;
  display: grid;
  gap: 0.375rem;
}

.meta > div {
  display: grid;
  grid-template-columns: 6.5rem 1fr;
  gap: 0.75rem;
}

.meta dt {
  font-family: var(--font-mono);
  font-size: var(--step--1);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-faint);
}

.meta dd {
  margin: 0;
  font-size: var(--step--1);
}

.note {
  padding-left: 0.75rem;
  border-left: 2px solid var(--hairline);
  color: var(--ink-muted);
  font-style: italic;
  font-size: var(--step--1);
}

.detail-link {
  font-size: var(--step--1);
}

.decide {
  display: grid;
  gap: 0.75rem;
  padding: 1rem;
  border: 1px solid var(--shallow);
  border-radius: var(--radius);
  background: var(--shelf-raised);
}

.actions {
  display: grid;
  gap: 0.625rem;
}

.correct {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.correct-label {
  font-size: var(--step--1);
  color: var(--ink-muted);
}

.reject-form {
  display: grid;
  gap: 0.75rem;
}

.reject-actions {
  display: flex;
  gap: 0.5rem;
}

kbd {
  padding: 0.0625rem 0.3125rem;
  border: 1px solid currentColor;
  border-radius: 2px;
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  opacity: 0.6;
}

.upnext ul {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
  display: grid;
  gap: 1px;
  background: var(--hairline);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.upnext li {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.625rem;
  background: var(--shelf);
}

.upnext-glyph {
  width: 1.25rem;
}

.upnext-date {
  font-size: var(--step--1);
  color: var(--ink-muted);
  margin-right: auto;
}

@media (max-width: 66rem) {
  .review {
    grid-template-columns: 1fr;
  }

  .inspector {
    position: static;
  }
}
</style>
