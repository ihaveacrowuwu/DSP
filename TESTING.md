# Testing and requirement traceability

`docs/07-requirements.md` closes by asking for a document that links FR/NFR IDs to
test names to results, "so the project's testing chapter can be generated from evidence
rather than memory". This is that document.

It is deliberately unflattering. A traceability matrix whose every row says "covered"
is not evidence, it is decoration — the value is in the rows that say **none**, because
those are the project's honest limitations section and the build's to-do list at the
same time.

Of thirty-three requirements, **three** have full automated evidence, nineteen are
partly covered, one rests on a human checklist and **ten have none at all**. Those
figures are tallied from the table below by `scripts/testing_matrix.py --check`, not
written by hand — the first draft of this paragraph claimed sixteen, which was wrong by
a factor of five and is exactly the kind of number a report should never carry
unchecked.

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
| ML service (pytest) | 15 | `make test-ml` | creates a venv on first run |
| Dashboard (Vitest) | 20 | `make test-web` | `npm install` |
| Android unit (JVM) | 47 | `cd android && ./gradlew testDebugUnitTest` | nothing |
| Android instrumented | 12 | `cd android && ./gradlew connectedDebugAndroidTest` | an emulator |
| iOS (XCTest + XCUITest) | 9 | `make test-ios` | a simulator; skips without the stack |
| **Total automated** | **143** | `make test && make mobile` | |
| End-to-end smoke | 33 checks | `make smoke` | the running stack |

All 143 passed and all 33 smoke checks passed on 2026-08-21.

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
| FR1 | Register, authenticate, enforce roles | Must | `TestHashPasswordProducesVerifiableArgon2idHash`, `TestVerifyPasswordRejectsWrongPassword`, `TestVerifyPasswordRejectsMalformedHashes`, `TestAccessTokenRoundTripCarriesSubjectAndRole`, `TestParseAccessTokenRejectsForeignSignature`, `TestParseAccessTokenRejectsGarbage`, `TestParseAccessTokenRejectsWrongIssuer`, `TestRoleCapabilities`; smoke 1–4 | ◐ |
| FR2 | Create a sighting: 1–5 photos, position, time, depth, note | Must | smoke 5; mobile capture screens built and screenshotted (`docs/evidence/mobile/`) | ◐ |
| FR3 | Queue offline, sync automatically | Must | `enqueuesASightingWithItsPhotographsAtomically`, `backoffKeepsARowOutOfTheQueueUntilItsTimeComes`, `theAttemptCounterOnlyEverIncreases`, `requeueingClearsTheFailureSoAContributorsRetryActuallyRetries`, `eight attempts is the give-up threshold`, `jitter stays within twenty percent, in both directions` | ◐ |
| FR4 | Submission is idempotent; retries never duplicate | Must | smoke 6 (replays an identical submission); `ids are unique across a burst`, `generated ids round-trip through the string form the API expects` | ◐ |
| FR5 | Classify each photo; record label, confidence, model version | Must | smoke 9, 10, 13; `test_classify_returns_one_patch_per_cell`, `test_label_follows_severity_threshold`, `test_severity_matches_bleached_patch_fraction`, `test_confidences_are_probabilities`, `test_to_dict_shape_matches_go_client_contract` | ◐ |
| FR6 | Verification queue: confirm, correct, reject, audit-logged | Must | smoke 11–14 | ◐ |
| FR7 | Map with clustering, heatmap, filters | Must | 14 `mapStyle` tests incl. `is valid against the MapLibre style specification`, `never lets a national-zoom cluster cover a whole atoll`; smoke 15 | ◐ |
| FR8 | Full provenance per sighting | Must | smoke 13, 15 (CSV carries provenance columns) | ◐ |
| FR9 | Contributor sees own sightings with status | Must | `testSignInShowsTheContributorsOwnSightings`, `testSearchingAndFilteringNarrowsTheHistory`, `testTheScopeBarFiltersWhileSearchIsActive`, `testNoScreenClaimsASightingIsSynced`; `every server status maps to a status the client did not invent`, `a queued row reads as waiting, whatever the server last said` | ✅ |
| FR10 | Admin: roles, bans, site data, model versions | Must | smoke 11 promotes a contributor to researcher | ○ |
| FR11 | Rejected sightings excluded from maps, trends, exports | Must | `TestFilterExcludesRejectedByDefault`, `TestFilterCanIncludeRejectedExplicitly`, `TestFilterVerifiedOnlyMatchesExpertDecisions` | ◐ |
| FR12 | Condition trends over time | Should | smoke 15 (trends returns buckets) | ◐ |
| FR13 | CSV export of the filtered set, with provenance | Should | smoke 15 | ✅ |
| FR14 | Queue prioritises low-confidence predictions | Should | — | ○ |
| FR15 | Auto-assign sightings to reef sites by polygon | Should | — | ○ |
| FR16 | Warn before submission if a photo looks unusable | Could | 6 `photos` tests incl. `flags the 224px dataset crops`, `does not flag real photographs`, `judges on the shorter side, because the frame is square` | ◐ |
| FR17 | Export verified labels as a training set; compare model versions | Could | — | ○ |

### What the functional gaps actually are

- **FR1** — the unit tests prove the crypto and the token rules; smoke proves one role
  guard on one endpoint. What is missing is the **matrix**: every role against every
  protected endpoint, including a contributor attempting a researcher's verification
  and an admin-only route. A single guard passing is not RBAC being enforced.
- **FR2/FR3** — the queue's *behaviour* is well covered against real SQLite. The
  **capture flow itself** is not automated on either platform, and the offline half of
  the checklist in `mobile-shared/README.md` has never been walked (see NFR7).
- **FR4** — proven end-to-end by smoke, but there is no Go-level test, so a regression
  shows up only when the whole stack is running.
- **FR6** — confirm and correct are covered; **reject is not**, and nothing asserts the
  audit log records *who* decided *what*, which is the half FR6 exists for.
- **FR7/FR8/FR12** — everything is verified at the style-and-API level. No test renders
  a component, so a filter control or a provenance panel could break without failing
  anything.
- **FR10, FR14, FR15, FR17** — no evidence at all. FR10 and FR14 are the two Musts and
  Shoulds most exposed here; FR15 needs PostGIS containment tests, which is the only
  place the project's choice of PostGIS is load-bearing and currently untested.

## Non-functional requirements

| ID | Requirement | MoSCoW | Evidence | |
|---|---|---|---|---|
| NFR1 | ML label visible in the dashboard ≤ 30 s after sync | Must | measured ~1.5 s ad hoc; smoke reports the inference time per run | ◐ |
| NFR2 | CPU inference ≤ 500 ms per image | Must | smoke run 2026-08-21 reported `inference=320ms` (fake model) | ◐ |
| NFR3 | Map interactive at 10,000 sightings; viewport ≤ 2 s | Must | measured 22 ms ad hoc | ◐ |
| NFR4 | argon2id, TLS in the demo config, JWT ≤ 15 min | Must | argon2id: `TestHashPasswordIsSaltedPerCall`, `TestHashPasswordProducesVerifiableArgon2idHash`; expiry: `TestParseAccessTokenRejectsExpiredToken`; refresh: `TestRefreshTokenStoresOnlyItsHash`, `TestRefreshTokensAreUnique` | ◐ |
| NFR5 | Validate, re-encode and strip EXIF from uploads | Must | smoke 8 refuses a non-image | ◐ |
| NFR6 | Capture completable in < 60 s and ≤ 8 taps | Must | — | ○ |
| NFR7 | Contributor app fully functional offline except register/login | Must | `journalsWriteAheadSoAReaderNeverBlocksTheCaptureFlow`, `commitsReachTheStorageMediumBeforeEnqueueReturns`, `DurabilityPragmaTests` | ◐ |
| NFR8 | SUS ≥ 70 from ≥ 5 users | Must | — | ○ |
| NFR9 | No component depends on a key-requiring external service | Must | `make mobile-lint` fails on an App Transport Security exception in a release plist; the basemap is committed vector GeoJSON with no tile or glyph server (D22/D23) | ◐ |
| NFR10 | Startable by one documented command; seed by one more | Must | `make up`, `make seed`; `make test`, `make test-ml`, `make test-web` and `make smoke` were all broken before 2026-08-21 and now run (D42) | ◐ |
| NFR11 | 50 concurrent sighting submissions with no error or loss | Should | — | ○ |
| NFR12 | Request IDs propagated through Go and Python logs | Should | — | ○ |
| NFR13 | ML-only labels visually distinct from expert-verified | Must | `paints unassessed sightings off the condition scale entirely`, `draws worse condition on top`; both apps encode it in shape and word, not colour (`docs/evidence/mobile/`) | ✋ |
| NFR14 | Material 3 / Liquid Glass; light and dark on both | Must | `testTheAppearanceToggleDarkensEveryScreen`, `testTheAppearanceChoiceIsRemembered`, `testTheLastRowIsReachableUnderTheFloatingTabBar`, `testThePatchGridCanBeTurnedOffAndStaysOff`; Android night resources verified with `aapt2 dump` (D33) | ✅ |
| NFR15 | Account deletion anonymises rather than deletes, and says so | Should | — | ○ |
| NFR16 | Training runs reproducible: config-driven, seeded, metrics per run | Should | — | ○ |

### What the non-functional gaps actually are

- **NFR1/NFR2/NFR3** — all three have a number, and none has a **harness**. "Measured
  ~1.5 s" is a memory, not evidence; the project needs a command that prints the figure
  and a recorded run. NFR2's 320 ms is also the *fake* model, so it says nothing about
  the real one.
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
- **NFR11/NFR12/NFR15/NFR16** — no evidence. NFR16 is blocked on the ML training track
  not existing yet.

## Where the tests live

```
backend/internal/{auth,httpapi,storage,store}/*_test.go   40 Go unit tests
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
