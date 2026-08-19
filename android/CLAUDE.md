# Android app — working notes for Claude

Read [`../CLAUDE.md`](../CLAUDE.md) first for the project's hard constraints, then
[`../mobile-shared/README.md`](../mobile-shared/README.md) for the contract this app
builds against.

## Non-negotiable

- **Kotlin + Jetpack Compose + Material 3.** Not Views, not Flutter, not React
  Native.
- **Follow Material 3.** [m3.material.io](https://m3.material.io) governs components,
  shape, elevation, motion and accessibility. Where M3 and the dashboard's
  appearance disagree, M3 wins — see
  [`../mobile-shared/design-language.md`](../mobile-shared/design-language.md).
- **No API-key services.** No Firebase, no Crashlytics, no Google Maps, no analytics
  SDK, no push. Local notifications only. This is a project constraint, and adding
  one of these breaks the whole submission's argument.
- **Never block on the network.** Every write lands in Room first; WorkManager
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

- Room for the offline queue and cached records
- WorkManager for sync, `NetworkType.CONNECTED`, exponential backoff
- Retrofit or Ktor with an OkHttp authenticator handling 401 → refresh → retry
- CameraX for capture; Photo Picker for importing action-camera photographs
- `FusedLocationProviderClient` for position

## Colour, specifically

Prefer **dynamic colour** from the wallpaper on Android 12+, seeded fallback below
that. But dynamic colour applies to chrome only — it must never re-tint the condition
scale, the severity ramp or the signal colours. Those are data: a user's wallpaper
deciding what "bleached" looks like would corrupt the reading and make two
screenshots in the project disagree. Keep them in a separate fixed palette, not in
`MaterialTheme.colorScheme`.

## Environment

Point the app at `http://10.0.2.2:8090` from the emulator (`localhost` on the host
machine is not the emulator's `localhost`). Cleartext HTTP is dev-only: limit
`usesCleartextTraffic` to the debug build, never the release one.

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
distinguishable without colour) · NFR14 (M3, light and dark).

The acceptance checklist at the end of
[`../mobile-shared/README.md`](../mobile-shared/README.md) is the definition of done;
several items are the project's only evidence for the requirements above, so record
the results as you go.
