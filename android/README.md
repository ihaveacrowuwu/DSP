# Android app

Kotlin, Jetpack Compose and Material 3. The contributor half of Muraka: capture a reef
sighting, queue it, and watch the server's verdict arrive.

Read [`../mobile-shared/`](../mobile-shared/) first — the API contract, the offline sync
protocol and the shared design tokens are settled, so this app is written against a fixed
target.

## Running it

```bash
make up && make seed N=200      # from the repository root
cd android && ./gradlew installDebug
```

Sign in as `diver@muraka.test` / `muraka-diver-2026`.

The debug build points at `http://10.0.2.2:8090`, which is the host machine as seen from the
emulator — the emulator's own `localhost` is the emulated device. For a physical phone on the
same Wi-Fi, pass your machine's address:

```bash
./gradlew installDebug -PmurakaApiBase=http://192.168.1.20:8090/
```

Cleartext HTTP is scoped to the debug build and to those hosts only; the release build has no
such exception and points at HTTPS (NFR4).

## What it does

| Screen | What it is for |
|---|---|
| Sign in / register | The **only** screen that needs connectivity (NFR7) |
| New sighting | One to five photographs, a position, depth, note — five taps to queue |
| My sightings | The contributor's own history, with the server's status on each |
| Sighting detail | The photograph, the patch lattice over it (toggleable), and any expert verdict |
| Sync | Everything still owed to the server, and a way out when one is stuck |
| Config | Totals from `GET /v1/me`, appearance, sign out, delete account |

## How it is put together

Layered, one module per layer, dependencies pointing inward only:

```
:app                     screens and view models
:core:designsystem       the M3 theme, the fixed data palette, the patch lattice
:core:domain             repository interfaces          ← pure Kotlin, no Android SDK
:core:model              enums and models               ← pure Kotlin, no dependencies
:core:common             ApiError, UUIDv7, ServerClock  ← pure Kotlin
:core:data               repository implementations, the sync engine
:core:network            Retrofit, DTOs, the refresh-on-401 authenticator
:core:database           the Room outbox and the display cache
:core:datastore          Keystore-encrypted session tokens
:core:sync               WorkManager scheduling only
```

`:core:model`, `:core:common` and `:core:domain` are **pure Kotlin/JVM** modules. That is not
tidiness: `import androidx.room.Entity` in any of them is a compile error, so "the domain
knows nothing about frameworks" is a fact about the build rather than a convention nobody
enforces. It is also what lets every view model be tested with a hand-written fake and no
emulator.

Three things are worth reading before changing anything:

- **[`core/model/…/SyncState.kt`](core/model/src/main/kotlin/mv/muraka/core/model/SyncState.kt)** —
  the outbox state machine, and why there is no "Synced" status.
- **[`core/data/…/SyncEngineImpl.kt`](core/data/src/main/kotlin/mv/muraka/core/data/sync/SyncEngineImpl.kt)** —
  the drain loop: ask the server what it has, send only what is missing, read it back, and
  only then delete anything local.
- **[`core/designsystem/…/ReefColors.kt`](core/designsystem/src/main/kotlin/mv/muraka/core/designsystem/theme/ReefColors.kt)** —
  why the condition colours live outside `MaterialTheme.colorScheme`.

## Design

Material 3, with **dynamic colour** from the wallpaper where it is available. That applies to
chrome only. The condition scale, the severity ramp and the signal colours are data — they
carry scientific meaning — so they live in `MurakaTheme.reef`, and nothing connects a
wallpaper to them. A user's home screen deciding what "bleached" looks like would corrupt the
reading and make two screenshots in the project disagree.

## Commands

```bash
./gradlew installDebug              # build and install
./gradlew testDebugUnitTest         # JVM unit tests, no device needed
./gradlew connectedDebugAndroidTest # instrumented: the outbox and its durability pragmas
./gradlew qualityCheck              # ktlint, detekt, Android Lint and every unit test
./gradlew ktlintFormat              # fix formatting
```

`qualityCheck` runs Android Lint with `warningsAsErrors`, so an accessibility or API-level
problem fails the build rather than accumulating.

## Testing

| Where | What it covers |
|---|---|
| `core/common/src/test` | UUIDv7 ordering, the server-clock correction |
| `core/model/src/test` | the wire enums, and D21 — that no status claims delivery |
| `core/data/src/test` | the retry curve and its jitter bounds |
| `core/database/src/androidTest` | the outbox against real SQLite, and WAL + `synchronous = FULL` |

The durability test reads the pragmas back rather than trusting that setting them worked, and
it earned its keep: `synchronous = FULL` set from `RoomDatabase.Callback.onOpen` is silently
overwritten by Android's own WAL configuration.
