# Performance evidence

Four of the project's non-functional requirements are numbers. Until 2026-08-21 all four
were carried in prose — "measured ~1.5s", "22ms", "320ms" — which is a memory, not
evidence. `scripts/perf_test.py` prints them, and the JSON files here are the runs that
produced the figures the project quotes.

```bash
make up && make seed N=10000     # NFR3 is a claim about 10,000 sightings
make perf                        # writes perf-YYYY-MM-DD.json beside this file
```

## Run of 2026-08-21 — [`perf-2026-08-21.json`](perf-2026-08-21.json)

Against the compose stack on the development machine (Apple M1 Pro), 10,304 sightings
seeded, ML service in fake mode.

| Requirement | Threshold | Measured | |
|---|---|---|---|
| NFR1 — label readable after sync | ≤ 30 s | **0.89 s** | ✅ |
| NFR2 — CPU inference per image | ≤ 500 ms | **22 ms** (stub) / **406 ms** (EfficientNet-B0, deployed config) | ✅ |
| NFR3 — map viewport at 10,000 sightings | ≤ 2 s | **56 ms** worst of 5 | ✅ |
| NFR11 — 50 concurrent submissions | no error, no loss | **0 errors, 0 lost**, 919/s | ✅ |

### NFR2, measured against the real architecture — [`nfr2-backbone-comparison.json`](nfr2-backbone-comparison.json)

The 22 ms above is the service's stub. The **architecture** is measured separately:
EfficientNet-B0 at 224 px, exported to ONNX and run on `CPUExecutionProvider`, for one
5×5 patch lattice — which is one photograph, in one call.

| | |
|---|---|
| Per photograph (25 patches), p50 | **406 ms** |
| p95 | 417 ms |
| Per patch | 16.2 ms |
| NFR2 threshold | 500 ms |
| Deployed `ONNX_THREADS` | 4 |

**Inside the budget, with about 19% headroom.** That settles a decision the plan left
open: `ml/README.md` said "if it is slow, drop to MobileNetV3-Large before touching
accuracy", and it is not slow, so the accuracy-first backbone stays.

⚠️ **This figure replaces the 381 ms recorded on 2026-08-21, and the reason is worth a
paragraph in the evaluation chapter** (D64). The original measurement was correct
arithmetic on the wrong session: `cpu_latency` built a plain `InferenceSession`, which
takes onnxruntime's default of one thread per core — ten on this machine — while
`ml/service/app/inference.py` builds its session with `intra_op_num_threads =
ONNX_THREADS` and the stack shipped `ONNX_THREADS: "2"`. Measured back to back on one
graph: **384.70 ms at the defaults, 479.71 ms at the service's setting**, 0.2% drift on a
repeat. At 2 threads the p95 reached **544 ms across runs — outside the budget** — so a
requirement recorded as passing with 24% headroom was in fact marginal in the
configuration that ships. `ONNX_THREADS` is now **4**, `cpu_latency` builds the service's
session, and `test_the_benchmark_measures_the_threads_the_stack_deploys` fails if the two
ever drift apart again.

#### The `ONNX_THREADS` sweep behind that choice

One graph, one machine, 25 timed runs each. p95 is the column that decides, because a
requirement met at the median and missed at the tail is not met.

| Threads | p50 | p95 | |
|---|---|---|---|
| 1 | 647 ms | 663 ms | ✗ fails outright |
| 2 (was shipped) | 497 ms | 527 ms | ✗ breaches at the tail |
| 3 | 432 ms | 445 ms | ✅ but only 11% |
| **4 (now shipped)** | **405 ms** | **411 ms** | ✅ ~18% headroom |
| 6 | 389 ms | 408 ms | ✅ 16 ms more, for 2 more cores |
| 8 | 445 ms | 454 ms | ✅ past the knee, and slower |

Those are one run. Across the three sweeps taken while making this change, `threads=2`
measured **477–497 ms p50 and 491–544 ms p95, over the 500 ms budget at the tail in two
runs of three** — which is the point: it was not reliably passing, and a single lucky
measurement is what made it look settled. `threads=4` measured 402–406 p50 and 411–417
p95 in every run.

4 is the knee. Beyond it the curve flattens while the cores are wanted by Postgres, the
Go API and the worker, which share this laptop in the compose stack — and every figure
here is from an *idle* machine, so the tail under real contention is worse than the table.

#### The backbone comparison — D65

`baseline.yaml` left open whether a modern backbone of similar size could be used
instead. Measured rather than argued, at the deployed configuration:

| Backbone | Parameters | p50 | vs 500 ms |
|---|---|---|---|
| **EfficientNet-B0** | 4.0 M | **406 ms** | ✅ |
| EfficientNetV2-S | 20.2 M | 862 ms | ✗ 1.7× over |
| ConvNeXt-Tiny | 27.8 M | 1,486 ms | ✗ 3.0× over |

Neither alternative is marginal, so the question is closed: accuracy has to come from the
recipe, the data or the patch grid rather than from a larger backbone.

The weights in all of these came from random or synthetic initialisation, so none of it
says anything about accuracy. Latency depends on the architecture, the input size and the
runtime, not on what the weights learned — so it is valid evidence for the *choice of
backbone* and not for the model's quality. ONNX/PyTorch parity on the same graph was
6.9e-07.

⚠️ **The 22 ms figure below is the fake model.** The ML service ships a deterministic stub until
the training track produces a real one, so 22 ms measures the plumbing and says nothing
about the model that will replace it. Quoting it as evidence for NFR2 without that
sentence beside it would be misleading.

### What the harness asserts that a stopwatch would not

- **NFR11 checks for data loss, not just errors.** Fifty successful responses do not
  prove fifty rows. Every sighting id is client-generated, so all fifty are known before
  anything is sent and every one is read back afterwards; `missing_after_write` is that
  count. A run where the API returns 201 fifty times and stores forty-nine is a pass on
  status codes and a failure here.
- **NFR3 reports the corpus it actually measured against.** The first run of this check
  returned 8 ms and **failed**, because the database held 253 sightings — a fast query
  against 253 rows is not evidence for a requirement about 10,000. The threshold and the
  precondition are both enforced.
- **NFR2 reads the time the service reported** rather than timing the request from
  outside, because the requirement is about inference, not about the network and the
  worker's poll interval.
- **NFR1 starts its clock at the photo upload**, which is the moment the contributor's
  phone has finished its side of the sync — not at the metadata POST.

### The cost of D45, measured

D45 added a primary-key lookup to every authenticated request so that bans and
demotions take effect immediately rather than when the access token expires. That trade
was made on the promise of measuring it:

| | p50 | p95 |
|---|---|---|
| `/healthz` (no auth) | 1.6 ms | 3.4 ms |
| `/v1/me` (auth + its own aggregate query) | 3.5 ms | 4.4 ms |

The gap is an **upper bound** — `/v1/me` runs an aggregate of its own — so the auth
lookup costs under ~1.9 ms at p50 and the throughput figure above was measured with it
in place. NFR11 passes at 919 submissions per second with the lookup on every one.

## Figures this supersedes

`CLAUDE.md` previously recorded "map with 10k sightings 22ms" and "sync→label ~1.5s".
The map figure is now **43–56 ms** at 10,304 sightings, measured five times; the 22 ms
was either a smaller corpus or a warm single sample. The sync figure is **0.89 s**. Both
are comfortably inside their thresholds, and the point of writing them down here is that
the next person to quote them can see what produced them.
