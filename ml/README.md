# Muraka ML

Two halves with one contract between them:

- **`training/`** — runs on the M1 Pro. Fine-tunes a patch classifier, evaluates
  it, exports ONNX. Never runs in production.
- **`service/`** — runs in Docker on CPU. Loads the exported ONNX, tiles incoming
  photos into a grid, returns per-patch labels plus a bleached-extent severity.

The contract is the exported artefact plus the preprocessing recipe. Both halves
share the same normalisation constants, and a golden-file test asserts that
PyTorch and ONNX agree — a silent mismatch there is the classic way to lose
accuracy points with nothing in the logs.

See [`docs/06-ml-specification.md`](../docs/06-ml-specification.md) for the task
definition, dataset provenance, evaluation protocol and fallback ladder.

## Service

Runs in **fake mode by default**, which is what lets the API, dashboard and both
mobile apps be built and tested before any model exists. Fake predictions are
derived from the image's own hash, so they are deterministic per image: demos and
client tests can assert on fixed values.

```bash
cd ml/service
python -m venv .venv && ./.venv/Scripts/pip install -r requirements-dev.txt   # Windows
# python3 -m venv .venv && ./.venv/bin/pip install -r requirements-dev.txt    # macOS/Linux

./.venv/Scripts/python -m pytest tests/ -q
./.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000
```

In the stack it is already wired up; check it with:

```bash
curl http://localhost:8010/healthz
```

### Serving a real model

1. Put the artefact at `ml/models/active.onnx` (mounted read-only into the
   container, so no image rebuild).
2. Set `FAKE_MODE=0` in `deploy/docker-compose.yml`.
3. Restart: `docker compose -f deploy/docker-compose.yml up -d ml`
4. Register the version in the dashboard's Operations screen so predictions cite
   it.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `FAKE_MODE` | `1` | Deterministic stubs instead of a model |
| `MODEL_PATH` | `models/active.onnx` | ONNX artefact to load |
| `MODEL_VERSION` | from artefact metadata | Recorded on every prediction |
| `PATCH_GRID` | `5` | Grid is `N×N` over the centre square |
| `PATCH_OVERLAP` | `0` | 0–0.9; grows each patch for extra context |
| `INPUT_SIZE` | `224` | Must match training |
| `BLEACHED_LABEL_THRESHOLD` | `0.35` | Severity at which the image reads bleached |
| `ONNX_THREADS` | `2` | CPU threads for inference |

`PATCH_GRID`, `PATCH_OVERLAP` and `BLEACHED_LABEL_THRESHOLD` are deliberately
configurable: grid granularity and the label threshold are experiments the project
reports on, not constants.

## Demo photographs for the dashboard

`make seed` attaches a photograph to every synthetic sighting. By default that is a
plain hatched swatch, because a fresh clone has no reef imagery — which means the
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

Not yet written — that work happens on the MacBook, where PyTorch has Metal
acceleration. `training/configs/baseline.yaml` records the intended recipe so the
first run has a starting point rather than a blank page.

### What exists now

The pipeline is **built and verified end to end**, on synthetic data, so everything
except the corpus is de-risked:

```
training/
  configs/          run configuration; baseline.yaml is the recipe
  muraka_train/     config, data, model, metrics, train loop, ONNX export
  scripts/          train.py, evaluate.py
  tests/            29 tests: contract, metrics, reproducibility, ONNX parity
  runs/             per-run checkpoint, metrics.csv, summary.json (gitignored)
```

```bash
make test-train                      # 29 tests, no dataset needed
cd ml/training && python3 scripts/train.py --config configs/baseline.yaml     --synthetic --epochs 4 --export-onnx     # verify the whole pipeline
```

Three things that verification already settled:

- **CPU latency is fine.** EfficientNet-B0 at 224 px, exported to ONNX, classifies a
  whole 5×5 lattice — one photograph — in **381 ms** on the M1 Pro, against NFR2's 500 ms.
  So the fallback in step 6 below is not needed and the accuracy-first backbone stays.
  Figures in [`docs/evidence/performance/`](../docs/evidence/performance/).
- **The graph the service will serve matches the model that gets evaluated**, to 6.9e-07.
- **Runs are reproducible** — same seed, same metrics; different seed, different metrics.
  Both directions are asserted, because a pipeline that ignores the seed passes the first
  test and fails the requirement.

`muraka_train/config.py` refuses to start a run whose class order or normalisation
disagrees with `ml/service/app`. That is not tidiness: a swapped class order inverts every
prediction *confidently*, and mismatched normalisation degrades accuracy invisibly.
Neither crashes, so neither would be noticed.

**What is missing is the data**, and nothing else.

### The plan, in order

1. **Verify the dataset.** ← *the only remaining blocker.* Download
   `NMFS-OSI/NOAA-PIFSC-ESD-CORAL-Bleaching-Dataset` from HuggingFace (no API key
   needed) and confirm its licence terms permit this use. This is the project's
   last real unknown, so it comes first.
2. **Freeze the test split immediately** and do not look at it again until final
   evaluation. Say so in the project; it is a discipline markers notice.
3. **Train the baseline**: EfficientNet-B0, head first, then staged unfreeze.
   Target under an hour per run on the M1 Pro.
4. **Evaluate**: accuracy, macro-F1, and **F2 on the bleached class** — a missed
   bleaching event costs more than a false alarm, so recall is weighted. Plus a
   confusion matrix and an error gallery.
5. **Export ONNX**, then run the parity test against the PyTorch model.
6. ~~**Check CPU latency** for a 25-patch batch. If it is slow, drop to
   MobileNetV3-Large before touching accuracy.~~ **Done** — 381 ms per photograph, so
   EfficientNet-B0 stays.

### Constraints

- **Training hardware is the M1 Pro** (PyTorch MPS). The 7900 XT is an untested
  backup. The work DGX is a bonus and must never be a dependency.
- **Inference is CPU-only.**
- **No API-key services**: no comet-ml, no W&B, no Roboflow. Log runs to local
  CSV/JSON — that is also what makes them reproducible for the project.
- Everything config-driven and seeded, so a run can be repeated exactly.

### Layout

```
training/
  configs/          run configuration; one file per experiment
  muraka_train/     the library: config, data, model, metrics, train, export
  scripts/          train.py, evaluate.py — thin CLIs over the library
  tests/            contract, metrics, reproducibility, ONNX parity
  runs/             metrics and checkpoints per run (gitignored)
```

The library sits beside `scripts/` rather than inside it because the tests import it, and
a test suite that imports from a scripts directory ends up manipulating `sys.path` in
every file.
