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

## Training

Not yet written — that work happens on the MacBook, where PyTorch has Metal
acceleration. `training/configs/baseline.yaml` records the intended recipe so the
first run has a starting point rather than a blank page.

### The plan, in order

1. **Verify the dataset.** Download
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
6. **Check CPU latency** for a 25-patch batch. If it is slow, drop to
   MobileNetV3-Large before touching accuracy.

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
  scripts/          data prep, train, evaluate, export
  runs/             metrics and checkpoints per run (gitignored)
```
