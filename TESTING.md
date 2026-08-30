# Testing and requirement traceability

`docs/07-requirements.md` closes by asking for a document that links FR/NFR IDs to
test names to results, "so the project's testing chapter can be generated from evidence
rather than memory". This is that document.

It is deliberately unflattering. A traceability matrix whose every row says "covered"
is not evidence, it is decoration - the value is in the rows that say **none**, because
those are the project's honest limitations section and the build's to-do list at the
same time.

Of thirty-two live requirements, **twenty-five** have full automated evidence, six are
partly covered and **one has none at all**. Those figures are tallied from the table
below by `scripts/testing_matrix.py --check`, not written by hand - an early draft of
this paragraph claimed sixteen when the true figure was three, which is exactly the kind
of number a report should never carry unchecked.

They were 20 / 10 / 2 until 2026-08-30, when the three gaps this document had been
recording longest were closed: the dashboard views (FR7, FR8, FR12), upload validation
(NFR5) and cross-service request tracing (NFR12). Two of the three turned out to be
hiding defects rather than merely lacking tests, which is the argument for writing them
and is set out under each below.

**Results below are from a real run on 2026-08-30**, not from memory. Reproduce with
the commands in [Running everything](#running-everything). Every suite except the
Android instrumented tests was re-run that day; those need an emulator and were last
run on 2026-08-21.

## How this document is kept honest

Every test name cited below in `backticks` is checked against the tests that actually
exist:

```bash
scripts/testing_matrix.py --list     # every test the repository defines, by suite
scripts/testing_matrix.py --check    # fail if this document cites one that is gone
```

`--check` runs in `make lint`. It answers "does this test exist", not "does it pass" -
deliberately, because a check that needs Docker, a simulator and an emulator to run is
a check nobody runs. Test names in this repository are often whole sentences, so the
checker treats an unmatched sentence as a warning and an unmatched *identifier* -
one shaped like a Go, Python or Swift test method - as a build failure.

## Suites

| Suite | Tests | Command | Needs |
|---|---:|---|---|
| Go unit + integration | 91 | `make test-go` | PostgreSQL+PostGIS; integration skips without it |
| ML service (pytest) | 15 | `make test-ml` | creates a venv on first run |
| Dashboard (Vitest) | 142 | `make test-web` | `npm install` |
| ML training | 42 | `make test-train` | `ml/training/requirements.txt` |
| Android unit (JVM) | 47 | `make test-android` | nothing |
| Android instrumented | 20 | `cd android && ./gradlew connectedDebugAndroidTest` | an emulator |
| iOS (XCTest + XCUITest) | 9 | `make test-ios` | a simulator; skips without the stack |
| **Total automated** | **366** | `make test && make mobile` | |
| End-to-end smoke | 33 checks | `make smoke` | the running stack |
| Performance | 4 checks | `make perf` | the stack, seeded to 10,000 |
| Config checks | 5 + matrix | `make lint` | nothing |
| Smoke over TLS | 33 checks | `make smoke-tls` | Docker |

**346 of the 366 passed on 2026-08-30 with none failing and none skipped**, along with
all 33 smoke checks. The remaining 20 are the Android instrumented suite, which needs an
emulator and was not re-run.

Two of those figures moved for reasons worth recording rather than quietly restating.

**Go went from 68 to 91** and the dashboard **from 84 to 142**: the upload suite
(NFR5), the tracing suite (NFR12) and the three view suites (FR7/FR8/FR12) are new, and
each of the three gaps they close is described below.

**Android was never running 47 tests.** `make test-android` invoked
`./gradlew testDebugUnitTest`, and `core:common` and `core:model` are plain JVM modules
that never get the Android variant tasks - so the target ran **6** of the 47 and
reported success, and had done since it was written. The counts in this document were
right because the tests exist and pass; the command offered for reproducing them was
not. It now runs `./gradlew test`, which covers both kinds, and all 47 pass. This is the
sharpest illustration of why the document tallies from runs rather than from memory: a
green build is not evidence that anything ran.

The counts in this table are **runtime results from the runners** - what
`Tests 84 passed` and `ok muraka/backend/...` actually reported.
`scripts/testing_matrix.py --list` counts *declarations* instead, which is a slightly
different number wherever a parameterised case expands: one `it.each` with five cases
is one declaration and five results, and one Go table test with eight subtests is one
function and eight results. The collector exists to resolve citations, not to total the
suites, and it excludes `build/`, `DerivedData/`, `node_modules/` and `.venv/`, which
hold copies of test sources that would otherwise inflate every figure - the ML
virtualenv alone once counted as 11,917 Python tests.

## Status vocabulary

| | Meaning |
|---|---|
| ✅ | Automated test(s) verify the requirement |
| ◐ | Partly automated - the gap is stated |
| ✋ | Verified, but only by a human following a checklist |
| ○ | **No evidence yet** |

## Functional requirements

| ID | Requirement | MoSCoW | Evidence | |
|---|---|---|---|---|
| FR1 | Register, authenticate, enforce roles | Must | `TestContributorsCannotReachResearcherOrAdminRoutes`, `TestResearchersCannotReachAdminRoutes`, `TestEachRoleReachesItsOwnRoutes`, `TestUnauthenticatedRequestsAreRefused`, `TestAGarbageOrForeignTokenIsRefused`, `TestAContributorCannotVerifyEvenTheirOwnSighting`, `TestAContributorCannotReadAnotherContributorsSighting`, `TestReplayingAnotherContributorsIDIsRefused` - plus the argon2id and JWT unit tests | ✅ |
| FR2 | Create a sighting: 1-5 photos, position, time, depth, note | Must | smoke 5; mobile capture screens built and screenshotted (`docs/evidence/mobile/`) | ◐ |
| FR3 | Queue offline, sync automatically | Must | `capturingWithNoNetworkQueuesTheSightingAndKeepsThePhotograph`, `drainingWithNoNetworkLeavesTheRowQueuedAndDoesNotBurnItsAttempts`, `theQueueSurvivesTheProcessDying`, `theQueueDrainsOnceTheNetworkReturns`, `aConnectionLostMidUploadResumesWithoutResendingWhatArrived`; plus the outbox and retry-curve tests | ✅ |
| FR4 | Submission is idempotent; retries never duplicate | Must | `TestReplayingASubmissionCreatesExactlyOneRow` (eight attempts, the outbox give-up threshold), `TestDepthAndNoteSurviveAReplay`; smoke 6 | ✅ |
| FR5 | Classify each photo; record label, confidence, model version | Must | smoke 9, 10, 13; `test_classify_returns_one_patch_per_cell`, `test_label_follows_severity_threshold`, `test_severity_matches_bleached_patch_fraction`, `test_to_dict_shape_matches_go_client_contract`; plus `test_the_exported_onnx_agrees_with_the_pytorch_model`. **Trained model effnetb0-0.1.0 served since 2026-08-26** - 0.8575 accuracy / 0.9027 F2-bleached on the held-out test split, all 33 smoke checks pass with `FAKE_MODE=0` (`docs/evidence/ml/baseline-effnetb0.md`). Field accuracy is still unevidenced: the model has never seen a Maldivian reef (D60) | ✅ |
| FR6 | Verification queue: confirm, correct, reject, audit-logged | Must | `TestRejectingASightingIsRecordedWithItsReasonAndAuthor`, `TestARejectionWithoutAReasonIsRefused`, `TestAnInvalidRejectReasonSaysSoRatherThanClaimingItIsMissing`; smoke 11-14 | ✅ |
| FR7 | Map with clustering, heatmap, filters | Must | 14 `mapStyle` tests incl. `is valid against the MapLibre style specification`, `never lets a national-zoom cluster cover a whole atoll`; 16 `ReefMapView` tests incl. `asks for the bounding box the map is actually showing`, `applies the verified-only filter to both queries`, `re-queries after a pan, and coalesces a burst into one request`; smoke 15 | ✅ |
| FR8 | Full provenance per sighting | Must | 21 `SightingDetailView` tests incl. `shows the model label, its confidence and the version that produced it`, `keeps the model's original claim visible after it was overruled`, `names who decided, what they decided, and when`; smoke 13, 15 (CSV carries provenance columns) | ✅ |
| FR9 | Contributor sees own sightings with status | Must | `testSignInShowsTheContributorsOwnSightings`, `testSearchingAndFilteringNarrowsTheHistory`, `testTheScopeBarFiltersWhileSearchIsActive`, `testNoScreenClaimsASightingIsSynced`; `every server status maps to a status the client did not invent`, `a queued row reads as waiting, whatever the server last said` | ✅ |
| FR10 | Admin: roles, bans, site data, model versions | Must | `TestPromotingAUserTakesEffectImmediately`, `TestDemotingAUserRemovesTheirAccess`, `TestBanningAUserStopsThemSigningIn`, `TestBanningAUserStopsTheirExistingSession`, `TestAnAdminCannotBanThemselves`, `TestActivatingAModelVersionLeavesExactlyOneActive`, `TestActivatingAnUnknownModelVersionIs404`, `TestAtollsAreUpsertedRatherThanDuplicated` | ✅ |
| FR11 | Rejected sightings excluded from maps, trends, exports | Must | `TestRejectedSightingsAreExcludedFromMapTrendsAndExport` asserts all three paths; plus the SQL-builder unit tests | ✅ |
| FR12 | Condition trends over time | Should | `draws one bar per bucket`, `carries the bleached share as well as the volume`, `changes the trend interval without changing the markers`, `applies the capture-date range to both queries`; smoke 15 (trends returns buckets) | ✅ |
| FR13 | CSV export of the filtered set, with provenance | Should | smoke 15 | ✅ |
| FR14 | Queue prioritises low-confidence predictions | Should | `TestTheVerificationQueuePutsLowConfidenceFirst` | ✅ |
| FR15 | Auto-assign sightings to reef sites by polygon | Should | `TestASightingInsideASitePolygonIsAssignedToIt`, `TestCreatingASiteBackfillsSightingsAlreadyInside` | ✅ |
| FR16 | Warn before submission if a photo looks unusable | Could | 6 `photos` tests incl. `flags the 224px dataset crops`, `does not flag real photographs`, `judges on the shorter side, because the frame is square` | ◐ |
| FR17 | Export verified labels as a training set; compare model versions | Could | - | ○ |

### What the functional gaps actually are

- **FR2** - the queue and the sync engine are now well covered, including the offline
  situations (`docs/evidence/mobile/acceptance.md`). What is still not automated is the
  **capture flow itself** on either platform: nothing drives the camera, the position
  fallback or the 1-5 photo limit through the UI.
- **FR7/FR8/FR12** - closed on 2026-08-30. All three views are now mounted: 58 tests
  across `QueueView.dom.test.ts`, `SightingDetailView.dom.test.ts` and
  `ReefMapView.dom.test.ts`, aimed at the failures the API cannot catch because the API
  would be behaving correctly while storing the wrong thing - a correction sending the
  model's label instead of the reviewer's, a filter reaching the marker query but not
  the trend query. MapLibre is faked; it needs a WebGL context no headless DOM provides,
  and the behaviour under test is the view's. Each suite was checked by mutation:
  breaking the view three ways failed the tests that name those behaviours.

  Writing them found a leak in `QueueView`. `onMounted` awaited the detail load after the queue load, but loading the queue had already moved `current` off null and
  triggered the watcher that does the same thing - two detail fetches and two image
  downloads raced on every mount, and whichever object URL lost was overwritten without
  being revoked.
- **FR16** - the dashboard's side of the unusable-photograph rule is tested; the
  mobile warning is not.
- **FR17** - no evidence. It is a Could, and it is blocked on the training track: there
  is no second model version to compare against.

Three defects were found by writing these tests rather than by reading the code, which
is the argument for having written them:

1. **A banned user's existing access token kept working.** `requireAuth` verified the
   JWT and never consulted the database, so a ban took up to fifteen minutes to bite -
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
| NFR1 | ML label visible in the dashboard ≤ 30 s after sync | Must | `make perf` - **2.2 s** on 2026-08-30 (`docs/evidence/performance/`) | ✅ |
| NFR2 | CPU inference ≤ 500 ms per image | Must | **405 ms p50 / 414 ms p95** per photograph (a 25-patch lattice) for the trained effnetb0-0.1.0 model on ONNX/CPU at the deployed `ONNX_THREADS=4` - `docs/evidence/performance/nfr2-backbone-comparison.json`. **Met on the CPU, missed in the container: 931 ms p50 / 992 ms p95 through Docker Desktop's macOS VM** (D67, re-measured 2026-08-30; the two field evaluations independently recorded 897 and 901 ms p50 over 2,004 photographs), which no thread count recovers and INT8 quantisation could not fix without costing 14 points of bleached recall (D68). Two earlier figures were wrong for instructive reasons - 381 ms measured the wrong thread count (D64) | ◐ |
| NFR3 | Map interactive at 10,000 sightings; viewport ≤ 2 s | Must | `make perf` - **80 ms** worst of 5 at 12,763 sightings; the check fails if the corpus is smaller | ✅ |
| NFR4 | argon2id, TLS in the demo config, JWT ≤ 15 min | Must | argon2id: `TestHashPasswordIsSaltedPerCall`, `TestHashPasswordProducesVerifiableArgon2idHash`; expiry: `TestParseAccessTokenRejectsExpiredToken`; refresh: `TestRefreshTokenStoresOnlyItsHash`, `TestRefreshTokensAreUnique`; TLS: `make lint` static checks + `make smoke-tls` (33 checks over TLS 1.3) | ✅ |
| NFR5 | Validate, re-encode and strip EXIF from uploads | Must | 10 upload tests incl. `TestUploadRefusesADecompressionBomb` (asserted on allocation, not only status), `TestUploadRejectsAFileOverTheSizeCap`, `TestUploadKeepsExifFactsAndStripsTheRest`; 11 `internal/imagemeta` tests incl. `TestMalformedExifIsSurvivedRatherThanTrusted`, `TestAPositionWithNoHemisphereIsDiscarded`; smoke 8 | ✅ |
| NFR6 | Capture completable in < 60 s and ≤ 8 taps | Must | **5 taps**, counted from the capture code (6 on the first ever capture, including the permission grant). The **timing** has never been measured | ◐ |
| NFR7 | Contributor app fully functional offline except register/login | Must | the 8 `SyncEngineOfflineTest` cases, `journalsWriteAheadSoAReaderNeverBlocksTheCaptureFlow`, `commitsReachTheStorageMediumBeforeEnqueueReturns`, `DurabilityPragmaTests`; plus a device walkthrough with the radio off - GPS resolved, camera captured (`docs/evidence/mobile/acceptance.md`) | ✅ |
| NFR9 | No component depends on a key-requiring external service | Must | `make mobile-lint` fails on an App Transport Security exception in a release plist; the basemap is committed vector GeoJSON with no tile or glyph server (D22/D23) | ◐ |
| NFR10 | Startable by one documented command; seed by one more | Must | `make up`, `make seed`; `make test`, `make test-ml`, `make test-web` and `make smoke` were all broken before 2026-08-21 and now run (D42) | ◐ |
| NFR11 | 50 concurrent sighting submissions with no error or loss | Should | `make perf` - **0 errors, 0 lost across 150 submissions** (3 rounds of 50, one warm-up discarded); every client-generated id is read back afterwards. Throughput is reported as a warm range, 1,081-1,429/s, because it is not what the requirement asks and a single burst is not a measurement of it - see below | ✅ |
| NFR12 | Request IDs propagated through Go and Python logs | Should | `TestTheClassificationCallCarriesACorrelationID`, `TestARetriedJobIsDistinguishableFromItsFirstAttempt`, `TestAnInboundRequestIDIsReusedRatherThanReplaced`, `TestEveryResponseCarriesARequestIDEvenWithoutOne` | ✅ |
| NFR13 | ML-only labels visually distinct from expert-verified | Must | `says "expert" when a human decided`, `says "model" when only the classifier has`, `is distinguishable with every colour class stripped`, `marks the two with different classes, so shape and border can differ`; both apps encode it in shape and word too (`docs/evidence/mobile/`) | ✅ |
| NFR14 | Material 3 / Liquid Glass; light and dark on both | Must | `testTheAppearanceToggleDarkensEveryScreen`, `testTheAppearanceChoiceIsRemembered`, `testTheLastRowIsReachableUnderTheFloatingTabBar`, `testThePatchGridCanBeTurnedOffAndStaysOff`; Android night resources verified with `aapt2 dump` (D33) | ✅ |
| NFR15 | Account deletion anonymises rather than deletes, and says so | Should | `TestDeletingAnAccountKeepsTheScienceAndDropsThePerson` | ✅ |
| NFR16 | Training runs reproducible: config-driven, seeded, metrics per run | Should | `test_the_same_seed_gives_the_same_metrics`, `test_a_different_seed_gives_different_metrics`, `test_the_synthetic_split_is_seeded_and_stable`; every run writes `metrics.csv` and `summary.json` | ✅ |

### What the non-functional gaps actually are

- **NFR2** - closed for the architecture, but the first figure was wrong in a way worth
  reading (D64). 381 ms was measured through a default `InferenceSession`, which takes one
  thread per core; the service runs `ONNX_THREADS`, and at the shipped value of 2 the same
  graph measured 477-497 ms p50 and up to 544 ms p95 - **over the budget at the tail in two
  runs of three**. The deployed thread count is now **4**, giving **406 ms p50 / 417 ms p95**
  with roughly 19%
  headroom, and a contract test fails if the benchmark and the compose file ever disagree
  again. This also settles the "drop to MobileNetV3 if it is slow" fallback in
  `ml/README.md`, and D65 closes the backbone comparison: ConvNeXt-Tiny (1,486 ms) and
  EfficientNetV2-S (862 ms) are both far outside the budget. The service's own 22 ms
  figure is still the stub.
- **NFR4** - now complete, but with a caveat the project must state rather than bury:
  the demo certificate is **self-signed**, because NFR9 rules out a certificate
  authority. A browser warns on first visit. That is the honest cost of the key-free
  constraint, not a defect.
- **NFR5** - closed on 2026-08-30, and it was hiding two defects rather than one gap.

  **The hostile file was not hypothetical.** A PNG's IHDR declares its dimensions and
  the decoder sizes its pixel buffer from that before reading a scanline, so the size
  checks - all of which measure bytes on the wire - passed a file that costs gigabytes
  to decode. Measured against the handler as it stood: **a 77-byte upload allocated
  244 MiB** at 8000x8000, and 30000x30000 would have asked for 3.6 GB. The handler now
  reads the header first and refuses anything over 80 MP or 20,000 pixels on a side.

  **The EXIF clause was satisfied by doing nothing.** NFR5 says "stripping EXIF *after*
  extracting capture time and GPS". The stripping was implemented; the extracting never
  was. The exif_captured_at and exif_location columns have existed since the first
  migration and were always NULL, so the ordering held only in the sense that discarding
  data trivially satisfies any claim about handling it first. `internal/imagemeta` now
  reads both, before the re-encode destroys them. The test asserts both halves at once -
  the row carries what the camera recorded, and the bytes served back no longer do -
  because each half is trivially satisfiable alone.
- **NFR6** - half done. The tap count is now counted from the code rather than claimed:
  **5**, or 6 on the first capture. The **timing** is still unmeasured, and its
  verification method is "usability testing measurement", so a stopwatch and a person is
  what closes it - not another test.
- **NFR7** - closed. Durability was already proven by reading the pragmas back; the
  network half is now eight instrumented tests with the server unreachable, a process
  restart between drains and a connection dropped mid-upload; and the capture screen was
  walked with the radio genuinely off, resolving GPS and taking a photograph. One ANR
  during that walkthrough is recorded as a risk to re-check on physical hardware - the
  launcher ANR'd first, which points at the emulator, but it is not settled.
- **NFR8 is withdrawn, not outstanding** (D70, 2026-08-27). It required a SUS score from
  five recruited users, and it was **self-imposed** - the module brief requires no user
  study under either reading of its contradictory weightings, and the requirements chapter
  needs at least 10 functional and 10 non-functional requirements, which 16 NFRs still
  clears comfortably. The honest alternative was a rushed five-person study behind an
  unresolved ethics process (Q5). Usability is now evidenced by **NFR6** instead - 5 taps,
  counted from the capture code, with the timing still to be measured.
- **NFR11** - the requirement (no error, no data loss) was always met, but the *throughput*
  figure beside it was one sample of a very wide spread. Consecutive bursts against the same
  warm stack ranged from 90 to 1,551 submissions/second: the first burst after a restart pays
  for an empty connection pool and a cold page cache, and the 919/s once quoted in the project
  landed in the middle of that by chance. The harness now discards a warm-up round and reports
  min/median/max over three measured rounds, and the project quotes the error and loss counts
  rather than the rate.
- **NFR12** - closed on 2026-08-30, and writing the assertion showed the requirement was
  implemented for a path nothing uses. The middleware puts a correlation id on inbound
  HTTP and `mlclient` forwards it, which covers a request that calls the classifier
  synchronously - and no request does. Classification runs in the **worker**, draining a
  queue with no inbound request on the stack, so every call the system had ever made to
  the Python service went out with no `X-Request-ID`, and every Python log line for a
  graded photograph recorded `request_id=None`. The worker now derives one from the job
  id and attempt number, distinct across retries so a retry is not mistaken for a
  duplicated log line. The tests assert the header on the wire rather than words in a
  log: a log line proves the Go side meant to correlate, the header proves it can.

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
  this project promises is implemented *in SQL* - `ST_Covers` containment, `ON CONFLICT
  DO NOTHING` idempotency, `ORDER BY confidence ASC NULLS FIRST` queue order - so those
  claims are untestable without a database, which is why eight requirements sat at "no
  evidence" while 40 unit tests passed.
- **`RFC3339Tests`** exists because Go emits nine fractional digits and PostgreSQL six,
  while `ISO8601FormatStyle` accepts exactly three - every timestamp failed to decode,
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
