# Mobile integration guide

Everything the Android and iOS apps need in order to talk to the Muraka backend.
Written before either app exists, so the contract is settled and the two
platforms cannot drift apart.

- **API contract:** [`docs/openapi.yaml`](../docs/openapi.yaml) - authoritative.
- **Sync protocol:** [`sync-protocol.md`](sync-protocol.md) - the offline queue,
  in detail. Read this before writing any networking code.
- **Integration reference:** [`integration.md`](integration.md) - roles, enums,
  validation rules, the error catalogue and the sighting state machine. Read
  alongside the sync protocol.
- **Design tokens:** [`design-tokens.json`](design-tokens.json) - the decisions that
  cross platforms. Read its `policy` block first: the apps are native-first, so this
  is deliberately not a mirror of the web theme.
- **Design language:** [`design-language.md`](design-language.md) - how Material 3
  and Liquid Glass apply here, and what must match the dashboard exactly.
- **Reference requests:** [`api-examples.http`](api-examples.http) - copy-pasteable
  calls for every endpoint a mobile client uses.

## The app in one paragraph

A contributor records a **sighting** - one to five photographs, a position, a
capture time, optionally depth and a note. Capture must work with no network at
all, because the phone is on a boat. Sightings queue on the device and sync when
connectivity returns. Once synced, the server classifies each photograph and the
sighting shows a model assessment, then later an expert's verdict.

The contributor app is deliberately small. It captures, it syncs, it shows your
own history and totals. Review, maps and administration live in the web
dashboard, not on the phone.

## Screens to build

| Screen | Purpose | Notes |
|---|---|---|
| Sign in / register | Get a session | Only screen that requires connectivity |
| New sighting | Capture and queue | Must complete in under 60 s and 8 taps (NFR6) |
| Sync status | Show what is pending | A count in the app bar plus a detail list |
| My sightings | History with status per item | Grouped by sync and review state |
| Sighting detail | Photo, model assessment, expert verdict | Patch overlay, with a toggle |
| Config | The account and the app's settings | Totals from `GET /v1/me`, appearance, sign out, delete account |

## Non-negotiables

These come from the project's requirements and apply to both platforms.

1. **Never block on the network.** Every write goes to the local database first
   and is uploaded by a background worker. The UI reads local state only.
2. **Client-generated UUIDv7 for every sighting and photo.** This is what makes
   retries safe; see [`sync-protocol.md`](sync-protocol.md).
3. **A model label is never presented as fact** (NFR13). Model output and expert
   verdicts must be visually distinct - different marker shape, not colour alone.
   The dashboard uses a dashed outline for model output and a filled marker for
   verified; match that idea, in each platform's idiom.
   **Follow the platform, not the dashboard's chrome.** Material 3 governs the
   Android app and the HIG plus Liquid Glass govern the iOS app; where either
   disagrees with how the web looks, the platform wins. See
   [`design-language.md`](design-language.md).
4. **Bleached reads as bone-white, healthy as living teal.** Severity ramps from
   teal to white because that is what bleaching looks like. Do not substitute a
   red/green scale.
5. **No API-key services.** No Firebase, no Crashlytics, no Google Maps, no
   analytics SDK. Local notifications only - there is no push infrastructure by
   design, and the project explains why.
6. **Ask for location and camera permission in context**, at the moment of
   capture, with a sentence explaining why. Never on launch.
7. **Position is required; GPS is not.** If there is no fix, the contributor
   drops a pin and the sighting records `locationSource: "manual_pin"`. Store the
   distinction - researchers filter on it.

## Platform stacks

Fixed by the project's constraints; do not substitute.

### Android
- Kotlin, **Jetpack Compose**, Material 3 (dynamic colour where available)
- **Room** for the queue and cached records
- **WorkManager** for sync, with `NetworkType.CONNECTED` and exponential backoff
- Retrofit or Ktor client with an OkHttp authenticator for token refresh
- `FusedLocationProviderClient` for position
- CameraX for capture; `PhotoPicker` for importing action-camera photos

### iOS
- Swift, **UIKit** (not SwiftUI), following the iOS 26 Liquid Glass design language
- **GRDB** (or Core Data) for the queue and cached records
- `URLSession` **background upload tasks** so uploads survive suspension, plus
  `BGProcessingTaskRequest` to drain the queue
- `CLLocationManager` with `desiredAccuracy = kCLLocationAccuracyBest`
- `UIImagePickerController`/`PHPickerViewController` for capture and import

Liquid Glass is a surface treatment, not a structural dependency: the app must
degrade to standard UIKit materials on older systems.

## Environment

| Setting | Local value |
|---|---|
| API base URL, Android emulator | `http://10.0.2.2:8090` |
| API base URL, iOS simulator | `http://localhost:8090` |
| API base URL, device on the same Wi-Fi | `http://<your-machine-ip>:8090` |

The stack is HTTP in development, so both platforms need a local exception:
Android `usesCleartextTraffic` limited to the dev build, iOS an
`NSAppTransportSecurity` exception limited to the dev configuration. Neither
belongs in a release build.

Start the backend before running an app:

```
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml exec api seed -sightings 500
```

## Demo accounts

Created by the seed loader. Sign in as a contributor for mobile work.

| Email | Role | Password |
|---|---|---|
| `diver@muraka.test` | contributor | `muraka-diver-2026` |
| `researcher@muraka.test` | researcher | `muraka-research-2026` |
| `admin@muraka.test` | admin | `muraka-admin-2026` |

## Rendering the patch overlay

A photo's prediction carries `patchGrid` (e.g. 5) and a `patches` array of
`{row, col, label, confidence}`. Draw it as a `patchGrid × patchGrid` lattice over
the image:

- cell fill: living teal for `healthy`, bone white for `bleached`
- cell opacity, **two different formulas** - over a photograph use
  `0.28 + confidence × 0.42`, and for the small standalone glyph in a list row use
  `0.45 + confidence × 0.55`. A hesitant model should look hesitant, but over a
  photograph the cells must stay an annotation: past roughly 0.7 they replace the
  reef and nobody can check the judgement against it
- the grid covers the **centre square** of the image, matching how the server
  tiled it - letterbox the photo the same way or the cells will not line up
- the overlay must be **toggleable** on the detail screen, and the choice remembered.
  An annotation that cannot be removed is an obstruction: turning it off is how a
  contributor checks the model's reading against the reef rather than against the
  model's own drawing of it, which is the whole argument for drawing it

The dashboard's implementation is in `web/src/components/PatchLattice.vue` if you
want a reference for the geometry.

## Acceptance checklist

Work through this before calling either app done; several items map directly to
requirements the project has to evidence.

Results are recorded in
[`docs/evidence/mobile/acceptance.md`](../docs/evidence/mobile/acceptance.md), including
the one item that could not be completed and why.

- [x] Capture completes with the device in airplane mode - *automated, and walked on a
      device with the radio off; see the evidence file for one ANR worth re-checking on
      real hardware*
- [x] Queued sightings survive a force-quit and a device restart
- [x] Sync resumes automatically when connectivity returns, with no user action
- [x] Killing the app mid-upload does not duplicate or lose the sighting
- [x] Submitting the same sighting twice creates exactly one record server-side
- [ ] A 401 mid-session refreshes silently and the request succeeds - *covered on iOS and
      by `make smoke`; no Android-side test*
- [x] Expired refresh token returns the user to sign-in without losing the queue
- [x] Model labels and expert verdicts are distinguishable without colour
- [ ] Capture flow measured at under 60 seconds and 8 taps - *5 taps, counted from the
      code; the **timing** has never been measured*
- [x] Light and dark appearance both correct
- [x] Account deletion explains that sightings are anonymised, not erased
