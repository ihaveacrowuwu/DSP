# Offline sync protocol

The contributor works underwater and on boats. The app must therefore treat the
network as an occasional luxury, and the server must tolerate a client that
retries the same submission any number of times. This document specifies how.

It is the same protocol on both platforms. Implement it once per platform, from
this document, rather than by reading the other app's code.

## The rule that makes everything else simple

**The client generates the id.**

Every sighting and every photo gets a UUIDv7 created on the device at capture
time. That id is the idempotency key. The server upserts on it, so:

- a retry after a timeout creates nothing new
- a retry after the app was killed mid-request creates nothing new
- the client never needs to ask "did that one get through?" — it can just send
  again

UUIDv7 rather than v4 because it is time-ordered, which keeps database index
locality good and makes the queue naturally chronological.

| Platform | How |
|---|---|
| Android | `java.util.UUID` has no v7; use a small helper or `com.fasterxml.uuid` |
| iOS | `UUID()` is v4; write a v7 helper (48-bit big-endian millisecond timestamp, version nibble 7, random remainder) |

A v4 id still works — the server only requires a UUID — but prefer v7.

## Local schema

Two tables mirroring the server's shape, plus queue bookkeeping.

```
sighting_queue
  id                TEXT PRIMARY KEY   -- client UUIDv7, sent as-is
  lat, lon          REAL NOT NULL
  location_source   TEXT NOT NULL      -- 'gps' | 'manual_pin'
  location_accuracy REAL
  depth_m           REAL
  captured_at       TEXT NOT NULL      -- RFC 3339, device clock
  note              TEXT
  self_condition    TEXT               -- 'healthy' | 'bleached' | null
  metadata_synced   INTEGER NOT NULL DEFAULT 0
  attempts          INTEGER NOT NULL DEFAULT 0
  last_error        TEXT
  created_at        TEXT NOT NULL

photo_queue
  id                TEXT PRIMARY KEY   -- client UUIDv7, sent as photoId
  sighting_id       TEXT NOT NULL REFERENCES sighting_queue(id)
  local_path        TEXT NOT NULL      -- app-private storage, not the shared gallery
  uploaded          INTEGER NOT NULL DEFAULT 0
  attempts          INTEGER NOT NULL DEFAULT 0
  last_error        TEXT
```

Copy the picked image into app-private storage at capture time. A gallery URI can
be revoked or the file deleted before the upload runs.

Keep rows after a successful sync (flip the flags) rather than deleting them: the
"My sightings" screen then works offline, and a replay is always possible.

## Submitting

Two steps, in order. Metadata first, because a photo cannot be attached to a
sighting the server has not seen.

### Step 1 — metadata

```
POST /v1/sightings
Authorization: Bearer <access token>
Content-Type: application/json

{
  "id": "018f3c2a-...",            // the client UUID
  "lat": 4.1755,
  "lon": 73.5093,
  "locationSource": "gps",
  "locationAccuracyM": 8.0,
  "depthM": 7.5,
  "capturedAt": "2026-08-18T09:14:22Z",
  "note": "North side, patchy",
  "selfAssessedCondition": "bleached"
}
```

| Response | Meaning | Client action |
|---|---|---|
| `201` | Created | Set `metadata_synced = 1` |
| `200` | Already existed — this was a replay | Set `metadata_synced = 1`. Treat exactly like 201 |
| `422` | A field is invalid; `fields` says which | **Do not retry.** Mark the item failed and show the reason |
| `409` | The id belongs to another account | Do not retry. Regenerate the id or discard |
| `401` | Access token expired | Refresh once, then retry (see below) |
| `5xx`, timeout, offline | Unknown outcome | Retry with backoff. Safe because of the id |

Treating `200` and `201` identically is the whole point. The client never has to
know whether it had already succeeded.

### Step 2 — each photo

```
POST /v1/sightings/{sightingId}/photos
Authorization: Bearer <access token>
Content-Type: multipart/form-data

photoId: 018f3c2b-...
file:    <JPEG or PNG bytes>
```

Same status handling. `413` means the file is too large — do not retry; downscale
locally and upload as a new photo id, or fail the item with a clear message.

Downscale before uploading anyway. The server analyses at 224 px per grid cell,
so a 5×5 grid gains nothing above roughly 1600 px on the long edge. Aim for
1600 px, JPEG quality ~85: that is well under the 12 MiB cap and far kinder to a
resort Wi-Fi connection.

The server strips EXIF when it re-encodes, so anything you want kept must be sent
as a field, not left in the file's metadata.

## Ordering and concurrency

- Upload in `created_at` order so the researcher's queue reflects capture order.
- One sighting at a time, its photos sequentially. Parallel uploads on a weak
  connection make every request slower and the failure modes harder to reason
  about.
- Guard the drain loop so two triggers (connectivity change and a periodic task
  firing together) cannot run it twice. WorkManager unique work on Android; a
  serial queue plus a flag on iOS.

## Retry policy

Exponential backoff with jitter, capped: `min(2^attempts, 300) seconds ± 20%`.

Give up after 8 attempts, mark the item failed, and surface it in the sync list
with a **Retry** action. Never retry silently forever — a contributor deserves to
know something is stuck.

Retry on: timeouts, connection failures, `5xx`, `429`.
Do not retry on: `400`, `401` (refresh first), `403`, `409`, `413`, `422`.

## Token handling

Access tokens last 15 minutes; refresh tokens are **single-use**.

```
On 401:
  refresh once  → POST /v1/auth/refresh { refreshToken }
    200 → store BOTH new tokens, retry the original request once
    401 → the session is over: clear tokens, keep the queue,
           show sign-in. The queue drains after the user signs back in.
```

Two rules that are easy to get wrong:

1. **Persist the new refresh token immediately.** The old one is dead the moment
   the server answers; losing the new one logs the user out.
2. **Serialise refreshes.** If several queued requests 401 at once, only one
   refresh may run; the others wait for its result. Two concurrent refreshes mean
   one of them presents an already-used token and kills the session.

Store tokens in the Android Keystore-backed `EncryptedSharedPreferences` and in
the iOS Keychain. Never in plain preferences or `UserDefaults`.

## When sync runs

Trigger a drain on: app foreground, connectivity becoming available, a completed
capture, a periodic background task, and a manual pull-to-refresh.

| Platform | Mechanism |
|---|---|
| Android | `WorkManager` unique periodic work + a `CONNECTED` constraint; expedited one-shot after a capture |
| iOS | `URLSession` background configuration for the uploads themselves, plus `BGProcessingTaskRequest` to start a drain |

On iOS the upload must be a background `URLSessionUploadTask` with a file body,
not an in-memory `dataTask`, or a suspended app loses the transfer.

## Reading state back

After sync, refresh the local copy from `GET /v1/sightings?limit=50` and, for a
detail screen, `GET /v1/sightings/{id}`.

Server status maps to what the contributor sees:

| Server status | Show as |
|---|---|
| (not yet synced) | Waiting to upload |
| `pending_photos`, `processing` | Uploaded, being analysed |
| `awaiting_verification` | Analysed — awaiting expert review |
| `verified` | Reviewed by an expert |
| `rejected` | Not usable (with the reason) |

Show the model's assessment as soon as it exists, clearly marked as automatic,
and replace it with the expert verdict when one arrives. Never let the two look
alike.

## Test scenarios

These are the cases that break naive implementations. All of them are worth
automating, and they are the evidence for requirement FR3/NFR7.

1. Capture three sightings in airplane mode, enable Wi-Fi → exactly three appear
   server-side, in capture order.
2. Force-quit the app with items queued, relaunch → the queue is intact.
3. Kill the app during a photo upload, relaunch → one photo server-side, not two.
4. Submit, then submit the same row again by hand → server returns `200`, and the
   record count is unchanged.
5. Let the access token expire (or shorten `ACCESS_TOKEN_TTL` to 1 minute on the
   server), then sync → one silent refresh, sync succeeds.
6. Corrupt the stored refresh token, then sync → user is returned to sign-in and
   the queue survives.
7. Upload a 20 MB image → `413`, item fails with a readable message, no retry
   loop.
8. Aeroplane mode throughout a whole session → the app is fully usable except
   sign-in, and nothing is lost.
