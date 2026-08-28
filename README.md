# Muraka — reef condition monitoring for the Maldives

Final-year project (UFCFXK-30-3 Digital Systems Project, Villa College / UWE
Bristol) by Ahmed Nauhaan Athif.

Reefs change faster than professional surveys can reach them. Divers photograph
those reefs every day, and that observation is currently lost. Muraka collects it:
contributors capture geotagged reef photographs in offline-first mobile apps, a
model grades each photograph patch by patch, and marine researchers confirm or
correct every result before it counts as data.

📚 Design rationale, requirements and the ML specification are in [`docs/`](docs/00-INDEX.md).

## Architecture

```
[Android app]──┐                                  ┌─[Python ML service]
 Kotlin/Compose│                                  │  FastAPI + ONNX, CPU only
               │ HTTPS/JSON                       │  patch-grid classification
[iOS app]──────┼────────► [Go API] ◄──internal────┘
 Swift/UIKit   │           │    │       HTTP
               │           │    └────────► image storage
[Vue dashboard]┘           ▼
 MapLibre           [PostgreSQL 16 + PostGIS]
                     single source of truth,
                     and the job queue
```

Five components, all self-hosted. **Nothing in the system depends on an external
API key** — that is a hard project constraint, and it shapes the map tiles,
notification strategy and ML stack.

| Component | Stack | Status |
|---|---|---|
| API + worker | Go 1.26, chi, pgx | Working |
| ML service | Python 3.12, FastAPI, ONNX Runtime | Working — serving a trained model |
| Dashboard | Vue 3, TypeScript, MapLibre | Working |
| Database | PostgreSQL 16 + PostGIS | Working |
| Android app | Kotlin, Jetpack Compose, Material 3 | Working — all six screens, offline outbox |
| iOS app | Swift, UIKit, Liquid Glass | Working — all six screens, offline outbox |

## Getting started

Requires Docker. Nothing else, for the stack itself.

```bash
make up                 # build and start everything
make seed N=2000        # load demo data
make smoke              # end-to-end pipeline test
```

Then open the dashboard at **http://localhost:5180** and sign in as
`researcher@muraka.test` / `muraka-research-2026`.

Ports are overridable if they clash: `make up API_PORT=9090 WEB_PORT=5190`.

### Working on the dashboard

`make up` serves the dashboard as a **static production build** behind nginx, which
is what the demo demo should run. It does not pick up source edits — a change needs
`make restart S=web` to be rebuilt.

For frontend work, run the dev server instead and get hot module reload: edits to a
component or to the design tokens apply in place, without a page reload and without
losing where you were in the app.

```bash
make dev-web            # hot reload on the same URL, :5180
make dev                # the same, plus starting postgres/api/ml first
```

`make dev-web` stops the static `web` container so the dev server can own port
5180 — the URL never changes, only what is answering on it. Vite is set to
`strictPort`, so if something else holds 5180 it says so instead of quietly moving
to another port and leaving you looking at a stale build. `make up` puts the static
container back.

The API, ML service and database keep running in Docker throughout; only the
dashboard moves to the host. Same pattern as the existing `dev-api` and `dev-ml`
targets.

| Service | URL |
|---|---|
| Dashboard | http://localhost:5180 |
| API | http://localhost:8090/healthz |
| ML service | http://localhost:8010/healthz |
| PostgreSQL | localhost:5433 |

### Demo accounts

Created by `make seed`.

| Email | Role | Password |
|---|---|---|
| `admin@muraka.test` | admin | `muraka-admin-2026` |
| `researcher@muraka.test` | researcher | `muraka-research-2026` |
| `diver@muraka.test` | contributor | `muraka-diver-2026` |

## How a sighting flows through the system

1. A contributor captures photographs, a position and a depth. It works with no
   network at all and queues on the device.
2. On connectivity the app posts metadata, then each photograph. Both are
   idempotent on a **client-generated UUIDv7**, so a retry can never duplicate.
3. The API stores the record and enqueues a classification job in PostgreSQL.
4. A worker claims jobs with `FOR UPDATE SKIP LOCKED` and calls the ML service,
   which tiles the photograph into a grid, classifies each cell, and returns
   per-patch labels plus a **bleached-extent severity**.
5. The sighting becomes `awaiting_verification` and enters the researcher's queue,
   ordered by lowest model confidence first.
6. A researcher confirms, corrects or rejects it. Expert labels win; the model's
   prediction is preserved for provenance rather than overwritten.

## Repository layout

```
backend/         Go API, worker, seed loader
ml/              service/ (inference) and training/ (M1 Pro recipes)
web/             Vue dashboard
mobile-shared/   API contract notes, sync protocol, design tokens for the apps
android/  ios/   the two native contributor apps
deploy/          docker compose stack
docs/            proposal, requirements, design, ML spec, OpenAPI
scripts/         smoke_test.py
```

## Tests

```bash
make test        # Go unit tests + ML service tests
make test-web    # dashboard typecheck
make smoke       # 33 end-to-end checks against the running stack
```

The smoke test walks the whole pipeline the way a mobile client will: register,
submit, replay to prove idempotency, upload, wait for classification, verify as a
researcher, then confirm the expert label overrides the model while the prediction
survives.

## Measured behaviour

Measured on the development machine (Apple M1 Pro) against 10,312 seeded sightings.
Every figure here comes from a harness or a timed request, not from memory — three
earlier numbers in this table were recalled rather than measured and turned out to
be wrong.

| Measure | Result |
|---|---|
| Sync to visible model label | **0.89 s** (target: ≤ 30 s) |
| National map viewport, 10,312 sightings | **56 ms** worst of 5 (target: < 2 s) |
| 50 concurrent submissions | **0 errors, 0 lost**, 919/s |
| Map points endpoint | 42 ms |
| Trends endpoint | 30 ms |
| Verification queue page | 32 ms |
| Classifier, held-out test split | 0.8575 accuracy, 0.8548 macro-F1, **0.9543 recall on bleached** |
| CPU inference, one 5×5 patch lattice | **405 ms** native (target: ≤ 500 ms) |

⚠️ That last figure is met on the processor and **missed inside Docker on macOS**,
where the same model takes ~822 ms — Docker Desktop runs containers in a virtual
machine, which roughly doubles it. A Linux host has no such penalty.

## Constraints worth knowing before changing anything

1. **The stack is fixed**: Go, Vue 3, Python for ML only, PostgreSQL + PostGIS.
   Android is Kotlin/Compose; iOS is Swift/UIKit. No substitutions.
2. **No external API keys.** No Mapbox, no Firebase, no cloud ML, no analytics.
   Local notifications instead of push, keyless map tiles, local models.
3. **ML training targets an M1 Pro**; deployed inference is CPU-only.
4. **A model label is never presented as fact.** Model output and expert verdicts
   are visually distinct everywhere they appear.

Reference material not owned by this project is ignored
rather than tracked.

be committed.
