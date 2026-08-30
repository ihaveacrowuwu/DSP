# Muraka ML

Two halves with one contract between them:

- **`training/`** - runs on the M1 Pro. Fine-tunes a patch classifier, evaluates
  it, exports ONNX. Never runs in production.
- **`service/`** - runs in Docker on CPU. Loads the exported ONNX, tiles incoming
  photos into a grid, returns per-patch labels plus a bleached-extent severity.

The contract is the exported artefact plus the preprocessing recipe. Both halves
share the same normalisation constants, and a golden-file test asserts that
PyTorch and ONNX agree - a silent mismatch there is the classic way to lose
accuracy points with nothing in the logs.

The task is binary patch classification - healthy or bleached - over an N×N lattice
tiled from the centre square of each photograph, with the bleached fraction becoming an
image-level severity. `training/configs/baseline.yaml` is the authoritative recipe.

## Service

The code's own default is **fake mode**, which is what lets the API, dashboard and
both mobile apps be built and tested before any model exists. The shipped stack
overrides it: `deploy/docker-compose.yml` sets `FAKE_MODE=0` and serves the
committed `ml/models/active.onnx`, so a clone classifies with the real model.

Fake predictions are derived from the image's own hash, so they are deterministic
per image: demos and client tests can assert on fixed values.

```bash
cd ml/service
python3 -m venv .venv && ./.venv/bin/pip install -r requirements-dev.txt
# .venv\Scripts\pip install -r requirements-dev.txt                          # Windows

./.venv/bin/python -m pytest tests/ -q
./.venv/bin/python -m uvicorn app.main:app --reload --port 8000
```

In the stack it is already wired up; check it with:

```bash
curl http://localhost:8010/healthz
```

### Serving a different model

The stack already serves one; these are the steps to replace it.

1. Put the artefact at `ml/models/active.onnx` (mounted read-only into the
   container, so no image rebuild).
2. Confirm `FAKE_MODE=0` in `deploy/docker-compose.yml` - it already is.
3. Restart: `make restart S=ml`
4. Register the version in the dashboard's Operations screen so predictions cite
   it.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `FAKE_MODE` | `1` | Deterministic stubs instead of a model |
| `MODEL_PATH` | `models/active.onnx` | ONNX artefact to load |
| `MODEL_VERSION` | from artefact metadata | Recorded on every prediction |
| `PATCH_GRID` | `5` | Grid is `N×N` over the centre square |
| `PATCH_OVERLAP` | `0` | 0-0.9; grows each patch for extra context |
| `INPUT_SIZE` | `224` | Must match training |
| `BLEACHED_LABEL_THRESHOLD` | `0.35` | Severity at which the image reads bleached |
| `ONNX_THREADS` | `2` (compose sets **4**) | CPU threads for inference. The code's fallback is 2; `deploy/docker-compose.yml` sets 4, because at 2 a 25-patch lattice breaches NFR2 at the tail - D64 |

`PATCH_GRID`, `PATCH_OVERLAP` and `BLEACHED_LABEL_THRESHOLD` are deliberately
configurable: grid granularity and the label threshold are experiments this project
reports on, not constants.

## Demo photographs for the dashboard

`make seed` attaches a photograph to every synthetic sighting. By default that is a
plain hatched swatch, because a fresh clone has no reef imagery - which means the
dashboard shows the model's patch overlay sitting on a blank blue-green square.

To see real coral under the overlay, drop reef photographs into
`ml/datasets/samples/` (created automatically, never committed):

```
ml/datasets/samples/
  healthy/     photographs of healthy coral
  bleached/    photographs of bleached coral
```

Both subdirectories are optional; files placed directly in `samples/` are used for
either label. Up to 60 images are read, each stored once and shared across
sightings, so two dozen is plenty. The seeder matches the photograph to each
sighting's label, so a sighting reported as 80% bleached does not show obviously
healthy coral.

Then reseed: `make reset-data N=2000`.

Everything is re-encoded to JPEG on the way in, exactly as the API does for real
uploads. Once the NOAA dataset is downloaded for training, it is the obvious source.

## Training

**Done - there is a trained model.** `effnetb0-0.1.0`, 59 minutes on the M1 Pro via MPS,
0.8575 accuracy and 0.9027 F2-bleached on the held-out test split, exported to ONNX and
served. The recipe that produced it is `training/configs/baseline.yaml`, unmodified,
and `manifests/noaa.sha256` pins the corpus it was trained on.

### The pipeline

```
training/
  configs/          run configuration; baseline.yaml is the recipe
  muraka_train/     config, data, model, metrics, train loop, ONNX export
  scripts/          train.py, evaluate.py, fetch_noaa.py, bench_backbones.py, quantize.py
  manifests/        SHA-256 corpus manifests (committed - they are the provenance record)
  tests/            42 tests: contract, corpus, metrics, reproducibility, ONNX parity
  runs/             per-run checkpoint, metrics.csv, summary.json (gitignored)
```

```bash
make test-train     # 42 tests, no dataset needed
```

Three properties the test suite holds the pipeline to:

- **The graph the service serves matches the model that was evaluated**, to 6.9e-07.
  A silent PyTorch/ONNX mismatch is the classic way to lose accuracy with nothing in
  the logs.
- **Runs are reproducible** - same seed, same metrics; different seed, different
  metrics. Both directions are asserted, because a pipeline that ignores the seed
  passes the first test and fails the requirement.
- **Class order and normalisation cannot drift** from `ml/service/app`.
  `muraka_train/config.py` refuses to start a run that disagrees with the service.
  That is not tidiness: a swapped class order inverts every prediction *confidently*,
  and mismatched normalisation degrades accuracy invisibly. Neither crashes, so
  neither would be noticed.

### Reproducing the trained model

The corpus is not committed (768 MB, and `ml/datasets/` is gitignored), so step 1
fetches it. `manifests/noaa.sha256` is committed, so an existing copy can be verified
without the network.

```bash
cd ml/training

# 1. Fetch the corpus into ml/datasets/noaa. Downloads anonymously, checks split
#    totals and per-class counts against the dataset card, and writes the manifest.
python3 scripts/fetch_noaa.py
python3 scripts/fetch_noaa.py --verify-only    # re-check an existing copy, no network

# 2. Sanity-check the recipe against real data before spending an hour on a run.
python3 scripts/train.py --config configs/baseline.yaml \
  --data-root ../datasets/noaa --epochs 1 --output-dir runs/smoke

# 3. The baseline. Roughly an hour on the M1 Pro.
python3 scripts/train.py --config configs/baseline.yaml \
  --data-root ../datasets/noaa --export-onnx --model-version effnetb0-0.1.0

# 4. Open the test split ONCE, at the end.
python3 scripts/evaluate.py --config configs/baseline.yaml \
  --data-root ../datasets/noaa --checkpoint runs/baseline-effnetb0/best.pt --split test

# 5. Serve it: copy the .onnx over ml/models/active.onnx, then `make restart S=ml`.
```

On macOS, if the ImageNet weights fail with `CERTIFICATE_VERIFY_FAILED`:
`export SSL_CERT_FILE=$(python3 -c "import certifi;print(certifi.where())")`.

The test split was frozen before training and opened once, at the end. Published work
on this dataset reports roughly **0.90 accuracy and 0.90 macro-F1** at patch level with
a comparable backbone, against this model's 0.8575 / 0.8548.

### Backbone and latency

EfficientNet-B0 at 224 px, exported to ONNX, classifies a whole 5×5 lattice - one
photograph - in **406 ms p50 / 417 ms p95** on the M1 Pro at the deployed
`ONNX_THREADS=4`, against NFR2's 500 ms. At `ONNX_THREADS=2` the same graph breaches
the budget at the tail, which is why the compose file sets 4.

`scripts/bench_backbones.py` closed the "compare a modern backbone" question:
ConvNeXt-Tiny is 1,486 ms and EfficientNetV2-S 862 ms, both far outside the budget
rather than marginally over. INT8 quantisation was tried and rejected: it could not
close the container-side gap without costing 14 points of bleached recall
(`scripts/quantize.py`, and the write-up in
[`docs/evidence/performance/nfr2-quantisation.md`](../docs/evidence/performance/nfr2-quantisation.md)).

### Domain-gap evaluation

The NOAA figures describe NOAA imagery, and nothing more. Two evaluations in
`ml/eval/` measure what happens off that distribution:

- `eval_coralscapes.py` - Red Sea wide scenes. Recall largely intact, precision
  collapsed.
- `eval_seaview_mdv.py` and `analyse_seaview_mdv.py` - Maldivian photo-quadrats, run
  against the deployed service at its production configuration. This is the evaluation
  that matters for this project, and the model does not survive it.

Both need their corpus fetched first; neither corpus is committed.

Two things it is easy to get wrong here:

- **Seaview has no healthy/bleached labels.** Its label set is hard coral, algae,
  soft coral, other invertebrate, other. It is an evaluation and hand-labelling
  corpus only - never add it to a training split.
- **The NOAA crops being low-quality is the point, not a problem.** Contributors
  photograph reefs on phones through moving water. A classifier trained on clean
  survey imagery would look better in a table and worse in the product.


### Constraints

- **Training hardware is the M1 Pro** (PyTorch MPS). The 7900 XT is an untested
  backup. The work DGX is a bonus and must never be a dependency.
- **Inference is CPU-only.**
- **No API-key services**: no comet-ml, no W&B, no Roboflow. Runs log to local
  CSV/JSON, which is also what makes them reproducible.
- Everything config-driven and seeded, so a run can be repeated exactly.

`muraka_train/` sits beside `scripts/` rather than inside it because the tests import
it, and a test suite that imports from a scripts directory ends up manipulating
`sys.path` in every file.
