# Integration reference

What a client must know to talk to the Muraka API correctly. This is the wiring
layer: roles, enums, validation, errors, state transitions and the handful of things
that will otherwise cost an afternoon.

Companion documents, all authoritative in their own area:

- [`../docs/openapi.yaml`](../docs/openapi.yaml) — endpoints and schemas
- [`sync-protocol.md`](sync-protocol.md) — the offline queue, retries, token refresh
- [`api-examples.http`](api-examples.http) — runnable calls in the order a client makes them

Every fact below was read out of the Go implementation, not from the spec, so where
this and the spec ever disagree, trust this and fix the spec.

## What a contributor may call

The mobile apps sign in as **contributors**. Anything outside this list returns
`403 forbidden` — do not build a feature against it.

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/auth/register` | New accounts are always contributors |
| POST | `/v1/auth/login` | |
| POST | `/v1/auth/refresh` | Refresh tokens are **single-use** — store the new one |
| POST | `/v1/auth/logout` | |
| GET | `/v1/me` | Profile plus contribution totals |
| DELETE | `/v1/me` | Anonymises; see below |
| POST | `/v1/sightings` | Create, idempotent on client `id` |
| POST | `/v1/sightings/{id}/photos` | One call per photo, idempotent on `photoId` |
| GET | `/v1/sightings` | **Scoped to your own sightings automatically** |
| GET | `/v1/sightings/{id}` | Own sightings only |
| GET | `/v1/photos/{id}/image` | JPEG bytes, **requires the bearer token** |
| GET | `/v1/atolls` | Reference data, unauthenticated |
| GET | `/v1/sites` | Reference data |
| GET | `/healthz`, `/readyz` | Liveness / readiness |

Researcher-only (`403` for contributors): `/v1/verifications/queue`,
`/v1/sightings/{id}/verification`, `/v1/map/points`, `/v1/trends`,
`/v1/export/sightings.csv`. Admin-only: everything under `/v1/admin/`.

`GET /v1/sightings` needs no contributor filter parameter — the API scopes it by the
caller's role. Do not pass a contributor id; a contributor cannot query anyone else.

## Enums

Send and compare these exact strings. They are the wire format in both directions.

| Enum | Values |
|---|---|
| `role` | `contributor`, `researcher`, `admin` |
| `condition` | `healthy`, `bleached` |
| `locationSource` | `gps`, `manual_pin` |
| `status` (sighting) | `pending_photos`, `processing`, `awaiting_verification`, `verified`, `rejected` |
| `decision` (verification) | `confirmed`, `corrected`, `rejected` |
| `rejectReason` | `blurry`, `not_coral`, `duplicate`, `spam`, `other` |

Both apps must show the **same words** for the same status — see the table further
down. A sighting that reads "Analysing" on Android and "Processing" on iOS is a bug
in the family, not a platform difference.

## Validation, as the server actually enforces it

A `422` carries `fields`, a map of field name to message, and is **never retryable**.

| Field | Rule |
|---|---|
| `id` | Required, must parse as a UUID. UUIDv7 recommended so the queue sorts by creation |
| `lat` / `lon` | Required, valid WGS84 |
| `locationSource` | Required, `gps` or `manual_pin` |
| `capturedAt` | Required, RFC3339, **must not be in the future** — check the device clock before queueing |
| `depthM` | Optional, 0–200 metres |
| `selfAssessedCondition` | Optional, `healthy` or `bleached` |
| `note` | Optional |
| `email` | Valid email address |
| `password` | At least 10 characters |
| `displayName` | Required on register |
| photo upload | JPEG or PNG in, **12 MiB** cap (`MAX_UPLOAD_BYTES`), re-encoded to JPEG server-side |

Two limits the client is **solely** responsible for, because the server does not
enforce them today:

- **1–5 photos per sighting (FR2).** The API accepts more. Cap it in the app.
- **Note length.** Unbounded server-side. Pick a sane limit and enforce it in the UI.

The server strips EXIF when it re-encodes, so anything worth keeping must be sent as
a field. It reads capture time and GPS out of EXIF first, but the JSON fields are
what the record is built from — send them explicitly.

## Error catalogue

Group by what the client should *do*, not by code.

**Terminal — do not retry, surface something useful**

| Status | `error` | Meaning |
|---|---|---|
| 422 | `validation_failed` | `fields` names what is wrong. Mark the queue item failed with the reason |
| 409 | `id_owned_by_another_user` | This UUID belongs to another account. Regenerate the id or discard |
| 409 | `email_taken` | Registration only |
| 413 | `upload_too_large` | Downscale locally and upload under a new photo id |
| 422 | `photo_count` | Reported per-photo problems |
| 400 | `invalid_json`, `invalid_id`, `invalid_version` | A malformed request — a client bug, not a transient failure |
| 403 | `forbidden` | Wrong role. Should never happen in the apps; treat as a bug |
| 403 | `account_disabled` | Suspended by an admin. Sign the user out with an explanation |
| 401 | `invalid_credentials` | Wrong email or password |
| 404 | `not_found` | Sighting or photo does not exist, or is not yours |

**Recoverable — refresh once, then retry**

| Status | `error` | Meaning |
|---|---|---|
| 401 | `unauthorized`, `invalid_token` | Access token expired or rejected. Refresh once; if the refresh fails, return to sign-in **without clearing the queue** |

**Transient — retry with backoff, the outcome is unknown**

| Status | `error` | Meaning |
|---|---|---|
| 500 | `internal_error` | |
| 503 | `not_ready`, `ml_service` | Stack still starting, or the classifier is unreachable. Ingest still succeeded |
| — | timeout, offline, connection reset | Retry. Safe, because the id makes the write idempotent |

Retrying a transient failure can never duplicate anything: both writes are keyed on
a client-generated UUID, and a replay answers `200` instead of `201`. Treat those two
identically — that is the entire point of the design.

## The sighting state machine

Read from the implementation. The client never sets `status`; it only displays it.

| Status | Set when | Set by |
|---|---|---|
| `pending_photos` | `POST /v1/sightings` succeeds | API |
| `processing` | The first photo upload lands and is queued for classification | API |
| `awaiting_verification` | Every classification job for the sighting finishes | Worker |
| `verified` | A researcher confirms or corrects | API |
| `rejected` | A researcher rejects the photograph | API |

Two consequences worth designing around:

- **`pending_photos` is a state your app can strand.** A sighting whose metadata
  synced but whose photos have not is real, visible server-side, and stuck until the
  photos arrive. The queue must keep trying, and "my sightings" should distinguish
  it from a fully synced item.
- **`rejected` sightings vanish from research views** (FR11) but remain the
  contributor's own record. Show them, with the reason if present.

Contributor-facing wording — the same strings on both platforms, and note the
`Source` column: only the first two may be stated on the client's own authority. See
[`sync-protocol.md`](sync-protocol.md#source-of-truth) for why that line matters.

| Source | Status | Show as |
|---|---|---|
| Outbox | queued, not yet sent | Waiting to upload |
| Outbox | request in flight | Uploading |
| — | accepted, not yet read back | Checking… |
| Server | `pending_photos` | Photos pending |
| Server | `processing` | Analysing |
| Server | `awaiting_verification` | Awaiting expert review |
| Server | `verified` | Verified |
| Server | `rejected` | Not usable |

There is deliberately no client-assertable "Synced". A local flag saying the upload
worked is a claim, not a fact; the app either has the server's answer or it says it is
still checking.

## Reconciling with the server

The client never has to guess whether a write landed, because the ids are its own:

| Call | Answers |
|---|---|
| `GET /v1/sightings/{id}` → `404` | The server has nothing under this id |
| `GET /v1/sightings/{id}` → `200` | It exists, and `photos[].id` lists the photos the server actually holds |

Photo rows are keyed on the client-supplied `photoId` (the server upserts with
`ON CONFLICT (id) DO NOTHING`), so diffing local photo ids against `photos[].id`
gives the exact set still missing. Re-uploading one the server already has is
harmless — it answers `200` and changes nothing — but there is no need to guess.

The full algorithm, the outbox state machine and the rules for when local data may be
deleted are in [`sync-protocol.md`](sync-protocol.md#reconciliation--how-the-outbox-and-the-database-agree).

## Reading a prediction

`GET /v1/sightings/{id}` returns `photos[]`, each optionally carrying `prediction`:

```
{ "label": "bleached", "confidence": 0.62, "severity": 0.41,
  "modelVersion": "fake-0.0.0", "patchGrid": 5,
  "patches": [ { "row": 0, "col": 0, "label": "healthy", "confidence": 0.88 }, ... ] }
```

- `label` / `confidence` — the whole-photo call and how sure the model is
- `severity` — bleached **extent**, 0–1. This is the number to lead with, not the label
- `modelVersion` — show it. It is provenance, and `fake-0.0.0` means no real model is
  loaded yet
- `patches` — `patchGrid × patchGrid` cells for the overlay. Geometry and the two
  opacity formulas are in [`design-tokens.json`](design-tokens.json)

`prediction` is absent until classification finishes. Absent is not an error.

A prediction is **never** an expert verdict. `sighting.verified` tells you which you
are looking at, and the distinction must be visible without colour (NFR13).

## Things that will cost you an afternoon

1. **Photo bytes need the Authorization header.** `GET /v1/photos/{id}/image` is not a
   public URL, so the image loader must be configured with the bearer token. A bare
   URL handed to a stock loader returns 401 and renders nothing.
2. **Refresh tokens are single-use.** Every refresh returns a new one; persist it in
   the same transaction that stores the access token, or the next refresh fails and
   the user is signed out for no reason.
3. **Emulator hosts differ.** Android emulator: `http://10.0.2.2:8090`. iOS
   simulator: `http://localhost:8090`. Physical device: the machine's LAN IP.
4. **HTTP is dev-only.** Limit Android's `usesCleartextTraffic` and iOS's ATS
   exception to debug builds.
5. **Upload in `createdAt` order**, one sighting at a time, its photos sequentially.
   Parallel uploads on a weak connection are slower and much harder to reason about.
6. **Downscale before uploading.** The server analyses at 224px per grid cell, so a
   5×5 grid gains nothing above ~1600px on the long edge. 1600px at JPEG q85 is far
   under the cap and kinder to resort Wi-Fi.
7. **`DELETE /v1/me` anonymises, it does not erase.** Sightings survive as scientific
   record under an anonymous owner. NFR15 requires the app to say so *before* the
   user confirms.
8. **Access tokens last 15 minutes.** Assume expiry mid-session; the 401→refresh→retry
   path is a normal code path, not an edge case.
9. **`GET /v1/me` is the only source of contribution totals.** Do not count local
   rows — a client-side tally drifts the moment anything is rejected, anonymised or
   verified, and the number the contributor sees would then disagree with the
   dashboard.

## Checking your work against the running system

`scripts/smoke_test.py` exercises the whole contributor path against a live stack —
register, login, create, replay for idempotency, upload, poll for the prediction,
then verify as a researcher. If a client behaves differently from that script, the
script is right.

```bash
make up && make seed N=500 && make smoke
```
