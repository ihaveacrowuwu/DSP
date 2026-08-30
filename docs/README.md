# Documentation

The [root README](../README.md) is the starting point: what the system is, how to run
it, and how to build the mobile apps. This directory holds the two things that are
too large or too raw to live there.

| | |
|---|---|
| [`openapi.yaml`](openapi.yaml) | **The API contract.** Authoritative for the dashboard and both mobile apps - `web/src/lib/api.ts` and the Go handler tests are written against it. |
| [`evidence/`](evidence/) | Screenshots of the running system, and the raw output of the performance harness. |

## Evidence

| Directory | Contents | Produced by |
|---|---|---|
| [`evidence/performance/`](evidence/performance/) | Raw JSON per run, the ONNX CPU-latency sweep, the backbone comparison, and why INT8 quantisation was rejected | `make perf`, `ml/training/scripts/bench_backbones.py`, `quantize.py` |
| [`evidence/mobile/`](evidence/mobile/) | Android and iOS screenshots, including dark mode and airplane-mode capture, plus the acceptance checklist results | `make test-ios` regenerates the iOS set; `adb shell screencap` for Android |
| [`evidence/dashboard/`](evidence/dashboard/) | Dashboard screenshots used by the root README | Captured against the running stack |

`make perf` writes a new timestamped file into `evidence/performance/` each time it
runs.

## Where the rest lives

Requirements, test results and traceability are in [`TESTING.md`](../TESTING.md), which
maps every FR and NFR identifier to the tests covering it - including the ones nothing
covers. Those identifiers are cited by number from source comments, the Makefile and
both compose files, and `TESTING.md` is where they are defined.

Each component documents itself, next to its own code:

[`backend/`](../backend/README.md) ·
[`ml/`](../ml/README.md) ·
[`web/`](../web/README.md) ·
[`android/`](../android/README.md) ·
[`ios/`](../ios/README.md) ·
[`deploy/`](../deploy/README.md) ·
[`mobile-shared/`](../mobile-shared/README.md)

## A note on `Dnn` references

Some comments cite decisions as `D26`, `D64` and so on. Those identifiers belong to a
project decision log kept **outside this repository**, alongside the other assessed
submissions. Every comment carrying one also states its reasoning, so nothing here
depends on resolving the number.
