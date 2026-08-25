# Plan: ML prep on the Windows machine, before the Mac training session

**Written 2026-08-24, for a Claude (Opus) session to implement on this machine.**
Context: Q6 is resolved (D63 — the NOAA dataset is cleared for use), so the Mac
session can train as soon as it starts. This plan removes everything from that
session's critical path that does not need macOS: the dataset fetch is currently
undocumented and untooled, the backbone latency comparison has no harness, and
two commits are unpushed. Nothing in this plan downloads the corpus, trains, or
touches the test split.

Read first: `CLAUDE.md` (hard constraints), `docs/08` §Instructions,
`ml/README.md` §Training. The two-machine rule applies: **check `origin/main`
before numbering anything in `docs/08`** — the next decision number is D64 *as of
this writing*, verify before using it.

## Task 1 — Commit and push (first, it unblocks the Mac pulling)

`main` is ahead of `origin/main` by 2 (`b5c488c`, `a4deb22`), plus the
uncommitted 2026-08-24 changes: the D63/Q6 edits to `docs/08` and `ml/README.md`,
`docs/guides/seaview-espace-review.md`, and this file. Commit the new work
(sensible message: recording the Q6 resolution and planning the Mac prep), then
push everything. **The user approved pushing on 2026-08-24.**

## Task 2 — `fetch_noaa.py`: the dataset fetch, verify and manifest script

**The gap:** `ml/README.md` step 1 says "get the corpus (768 MB) into
`ml/datasets/noaa` as train/val/test with CORAL/CORAL_BL subdirectories" but no
tool exists, `huggingface_hub` is not a dependency, and nothing would record
corpus integrity — `baseline.yaml` has manifest fields for Seaview but not NOAA,
which is a reproducibility hole (NFR16's spirit) in the project's data section.

Write `ml/training/scripts/fetch_noaa.py`, a thin CLI in the style of the two
existing scripts (read them first — they are deliberately thin wrappers over
`muraka_train`). Behaviour:

1. **Download** `NMFS-OSI/NOAA-PIFSC-ESD-CORAL-Bleaching-Dataset` via
   `huggingface_hub.snapshot_download` **anonymously — no token, ever** (the
   no-API-key constraint; the dataset is public and keyless). Target
   `ml/datasets/noaa/`, which `.gitignore` already excludes. Make the target dir
   an argument with that default, and make the download step skippable
   (`--verify-only`) so verification can rerun without network.
2. **Verify the layout** against the dataset card's own facts: `train/`, `val/`,
   `test/`, each containing `CORAL/` and `CORAL_BL/`; split totals
   **7,292 / 1,562 / 1,565**; train class counts **4,541 CORAL / 2,751
   CORAL_BL**. Fail loudly on any mismatch — a silently different corpus is
   exactly the class of bug this repo's config module exists to prevent. If the
   snapshot's on-disk layout differs from the expected folders (check the card /
   repo structure at implementation time), map or fail — do not guess silently.
3. **Write a SHA-256 manifest** (relative path + hash per file, sorted) to
   `ml/training/manifests/noaa.sha256`, which **is committed** — it is small and
   it is the project's provenance evidence. Also print the manifest file's own
   digest so a run can be quoted in one line.
4. **Print the owed citation** ("Pacific Islands Fisheries Science Center (2025).
   Ecosystem Sciences Division (ESD)") and a pointer to D63 on success.

Constraints and cautions:

- Pin `huggingface_hub` in `ml/training/requirements.txt` (the file explains why
  everything is pinned — keep the comment style).
- **Do not add keys to `baseline.yaml` without reading
  `muraka_train/config.py` first** — it validates the config against the service
  at load time. The committed manifest file is sufficient; touching the config
  is optional and only if its loader tolerates it.
- **Do not run the download on this machine.** It runs on the Mac. Everything
  else must be testable without network.
- Tests (add to `ml/training/tests/`, matching the existing sentence-style
  names): the verify logic against a tmp-dir fixture with correct counts, wrong
  counts, a missing class folder; manifest determinism (same tree → same
  manifest, changed byte → changed manifest). No network in any test.

## Task 3 — `bench_backbones.py`: the NFR2 latency table

**The purpose:** the user wants the "could a bigger backbone fit under 500 ms?"
question answered with measurement, not argument. Latency depends on
architecture, input size and runtime — not weights — so random weights are valid
evidence (that is D58's own reasoning; keep the caveat in the output).

Write `ml/training/scripts/bench_backbones.py`:

1. For each of `efficientnet_b0`, `convnext_tiny`, `efficientnet_v2_s` (make the
   list an argument; all three are already registered in `muraka_train/model.py`):
   build with random weights (`pretrained=False`-equivalent — avoid downloading
   ImageNet weights for a latency test), **reuse the existing ONNX export path**
   in `muraka_train` (do not write a second exporter — D59 chose the exporter
   deliberately), then measure a **25-patch batch** (one photograph, matching the
   service's 5×5 lattice) through `onnxruntime` with `CPUExecutionProvider` and
   **2 threads** (the service's `ONNX_THREADS` default — match it or the numbers
   are not comparable).
2. Report p50/p95 over ≥20 timed runs after warmup, per backbone, in JSON with
   the same field shape as
   `docs/evidence/performance/nfr2-onnx-cpu-latency.json`, including the caveat
   text and the machine string, written to
   `docs/evidence/performance/nfr2-backbone-comparison.json`.
3. Exit nonzero if the export or parity plumbing fails; a backbone exceeding
   500 ms is a *result*, not a failure.

**This script is written and smoke-tested here but the evidence run happens on
the Mac** — the existing 381 ms figure is from the M1 Pro, and a cross-machine
comparison is worthless. A run on this machine may be used only to prove the
script works end to end; do not write its output into `docs/evidence/`.

Optional, only if the numbers later justify it: the `PATCH_GRID` 3×3 trade is
already documented as an experiment — do not implement anything for it now.

## Task 4 — Small doc syncs

1. `ml/README.md` §Training step 1: replace the bare "get the corpus" comment
   with the actual `fetch_noaa.py` invocation, and add the bench script to the
   backbone-comparison sentence.
2. `CLAUDE.md` status block: it still reports inference as "22ms … fake model".
   Update to the real NFR2 evidence (381 ms, EfficientNet-B0, ONNX/CPU, D58) and
   note Q6 is resolved (D63). Keep the block's existing tone — measured claims
   with dates.

## Acceptance — all of it, before calling this done

- `make test-train` green, including the new tests (was 29; the count in
  `TESTING.md`'s suite table will drift — check whether
  `scripts/testing_matrix.py --check` cares and update the table if it does).
- `make lint` green.
- New dependency: `huggingface_hub` only, pinned. No API keys, no tokens, no
  experiment trackers.
- Nothing downloaded into `ml/datasets/`, no `runs/` created, `ml/models/`
  untouched.
- Committed and pushed (Task 1 covers the how).

## Explicitly out of scope

Training, downloading the corpus, the Seaview download, touching the test split,
`FAKE_MODE`, HPO, and anything on the project chapters. The Mac-day sequence
itself is already written in `ml/README.md` §Training and this plan must not
duplicate it.
