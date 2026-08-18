# Android app (not started)

Kotlin + Jetpack Compose + Material 3. Built on the MacBook alongside the iOS app.

**Read [`../mobile-shared/`](../mobile-shared/) first** — the API contract, the
offline sync protocol and the shared design tokens are all settled, so this app
can be written against a fixed target.

Planned stack (see `mobile-shared/README.md` for the reasoning):

- Room for the offline queue, WorkManager for sync with a `CONNECTED` constraint
- Retrofit or Ktor with an OkHttp authenticator handling token refresh
- CameraX for capture, PhotoPicker for importing action-camera photos
- FusedLocationProviderClient for position

Point the app at `http://10.0.2.2:8090` from the emulator.
