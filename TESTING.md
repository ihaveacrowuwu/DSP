# Testing and requirement traceability

`docs/07-requirements.md` closes by asking for a document that links FR/NFR IDs to
test names to results, "so the project's testing chapter can be generated from evidence
rather than memory". This is that document.

It is deliberately unflattering. A traceability matrix whose every row says "covered"
is not evidence, it is decoration — the value is in the rows that say **none**, because
those are the project's honest limitations section and the build's to-do list at the
same time.

Of thirty-three requirements, **twenty** have full automated evidence, ten are partly
covered and **three have none at all**. Those
figures are tallied from the table below by `scripts/testing_matrix.py --check`, not
written by hand — an early draft of this paragraph claimed sixteen when the true figure
was three, which is exactly the kind of number a report should never carry unchecked.

**Results below are from a real run on 2026-08-21**, not from memory. Reproduce with
the commands in [Running everything](#running-everything).

## How this document is kept honest

Every test name cited below in `backticks` is checked against the tests that actually
exist:

```bash
scripts/testing_matrix.py --list     # every test the repository defines, by suite
scripts/testing_matrix.py --check    # fail if this document cites one that is gone
```

`--check` runs in `make lint`. It answers "does this test exist", not "does it pass" —
deliberately, because a check that needs Docker, a simulator and an emulator to run is
a check nobody runs. Test names in this repository are often whole sentences, so the
checker treats an unmatched sentence as a warning and an unmatched *identifier* —
one shaped like a Go, Python or Swift test method — as a build failure.

## Suites

| Suite | Tests | Command | Needs |
|---|---:|---|---|
| Go unit | 40 | `make test-go` | nothing |
| Go integration | 28 | `make test-go` | PostgreSQL+PostGIS; skips without it |
| ML service (pytest) | 15 | `make test-ml` | creates a venv on first run |
| Dashboard (Vitest) | 84 | `make test-web` | `npm install` |
| ML training | 41 | `make test-train` | `ml/training/requirements.txt` |
| Android unit (JVM) | 47 | `cd android && ./gradlew testDebugUnitTest` | nothing |
| Android instrumented | 20 | `cd android && ./gradlew connectedDebugAndroidTest` | an emulator |
| iOS (XCTest + XCUITest) | 9 | `make test-ios` | a simulator; skips without the stack |
| **Total automated** | **284** | `make test && make mobile` | |
| End-to-end smoke | 33 checks | `make smoke` | the running stack |
| Performance | 4 checks | `make perf` | the stack, seeded to 10,000 |
| Config checks | 5 + matrix | `make lint` | nothing |
| Smoke over TLS | 33 checks | `make smoke-tls` | Docker |

All 270 passed and all 33 smoke checks passed on 2026-08-21. The ML training suite
grew from 29 to 41 on 2026-08-26 (corpus fetch and verification, plus the ONNX thread
contract); those 41 were re-run and pass. The other suites have not been re-run since.

The counts in this table are **runtime results from the runners** — what
`Tests 84 passed` and `ok muraka/backend/...` actually reported.
`scripts/testing_matrix.py --list` counts *declarations* instead, which is a slightly
different number wherever a parameterised case expands: one `it.each` with five cases
is one declaration and five results, and one Go table test with eight subtests is one
function and eight results. The collector exists to resolve citations, not to total the
suites, and it excludes `build/`, `DerivedData/`, `node_modules/` and `.venv/`, which
hold copies of test sources that would otherwise inflate every figure — the ML
virtualenv alone once counted as 11,917 Python tests.

## Status vocabulary

| | Meaning |
|---|---|
| ✅ | Automated test(s) verify the requirement |
| ◐ | Partly automated — the gap is stated |
| ✋ | Verified, but only by a human following a checklist |
| ○ | **No evidence yet** |

## Functional requirements

| ID | Requirement | MoSCoW | Evidence | |
|---|---|---|---|---|
| FR1 | Register, authenticate, enforce roles | Must | `TestContributorsCannotReachResearcherOrAdminRoutes`, `TestResearchersCannotReachAdminRoutes`, `TestEachRoleReachesItsOwnRoutes`, `TestUnauthenticatedRequestsAreRefused`, `TestAGarbageOrForeignTokenIsRefused`, `TestAContributorCannotVerifyEvenTheirOwnSighting`, `TestAContributorCannotReadAnotherContributorsSighting`, `TestReplayingAnotherContributorsIDIsRefused` — plus the argon2id and JWT unit tests | ✅ |
| FR2 | Create a sighting: 1–5 photos, position, time, depth, note | Must | smoke 5; mobile capture screens built and screenshotted (`docs/evidence/mobile/`) | ◐ |
| FR3 | Queue offline, sync automatically | Must | `capturingWithNoNetworkQueuesTheSightingAndKeepsThePhotograph`, `drainingWithNoNetworkLeavesTheRowQueuedAndDoesNotBurnItsAttempts`, `theQueueSurvivesTheProcessDying`, `theQueueDrainsOnceTheNetworkReturns`, `aConnectionLostMidUploadResumesWithoutResendingWhatArrived`; plus the outbox and retry-curve tests | ✅ |
| FR4 | Submission is idempotent; retries never duplicate | Must | `TestReplayingASubmissionCreatesExactlyOneRow` (eight attempts, the outbox give-up threshold), `TestDepthAndNoteSurviveAReplay`; smoke 6 | ✅ |
| FR5 | Classify each photo; record label, confidence, model version | Must | smoke 9, 10, 13; `test_classify_returns_one_patch_per_cell`, `test_label_follows_severity_threshold`, `test_severity_matches_bleached_patch_fraction`, `test_to_dict_shape_matches_go_client_contract`; plus `test_the_exported_onnx_agrees_with_the_pytorch_model`. **Trained model effnetb0-0.1.0 served since 2026-08-26** — 0.8575 accuracy / 0.9027 F2-bleached on the held-out test split, all 33 smoke checks pass with `FAKE_MODE=0` (`docs/evidence/ml/baseline-effnetb0.md`). Field accuracy is still unevidenced: the model has never seen a Maldivian reef (D60) | ✅ |
| FR6 | Verification queue: confirm, correct, reject, audit-logged | Must | `TestRejectingASightingIsRecordedWithItsReasonAndAuthor`, `TestARejectionWithoutAReasonIsRefused`, `TestAnInvalidRejectReasonSaysSoRatherThanClaimingItIsMissing`; smoke 11–14 | ✅ |
| FR7 | Map with clustering, heatmap, filters | Must | 14 `mapStyle` tests incl. `is valid against the MapLibre style specification`, `never lets a national-zoom cluster cover a whole atoll`; smoke 15 | ◐ |
| FR8 | Full provenance per sighting | Must | smoke 13, 15 (CSV carries provenance columns) | ◐ |
| FR9 | Contributor sees own sightings with status | Must | `testSignInShowsTheContributorsOwnSightings`, `testSearchingAndFilteringNarrowsTheHistory`, `testTheScopeBarFiltersWhileSearchIsActive`, `testNoScreenClaimsASightingIsSynced`; `every server status maps to a status the client did not invent`, `a queued row reads as waiting, whatever the server last said` | ✅ |
| FR10 | Admin: roles, bans, site data, model versions | Must | `TestPromotingAUserTakesEffectImmediately`, `TestDemotingAUserRemovesTheirAccess`, `TestBanningAUserStopsThemSigningIn`, `TestBanningAUserStopsTheirExistingSession`, `TestAnAdminCannotBanThemselves`, `TestActivatingAModelVersionLeavesExactlyOneActive`, `TestActivatingAnUnknownModelVersionIs404`, `TestAtollsAreUpsertedRatherThanDuplicated` | ✅ |
| FR11 | Rejected sightings excluded from maps, trends, exports | Must | `TestRejectedSightingsAreExcludedFromMapTrendsAndExport` asserts all three paths; plus the SQL-builder unit tests | ✅ |
| FR12 | Condition trends over time | Should | smoke 15 (trends returns buckets) | ◐ |
| FR13 | CSV export of the filtered set, with provenance | Should | smoke 15 | ✅ |
| FR14 | Queue prioritises low-confidence predictions | Should | `TestTheVerificationQueuePutsLowConfidenceFirst` | ✅ |
| FR15 | Auto-assign sightings to reef sites by polygon | Should | `TestASightingInsideASitePolygonIsAssignedToIt`, `TestCreatingASiteBackfillsSightingsAlreadyInside` | ✅ |
| FR16 | Warn before submission if a photo looks unusable | Could | 6 `photos` tests incl. `flags the 224px dataset crops`, `does not flag real photographs`, `judges on the shorter side, because the frame is square` | ◐ |
| FR17 | Export verified labels as a training set; compare model versions | Could | — | ○ |

### What the functional gaps actually are

- **FR2** — the queue and the sync engine are now well covered, including the offline
  situations (`docs/evidence/mobile/acceptance.md`). What is still not automated is the
  **capture flow itself** on either platform: nothing drives the camera, the position
  fallback or the 1–5 photo limit through the UI.
- **FR7/FR8/FR12** — the map style, the basemap, the condition chip and the patch
  lattice are now covered, but the **views** are not: nothing mounts `QueueView`,
  `ReefMapView` or `SightingDetailView`, so a filter control or a provenance panel could
  break without failing anything. The component harness now exists, so this is work
  rather than groundwork.
- **FR16** — the dashboard's side of the unusable-photograph rule is tested; the
  mobile warning is not.
- **FR17** — no evidence. It is a Could, and it is blocked on the training track: there
  is no second model version to compare against.

Three defects were found by writing these tests rather than by reading the code, which
is the argument for having written them:

1. **A banned user's existing access token kept working.** `requireAuth` verified the
   JWT and never consulted the database, so a ban took up to fifteen minutes to bite —
   on an abuse-response feature. Refresh already refused a non-active account, so the
   ban was permanent but not immediate. `requireAuth` now reads status and role from the
   database, which also makes demotions immediate (D45).
2. **A 422 that told the caller a field was missing when they had sent it.** An invalid
   `rejectReason` reported "is required when rejecting", because the required-field check
   overwrote the more accurate "must be blurry, not_coral, …". Found by being misled by
   it while writing the rejection test.
3. **`make test` had not run the ML or dashboard suites on this machine** (D42).

## Non-functional requirements

| ID | Requirement | MoSCoW | Evidence | |
|---|---|---|---|---|
| NFR1 | ML label visible in the dashboard ≤ 30 s after sync | Must | `make perf` — **0.89 s** on 2026-08-21 (`docs/evidence/performance/`) | ✅ |
| NFR2 | CPU inference ≤ 500 ms per image | Must | **405 ms p50 / 414 ms p95** per photograph (a 25-patch lattice) for the trained effnetb0-0.1.0 model on ONNX/CPU at the deployed `ONNX_THREADS=4` — `docs/evidence/performance/nfr2-backbone-comparison.json`. **Met on the CPU, missed in the container: 822 ms through Docker Desktop's macOS VM** (D67), which no thread count recovers and INT8 quantisation could not fix without costing 14 points of bleached recall (D68). Two earlier figures were wrong for instructive reasons — 381 ms measured the wrong thread count (D64) | ◐ |
| NFR3 | Map interactive at 10,000 sightings; viewport ≤ 2 s | Must | `make perf` — **56 ms** worst of 5 at 10,304 sightings; the check fails if the corpus is smaller | ✅ |
| NFR4 | argon2id, TLS in the demo config, JWT ≤ 15 min | Must | argon2id: `TestHashPasswordIsSaltedPerCall`, `TestHashPasswordProducesVerifiableArgon2idHash`; expiry: `TestParseAccessTokenRejectsExpiredToken`; refresh: `TestRefreshTokenStoresOnlyItsHash`, `TestRefreshTokensAreUnique`; TLS: `make lint` static checks + `make smoke-tls` (33 checks over TLS 1.3) | ✅ |
| NFR5 | Validate, re-encode and strip EXIF from uploads | Must | smoke 8 refuses a non-image | ◐ |
| NFR6 | Capture completable in < 60 s and ≤ 8 taps | Must | **5 taps**, counted from the capture code (6 on the first ever capture, including the permission grant). The **timing** has never been measured | ◐ |
| NFR7 | Contributor app fully functional offline except register/login | Must | the 8 `SyncEngineOfflineTest` cases, `journalsWriteAheadSoAReaderNeverBlocksTheCaptureFlow`, `commitsReachTheStorageMediumBeforeEnqueueReturns`, `DurabilityPragmaTests`; plus a device walkthrough with the radio off — GPS resolved, camera captured (`docs/evidence/mobile/acceptance.md`) | ✅ |
| NFR9 | No component depends on a key-requiring external service | Must | `make mobile-lint` fails on an App Transport Security exception in a release plist; the basemap is committed vector GeoJSON with no tile or glyph server (D22/D23) | ◐ |
| NFR10 | Startable by one documented command; seed by one more | Must | `make up`, `make seed`; `make test`, `make test-ml`, `make test-web` and `make smoke` were all broken before 2026-08-21 and now run (D42) | ◐ |
| NFR11 | 50 concurrent sighting submissions with no error or loss | Should | `make perf` — **0 errors, 0 lost**, 919/s; every client-generated id is read back afterwards | ✅ |
| NFR12 | Request IDs propagated through Go and Python logs | Should | — | ○ |
| NFR13 | ML-only labels visually distinct from expert-verified | Must | `says "expert" when a human decided`, `says "model" when only the classifier has`, `is distinguishable with every colour class stripped`, `marks the two with different classes, so shape and border can differ`; both apps encode it in shape and word too (`docs/evidence/mobile/`) | ✅ |
| NFR14 | Material 3 / Liquid Glass; light and dark on both | Must | `testTheAppearanceToggleDarkensEveryScreen`, `testTheAppearanceChoiceIsRemembered`, `testTheLastRowIsReachableUnderTheFloatingTabBar`, `testThePatchGridCanBeTurnedOffAndStaysOff`; Android night resources verified with `aapt2 dump` (D33) | ✅ |
| NFR15 | Account deletion anonymises rather than deletes, and says so | Should | `TestDeletingAnAccountKeepsTheScienceAndDropsThePerson` | ✅ |
| NFR16 | Training runs reproducible: config-driven, seeded, metrics per run | Should | `test_the_same_seed_gives_the_same_metrics`, `test_a_different_seed_gives_different_metrics`, `test_the_synthetic_split_is_seeded_and_stable`; every run writes `metrics.csv` and `summary.json` | ✅ |

### What the non-functional gaps actually are

- **NFR2** — closed for the architecture, but the first figure was wrong in a way worth
  reading (D64). 381 ms was measured through a default `InferenceSession`, which takes one
  thread per core; the service runs `ONNX_THREADS`, and at the shipped value of 2 the same
  graph measured 477–497 ms p50 and up to 544 ms p95 — **over the budget at the tail in two
  runs of three**. The deployed thread count is now **4**, giving **406 ms p50 / 417 ms p95**
  with roughly 19%
  headroom, and a contract test fails if the benchmark and the compose file ever disagree
  again. This also settles the "drop to MobileNetV3 if it is slow" fallback in
  `ml/README.md`, and D65 closes the backbone comparison: ConvNeXt-Tiny (1,486 ms) and
  EfficientNetV2-S (862 ms) are both far outside the budget. The service's own 22 ms
  figure is still the stub.
- **NFR4** — now complete, but with a caveat the project must state rather than bury:
  the demo certificate is **self-signed**, because NFR9 rules out a certificate
  authority. A browser warns on first visit. That is the honest cost of the key-free
  constraint, not a defect.
- **NFR5** — refusing a non-image is the easy half. Nothing tests the size cap, a
  hostile file (a valid image header with a huge decompressed size), or that EXIF is
  stripped *after* capture time and GPS are extracted — which is a privacy claim the
  report makes.
- **NFR6** — half done. The tap count is now counted from the code rather than claimed:
  **5**, or 6 on the first capture. The **timing** is still unmeasured, and its
  verification method is "usability testing measurement", so a stopwatch and a person is
  what closes it — not another test.
- **NFR7** — closed. Durability was already proven by reading the pragmas back; the
  network half is now eight instrumented tests with the server unreachable, a process
  restart between drains and a connection dropped mid-upload; and the capture screen was
  walked with the radio genuinely off, resolving GPS and taking a photograph. One ANR
  during that walkthrough is recorded as a risk to re-check on physical hardware — the
  launcher ANR'd first, which points at the emulator, but it is not settled.
- **NFR8 is withdrawn, not outstanding** (D70, 2026-08-27). It required a SUS score from
  five recruited users, and it was **self-imposed** — the module brief requires no user
  study under either reading of its contradictory weightings, and the requirements chapter
  needs at least 10 functional and 10 non-functional requirements, which 16 NFRs still
  clears comfortably. The honest alternative was a rushed five-person study behind an
  unresolved ethics process (Q5). Usability is now evidenced by **NFR6** instead — 5 taps,
  counted from the capture code, with the timing still to be measured.
- **NFR12** — no evidence. Request IDs propagated across Go and Python needs log
  assertions rather than a harness.

## Where the tests live

```
backend/internal/{auth,httpapi,storage,store}/*_test.go   40 Go unit tests
backend/internal/httpapi/{harness,rbac,admin,data_integrity}_test.go
                                                          28 Go integration tests
ml/service/tests/test_inference.py                        15 pytest
web/src/lib/{mapStyle,photos,dates}.test.ts               46 Vitest (logic)
web/src/components/*.dom.test.ts                          38 Vitest (components)
android/core/*/src/test/                                  47 JVM unit
android/core/database/src/androidTest/                    12 instrumented
ios/MurakaTests/, ios/MurakaUITests/                       9 XCTest/XCUITest
scripts/smoke_test.py                                     33 end-to-end checks
```

Two suites are worth knowing about specifically, because both were written in response
to a real defect rather than for coverage:

- **`DurabilityPragmaTest` / `DurabilityPragmaTests`** read `journal_mode` and
  `synchronous` back from a real database file. `synchronous = FULL` set from Room's
  `onOpen` is silently overwritten by Android's own WAL configuration, so the obvious
  version of this test passes against a setting that is not in effect (D28).
- **The Go integration harness** gives each test its own database, created and dropped
  around it, and skips rather than fails when PostgreSQL is unreachable. Most of what
  this project promises is implemented *in SQL* — `ST_Covers` containment, `ON CONFLICT
  DO NOTHING` idempotency, `ORDER BY confidence ASC NULLS FIRST` queue order — so those
  claims are untestable without a database, which is why eight requirements sat at "no
  evidence" while 40 unit tests passed.
- **`RFC3339Tests`** exists because Go emits nine fractional digits and PostgreSQL six,
  while `ISO8601FormatStyle` accepts exactly three — every timestamp failed to decode,
  and it presented as "signing in does not work".

## Running everything

```bash
make up                 # the stack
make seed N=2000        # demo data
make test               # Go + ML + dashboard
make smoke              # 33 end-to-end checks against the running stack
make mobile             # Android + iOS unit tests
make mobile-lint        # linters, the status-vocabulary contract, the ATS check
make lint               # this document's citations, plus the demo TLS configuration
make smoke-tls          # the 33 end-to-end checks again, through TLS (NFR4)
make perf               # NFR1, NFR2, NFR3, NFR11
cd android && ./gradlew connectedDebugAndroidTest   # needs an emulator
```

`make test-ios` and the iOS integration suites **skip rather than fail** when the stack
is not running, because a red suite on a machine with no Docker tells nobody anything.
