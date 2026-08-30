/**
 * Fixtures for the view tests.
 *
 * Every builder returns a complete, valid record and takes an override object, so
 * a test states only the field it is about. A test that reads
 * `sighting({ confidence: 0.31 })` says what it depends on; one that assembles
 * twenty fields inline hides its own subject.
 *
 * The shapes here are the API's, not the view's. If `lib/api.ts` changes, these
 * stop type-checking under `vue-tsc --noEmit`, which is the point - a view test
 * passing against a shape the server no longer sends is worse than no test.
 */
import type {
  Condition,
  MapPoint,
  Patch,
  Photo,
  Prediction,
  Sighting,
  SightingStatus,
  TrendBucket,
  Verification,
} from '@/lib/api'

/** A full lattice, so `patches.length` is the real 25 and not a stand-in. */
export function patches(bleachedCells = 0, grid = 5): Patch[] {
  const cells: Patch[] = []
  for (let row = 0; row < grid; row += 1) {
    for (let col = 0; col < grid; col += 1) {
      const index = row * grid + col
      cells.push({
        row,
        col,
        label: index < bleachedCells ? 'bleached' : 'healthy',
        confidence: 0.8,
      })
    }
  }
  return cells
}

export function prediction(overrides: Partial<Prediction> = {}): Prediction {
  return {
    id: 'pred-1',
    photoId: 'photo-1',
    modelVersion: 'effnetb0-0.1.0',
    label: 'bleached',
    confidence: 0.42,
    severity: 0.36,
    patchGrid: 5,
    patches: patches(9),
    inferenceMs: 406,
    createdAt: '2026-08-20T09:00:00Z',
    ...overrides,
  }
}

export function photo(overrides: Partial<Photo> = {}): Photo {
  return {
    id: 'photo-1',
    sightingId: 'sighting-1',
    url: '/v1/photos/photo-1/image',
    width: 1920,
    height: 1440,
    bytes: 820_000,
    createdAt: '2026-08-20T08:59:00Z',
    prediction: prediction(),
    ...overrides,
  }
}

export function sighting(overrides: Partial<Sighting> = {}): Sighting {
  return {
    id: 'sighting-1',
    contributorId: 'user-1',
    contributorName: 'Aishath Reef',
    siteName: 'Maaya Thila',
    location: { lat: 4.0521, lon: 72.9482 },
    locationSource: 'gps',
    locationAccuracyM: 8,
    depthM: 12.5,
    capturedAt: '2026-08-20T08:30:00Z',
    note: 'Visibility poor on the north side.',
    status: 'awaiting_verification' as SightingStatus,
    createdAt: '2026-08-20T08:58:00Z',
    photoCount: 1,
    condition: 'bleached' as Condition,
    severity: 0.36,
    confidence: 0.42,
    verified: false,
    ...overrides,
  }
}

export function verification(overrides: Partial<Verification> = {}): Verification {
  return {
    id: 'verif-1',
    sightingId: 'sighting-1',
    verifierId: 'user-2',
    verifierName: 'Dr Hassan',
    decision: 'corrected',
    label: 'healthy',
    createdAt: '2026-08-21T10:00:00Z',
    ...overrides,
  } as Verification
}

export function mapPoint(overrides: Partial<MapPoint> = {}): MapPoint {
  return {
    id: 'sighting-1',
    lat: 4.0521,
    lon: 72.9482,
    ...overrides,
  } as MapPoint
}

export function trendBucket(overrides: Partial<TrendBucket> = {}): TrendBucket {
  return {
    bucket: '2026-08-01',
    total: 40,
    ...overrides,
  } as TrendBucket
}
