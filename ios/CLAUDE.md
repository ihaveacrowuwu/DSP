# iOS app — working notes for Claude

Read [`../CLAUDE.md`](../CLAUDE.md) first for the project's hard constraints, then
[`../mobile-shared/README.md`](../mobile-shared/README.md) for the contract this app
builds against.

## Non-negotiable

- **Swift + UIKit.** **Not SwiftUI.** This is a project requirement, not a
  preference — do not "modernise" it, and do not mix SwiftUI in for convenience.
- **Follow the Human Interface Guidelines**, with the iOS 26 **Liquid Glass** design
  language. Where the HIG and the dashboard's appearance disagree, the HIG wins —
  see [`../mobile-shared/design-language.md`](../mobile-shared/design-language.md).
- **No API-key services.** No Firebase, no Crashlytics, no analytics SDK, no push.
  Local notifications only.
- **Never block on the network.** Every write lands in the local database first; a background worker
  uploads.
- **The server is the source of truth.** The outbox is authoritative only about what
  has NOT been delivered; the server is authoritative about everything that has. The
  app may say "waiting to upload" and "uploading" on its own authority and nothing
  else — no local flag may ever be displayed as "synced", and totals come from
  `GET /v1/me`, never from counting local rows. Read a sighting back after upload
  rather than trusting your own flag.
- **Client-generated UUIDv7** for every sighting and photo — that is what makes
  retries safe. See
  [`../mobile-shared/sync-protocol.md`](../mobile-shared/sync-protocol.md). Roles, enums, validation, the error
  catalogue and the sighting state machine are in
  [`../mobile-shared/integration.md`](../mobile-shared/integration.md).

## Stack

- GRDB (or Core Data) for the offline queue and cached records
- `URLSession` **background upload tasks** so uploads survive suspension, plus
  `BGProcessingTaskRequest` to drain the queue
- `PHPickerViewController` / `UIImagePickerController` for capture and import
- `CLLocationManager`, `desiredAccuracy = kCLLocationAccuracyBest`

## Liquid Glass, specifically

Two rules matter more than the API details:

1. **Glass belongs on chrome, never on content.** Bars, toolbars, floating controls
   and sheets, yes. Photographs, the patch lattice and the sightings list are
   content — a reef photograph behind a glass panel is a reef photograph nobody can
   assess.
2. **It must degrade.** Liquid Glass is a visual layer, not a structural dependency.
   The app falls back to standard UIKit materials where the effect is unavailable,
   and no layout may depend on it.

The expected UIKit surface is `UIGlassEffect` inside a `UIVisualEffectView`,
`UIGlassContainerEffect` for grouping nearby glass elements, glass button
configurations, concentric corner configuration instead of hardcoded radii, and SF
Symbols for iconography.

> **Verify every one of those names against the installed SDK in Xcode before
> using it.** The surrounding documentation was written on a Windows machine with no
> Apple SDK available. For a design language this new, Xcode's documentation and the
> current HIG are authoritative over anything written here — and if this file is
> wrong, correct it.

Use semantic colours (`label`, `secondaryLabel`, `systemBackground`,
`secondarySystemBackground`, `separator`) so dark mode and increased contrast come
free. The condition and severity colours are the exception: define them as fixed
light/dark pairs in an asset catalogue, because they carry scientific meaning and
must not shift with system theming.

## Environment

Point the app at `http://localhost:8090` from the simulator. Cleartext HTTP is
dev-only: limit the `NSAppTransportSecurity` exception to the debug configuration,
never a release build.

Start the backend first:

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml exec api seed -sightings 500
```

Sign in as `diver@muraka.test` / `muraka-diver-2026` for contributor work.

## Requirements this app owns

FR2 (capture 1–5 photos, GPS or dropped pin, time, depth, note) · FR3 (offline
queue, automatic sync) · FR9 (own sightings with sync and review status) · FR16
(warn on unusable photographs, Could) · NFR6 (capture in under 60s and ≤8 taps) ·
NFR7 (fully functional offline except register/login) · NFR13 (model vs expert
distinguishable without colour) · NFR14 (Liquid Glass, light and dark).

The acceptance checklist at the end of
[`../mobile-shared/README.md`](../mobile-shared/README.md) is the definition of done;
several items are the project's only evidence for the requirements above, so record
the results as you go.
