# NFR2 in the container, and why INT8 quantisation was rejected

Two findings from 2026-08-26, in the order they were discovered. The first is a
measurement error in this project's own evidence. The second is an optimisation that
worked, was measured properly, and was turned down.

## 1. The requirement is met on the CPU and missed in the container

NFR2 asks for CPU inference at or under **500 ms per image**, where one image is a 5×5
patch lattice classified in a single call.

| Where | p50 | p95 | |
|---|---|---|---|
| Host, native macOS, `ONNX_THREADS=4` | **405 ms** | 417 ms | ✅ |
| Inside the `ml` container, same settings | **822 ms** | 843 ms | ❌ |
| Through the service's HTTP path, warm | **863 ms** | 892 ms | ❌ |

The gap is **not** any of the usual suspects, all of which were checked:

- **Not emulation.** The container reports `aarch64`; the host is arm64. No Rosetta.
- **Not a CPU quota.** `cpu.max` is `max 100000`, and the container sees all 10 cores.
- **Not a different runtime.** onnxruntime 1.20.1 in both, the same pinned version.
- **Not contention.** The host figure was re-measured with the whole compose stack
  running - Postgres, the API, the dashboard, the worker - and came back at 401.5 ms.
- **Not the service's preprocessing.** The raw graph, benchmarked *inside* the container
  with the same session options, is 821.5 ms. The Python tiling and resizing account for
  the ~40 ms difference between that and the HTTP figure, not the 2×.

What is left is **Docker Desktop on macOS, which runs containers in a Linux virtual
machine**. That costs roughly 2× on this workload. No thread setting recovers it:

| Threads in container | p50 | p95 | |
|---|---|---|---|
| 2 | 1,209 ms | 1,214 ms | ❌ |
| 4 (deployed) | 822 ms | 846 ms | ❌ |
| 6 | 707 ms | 733 ms | ❌ |
| 8 | 649 ms | 669 ms | ❌ |
| 10 | 683 ms | 702 ms | ❌ |

Smaller lattices do fit, and are recorded here as available remedies rather than as
changes made:

| Lattice | Patches | Threads | p95 in container | |
|---|---|---|---|---|
| 3×3 | 9 | 8 | 285 ms | ✅ |
| 4×4 | 16 | 8 | 406 ms | ✅ |
| **5×5 (shipped)** | 25 | 8 | 657 ms | ❌ |

**The honest statement for the project:** NFR2 is met on the target CPU and missed when
the service is run through Docker Desktop's macOS VM. The overhead is a property of the
development environment, not of the system - a Linux host running the same container has
no such VM in the path - but the project's demo runs on this laptop, so the caveat is
real and belongs in the evaluation chapter rather than in a footnote.

The patch lattice was **not** reduced. It is pinned across Vue, Compose and UIKit by
`scripts/check_patch_lattice.py` and is the substrate of the argument that drawing the
lattice is drawing the model's reasoning (FR5). Coarsening a product design to
accommodate a virtualisation artefact is the wrong direction, and the 4×4 option is
recorded above for whoever disagrees.

## 2. INT8 quantisation: a 4.2× speedup, rejected

The obvious way to buy back 2× without touching the lattice. Static quantisation
(`QuantFormat.QDQ`, per-channel weights, calibrated on **256 training images** - never
val, never test), via [`ml/training/scripts/quantize.py`](../../../ml/training/scripts/quantize.py).

It worked, on latency:

| | FP32 | INT8 |
|---|---|---|
| Artefact | 16.0 MB | **4.9 MB** |
| Host p50, 25 patches | 408 ms | **98 ms** |
| Speedup | - | **4.17×** |

98 ms on the host implies roughly 200 ms in the container - comfortably inside NFR2 with
the 5×5 lattice intact. On latency alone this was the answer.

### Why it was turned down

Calibration method mattered enormously, and the naive choice was the worst:

| Calibration | Accuracy | Agreement with FP32 |
|---|---|---|
| MinMax | 0.717 | 0.679 |
| Entropy | 0.717 | 0.679 |
| **Percentile 99.999** | **0.788** | **0.833** |

That is the expected shape for an EfficientNet: depthwise convolutions have wide
per-channel activation ranges, and a MinMax range set by one outlier clips everything
else. Percentile calibration fixes most of it.

Then the metric that matters was measured, on a 240-image validation subset:

| | FP32 | INT8 (best config) | Δ |
|---|---|---|---|
| Accuracy | 0.804 | 0.783 | **−2.1 pts** |
| **Recall, bleached** | **0.843** | **0.699** | **−14.5 pts** |
| **F2, bleached** | **0.803** | **0.695** | **−10.7 pts** |

**Accuracy hid the damage.** A 2-point accuracy drop for a 4× speedup is the trade every
quantisation write-up quotes, and on that number alone this would have shipped. The loss
is not spread evenly: it lands almost entirely on the **minority class**, which is the
bleached one, which is the class the entire system exists to catch. In product terms the
quantised model goes from finding about five bleaching events in six to about two in
three.

Leaving the classifier `Gemm` and the stem convolution in float - the standard remedy -
recovered some of it and not enough:

| Configuration | Recall bleached | F2 bleached | Size |
|---|---|---|---|
| Percentile, all layers quantised | 0.699 | 0.695 | 4.9 MB |
| Percentile, classifier + stem excluded | 0.723 | 0.716 | 6.1 MB |
| FP32 | **0.843** | **0.803** | 16.0 MB |

Still −12 points of recall. Rejected.

### The part worth putting in the project

The project already argues that a missed bleaching event costs more than a false alarm -
that is why the selection metric is F2 on the bleached class rather than accuracy, and
why the training recipe carries class weighting, minority oversampling and a
`bleached_loss_multiplier`. Quantisation is where that argument stopped being a
configuration choice and started paying rent: **an optimisation that looked cheap on the
headline metric was expensive on the one the system is designed around, and only
measuring the right metric revealed it.**

A quantised model would have passed NFR2, passed the smoke tests, served predictions with
full confidence, and quietly missed one bleaching event in three. Nothing in the pipeline
would have objected.
