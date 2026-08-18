<script setup lang="ts">
/**
 * Full sighting record with complete provenance: who submitted it, what the model
 * said, which model version said it, and every expert decision since. Nothing is
 * overwritten, so the history reads as an audit trail.
 */
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import ConditionChip from '@/components/ConditionChip.vue'
import PatchLattice from '@/components/PatchLattice.vue'
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

const decisionWording: Record<Verification['decision'], string> = {
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
  <div v-if="loading" class="state">Loading record…</div>
  <div v-else-if="error" class="state">
    <p class="error" role="alert">{{ error }}</p>
    <RouterLink :to="{ name: 'sightings' }">Back to sightings</RouterLink>
  </div>

  <template v-else-if="sighting">
    <header class="head">
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
            v-if="photos.some((p) => p.prediction?.patches?.length)"
            type="button"
            class="btn btn-ghost"
            @click="showLattice = !showLattice"
          >
            {{ showLattice ? 'Hide model grid' : 'Show model grid' }}
          </button>
        </div>

        <p v-if="!photos.length" class="muted">No photographs were attached to this sighting.</p>

        <figure v-for="photo in photos" :key="photo.id" class="plate">
          <div class="frame">
            <img
              v-if="imageUrls[photo.id]"
              :src="imageUrls[photo.id]"
              :alt="`Reef photograph ${photo.id}`"
            />
            <div v-else class="frame-empty">Image unavailable</div>

            <PatchLattice
              v-if="showLattice && photo.prediction?.patches?.length"
              :patches="photo.prediction.patches"
              :grid="photo.prediction.patchGrid"
              variant="overlay"
            />
          </div>

          <figcaption v-if="photo.prediction">
            <span class="readout severity">
              {{ Math.round(photo.prediction.severity * 100) }}%
            </span>
            <span class="caption-text">
              bleached extent · {{ photo.prediction.label }} at
              {{ Math.round(photo.prediction.confidence * 100) }}% confidence ·
              model {{ photo.prediction.modelVersion }}
            </span>
          </figcaption>
          <figcaption v-else class="muted">Awaiting model analysis.</figcaption>
        </figure>
      </section>

      <aside class="side">
        <section class="panel block">
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
          <p v-if="sighting.note" class="note">“{{ sighting.note }}”</p>
        </section>

        <section class="panel block">
          <span class="eyebrow">Review history</span>
          <p v-if="!verifications.length" class="muted">
            No expert has reviewed this sighting yet. Until then its condition is model output only.
          </p>
          <ol v-else class="history">
            <li v-for="entry in verifications" :key="entry.id">
              <span class="readout when">{{ new Date(entry.createdAt).toLocaleString() }}</span>
              <p class="what">
                <strong>{{ entry.verifierName }}</strong>
                {{ decisionWording[entry.decision] }}
                <template v-if="entry.label"> as {{ entry.label }}</template>
                <template v-if="entry.rejectReason"> ({{ entry.rejectReason.replace('_', ' ') }})</template>
              </p>
              <p v-if="entry.comment" class="comment">{{ entry.comment }}</p>
            </li>
          </ol>
        </section>
      </aside>
    </div>
  </template>
</template>

<style scoped>
.state {
  padding: 4rem clamp(1.25rem, 3vw, 2rem);
  color: var(--ink-muted);
  display: grid;
  gap: 1rem;
  justify-items: start;
}

.error {
  color: var(--rust);
}

.head {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: flex-start;
  justify-content: space-between;
  padding: 1.5rem clamp(1.25rem, 3vw, 2rem) 1.25rem;
  border-bottom: 1px solid var(--hairline);
}

.head h1 {
  margin-top: 0.375rem;
  font-size: var(--step-2);
}

.position {
  margin-top: 0.25rem;
  color: var(--ink-muted);
  font-size: var(--step--1);
}

.body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(19rem, 24rem);
  gap: clamp(1rem, 2.5vw, 2rem);
  padding: clamp(1.25rem, 3vw, 2rem);
  align-items: start;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.75rem;
}

.plate {
  margin: 0 0 1.5rem;
  display: grid;
  gap: 0.5rem;
}

.frame {
  position: relative;
  aspect-ratio: 4 / 3;
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
  align-items: baseline;
  gap: 0.5rem;
  flex-wrap: wrap;
  font-size: var(--step--1);
  color: var(--ink-muted);
}

.severity {
  font-size: var(--step-2);
  font-weight: 600;
  color: var(--bone);
  line-height: 1;
}

.caption-text {
  max-width: 52ch;
}

.side {
  display: grid;
  gap: 0.875rem;
}

.block {
  padding: 1rem;
  display: grid;
  gap: 0.75rem;
}

.meta {
  margin: 0;
  display: grid;
  gap: 0.375rem;
}

.meta > div {
  display: grid;
  grid-template-columns: 7rem 1fr;
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

.muted {
  color: var(--ink-faint);
  font-size: var(--step--1);
}

.history {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.75rem;
}

.history li {
  padding-left: 0.75rem;
  border-left: 2px solid var(--verified);
}

.when {
  font-size: 0.6875rem;
  color: var(--ink-faint);
}

.what {
  font-size: var(--step--1);
}

.comment {
  margin-top: 0.25rem;
  color: var(--ink-muted);
  font-size: var(--step--1);
  font-style: italic;
}

@media (max-width: 66rem) {
  .body {
    grid-template-columns: 1fr;
  }
}
</style>
