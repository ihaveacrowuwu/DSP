# Muraka / Reef Watch — working notes for Claude

Final-year project (UFCFXK-30-3 Digital Systems Project, Villa College / UWE
Bristol). Citizen-science coral reef condition monitoring for the Maldives:
contributors capture geotagged reef photographs in offline-first native apps, a Go
API ingests them, a Python service grades each photograph patch by patch, and marine
researchers confirm or correct every result in a Vue dashboard before it counts as
data.

**This is an assessed solo academic project.** The project is 70% of the grade and is
assembled from evidence — metrics, test output, benchmarks, screenshots. Evaluation
artefacts are first-class deliverables, not afterthoughts. Decisions should maximise
project and demo quality, not production scale.

## Hard constraints — never violate these

1. **The stack is fixed.** Go (API), Vue 3 (dashboard), Python (ML service only),
   PostgreSQL + PostGIS (the only database). Android is Kotlin + Jetpack Compose +
   Material 3. iOS is Swift + **UIKit** (not SwiftUI) with Liquid Glass. No other
   database, no Node backend, no cross-platform mobile framework.
2. **No external API keys.** Nothing may depend on a third-party service requiring
   registration: no cloud ML, no LLM APIs, no Firebase, no Crashlytics, no Google
   Maps, no analytics SDK, no experiment trackers (W&B, comet-ml). Maps use keyless
   or self-hosted tiles. Datasets must be freely downloadable.
3. **ML hardware.** Training happens on an Apple M1 Pro via PyTorch MPS. A work DGX
   may occasionally be available but **nothing may depend on it**. Deployed
   inference must run on CPU.
4. **Push notifications do not exist** by design (constraint 2). Local
   notifications only, and the project explains why.

If a task appears to require breaking one of these, stop and ask. Do not work around
a constraint silently.

## Read before proposing anything

| Document | For |
|---|---|
| [`docs/00-INDEX.md`](docs/00-INDEX.md) | Orientation and the canonical reading order |
| [`docs/07-requirements.md`](docs/07-requirements.md) | The FR/NFR list with MoSCoW priorities and verification methods |
| [`docs/08-scope-risks-decisions.md`](docs/08-scope-risks-decisions.md) | Scope boundaries, cut lines, risks, **and the decision log** |
| [`docs/openapi.yaml`](docs/openapi.yaml) | The API contract — verified accurate against the implementation |
| [`mobile-shared/`](mobile-shared/) | Everything the two apps need: sync protocol, design tokens, design language, reference requests |

## Repository map

```
backend/      Go API, worker, seed loader. chi router, pgx, argon2id, JWT
ml/           service/ = FastAPI + ONNX (CPU, fake mode by default)
              training/ = M1 Pro recipes. Only configs/baseline.yaml exists so far
web/          Vue 3 + TypeScript dashboard, MapLibre, Vite
android/      Kotlin + Compose + M3. Multi-module Gradle; see its README
ios/          Swift + UIKit + Liquid Glass. XcodeGen; the .xcodeproj is generated
mobile-shared/ The contract both apps build against
deploy/       docker-compose.yml for the whole stack
scripts/      smoke_test.py — end-to-end pipeline check
              check_status_vocabulary.py — fails if the two apps disagree about a
              contributor-facing status, or if either claims a sighting is delivered
              build_basemap.py — regenerates the dashboard's offline Maldives
              basemap from Natural Earth. Only needed if the geography or the
              clip box changes; its output is committed
docs/         Academic documents 01–09 plus the OpenAPI contract
```

## Running it

```bash
make up                 # build and start everything
make seed N=2000        # demo data
make smoke              # end-to-end pipeline test
make test               # Go + ML unit tests
make dev-web            # dashboard with hot reload on :5180
make perf               # NFR1/NFR2/NFR3/NFR11, writes docs/evidence/performance/
make lint               # TESTING.md citations + the demo TLS configuration
make up-tls             # the demo stack, TLS on :8443 (NFR4) — see deploy/README.md
make smoke-tls          # the 33 end-to-end checks again, over TLS
```

Ports: api 8090, web 5180, ml 8010, postgres 5433 — 8080 and 5173 were taken by
another project on the development machine. All overridable.

Demo accounts (created by `make seed`): `admin@muraka.test` /
`muraka-admin-2026`, `researcher@muraka.test` / `muraka-research-2026`,
`diver@muraka.test` / `muraka-diver-2026`.

## Rules for changing things

- **The docs define WHAT.** Do not silently change scope while building. Log any
  deviation in the decision log in `docs/08` with its rationale, and get approval
  for anything touching a Must requirement, the stack, or the key-free rule.
- **Keep evidence as you go.** Test output, benchmark numbers, screenshots,
  iteration notes. The project is assembled from these.
- **Do not invent a schedule.** There is deliberately no sprint plan: the module
  timeline is still an open question (Q4 in `docs/08`). If asked for plans, that is
  the trigger to write them — with the user, not for them.
- **Verify, don't assume.** Several documents in this repo drifted from the code
  before (the design tokens described a palette the dashboard no longer had). When a
  document and the code disagree, the code is the truth and the document is a bug.

## Frontend work

The dashboard has its own design system in `web/src/assets/` — `base.css`
(structure and motion tokens), `theme.css` (every colour), `components.css` (shared
primitives), loaded in that order. Conventions: one canonical component per
interaction, `.btn` plus a variant on every button, MDI icons only via
`lib/icons.ts`, `data-tip` never `title`, and no `backdrop-filter` on surfaces that
do not float over content. Read the file headers — they explain the reasoning,
including several bugs that are easy to reintroduce.

## Current status (2026-08-21)

Built and running: Go API and worker, ML service (**fake mode — no trained model
yet**), Vue dashboard, database, seeded sightings, Docker stack, **and both mobile
apps**.

Both contributor apps are feature-complete against the `mobile-shared` contract: all
six screens, the offline outbox, reconciliation, token refresh, the patch lattice and
the provenance encoding. They were built and verified against the running stack — see
`docs/evidence/mobile/` for screenshots of every screen on both platforms.

Not started: the ML training track.

Measured, with a harness and a recorded run rather than from memory — `make perf`,
figures and caveats in [`docs/evidence/performance/`](docs/evidence/performance/):
sync→label **0.89s** (≤30s), map at 10,304 sightings **56ms** worst of 5 (≤2s), 50
concurrent submissions **0 errors and 0 lost** at 919/s, inference **22ms** (≤500ms, but
that is the fake model and means nothing until one is trained). The old notes here said
22ms for the map and ~1.5s for sync; both were remembered, and the map figure was wrong.

The reef map draws real geography: Natural Earth 10m clipped to the Maldives and
committed as 67 KB of vector GeoJSON (`web/public/basemap/maldives.json`), so the map
path has no tile server, no glyph server and no network. See D22/D23 in `docs/08` and
the header of `web/src/lib/mapStyle.ts`.

[`TESTING.md`](TESTING.md) is the requirement-to-test traceability matrix, and it is
the honest picture: of 33 requirements, **16** have full automated evidence, 12 are
partly covered and **5 have none**. Read it before planning work — it is also the
to-do list, and its counts are tallied by `scripts/testing_matrix.py` rather than
maintained by hand. 233 automated tests across five suites, all passing, plus 33
end-to-end smoke checks and 4 measured performance checks.

Known gaps, in the order they hurt the project: **no trained model** (the ML training
track is the one unstarted track, and it blocks NFR2's real figure, NFR16 and FR17), no
dashboard **view** tests (the components are covered but nothing mounts `QueueView`,
`ReefMapView` or `SightingDetailView`), the capture flow is unmeasured against NFR6, the
offline half of the mobile acceptance checklist is unwalked, no request-ID propagation
test (NFR12), and no SUS study (NFR8).

On mobile, the gap is the acceptance checklist at the end of
`mobile-shared/README.md`: the offline scenarios that need a device to be put into
aeroplane mode and force-quit mid-upload have not been walked through yet, and neither
has the NFR6 timing (under 60 seconds and 8 taps). The capture flow is five taps by
construction, but that is a claim until somebody holds a stopwatch.

## Mobile

Both apps are native and share nothing but the contract in `mobile-shared/`. Two rules
carry most of the design and are worth knowing before touching either:

- **The server is the source of truth (D21).** The outbox is authoritative only about
  what has NOT been delivered. There is no "Synced" status anywhere in either app, by
  design — `scripts/check_status_vocabulary.py` fails the build if one appears, and if
  the two apps ever disagree about a word.
- **Platform guidelines govern chrome; the data carries the family resemblance.**
  Material 3 with dynamic colour on Android, Liquid Glass on iOS — but the condition
  scale, the severity ramp and the patch lattice are identical, and deliberately live
  outside each platform's themable palette.

```bash
make android-install    # build and install on the running emulator
make ios-build          # regenerate the Xcode project and build for the simulator
make mobile             # unit tests for both
make mobile-lint        # linters, the status-vocabulary check and the ATS check
```
