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
| `ONNX_THREADS` | `2` (compose sets **4**) | CPU threads for inference. The code's fallback is 2; `deploy/docker-compose.yml` sets 4, because at 2 a 25-patch lattice breaches NFR2 at the tail — D64 |

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

**Done — there is a trained model.** `effnetb0-0.1.0`, 59 minutes on the M1 Pro via MPS,
0.8575 accuracy and 0.9027 F2-bleached on the held-out test split, exported to ONNX and
served. Full numbers, the training curve and the honest caveats are in
[`docs/evidence/ml/baseline-effnetb0.md`](../docs/evidence/ml/baseline-effnetb0.md).
The recipe that produced it is `training/configs/baseline.yaml`, unmodified.

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

- **CPU latency fits, at the thread count the stack actually deploys.** EfficientNet-B0 at
  224 px, exported to ONNX, classifies a whole 5×5 lattice — one photograph — in **406 ms
  p50 / 417 ms p95** on the M1 Pro at `ONNX_THREADS=4`, against NFR2's 500 ms. So the
  fallback in step 6 below is not needed and the accuracy-first backbone stays. An earlier
  381 ms figure measured onnxruntime's *default* thread count rather than the service's,
  and at the previously shipped `ONNX_THREADS=2` the same graph breached the budget at the
  tail — see D64, and `scripts/bench_backbones.py --sweep-threads` for the sweep that
  chose 4. Figures in [`docs/evidence/performance/`](../docs/evidence/performance/).
- **The graph the service will serve matches the model that gets evaluated**, to 6.9e-07.
- **Runs are reproducible** — same seed, same metrics; different seed, different metrics.
  Both directions are asserted, because a pipeline that ignores the seed passes the first
  test and fails the requirement.

`muraka_train/config.py` refuses to start a run whose class order or normalisation
disagrees with `ml/service/app`. That is not tidiness: a swapped class order inverts every
prediction *confidently*, and mismatched normalisation degrades accuracy invisibly.
Neither crashes, so neither would be noticed.

**What is missing is the data**, and nothing else.

### Picking this up next session — the exact next steps

Everything below the dataset is done. **The gate is open: Q6 was resolved on 2026-08-24
(D63 in `docs/08`) — the NOAA dataset is cleared for use**, with a citation and the as-is
disclaimer owed in the project's data section. The terms as read are recorded in
`docs/08-scope-risks-decisions.md` under Q6: no explicit licence tag, the standard NOAA as-is disclaimer, a requested
citation, and the dataset's own facts (224 px, `CORAL`/`CORAL_BL`, 7,292 training images)
matching `configs/baseline.yaml` exactly. **Nothing has been downloaded.**

The steps, in order (the prep for steps 1–2 is planned in
`docs/plans/ml-prep-before-mac.md`):

```bash
# 1. Get the corpus (768 MB) into ml/datasets/noaa. The script downloads it anonymously,
#    checks the split totals and per-class counts against the dataset card, writes a
#    SHA-256 manifest to manifests/noaa.sha256 (committed — it is the project's
#    provenance evidence), and prints the citation D63 owes.
cd ml/training
python3 scripts/fetch_noaa.py
python3 scripts/fetch_noaa.py --verify-only    # re-check an existing copy, no network

# 2. Sanity-check the recipe against the real data before spending an hour on a run:
python3 scripts/train.py --config configs/baseline.yaml     --data-root ../datasets/noaa --epochs 1 --output-dir runs/smoke

# 3. The real baseline. Roughly an hour on the M1 Pro.
python3 scripts/train.py --config configs/baseline.yaml     --data-root ../datasets/noaa --export-onnx --model-version effnetb0-0.1.0

# 4. Open the test split ONCE, at the end:
python3 scripts/evaluate.py --config configs/baseline.yaml     --data-root ../datasets/noaa --checkpoint runs/baseline-effnetb0/best.pt --split test

# 5. Serve it: copy the .onnx to ml/models/active.onnx, set FAKE_MODE=0 in
#    deploy/docker-compose.yml, then `make up && make smoke`.
```

On macOS, if the ImageNet weights fail with `CERTIFICATE_VERIFY_FAILED`:
`export SSL_CERT_FILE=$(python3 -c "import certifi;print(certifi.where())")`.

Two things to compare against when the numbers arrive: prior published work on this
dataset reports roughly **0.90 accuracy and 0.90 macro-F1** at patch level with a
comparable backbone, and CPU latency is already known to be **406 ms** per photograph at the
deployed `ONNX_THREADS=4`, so a slowdown after training would mean something changed in the
graph rather than in the weights.

### The plan, in order

1. **Verify the dataset.** ← *the only remaining blocker, and it is a decision rather than
   a task.* Download
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
   MobileNetV3-Large before touching accuracy.~~ **Done** — 406 ms per photograph at
   `ONNX_THREADS=4`, so EfficientNet-B0 stays. `scripts/bench_backbones.py` also closed the
   "compare a modern backbone" question (D65): ConvNeXt-Tiny is 1,486 ms and
   EfficientNetV2-S 862 ms, both far outside the budget rather than marginally over.
7. **Then the domain-gap work** (D60 in `docs/08`): pull the **Central Indian
   Ocean** region of the Seaview Survey dataset — that region only, the whole thing
   is 1.5 TB across 22 regional partitions — and record the survey IDs and a
   manifest hash in `configs/baseline.yaml`, which already has the fields waiting
   under `evaluation.cross_domain_sets`. The eSpace page (read 2026-08-24, docs/08
   Q6) describes the region as "Indian Ocean (Maldives, Chagos Archipelago)": confirm
   the partition's real name at download, select Maldivian transects via the shipped
   CSV metadata rather than by folder name, and take only the photo-quadrats,
   annotations and tabular files — never the raw 360° triplets, which are the bulk
   of the 1.5 TB. Evaluate the NOAA-trained model on
   Maldivian quadrats, then hand-label ~100 of them for the image-level set.
   Label them *before* looking at any model output on them.

Two things it is easy to get wrong here:

- **Seaview has no healthy/bleached labels.** Its label set is hard coral, algae,
  soft coral, other invertebrate, other. It is an evaluation and hand-labelling
  corpus only — never add it to a training split.
- **The NOAA crops being low-quality is the point, not a problem.** Contributors
  photograph reefs on phones through moving water. A classifier trained on clean
  survey imagery would look better in a table and worse in the product. Do not
  swap NOAA out for something prettier; it is also the dataset the published
  0.902 acc / 0.896 macro-F1 comparison is measured on.

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
