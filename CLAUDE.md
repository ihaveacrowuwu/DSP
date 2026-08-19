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
android/      Not started. Kotlin + Compose + M3
ios/          Not started. Swift + UIKit + Liquid Glass
mobile-shared/ The contract both apps build against
deploy/       docker-compose.yml for the whole stack
scripts/      smoke_test.py — end-to-end pipeline check
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

## Current status (2026-08-19)

Built and running: Go API and worker, ML service (**fake mode — no trained model
yet**), Vue dashboard, database, 10k seeded sightings, Docker stack.

Not started: both mobile apps, and the ML training track.

Measured so far: sync→label ~1.5s (target ≤30s), map with 10k sightings 22ms
(target <2s).

The reef map draws real geography: Natural Earth 10m clipped to the Maldives and
committed as 67 KB of vector GeoJSON (`web/public/basemap/maldives.json`), so the map
path has no tile server, no glyph server and no network. See D22/D23 in `docs/08` and
the header of `web/src/lib/mapStyle.ts`.

Known gaps: no DB-backed integration tests (all 40 Go tests are pure unit tests),
dashboard tests cover only the map style, the basemap and the photo-resolution rule
(`web/src/lib/*.test.ts`, 18 tests) and nothing else, no TLS in the demo config, no
load test, no `TESTING.md` traceability document, and no trained model.
