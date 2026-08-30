<script setup lang="ts">
/**
 * Full sighting record with complete provenance: who submitted it, what the model
 * said, which model version said it, and every expert decision since.
 *
 * Nothing is ever overwritten in this system, so the review history is an audit
 * trail rather than a status field - it is presented as a timeline for exactly
 * that reason. A record whose condition was corrected still shows what the model
 * originally claimed, because that disagreement is the interesting part.
 */
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import ConditionChip from '@/components/ConditionChip.vue'
import PatchLattice from '@/components/PatchLattice.vue'
import Icon from '@/components/ui/Icon.vue'
import MetricBar from '@/components/ui/MetricBar.vue'
import { iconBack } from '@/lib/icons'
import {
  ApiError,
  api,
  fetchPhotoObjectUrl,
  type Photo,
  type Prediction,
  type Sighting,
  type Verification,
} from '@/lib/api'
import { dimensions, isLowResolution, lowResolutionNote } from '@/lib/photos'

const route = useRoute()

const sighting = ref<Sighting | null>(null)
const photos = ref<Photo[]>([])
const verifications = ref<Verification[]>([])
const imageUrls = ref<Record<string, string>>({})
const showLattice = ref(true)
const loading = ref(true)
const error = ref<string | null>(null)

/**
 * The lattice as one number: how much of the grid the model called bleached.
 *
 * Worth showing next to the extent score because they can disagree. A photograph
 * can read 40% bleached extent off two badly bleached cells out of twenty-five,
 * and the tally is what makes that visible without counting squares by eye.
 */
function bleachedPatches(prediction: Prediction): number {
  return prediction.patches.filter((patch) => patch.label === 'bleached').length
}

/** The interface's own voice, not the database's enum values. */
const DECISION_WORDING: Record<Verification['decision'], string> = {
  confirmed: 'confirmed the model',
  corrected: 'corrected the model',
  rejected: 'rejected the photograph',
}

onMounted(async () => {
  try {
    const detail = await api.getSighting(String(route.params.id))
    sighting.value = detail.sighting
    photos.value = detail.photos
    verifications.value = detail.verifications

    for (const photo of detail.photos) {
      try {
        imageUrls.value[photo.id] = await fetchPhotoObjectUrl(photo.id)
      } catch {
        // One unreadable image must not blank the whole record.
      }
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : 'Could not load this sighting.'
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  for (const url of Object.values(imageUrls.value)) URL.revokeObjectURL(url)
})
</script>

<template>
  <div class="page">
    <div v-if="loading" class="state">Loading record…</div>

    <div v-else-if="error" class="state">
      <p class="notice" role="alert">{{ error }}</p>
      <RouterLink class="btn btn-secondary" :to="{ name: 'sightings' }">
        Back to sightings
      </RouterLink>
    </div>

    <template v-else-if="sighting">
      <RouterLink class="back row-hover" :to="{ name: 'sightings' }">
        <Icon :path="iconBack" :size="1" />
        All sightings
      </RouterLink>

      <header class="page-head">
        <div>
          <span class="eyebrow">Sighting record</span>
          <h1>{{ new Date(sighting.capturedAt).toLocaleString() }}</h1>
          <p class="position readout">
            {{ sighting.location.lat.toFixed(5) }}, {{ sighting.location.lon.toFixed(5) }}
            <span v-if="sighting.siteName"> · {{ sighting.siteName }}</span>
          </p>
        </div>
        <ConditionChip
          :condition="sighting.condition"
          :status="sighting.status"
          :verified="sighting.verified"
          :severity="sighting.severity"
        />
      </header>

      <div class="body">
        <section class="photos">
          <div class="section-head">
            <span class="eyebrow">Photographs / {{ photos.length }}</span>
            <button
              v-if="photos.some((photo) => photo.prediction?.patches?.length)"
              type="button"
              class="btn btn-ghost"
              @click="showLattice = !showLattice"
            >
              {{ showLattice ? 'Hide model grid' : 'Show model grid' }}
            </button>
          </div>

          <p v-if="!photos.length" class="muted small">
            No photographs were attached to this sighting.
          </p>

          <figure v-for="photo in photos" :key="photo.id" class="plate">
            <div class="frame well">
              <img
                v-if="imageUrls[photo.id]"
                :src="imageUrls[photo.id]"
                :alt="`Reef photograph from this sighting`"
              />
              <div v-else class="frame-empty">Image unavailable</div>

              <PatchLattice
                v-if="showLattice && photo.prediction?.patches?.length"
                :patches="photo.prediction.patches"
                :grid="photo.prediction.patchGrid"
                variant="overlay"
              />
            </div>

            <figcaption class="assessment card card-pad">
              <template v-if="photo.prediction">
                <span class="eyebrow">Model assessment</span>

                <p class="verdict">
                  <span class="readout severity">
                    {{ Math.round(photo.prediction.severity * 100) }}%
                  </span>
                  <span class="verdict-text">
                    bleached extent - reads
                    <strong>{{ photo.prediction.label }}</strong>
                  </span>
                </p>

                <!-- Extent, confidence, then the lattice's own tally: what it
                     found, how sure it is, and how much of the grid agrees. -->
                <div class="metrics">
                  <MetricBar label="Extent" :value="photo.prediction.severity" tone="bone" />
                  <MetricBar label="Confidence" :value="photo.prediction.confidence" tone="reef" />
                  <MetricBar
                    v-if="photo.prediction.patches.length"
                    label="Patches"
                    :value="bleachedPatches(photo.prediction) / photo.prediction.patches.length"
                    tone="bone"
                    :display="`${bleachedPatches(photo.prediction)}/${photo.prediction.patches.length}`"
                  />
                </div>

                <dl class="meta">
                  <div>
                    <dt>Model</dt>
                    <dd class="readout">{{ photo.prediction.modelVersion }}</dd>
                  </div>
                  <div v-if="photo.prediction.patchGrid">
                    <dt>Grid</dt>
                    <dd class="readout">
                      {{ photo.prediction.patchGrid }}×{{ photo.prediction.patchGrid }}
                    </dd>
                  </div>
                  <div v-if="photo.prediction.inferenceMs !== undefined">
                    <dt>Inference</dt>
                    <dd class="readout">{{ photo.prediction.inferenceMs }} ms</dd>
                  </div>
                  <div>
                    <dt>Resolution</dt>
                    <dd class="readout">{{ dimensions(photo.width, photo.height) }}</dd>
                  </div>
                </dl>

                <!-- Printed in full rather than hidden behind a hover: the panel
                     has the room, and whether a photograph is good enough to
                     judge is part of the judgement. -->
                <p v-if="isLowResolution(photo.width, photo.height)" class="low-res">
                  {{ lowResolutionNote(photo.width, photo.height) }}
                </p>
              </template>

              <template v-else>
                <span class="eyebrow">Model assessment</span>
                <p class="muted small">
                  Awaiting model analysis. The photograph is stored; the grader has not
                  reached it yet.
                </p>
              </template>
            </figcaption>
          </figure>
        </section>

        <aside class="side">
          <section class="section">
            <span class="eyebrow">Capture</span>
            <dl class="meta">
              <div>
                <dt>Contributor</dt>
                <dd>{{ sighting.contributorName }}</dd>
              </div>
              <div>
                <dt>Captured</dt>
                <dd class="readout">{{ new Date(sighting.capturedAt).toLocaleString() }}</dd>
              </div>
              <div>
                <dt>Received</dt>
                <dd class="readout">{{ new Date(sighting.createdAt).toLocaleString() }}</dd>
              </div>
              <div>
                <dt>Fix</dt>
                <dd>{{ sighting.locationSource === 'gps' ? 'GPS' : 'Dropped pin' }}</dd>
              </div>
              <div v-if="sighting.locationAccuracyM !== undefined">
                <dt>Accuracy</dt>
                <dd class="readout">±{{ Math.round(sighting.locationAccuracyM) }} m</dd>
              </div>
              <div v-if="sighting.depthM !== undefined">
                <dt>Depth</dt>
                <dd class="readout">{{ sighting.depthM.toFixed(1) }} m</dd>
              </div>
              <div v-if="sighting.selfAssessedCondition">
                <dt>Diver's call</dt>
                <dd>{{ sighting.selfAssessedCondition }}</dd>
              </div>
            </dl>
            <p v-if="sighting.note" class="quote">“{{ sighting.note }}”</p>
          </section>

          <section class="section">
            <span class="eyebrow">Review history</span>
            <p v-if="!verifications.length" class="muted small">
              No expert has reviewed this sighting yet. Until then its condition is model
              output only.
            </p>
            <ol v-else class="history">
              <li v-for="entry in verifications" :key="entry.id">
                <span class="when readout">{{ new Date(entry.createdAt).toLocaleString() }}</span>
                <p class="what">
                  <strong>{{ entry.verifierName }}</strong>
                  {{ DECISION_WORDING[entry.decision] }}
                  <template v-if="entry.label"> as {{ entry.label }}</template>
                  <template v-if="entry.rejectReason">
                    ({{ entry.rejectReason.replace('_', ' ') }})
                  </template>
                </p>
                <p v-if="entry.comment" class="quote">{{ entry.comment }}</p>
              </li>
            </ol>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
  margin-bottom: 0.75rem;
  color: var(--ink-3);
  font-size: var(--step--1);
  font-weight: 600;
  text-decoration: none;
  /* The scale grows from the left so the link does not drift away from the page
     edge it is aligned to. */
  transform-origin: left center;
}

.page-head {
  align-items: flex-start;
}

.position {
  margin-top: 0.25rem;
  color: var(--ink-3);
  font-size: var(--step--1);
}

.body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(18rem, 23rem);
  gap: clamp(1rem, 2vw, 1.75rem);
  align-items: start;
  /* Roughly frame + panel + side rail at their caps. Past this the spare width
     would go into the assessment panel, stretching its rows into ribbons. */
  max-width: 84rem;
}

.photos {
  display: grid;
  gap: 0.75rem;
}

/* The photograph and the model's reading of it, side by side.
 *
 * The panel keeps its place at every width the two-column body survives, and the
 * frame gives up size to make room. This was flex-wrap first, which let the panel
 * drop below the photograph when it no longer fit - and at 1366px, the commonest
 * laptop width, it did: the numbers landed under a 700px-tall frame, off screen,
 * which is the layout this change exists to remove.
 *
 * So the tracks are explicit. Track one is capped, never flexible, so the frame
 * cannot grow past its ceiling; track two has a floor of 17rem, which is what a
 * MetricBar needs before its label starts eliding, and a cap of its own so the
 * panel's rows are never stretched into ribbons. When the column cannot satisfy
 * both, grid honours the floor and the frame shrinks - still one size for every
 * photograph, which was always the point.
 *
 * `align-items: start` keeps the panel the height of its own content. A card
 * stretched to the frame's full height with five rows in it reads as something
 * unfinished. */
.plate {
  display: grid;
  grid-template-columns: minmax(0, min(70vh, 34rem)) minmax(17rem, 26rem);
  align-items: start;
  gap: 0.875rem;
  margin-bottom: 1.25rem;
}

/* One width for every photograph, whatever resolution it arrived at.
 *
 * This used to be capped against each image's own pixel width, on the principle
 * that upscaling a 224 px dataset crop adds no detail. True, but it made the frame
 * vary from 22rem to the full column depending on the source - so the same reef
 * could not be compared with itself, and the small ones were too small to judge,
 * which is the whole job on this page. The frame is furniture: fixed, with the
 * panel beside it saying when a photograph has less detail than it fills.
 *
 * The size itself comes from the grid track above: 70vh so a square frame is
 * never taller than the screen, and 34rem as the ceiling. This is a record
 * page, not the judging screen - the queue keeps its 44rem frame because
 * grading is done there - and a frame this size is what lets the assessment
 * panel and the capture rail stand beside the photograph at the same height
 * instead of leaving a column of dead space under each. */
.frame {
  position: relative;
  width: 100%;
  /* Square, because the model tiles the centre square of the image. Any other
     ratio here would crop to a different region than the one the patch grid
     describes, and the overlay would annotate pixels that are not on screen. */
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

/* The model's reading, in the space beside the photograph. This used to sit under
   the frame, which put the numbers below the fold on a laptop and left the column
   next to the image empty. 18rem is the point below which the rows stop being
   readable, so that is where it wraps instead of shrinking. */
.assessment {
  display: grid;
  gap: 0.75rem;
}

.verdict {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.severity {
  font-size: var(--step-3);
  font-weight: 600;
  line-height: 1;
  color: var(--bone);
}

.verdict-text {
  color: var(--ink-3);
  font-size: var(--step--1);
}

.verdict-text strong {
  color: var(--ink);
}

.metrics {
  display: grid;
  gap: 0.375rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--line);
}

.assessment .meta {
  padding-top: 0.75rem;
  border-top: 1px solid var(--line);
}

/* Stated plainly, not as a warning chip: it is a fact about the photograph's
   resolution, not a problem with the sighting. */
.low-res {
  color: var(--ink-4);
  font-size: var(--step--2);
}

.side {
  display: grid;
  gap: 0.875rem;
}

/* The timeline's left edge is drawn in the verification colour: this column is
   the record of human decisions, and it should not look like model output. */
.history {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.75rem;
}

.history li {
  padding-left: 0.75rem;
  border-left: 2px solid color-mix(in srgb, var(--verified) 60%, transparent);
}

.when {
  font-size: var(--step--2);
  color: var(--ink-4);
}

.what {
  font-size: var(--step--1);
  color: var(--ink-2);
}

.what strong {
  color: var(--ink);
}

@media (max-width: 66rem) {
  .body {
    grid-template-columns: 1fr;
  }
}

/* Narrow enough that a 17rem panel beside the frame would leave the photograph
   too small to read. Below here the panel goes under it, which is the one place
   that arrangement is the right answer. */
@media (max-width: 46rem) {
  .plate {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
