# Testing and requirement traceability

`docs/07-requirements.md` closes by asking for a document that links FR/NFR IDs to
test names to results, "so the project's testing chapter can be generated from evidence
rather than memory". This is that document.

It is deliberately unflattering. A traceability matrix whose every row says "covered"
is not evidence, it is decoration — the value is in the rows that say **none**, because
those are the project's honest limitations section and the build's to-do list at the
same time.

Of thirty-three requirements, **fourteen** have full automated evidence, thirteen are
partly covered, one rests on a human checklist and **five have none at all**. Those
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
| Go integration | 26 | `make test-go` | PostgreSQL+PostGIS; skips without it |
| ML service (pytest) | 15 | `make test-ml` | creates a venv on first run |
| Dashboard (Vitest) | 20 | `make test-web` | `npm install` |
| Android unit (JVM) | 47 | `cd android && ./gradlew testDebugUnitTest` | nothing |
| Android instrumented | 12 | `cd android && ./gradlew connectedDebugAndroidTest` | an emulator |
| iOS (XCTest + XCUITest) | 9 | `make test-ios` | a simulator; skips without the stack |
| **Total automated** | **169** | `make test && make mobile` | |
| End-to-end smoke | 33 checks | `make smoke` | the running stack |
| Performance | 4 checks | `make perf` | the stack, seeded to 10,000 |

All 169 passed and all 33 smoke checks passed on 2026-08-21.

The counts are not hand-maintained — `scripts/testing_matrix.py --list` prints them,
and it excludes `build/`, `DerivedData/` and `node_modules/`, which contain copies of
test sources that would otherwise inflate every number.

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
| FR3 | Queue offline, sync automatically | Must | `enqueuesASightingWithItsPhotographsAtomically`, `backoffKeepsARowOutOfTheQueueUntilItsTimeComes`, `theAttemptCounterOnlyEverIncreases`, `requeueingClearsTheFailureSoAContributorsRetryActuallyRetries`, `eight attempts is the give-up threshold`, `jitter stays within twenty percent, in both directions` | ◐ |
| FR4 | Submission is idempotent; retries never duplicate | Must | `TestReplayingASubmissionCreatesExactlyOneRow` (eight attempts, the outbox give-up threshold), `TestDepthAndNoteSurviveAReplay`; smoke 6 | ✅ |
| FR5 | Classify each photo; record label, confidence, model version | Must | smoke 9, 10, 13; `test_classify_returns_one_patch_per_cell`, `test_label_follows_severity_threshold`, `test_severity_matches_bleached_patch_fraction`, `test_confidences_are_probabilities`, `test_to_dict_shape_matches_go_client_contract` | ◐ |
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

- **FR2/FR3** — the queue's *behaviour* is well covered against real SQLite. The
  **capture flow itself** is not automated on either platform, and the offline half of
  the checklist in `mobile-shared/README.md` has never been walked (see NFR7).
- **FR7/FR8/FR12** — everything is verified at the style-and-API level. No test renders
  a component, so a filter control or a provenance panel could break without failing
  anything.
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
| NFR2 | CPU inference ≤ 500 ms per image | Must | `make perf` — **22 ms**, but against the service's **fake mode**; says nothing about a trained model | ◐ |
| NFR3 | Map interactive at 10,000 sightings; viewport ≤ 2 s | Must | `make perf` — **56 ms** worst of 5 at 10,304 sightings; the check fails if the corpus is smaller | ✅ |
| NFR4 | argon2id, TLS in the demo config, JWT ≤ 15 min | Must | argon2id: `TestHashPasswordIsSaltedPerCall`, `TestHashPasswordProducesVerifiableArgon2idHash`; expiry: `TestParseAccessTokenRejectsExpiredToken`; refresh: `TestRefreshTokenStoresOnlyItsHash`, `TestRefreshTokensAreUnique` | ◐ |
| NFR5 | Validate, re-encode and strip EXIF from uploads | Must | smoke 8 refuses a non-image | ◐ |
| NFR6 | Capture completable in < 60 s and ≤ 8 taps | Must | — | ○ |
| NFR7 | Contributor app fully functional offline except register/login | Must | `journalsWriteAheadSoAReaderNeverBlocksTheCaptureFlow`, `commitsReachTheStorageMediumBeforeEnqueueReturns`, `DurabilityPragmaTests` | ◐ |
| NFR8 | SUS ≥ 70 from ≥ 5 users | Must | — | ○ |
| NFR9 | No component depends on a key-requiring external service | Must | `make mobile-lint` fails on an App Transport Security exception in a release plist; the basemap is committed vector GeoJSON with no tile or glyph server (D22/D23) | ◐ |
| NFR10 | Startable by one documented command; seed by one more | Must | `make up`, `make seed`; `make test`, `make test-ml`, `make test-web` and `make smoke` were all broken before 2026-08-21 and now run (D42) | ◐ |
| NFR11 | 50 concurrent sighting submissions with no error or loss | Should | `make perf` — **0 errors, 0 lost**, 919/s; every client-generated id is read back afterwards | ✅ |
| NFR12 | Request IDs propagated through Go and Python logs | Should | — | ○ |
| NFR13 | ML-only labels visually distinct from expert-verified | Must | `paints unassessed sightings off the condition scale entirely`, `draws worse condition on top`; both apps encode it in shape and word, not colour (`docs/evidence/mobile/`) | ✋ |
| NFR14 | Material 3 / Liquid Glass; light and dark on both | Must | `testTheAppearanceToggleDarkensEveryScreen`, `testTheAppearanceChoiceIsRemembered`, `testTheLastRowIsReachableUnderTheFloatingTabBar`, `testThePatchGridCanBeTurnedOffAndStaysOff`; Android night resources verified with `aapt2 dump` (D33) | ✅ |
| NFR15 | Account deletion anonymises rather than deletes, and says so | Should | `TestDeletingAnAccountKeepsTheScienceAndDropsThePerson` | ✅ |
| NFR16 | Training runs reproducible: config-driven, seeded, metrics per run | Should | — | ○ |

### What the non-functional gaps actually are

- **NFR2** — the only one of the four performance numbers still short of evidence, and
  the gap is not the harness but the model: 22 ms measures a deterministic stub. It
  cannot be closed until the training track produces something to measure.
- **NFR4** — two thirds done. **There is no TLS anywhere**, which is the single
  clearest failure against a Must in this table.
- **NFR5** — refusing a non-image is the easy half. Nothing tests the size cap, a
  hostile file (a valid image header with a huge decompressed size), or that EXIF is
  stripped *after* capture time and GPS are extracted — which is a privacy claim the
  report makes.
- **NFR6** — the capture flow is five taps by construction and the code makes that
  checkable, but nobody has held a stopwatch. Unmeasured is unmeasured.
- **NFR7** — durability is proven properly, on both platforms, by reading the pragmas
  back rather than trusting that setting them worked. The **network** half is not: no
  scenario runs with the API unreachable.
- **NFR8** — a human-subjects study. Not automatable and not started; the project needs
  to either run it or narrow the claim.
- **NFR12/NFR16** — no evidence. NFR16 is blocked on the ML training track not existing
  yet. NFR12 (request IDs propagated across Go and Python) needs log assertions rather
  than a harness.

## Where the tests live

```
backend/internal/{auth,httpapi,storage,store}/*_test.go   40 Go unit tests
backend/internal/httpapi/{harness,rbac,admin,data_integrity}_test.go
                                                          26 Go integration tests
ml/service/tests/test_inference.py                        15 pytest
web/src/lib/{mapStyle,photos}.test.ts                     20 Vitest
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
make lint               # the above plus this document's citations
cd android && ./gradlew connectedDebugAndroidTest   # needs an emulator
```

`make test-ios` and the iOS integration suites **skip rather than fail** when the stack
is not running, because a red suite on a machine with no Docker tells nobody anything.
