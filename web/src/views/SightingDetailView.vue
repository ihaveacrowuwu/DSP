<script setup lang="ts">
/**
 * Full sighting record with complete provenance: who submitted it, what the model
 * said, which model version said it, and every expert decision since.
 *
 * Nothing is ever overwritten in this system, so the review history is an audit
 * trail rather than a status field — it is presented as a timeline for exactly
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
  type Sighting,
  type Verification,
} from '@/lib/api'

const route = useRoute()

const sighting = ref<Sighting | null>(null)
const photos = ref<Photo[]>([])
const verifications = ref<Verification[]>([])
const imageUrls = ref<Record<string, string>>({})
const showLattice = ref(true)
const loading = ref(true)
const error = ref<string | null>(null)

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
            <div class="frame well" :style="{ '--natural-width': `${photo.width}px` }">
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

            <figcaption v-if="photo.prediction" class="caption card card-pad">
              <span class="readout severity">
                {{ Math.round(photo.prediction.severity * 100) }}%
              </span>
              <span class="caption-text">
                bleached extent · reads
                <strong>{{ photo.prediction.label }}</strong>
                · model
                <span class="readout">{{ photo.prediction.modelVersion }}</span>
                · <span class="readout">{{ photo.width }}×{{ photo.height }}</span>
              </span>
              <div class="caption-metrics">
                <MetricBar label="Extent" :value="photo.prediction.severity" tone="bone" />
                <MetricBar label="Confidence" :value="photo.prediction.confidence" tone="reef" />
              </div>
            </figcaption>
            <figcaption v-else class="muted small">Awaiting model analysis.</figcaption>
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
}

.photos {
  display: grid;
  gap: 0.75rem;
}

/* Capped against the viewport height rather than the column width: a 4:3 frame
   allowed to fill a wide column pushes its own caption off screen, and the
   caption carries the model's numbers. */
.plate {
  display: grid;
  gap: 0.625rem;
  margin-bottom: 1rem;
  width: min(100%, 80vh);
}

.frame {
  position: relative;
  /* Square, because the model tiles the centre square of the image. Any other
     ratio here would crop to a different region than the one the patch grid
     describes, and the overlay would annotate pixels that are not on screen. */
  aspect-ratio: 1;
  /* Never blow a photograph up much past its own resolution: upscaling a 224 px
     dataset crop to fill a wide column adds no detail and looks broken. */
  max-width: min(100%, max(var(--natural-width, 100%), 22rem));
  margin-inline: auto;
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

.caption {
  display: grid;
  gap: 0.625rem;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
}

.severity {
  font-size: var(--step-2);
  font-weight: 600;
  line-height: 1;
  color: var(--bone);
}

.caption-text {
  color: var(--ink-3);
  font-size: var(--step--1);
}

.caption-text strong {
  color: var(--ink);
}

.caption-metrics {
  grid-column: 1 / -1;
  display: grid;
  gap: 0.375rem;
  padding-top: 0.625rem;
  border-top: 1px solid var(--line);
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
</style>
