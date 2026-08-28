/**
 * Typed client for the Muraka API.
 *
 * Access tokens are short-lived, so every request transparently retries once
 * after a refresh. Types here mirror backend/internal/domain - keep them in step
 * with docs/openapi.yaml.
 */

export type Role = 'contributor' | 'researcher' | 'admin'
export type Condition = 'healthy' | 'bleached'
export type Decision = 'confirmed' | 'corrected' | 'rejected'
export type RejectReason = 'blurry' | 'not_coral' | 'duplicate' | 'spam' | 'other'
export type SightingStatus =
  | 'pending_photos'
  | 'processing'
  | 'awaiting_verification'
  | 'verified'
  | 'rejected'

export interface User {
  id: string
  email: string
  displayName: string
  role: Role
  status: string
  createdAt: string
}

export interface ContributorStats {
  total: number
  verified: number
  pending: number
  rejected: number
}

export interface Patch {
  row: number
  col: number
  label: Condition
  confidence: number
}

export interface Prediction {
  id: string
  photoId: string
  modelVersion: string
  label: Condition
  confidence: number
  severity: number
  patchGrid: number
  patches: Patch[]
  inferenceMs?: number
  createdAt: string
}

export interface Photo {
  id: string
  sightingId: string
  url: string
  width: number
  height: number
  bytes: number
  createdAt: string
  prediction?: Prediction
}

export interface Sighting {
  id: string
  contributorId: string
  contributorName?: string
  siteId?: string
  siteName?: string
  location: { lat: number; lon: number }
  locationSource: 'gps' | 'manual_pin'
  locationAccuracyM?: number
  depthM?: number
  capturedAt: string
  note?: string
  selfAssessedCondition?: Condition
  status: SightingStatus
  createdAt: string
  photoCount: number
  condition?: Condition
  severity?: number
  confidence?: number
  verified: boolean
}

export interface Verification {
  id: string
  sightingId: string
  verifierId: string
  verifierName?: string
  decision: Decision
  label?: Condition
  rejectReason?: RejectReason
  comment?: string
  createdAt: string
}

export interface MapPoint {
  lat: number
  lon: number
  count: number
  avgSeverity?: number
  sightingId?: string
}

export interface TrendBucket {
  bucket: string
  total: number
  bleached: number
  healthy: number
  avgSeverity?: number
}

export interface Atoll {
  id: string
  name: string
  code: string
  centroid: { lat: number; lon: number }
}

export interface ReefSite {
  id: string
  atollId?: string
  name: string
}

export interface ModelVersion {
  id: string
  version: string
  task: string
  isActive: boolean
  metrics: Record<string, unknown>
  datasetHash?: string
  notes?: string
  trainedAt?: string
  createdAt: string
}

export interface QueueDepth {
  queued: number
  running: number
  failed: number
  done: number
}

export interface Session {
  accessToken: string
  refreshToken: string
  expiresAt: string
  user: User
}

export interface Page<T> {
  items: T[]
  total: number
  limit: number
  offset: number
}

/** Structured API failure, so views can show field-level messages. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields?: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

const ACCESS_KEY = 'muraka.accessToken'
const REFRESH_KEY = 'muraka.refreshToken'

export const tokens = {
  get access() {
    return localStorage.getItem(ACCESS_KEY)
  },
  get refresh() {
    return localStorage.getItem(REFRESH_KEY)
  },
  set(session: Session) {
    localStorage.setItem(ACCESS_KEY, session.accessToken)
    localStorage.setItem(REFRESH_KEY, session.refreshToken)
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

/** Set by the auth store so a failed refresh can bounce the user to sign-in. */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

interface RequestOptions {
  method?: string
  body?: unknown
  query?: Record<string, string | number | boolean | undefined | null>
  auth?: boolean
  raw?: boolean
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = `${BASE}${path}`
  if (!query) return url

  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

async function toApiError(response: Response): Promise<ApiError> {
  let code = 'request_failed'
  let message = response.statusText || 'Request failed'
  let fields: Record<string, string> | undefined

  try {
    const body = await response.json()
    code = body.error ?? code
    message = body.message ?? message
    fields = body.fields
  } catch {
    // Non-JSON error body (e.g. a proxy failure); keep the status text.
  }
  return new ApiError(response.status, code, message, fields)
}

async function send(path: string, options: RequestOptions = {}): Promise<Response> {
  const { method = 'GET', body, query, auth = true } = options

  const headers: Record<string, string> = {}
  if (body !== undefined && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  if (auth && tokens.access) {
    headers.Authorization = `Bearer ${tokens.access}`
  }

  return fetch(buildUrl(path, query), {
    method,
    headers,
    body: body instanceof FormData ? body : body !== undefined ? JSON.stringify(body) : undefined,
  })
}

/** Refresh in flight, shared so concurrent 401s trigger only one refresh. */
let refreshInFlight: Promise<boolean> | null = null

async function refreshSession(): Promise<boolean> {
  const refreshToken = tokens.refresh
  if (!refreshToken) return false

  refreshInFlight ??= (async () => {
    try {
      const response = await send('/v1/auth/refresh', {
        method: 'POST',
        body: { refreshToken },
        auth: false,
      })
      if (!response.ok) return false
      tokens.set((await response.json()) as Session)
      return true
    } catch {
      return false
    } finally {
      refreshInFlight = null
    }
  })()

  return refreshInFlight
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await send(path, options)

  if (response.status === 401 && options.auth !== false) {
    if (await refreshSession()) {
      response = await send(path, options)
    } else {
      tokens.clear()
      onUnauthorized?.()
      throw await toApiError(response)
    }
  }

  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T
  if (options.raw) return response as unknown as T

  return (await response.json()) as T
}

export interface SightingFilters {
  bbox?: string
  from?: string
  to?: string
  status?: string
  condition?: Condition | ''
  verified?: boolean
  site?: string
  contributor?: string
  limit?: number
  offset?: number
}

export const api = {
  // --- auth
  login: (email: string, password: string) =>
    request<Session>('/v1/auth/login', { method: 'POST', body: { email, password }, auth: false }),

  register: (email: string, password: string, displayName: string) =>
    request<Session>('/v1/auth/register', {
      method: 'POST',
      body: { email, password, displayName },
      auth: false,
    }),

  logout: () =>
    request<void>('/v1/auth/logout', {
      method: 'POST',
      body: { refreshToken: tokens.refresh ?? '' },
      auth: false,
    }),

  me: () => request<{ user: User; stats: ContributorStats }>('/v1/me'),

  // --- sightings
  listSightings: (filters: SightingFilters = {}) =>
    request<Page<Sighting>>('/v1/sightings', { query: filters as RequestOptions['query'] }),

  getSighting: (id: string) =>
    request<{ sighting: Sighting; photos: Photo[]; verifications: Verification[] }>(
      `/v1/sightings/${id}`,
    ),

  // --- verification
  verificationQueue: (limit = 25, offset = 0) =>
    request<Page<Sighting>>('/v1/verifications/queue', { query: { limit, offset } }),

  verify: (
    sightingId: string,
    payload: {
      decision: Decision
      label?: Condition
      rejectReason?: RejectReason
      comment?: string
    },
  ) =>
    request<Verification>(`/v1/sightings/${sightingId}/verification`, {
      method: 'POST',
      body: payload,
    }),

  // --- spatial & temporal
  mapPoints: (filters: SightingFilters & { zoom?: number } = {}) =>
    request<{ points: MapPoint[]; zoom: number; clustered: boolean }>('/v1/map/points', {
      query: filters as RequestOptions['query'],
    }),

  trends: (filters: SightingFilters & { bucket?: 'day' | 'week' | 'month' } = {}) =>
    request<{ buckets: TrendBucket[] }>('/v1/trends', {
      query: filters as RequestOptions['query'],
    }),

  exportCsvUrl: (filters: SightingFilters = {}) =>
    buildUrl('/v1/export/sightings.csv', filters as RequestOptions['query']),

  // --- reference
  atolls: () => request<{ items: Atoll[] }>('/v1/atolls', { auth: false }),
  sites: () => request<{ items: ReefSite[] }>('/v1/sites'),

  // --- admin
  users: () => request<{ items: User[] }>('/v1/admin/users'),
  setUserRole: (id: string, role: Role) =>
    request<void>(`/v1/admin/users/${id}/role`, { method: 'PUT', body: { role } }),
  setUserStatus: (id: string, status: 'active' | 'banned') =>
    request<void>(`/v1/admin/users/${id}/status`, { method: 'PUT', body: { status } }),
  models: () => request<{ items: ModelVersion[] }>('/v1/admin/models'),
  activateModel: (version: string) =>
    request<void>(`/v1/admin/models/${encodeURIComponent(version)}/activate`, { method: 'POST' }),
  queueDepth: () => request<QueueDepth>('/v1/admin/queue'),
}

/** Authenticated image URLs need the token, so fetch as a blob for <img src>. */
export async function fetchPhotoObjectUrl(photoId: string): Promise<string> {
  const response = await send(`/v1/photos/${photoId}/image`)
  if (!response.ok) throw await toApiError(response)
  return URL.createObjectURL(await response.blob())
}
