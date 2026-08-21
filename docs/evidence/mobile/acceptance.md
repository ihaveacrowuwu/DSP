# Mobile acceptance checklist — results

The checklist at the end of [`mobile-shared/README.md`](../../../mobile-shared/README.md)
is the apps' stated definition of done. Every item was unticked until 2026-08-21, and
the offline half had never been walked at all.

This records what is now **automated**, what was **measured**, and what is still
**unverified** — including one item that could not be completed and why. An acceptance
checklist that quietly marks itself done is worth less than one with a gap in it.

## Summary

| Item | Status | Evidence |
|---|---|---|
| Capture completes with the device in airplane mode | ✅ automated | `capturingWithNoNetworkQueuesTheSightingAndKeepsThePhotograph` |
| Queued sightings survive a force-quit and a device restart | ✅ automated | `theQueueSurvivesTheProcessDying` |
| Sync resumes automatically when connectivity returns | ✅ automated | `theQueueDrainsOnceTheNetworkReturns` |
| Killing the app mid-upload does not duplicate or lose | ✅ automated | `aConnectionLostMidUploadResumesWithoutResendingWhatArrived` |
| Submitting the same sighting twice creates one record | ✅ automated | `drainingTwiceCreatesOneRecordAndUploadsNothingTheSecondTime`, and server-side `TestReplayingASubmissionCreatesExactlyOneRow` |
| A 401 mid-session refreshes silently | ◐ partial | `TokenAuthenticator` is exercised end-to-end by the iOS `APIClientIntegrationTests` and by `make smoke`; no Android-side test |
| Expired refresh token returns to sign-in without losing the queue | ✅ automated | `signingOutLeavesTheQueueUntouchedRatherThanUploadingOrDiscardingIt` |
| Model labels and expert verdicts distinguishable without colour | ✅ automated | the dashboard's NFR13 tests, incl. `is distinguishable with every colour class stripped`; both apps carry it in shape and word |
| Capture flow under 60 seconds and 8 taps | ◐ **taps counted, time not measured** | see below |
| Light and dark appearance both correct | ✅ automated | `testTheAppearanceToggleDarkensEveryScreen`, `testTheAppearanceChoiceIsRemembered`; Android night resources via `aapt2 dump` (D33) |
| Account deletion explains anonymisation | ✅ automated (server) | `TestDeletingAnAccountKeepsTheScienceAndDropsThePerson`; the UI copy is a screenshot, not a test |

## The offline items are automated, not walked

`android/core/data/src/androidTest/.../SyncEngineOfflineTest.kt` — 8 tests, all passing
on the `SkyCast_API36` emulator.

Each checklist item describes a **situation** rather than a function: no network, a
force-quit, a connection dropped halfway through an upload. Walking those by hand is
worth doing once and useless as a regression guard, because nobody re-walks it after
every change. So the situations are constructed instead: real Room, real files on disk,
a real Keystore-encrypted session store, and only the server faked — and the fake keeps
genuine state rather than expectations, so a test cannot pass while describing a server
that could not exist.

Two things that only came out of writing them:

1. **The exception type decides the code path.** `ErrorMapper` maps
   `UnknownHostException` and `ConnectException` to `ApiError.Offline`, and a plain
   `IOException` to `ApiError.Timeout`. The first version of the fake threw
   `IOException`, so every "airplane mode" test silently exercised the
   *retryable-failure* path instead — burning attempt counters against a network that
   was not there and never reporting itself offline. A device with its radio off fails to
   resolve the host, so `UnknownHostException` is both faithful and the one the engine
   reads correctly.
2. **A second drain immediately after a failure finds nothing to do**, because the retry
   curve has already set `next_attempt_at` ahead. That is correct behaviour and it read
   at first as "the engine failed to resume". The tests now bring the row forward
   explicitly, with a comment saying that the curve itself is covered by
   `RetryPolicyTest`.

The reconciliation assertion is the one worth reading. After a connection drops during
the second of three photographs, the test does not merely check that all three end up
stored — it checks that **only the two missing ones were re-sent**. That is the
difference between "safe because the ids are idempotent" and "actually reconciled", and
only the second one saves a diver's tethering allowance.

## NFR6: taps counted, time not measured

**≤ 8 taps: satisfied, and it is 5.** Counted from the code
(`app/src/main/kotlin/mv/muraka/ui/capture/`), the shortest camera path is:

1. `New sighting` (the FAB on My sightings)
2. `Add photo`
3. `Take a photograph` (the source sheet)
4. the shutter
5. `Queue sighting`

GPS is acquired automatically, and depth, note and self-assessment are all optional. The
**first ever** capture adds one tap for the camera permission grant, so it is 6 once and
5 thereafter — both inside the limit.

**< 60 seconds: still not measured.** NFR6's verification method is "usability testing
measurement", and a tap count is not a stopwatch. This stays open, and the project should
say so rather than infer the timing from the tap count.

## What could not be verified, and why

**Capture with the radio actually off, on a device: not completed.**

The automated test proves the *queue* accepts a sighting with no network. It cannot prove
the *capture screen renders and behaves* with the radio off, because that is a screen.

Attempting it on the `SkyCast_API36` emulator failed four times — not in the app, but in
the emulator. `logcat` shows the ANRs are in **`system_server` (pid 654)**, with the app
itself at 17–23% CPU:

```
ANR in input window owned by pid=654.
  Reason: Input dispatching timed out (PointerEventDispatcher0 is not responding…)
ANR in system
  17% 10256/mv.muraka.debug: 14% user + 3.2% kernel
```

It was preceded by a `Bluetooth keeps stopping` system crash and recurred immediately
after a full `adb reboot`. Ping latency to the host was averaging 448 ms. This is an
unhealthy emulator, and no amount of retrying it produces evidence about the app.

It is recorded as **unverified** rather than assumed. The honest next step is a physical
Android device, or a fresh AVD.

## Reproducing

```bash
make up && make seed N=200
cd android && ./gradlew connectedDebugAndroidTest   # 20 instrumented tests, 8 of them offline
make test-ios                                        # 9, incl. appearance and grid toggle
make smoke                                           # 33 end-to-end, incl. the 401 refresh path
```
