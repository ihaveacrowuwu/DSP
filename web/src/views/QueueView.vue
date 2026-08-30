<script setup lang="ts">
/**
 * Review queue - the screen researchers spend their time in.
 *
 * Designed for bursts of keyboard work: lowest-confidence sighting first, one key
 * per decision, the next item prefetched so the queue never stalls. Every action
 * shows its key on the button, because a reviewer who has done fifty of these
 * should never need to reach for the mouse, and one who is on their first should
 * not need a manual.
 *
 * The model's output is presented as a claim with its own confidence attached,
 * never as a verdict: the patch lattice shows where it thinks the bleaching is,
 * the bars show how sure it is, and the buttons treat confirming and correcting
 * as equally normal outcomes.
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import ConditionChip from '@/components/ConditionChip.vue'
import PatchLattice from '@/components/PatchLattice.vue'
import MetricBar from '@/components/ui/MetricBar.vue'
import SelectMenu from '@/components/ui/SelectMenu.vue'
import {
  ApiError,
  api,
  fetchPhotoObjectUrl,
  type Condition,
  type Photo,
  type RejectReason,
  type Sighting,
} from '@/lib/api'
import { LOW_RESOLUTION_TIP, dimensions, isLowResolution } from '@/lib/photos'

const queue = ref<Sighting[]>([])
const total = ref(0)
const loading = ref(true)
const submitting = ref(false)
const error = ref<string | null>(null)

const photos = ref<Photo[]>([])
const imageUrl = ref<string | null>(null)
const showLattice = ref(true)
const rejecting = ref(false)
// A plain string, because the select speaks strings; narrowed back to the
// API's union at the one place it is submitted.
const rejectReason = ref('blurry')

const current = computed(() => queue.value[0] ?? null)
const activePhoto = computed(() => photos.value[0] ?? null)
const prediction = computed(() => activePhoto.value?.prediction ?? null)

/** Reasons carry a plain-language hint, because "other" needs a boundary. */
const REJECT_REASONS = [
  { value: 'blurry', label: 'Too blurred to assess', hint: 'No patch can be graded' },
  { value: 'not_coral', label: 'Not a coral photograph', hint: 'Wrong subject entirely' },
  { value: 'duplicate', label: 'Duplicate submission', hint: 'Same reef, same moment' },
  { value: 'spam', label: 'Spam', hint: 'Deliberate noise' },
  { value: 'other', label: 'Other', hint: 'Unusable for a reason not listed' },
]

/** Share of patches the model called bleached - the lattice as one number. */
const bleachedShare = computed(() => {
  const patches = prediction.value?.patches ?? []
  if (!patches.length) return 0
  return patches.filter((patch) => patch.label === 'bleached').length / patches.length
})

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
      const seen = new Set(queue.value.map((item) => item.id))
      queue.value = [...queue.value, ...page.items.filter((item) => !seen.has(item.id))]
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
  // Shortcuts must never fire while someone is typing or working a menu.
  const target = event.target as HTMLElement | null
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target?.getAttribute('role') === 'listbox' ||
    target?.getAttribute('aria-haspopup') === 'listbox'
  ) {
    return
  }

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
  <div class="page">
    <header class="page-head">
      <div>
        <span class="eyebrow">Review queue / {{ total }} awaiting</span>
        <h1>Confirm or correct the model</h1>
      </div>
      <p class="page-head-note">
        Lowest-confidence sightings come first, so your time goes where the model is
        weakest.
      </p>
    </header>

    <p v-if="error" class="notice" role="alert">{{ error }}</p>

    <div v-if="loading" class="state">Loading the queue…</div>

    <div v-else-if="!current" class="state">
      <h2>The queue is clear</h2>
      <p>
        Every synced sighting has been reviewed. New submissions appear here as soon as
        the model finishes with them.
      </p>
    </div>

    <div v-else class="review">
      <figure class="plate">
        <div class="frame well">
          <img
            v-if="imageUrl"
            :src="imageUrl"
            :alt="`Reef photograph captured on ${new Date(current.capturedAt).toLocaleDateString()}`"
          />
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
            <span class="kbd">L</span>
          </button>

          <span v-if="prediction" class="scale">
            <span class="swatch swatch-reef" aria-hidden="true" />healthy
            <span class="swatch swatch-bone" aria-hidden="true" />bleached
            <span class="faint">cell opacity is confidence</span>
          </span>

          <!-- The source resolution belongs on the screen where the verdict is
               given: whether the photograph is good enough to judge is part of
               the judgement. -->
          <span v-if="activePhoto" class="source">
            <span class="readout">{{ dimensions(activePhoto.width, activePhoto.height) }}</span>
            <span
              v-if="isLowResolution(activePhoto.width, activePhoto.height)"
              class="faint low-res"
              :data-tip="LOW_RESOLUTION_TIP"
              data-tip-side="top"
              >low resolution</span
            >
          </span>
        </figcaption>
      </figure>

      <!-- Two independent stacks, not one: side by side when the inspector has
           the width, so the decision block sits at the top right of the screen
           instead of below the fold at the end of one long column. -->
      <aside class="inspector">
        <div class="inspector-col">
          <section class="section">
            <span class="eyebrow">Model assessment</span>

            <template v-if="prediction">
              <p class="verdict">
                <span class="readout severity">{{ Math.round(prediction.severity * 100) }}%</span>
                <span class="verdict-text">
                  bleached extent - reads
                  <strong>{{ prediction.label }}</strong>
                </span>
              </p>

              <!-- Extent, confidence and the lattice's own tally, in that order:
                   what it found, how sure it is, and how much of the grid agrees. -->
              <div class="metrics">
                <MetricBar label="Extent" :value="prediction.severity" tone="bone" />
                <MetricBar label="Confidence" :value="prediction.confidence" tone="reef" />
                <MetricBar
                  label="Patches"
                  :value="bleachedShare"
                  tone="bone"
                  :display="`${prediction.patches.filter((p) => p.label === 'bleached').length}/${prediction.patches.length}`"
                />
              </div>

              <dl class="meta">
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
            </template>

            <p v-else class="muted small">No model result yet for this sighting.</p>
          </section>

          <section class="section">
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
            <p v-if="current.note" class="quote">“{{ current.note }}”</p>
            <RouterLink
              class="small"
              :to="{ name: 'sighting', params: { id: current.id } }"
            >
              Open the full record
            </RouterLink>
          </section>
        </div>

        <div class="inspector-col">
          <!-- The decision block is the only accented surface on the screen, so
               where to act is never in question. -->
          <section class="decide">
            <span class="eyebrow">Your decision</span>

            <div v-if="!rejecting" class="actions">
              <button
                type="button"
                class="btn btn-primary btn-block"
                :disabled="submitting || !prediction"
                @click="confirmModel"
              >
                Confirm {{ prediction?.label ?? 'assessment' }}
                <span class="kbd">C</span>
              </button>

              <div class="correct">
                <span class="correct-label">Correct to</span>
                <button
                  type="button"
                  class="btn btn-secondary"
                  :disabled="submitting"
                  @click="correctTo('healthy')"
                >
                  Healthy <span class="kbd">H</span>
                </button>
                <button
                  type="button"
                  class="btn btn-secondary"
                  :disabled="submitting"
                  @click="correctTo('bleached')"
                >
                  Bleached <span class="kbd">B</span>
                </button>
              </div>

              <button
                type="button"
                class="btn btn-danger btn-block"
                :disabled="submitting"
                @click="rejecting = true"
              >
                Reject photograph <span class="kbd">R</span>
              </button>
            </div>

            <div v-else class="reject">
              <div class="field">
                <span class="field-label">Why is it unusable?</span>
                <SelectMenu
                  v-model="rejectReason"
                  :options="REJECT_REASONS"
                  ariaLabel="Rejection reason"
                  block
                />
              </div>
              <div class="reject-actions">
                <button
                  type="button"
                  class="btn btn-danger"
                  :disabled="submitting"
                  @click="decide('rejected', undefined, rejectReason as RejectReason)"
                >
                  Reject
                </button>
                <button type="button" class="btn btn-ghost" @click="rejecting = false">
                  Cancel <span class="kbd">Esc</span>
                </button>
              </div>
            </div>
          </section>

          <section v-if="queue.length > 1" class="section">
            <span class="eyebrow">Next in queue</span>
            <ul class="upnext">
              <li v-for="sighting in queue.slice(1, 5)" :key="sighting.id" class="row-hover-wide">
                <span class="readout upnext-date">
                  {{ new Date(sighting.capturedAt).toLocaleDateString() }}
                </span>
                <span v-if="sighting.confidence !== undefined" class="readout upnext-conf">
                  {{ Math.round(sighting.confidence * 100) }}% sure
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
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* The photograph's cap lives on ITS track, not on the plate. When the first
   track was 1fr and the plate capped its own width, the leftover width of the
   track became a dead gutter between the photograph and the inspector; sizing
   the track to the cap hands that width to the inspector instead. The ceiling
   on the whole row keeps the inspector's columns from being stretched into
   ribbons on very wide screens. */
.review {
  display: grid;
  grid-template-columns: minmax(0, min(66vh, 44rem)) minmax(20rem, 1fr);
  gap: clamp(1rem, 2vw, 1.75rem);
  align-items: start;
  max-width: 90rem;
}

/* One width for every photograph, whatever resolution it arrived at. See the
 * same rule in SightingDetailView for the reasoning; in short, a frame that
 * shrank to the source's pixel width made low-resolution crops too small to
 * judge, and judging them is this screen's entire purpose.
 *
 * The track above carries the size: 66vh keeps the caption and the
 * accept/correct row on screen with the square frame, and 44rem is the
 * upscale ceiling. */
.plate {
  display: grid;
  gap: 0.75rem;
}

.frame {
  position: relative;
  /* Square matches how the model tiles the image, so the lattice lines up with
     the pixels on screen. */
  aspect-ratio: 1;
  border-radius: var(--r-lg);
  overflow: hidden;
}

.frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.frame-empty {
  display: grid;
  place-items: center;
  height: 100%;
  color: var(--ink-4);
  font-size: var(--step--1);
}

figcaption {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem 1rem;
}

.scale {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: var(--step--1);
  color: var(--ink-3);
}

.swatch {
  width: 0.6875rem;
  height: 0.6875rem;
  border-radius: 3px;
}

.swatch-reef {
  background: var(--reef);
}

.swatch-bone {
  background: var(--bone);
  margin-left: 0.5rem;
}

.scale .faint {
  margin-left: 0.5rem;
  font-family: var(--font-mono);
  font-size: var(--step--2);
}

.source {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: var(--step--1);
  color: var(--ink-3);
}

.low-res {
  font-family: var(--font-mono);
  font-size: var(--step--2);
  border-bottom: 1px dotted var(--line-strong);
  cursor: help;
}

/* auto-fit, so the two stacks sit side by side once both can hold a MetricBar
   row (19rem) and fall into one column below that. The stacks are separate
   elements rather than grid rows because each column's cards must pack their
   own heights: placed in one shared grid, a tall assessment row would push the
   "next in queue" list down and leave a hole under the decision block. */
.inspector {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 19rem), 1fr));
  gap: 0.875rem;
  align-items: start;
  position: sticky;
  top: clamp(1.25rem, 2.4vw, 2rem);
}

.inspector-col {
  display: grid;
  gap: 0.875rem;
}

.verdict {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.severity {
  font-size: var(--step-4);
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--bone);
}

.verdict-text {
  max-width: 20ch;
  color: var(--ink-3);
  font-size: var(--step--1);
}

.verdict-text strong {
  color: var(--ink);
}

.metrics {
  display: grid;
  gap: 0.375rem;
  padding: 0.625rem;
  border-radius: var(--r-md);
  background: var(--surface--1);
}

.decide {
  display: grid;
  gap: 0.75rem;
  padding: 1.125rem;
  border: 1px solid color-mix(in srgb, var(--reef) 26%, transparent);
  border-radius: var(--r-lg);
  background: var(--surface-1);
  box-shadow: var(--glow-accent), var(--sheen);
  backdrop-filter: blur(var(--blur));
  -webkit-backdrop-filter: blur(var(--blur));
}

.actions {
  display: grid;
  gap: 0.5rem;
}

.correct {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
}

.correct-label {
  margin-right: 0.125rem;
  font-size: var(--step--1);
  color: var(--ink-3);
}

.reject {
  display: grid;
  gap: 0.75rem;
}

.reject-actions {
  display: flex;
  gap: 0.375rem;
}

.upnext {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.25rem;
}

.upnext li {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.375rem 0.5rem;
  border-radius: var(--r-sm);
  background: var(--surface--1);
  color: var(--ink-3);
}

.upnext-date {
  font-size: var(--step--1);
}

.upnext-conf {
  margin-right: auto;
  font-size: var(--step--2);
  color: var(--ink-4);
}

@media (max-width: 66rem) {
  .review {
    grid-template-columns: 1fr;
  }

  /* The cap moves back onto the plate here: the single column is wider than
     the photograph should be, and the inspector below still wants all of it
     for its two stacks. */
  .plate {
    width: min(100%, 66vh, 44rem);
  }

  .inspector {
    position: static;
  }
}
</style>
