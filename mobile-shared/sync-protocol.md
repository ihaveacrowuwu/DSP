# Offline sync protocol

The contributor works underwater and on boats. The app must therefore treat the
network as an occasional luxury, and the server must tolerate a client that
retries the same submission any number of times. This document specifies how.

It is the same protocol on both platforms. Implement it once per platform, from
this document, rather than by reading the other app's code.

## Source of truth

**The outbox is authoritative only about what has NOT been delivered. The server is
authoritative about everything that has.**

The client may state two things on its own authority, because they are facts about
its own queue: *waiting to upload* and *uploading*. It may never assert anything
else. "Synced", "analysing", "awaiting review", "verified", "rejected" — every one of
those comes from the server or is not shown at all.

This exists because a local `synced = 1` flag is a *claim*, not a fact. Set it a
moment before the process dies, or after a response the client misread, and the app
shows a confident green tick for a sighting the server never received. The
contributor then believes their reef data is safe when it is not, which is the worst
failure this system can have — worse than an obvious error, because nobody goes
looking for it.

So the rules are:

1. **Never display a local flag as status.** Local flags decide what to send next;
   they never decide what the user is told.
2. **Confirm, do not assume.** After the last photo of a sighting uploads, read the
   sighting back from the server. That response is what the UI shows.
3. **Nothing local is a record.** No locally computed totals, no client-invented
   statuses, no edits to a record the server already holds. Counts come from
   `GET /v1/me`; statuses come from `GET /v1/sightings`.
4. **Retained rows are a display cache, never a source of truth.** Anything kept
   after acknowledgement is last-known-server-state, labelled as such, and refreshed
   whenever the app can reach the server.

The outbox itself stays, and it is not in tension with any of the above. It holds
bytes the server has not accepted yet, so that a dead process, a timeout or a boat
with no signal cannot destroy a sighting. Deleting it would not make uploads more
truthful — it would make a failed upload unrecoverable, which is how you lose data
rather than merely misreport it.

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
  user_id           TEXT NOT NULL      -- owning account; see "Outbox rows belong
                                       -- to an account". Never upload under another
  lat, lon          REAL NOT NULL
  location_source   TEXT NOT NULL      -- 'gps' | 'manual_pin'
  location_accuracy REAL
  depth_m           REAL
  captured_at       TEXT NOT NULL      -- RFC 3339, clamped to now if the clock is ahead
  note              TEXT
  self_condition    TEXT               -- 'healthy' | 'bleached' | null
  state             TEXT NOT NULL      -- 'queued' | 'sending' | 'in_doubt'
                                       -- | 'confirmed' | 'failed'
  attempts          INTEGER NOT NULL DEFAULT 0
  last_error        TEXT
  created_at        TEXT NOT NULL

photo_queue
  id                TEXT PRIMARY KEY   -- client UUIDv7, sent as photoId
  sighting_id       TEXT NOT NULL REFERENCES sighting_queue(id)
  local_path        TEXT NOT NULL      -- app-private storage, not the shared gallery
  state             TEXT NOT NULL      -- same vocabulary as above
  attempts          INTEGER NOT NULL DEFAULT 0
  last_error        TEXT
```

`state` is a string, not a `synced` boolean, because a boolean cannot express **"we
sent it and do not know what happened"** — and that is the state a lost response
actually leaves you in. Collapsing it to `0` re-sends work that already succeeded;
collapsing it to `1` tells the contributor their sighting is safe when nobody has
confirmed it. Both are the bug this protocol exists to prevent, so the third value
is not optional.

`state` decides what to send next. It is never what the user is shown — see
[Source of truth](#source-of-truth).

Copy the picked image into app-private storage at capture time. A gallery URI can
be revoked or the file deleted before the upload runs.

A row's job ends when the server acknowledges it. Keep it if you want a replay path
or an offline history, but from that moment it is a **display cache holding
last-known server state** — never the answer to "did this upload succeed?". Mark
cached records as such in the UI (a "last updated" line is enough) and refresh from
the server whenever the app can reach it.

Delete a row only after acknowledgement, never before, and never on a response the
client could not parse.

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
| `201` | Created | `state = confirmed` for the metadata; photos still to go |
| `200` | Already existed — this was a replay | Identical to `201`. The client never has to know which it was |
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

Uploading is not finishing. When the last photo of a sighting has been accepted,
**read the sighting back** — `GET /v1/sightings/{id}` — and show what comes back. The
list screen refreshes the same way from `GET /v1/sightings?limit=50`. Until that read
succeeds, the sighting's status is *unknown to the client*, and "unknown" is an
honest thing to display; "synced" is not.

Only two rows in this table may be stated without the server. Everything below the
line is the server's answer or nothing:

| Source | State | Show as |
|---|---|---|
| Outbox | in the queue, not sent | Waiting to upload |
| Outbox | request in flight | Uploading |
| — | accepted, not yet read back | Checking… |
| Server | `pending_photos` | Photos pending |
| Server | `processing` | Analysing |
| Server | `awaiting_verification` | Awaiting expert review |
| Server | `verified` | Verified by an expert |
| Server | `rejected` | Not usable (with the reason) |

If the server cannot be reached, show the last-known status **with its age** rather
than a fresh-looking one. A stale truth labelled stale is fine; a stale truth
presented as current is the bug this protocol exists to prevent.

Show the model's assessment as soon as it exists, clearly marked as automatic,
and replace it with the expert verdict when one arrives. Never let the two look
alike.

## Reconciliation — how the outbox and the database agree

Everything above assumes the client knows whether a request succeeded. Sometimes it
cannot: the server commits, then the response is lost to a dropped connection or the
process dies before it is handled. The write happened; the client has no idea. That
window is unavoidable, so the protocol closes it by **asking** rather than guessing.

### The primitive

`GET /v1/sightings/{id}` answers both questions at once, because the ids are the
client's own:

| Response | Meaning |
|---|---|
| `404` | The server has nothing under this id. Everything still needs sending |
| `200` | The sighting exists. `photos[]` carries the ids **the server actually holds** |

Photo rows are keyed on the client-generated `photoId` too, so comparing local photo
ids against `photos[].id` yields the exact set still missing — not an estimate. No
extra endpoint and no bookkeeping column can be more trustworthy than this, because
it is the database answering.

### The outbox row's life

```
queued ──▶ sending ──▶ in doubt ──▶ confirmed ──▶ (row dropped, record cached)
   ▲          │            │
   └──────────┴────────────┘   transient failure: back to queued, with backoff
              │
              └──▶ failed   terminal (422/409/413) — needs the user
```

**`in doubt` is a real state and must be modelled.** Any row whose request was sent
but whose outcome was not durably recorded lands here — that is exactly the state the
UI shows as "Checking…". A row may only leave for `confirmed` on the server's word.

### The algorithm

Run for every row that is not `confirmed`, on launch, on regaining connectivity, when
"My sightings" opens, and after any upload finishes:

1. `GET /v1/sightings/{id}`
2. **`404`** — send the metadata, then every photo.
3. **`200`** — diff local photo ids against `photos[].id`:
   - **missing ids** → upload only those. Never re-send one the server already has;
     it is harmless but wastes a diver's tethering allowance.
   - **none missing** → the row is complete. Drop it, and cache the returned record.
4. **`401`** — refresh once, then repeat.
5. **`5xx`, timeout, offline** — leave the row as it is and try later. Never downgrade
   a row's state on a failure to reach the server; not knowing is not the same as not
   existing.

Reconciliation is idempotent and safe to run as often as you like. If in doubt, run
it — that is cheaper than showing the contributor something untrue.

### When local data may be deleted

Only ever on the server's confirmation:

- **A photo's local file** may be deleted once its id appears in `photos[]`. Not when
  the upload call returns, not when a flag is set — when the database says so.
- **An outbox row** may be dropped once the sighting exists and every one of its
  photos is present.
- **Everything for an account** is purged after `DELETE /v1/me` succeeds.

Deleting earlier than this is how a sighting disappears with nothing left to retry
from, and it cannot be recovered afterwards.

### Outbox rows belong to an account

Store the owning user id on every row, and **only ever upload a row using that
user's session**.

Two people share a boat and a phone more often than you would think. Without this
rule, one diver's queued sighting uploads under whoever signs in next, and the
record is silently attributed to the wrong contributor — corrupt scientific data,
and an ethics problem in a project that collects named contributions. On sign-out,
keep the rows; on sign-in as somebody else, leave them alone until their owner
returns.

### Server-side changes always win

A cached record is replaced wholesale on every refresh — never merged, never
patched field by field. An expert correcting a label, a rejection, or an account
anonymisation all reach the app the same way: the next read returns different data
and the cache is overwritten. There is no client-side merge logic to get wrong, and
no field the client may edit after acknowledgement (D11, append-only).

### Two cases worth designing for explicitly

**A sighting stranded in `pending_photos`.** The metadata is on the server but a
photo will not upload — usually a `413` that retrying cannot fix. The record is real
and visible to researchers with fewer photographs than intended. Offer the
contributor a way out: retry after downscaling, or accept it as it stands. A sighting
that reached the server with **zero** photos should be surfaced clearly, because it
can never be classified.

**A device clock that is ahead.** `capturedAt` must not be in the future or the
server returns `422` and the row fails terminally — a captured sighting lost to a
wrong clock. Clamp `capturedAt` to "now" at capture time if the device clock is
ahead of it, and prefer a monotonically-sane value over whatever the OS reports.

## Making the outbox trustworthy

The outbox is the only thing standing between a captured sighting and lost data, so
it is worth more care than a cache would be:

- **SQLite in WAL mode with `synchronous = FULL`** for the queue. The default
  settings trade durability for speed, which is the wrong trade here.
- **Photo bytes as files, not blobs**, in app-private storage, written to a temporary
  name and then **atomically renamed**. A half-written file that looks complete is
  indistinguishable from a real one at upload time.
- **Copy the picked image at capture time.** A gallery URI can be revoked or the
  underlying file deleted before the upload runs.
- **Never delete before acknowledgement**, and never on an unparseable response.
- **Let the OS drive the transfer** — WorkManager on Android, background
  `URLSession` tasks on iOS — so a suspended or killed app resumes rather than
  restarting from nothing.
- **Make pending work visible.** A count of unsent sightings, somewhere permanent.
  Silent queues are how data goes missing unnoticed.

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
9. **Kill the app in the window between a successful upload and the read-back** →
   on relaunch the sighting shows "Checking…" and resolves to the server's real
   status. It must never show a confident success the server cannot confirm.
10. **Sync, then delete the sighting directly in the database** (`make psql`) → the
    app stops claiming it exists. Nothing survives in the UI on local authority
    alone.
11. **Upload two of three photos, then kill the app** → reconciliation uploads
    exactly the missing one, and the other two are not re-sent.
12. **Queue a sighting as diver A, sign out, sign in as diver B, sync** → nothing
    of A's uploads. Sign back in as A → it uploads, attributed to A.
13. **Set the device clock a day ahead, capture** → the sighting still submits
    successfully rather than failing `422`.
14. **Reinstall the app and sign in** → history is present (it comes from the
    server), and anything that had not been acknowledged before the reinstall is
    honestly gone rather than silently forgotten.
