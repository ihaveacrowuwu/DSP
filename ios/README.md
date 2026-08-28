# iOS app

Swift and **UIKit** — not SwiftUI, which is a project requirement rather than a preference —
with the iOS 26 Liquid Glass design language. The contributor half of Muraka: capture a reef
sighting, queue it, and watch the server's verdict arrive.

Read [`../mobile-shared/`](../mobile-shared/) first — the API contract, the offline sync
protocol and the shared design tokens are settled.

## Running it

```bash
make up && make seed N=200      # from the repository root
cd ios && xcodegen generate && open Muraka.xcodeproj
```

Sign in as `diver@muraka.test` / `muraka-diver-2026`.

The simulator shares the Mac's loopback, so the debug build talks to `http://localhost:8090`.
For a physical iPhone, copy `Config/Local.xcconfig.example` to `Config/Local.xcconfig` and set
your Mac's LAN address there. Cleartext HTTP lives in `Config/Info-Debug.plist` only; the
release plist has no App Transport Security exception at all, and `make mobile-lint` fails if
one ever appears (NFR4).

**`Muraka.xcodeproj` is generated and gitignored.** `project.yml` is the source of truth — run
`xcodegen generate` after changing it, and never hand-edit the project file.

## What it does

The same six screens as the Android app, with the same words for the same states — that
vocabulary is a checked contract, not a coincidence (`scripts/check_status_vocabulary.py`).

## How it is put together

```
App/          the container, the scene, the signed-in/signed-out switch
Core/
  Common/     ApiError, UUIDv7, ServerClock, session events
  DesignSystem/  the data palette, the patch lattice, and GlassSurface
  Location/   CLLocationManager, bridged to async
Data/
  Local/      the GRDB outbox and the photo store
  Remote/     the API client, DTOs and mappers
  Repository/ auth, sightings, outbox
  Sync/       the drain loop and the background task
Domain/Model/ pure models, no framework imports
Features/     one folder per screen
```

Two files carry most of the design:

- **[`Data/Sync/SyncEngine.swift`](Muraka/Data/Sync/SyncEngine.swift)** — ask the server what
  it has, send only what is missing, read it back, and only then delete anything local.
- **[`Core/DesignSystem/GlassSurface.swift`](Muraka/Core/DesignSystem/GlassSurface.swift)** —
  **the only place in the app that calls a Liquid Glass API.**

## Liquid Glass

Two rules matter more than the API details:

1. **Glass is chrome, never content.** Bars, floating controls and sheets, yes. A reef
   photograph behind a glass panel is a reef photograph nobody can assess.
2. **It degrades.** `GlassSurface` falls back to standard materials when the effect is
   unavailable, and no layout depends on which it got.

That fallback is not hypothetical even on iOS 26: **Reduce Transparency** turns the effect off
system-wide, so it is a code path a reviewer can switch on in Settings rather than a claim.

Every Liquid Glass API name was checked against the iPhoneSimulator26.5 SDK before use.
They were correct, with one exception recorded there: the Swift importer renames
`containerConcentricRadiusWithMinimum:` to `UICornerRadius.containerConcentric(minimum:)`.

## Commands

```bash
xcodegen generate                                   # regenerate the project
xcodebuild -project Muraka.xcodeproj -scheme Muraka \
  -destination 'platform=iOS Simulator,name=iPhone 17' test | xcbeautify
swiftlint                                           # lint
swiftformat .                                       # fix formatting
```

Or from the repository root: `make ios-build`, `make test-ios`, `make mobile-lint`.

## Testing

| Suite | What it covers |
|---|---|
| `RFC3339Tests` | timestamp parsing, with real payloads from both producers |
| `DurabilityPragmaTests` | WAL and `synchronous = FULL`, read back from a real file |
| `APIClientIntegrationTests` | the client against the **running** API, not a stub |
| `AppConfigurationTests` | the build configuration the app depends on |
| `SignInFlowUITests` | sign in, every tab, a sighting, search and filtering, the appearance toggle, the patch-grid toggle — and that no screen claims delivery |
| `SignInFlowUITests` (layout) | that the last row clears the floating tab bar, which the safe area alone does not guarantee |

The integration and UI suites skip rather than fail when the stack is not running, because a
red suite on a machine with no Docker tells nobody anything.

`RFC3339Tests` exists because of a real bug: Go emits nine fractional digits and PostgreSQL
six, while `ISO8601FormatStyle` accepts exactly three — so every timestamp failed to decode,
and it presented as "signing in does not work".
