#!/usr/bin/env python3
"""Measure CPU inference latency per backbone, so NFR2's headroom is a number.

    python3 scripts/bench_backbones.py
    python3 scripts/bench_backbones.py --backbones efficientnet_b0 resnet18 --runs 40

The question this answers is "could a larger backbone still fit under 500 ms?", and it
answers it by measuring rather than by arguing from parameter counts.

**Random weights are valid evidence here.** Latency is a function of the architecture,
the input size and the runtime - not of what the weights learned - so an untrained graph
times identically to a trained one. That is also why this script does not download
ImageNet weights: they would cost bandwidth and change nothing. The caveat travels in the
output file rather than living only in this docstring, because the file is what a reader
of the project sees.

Two things are held fixed on purpose:

* **The batch is one photograph**, `patch_grid2` = 25 patches, because the service
  classifies a whole lattice in a single call. A per-patch figure would understate the
  requirement by 25x.
* **The session is the service's session** - `ONNX_THREADS` intra-op, one inter-op,
  full graph optimisation. Thread count moves this number by more than the choice of
  backbone does, so a benchmark on onnxruntime's defaults would rank architectures
  correctly and size the headroom wrongly.

A backbone over budget is a **result**, not a failure: this exits nonzero only if the
export or the parity check breaks, which would mean the measurement is of a graph that
does not match its model.
"""

from __future__ import annotations

import argparse
import json
import platform
import subprocess
import sys
import tempfile
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from muraka_train import config as config_module
from muraka_train import export as export_module
from muraka_train import model as model_module

TRAINING_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = TRAINING_ROOT / "configs" / "baseline.yaml"
DEFAULT_OUTPUT = TRAINING_ROOT.parent.parent / "docs" / "evidence" / "performance" / "nfr2-backbone-comparison.json"

# The recipe's own backbone first, then the two the recipe names as candidates for a
# second run ("one modern timm backbone of similar size").
DEFAULT_BACKBONES = ["efficientnet_b0", "convnext_tiny", "efficientnet_v2_s"]

THRESHOLD_MS = 500

CAVEAT = (
    "Random weights, deliberately: latency depends on the architecture, the input size "
    "and the runtime, not on what the weights learned, so an untrained graph times "
    "identically to a trained one. These figures are evidence for the backbone choice "
    "and say nothing about accuracy. The session matches ml/service/app/inference.py "
    "(intra-op threads from ONNX_THREADS, one inter-op thread, full graph optimisation) "
    "because a figure measured on onnxruntime's defaults describes a deployment nobody runs."
)


def machine_string() -> str:
    """Something a reader can compare two runs against."""
    import onnxruntime

    cpu = platform.processor() or platform.machine()
    if sys.platform == "darwin":
        try:
            cpu = subprocess.run(
                ["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True, check=True
            ).stdout.strip()
        except (subprocess.SubprocessError, OSError):
            pass
    return f"{cpu}, onnxruntime {onnxruntime.__version__}, CPUExecutionProvider"


def measure(backbone: str, cfg, workdir: Path, *, runs: int, threads: int) -> dict[str, object]:
    """Export one backbone and time a single-photograph batch through it."""
    # The config is shared, so the backbone is swapped rather than the file rewritten.
    # `pretrained` is forced off: this is a latency test and ImageNet weights are a
    # download that would not move the number.
    cfg.model["backbone"] = backbone
    cfg.model["pretrained"] = "none"

    model = model_module.build(cfg)
    destination = workdir / f"{backbone}.onnx"
    # Reuse the project's exporter rather than writing a second one: D59 chose this
    # exporter deliberately, and a benchmark of a differently-produced graph would not
    # be a benchmark of what gets served. `export` runs the parity check itself.
    export_module.export(model, cfg, output=destination, model_version=f"{backbone}-latencybench")
    parity = export_module.parity(model, destination, cfg)
    latency = export_module.cpu_latency(destination, cfg, runs=runs, threads=threads)

    parameters = sum(p.numel() for p in model.parameters())
    return {
        "backbone": backbone,
        "parameters_m": round(parameters / 1e6, 2),
        "onnx_bytes": destination.stat().st_size,
        "onnx_parity_max_abs_diff": parity,
        "latency": latency,
        "passed": latency["batch_ms_p50"] <= THRESHOLD_MS,
    }


def sweep_threads(backbone: str, cfg, workdir: Path, *, runs: int, counts: list[int]) -> list[dict[str, object]]:
    """The same graph at several `ONNX_THREADS` settings.

    This exists because the first NFR2 measurement took onnxruntime's defaults and the
    service does not: on a 10-core machine that is a 1.25x difference, which was the
    whole gap between "24% headroom" and "4% headroom". A sweep makes the setting's cost
    explicit instead of leaving it to a default nobody chose.
    """
    cfg.model["backbone"] = backbone
    cfg.model["pretrained"] = "none"
    model = model_module.build(cfg)
    destination = workdir / f"{backbone}-sweep.onnx"
    export_module.export(model, cfg, output=destination, model_version=f"{backbone}-threadsweep")

    rows = []
    for threads in counts:
        latency = export_module.cpu_latency(destination, cfg, runs=runs, threads=threads)
        rows.append(
            {
                "intra_op_threads": threads,
                "latency": latency,
                # p95 rather than p50: a requirement met at the median and missed at the
                # tail is not met. The stack shares this machine with Postgres, the API
                # and the worker, so the tail is the honest number.
                "passed": latency["batch_ms_p95"] <= THRESHOLD_MS,
            }
        )
        print(
            f"  threads={threads}: p50 {latency['batch_ms_p50']} ms, p95 {latency['batch_ms_p95']} ms",
            flush=True,
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--backbones", nargs="+", default=DEFAULT_BACKBONES)
    parser.add_argument(
        "--sweep-threads",
        nargs="*",
        type=int,
        metavar="N",
        help="also measure the first backbone at these ONNX_THREADS values (default 1 2 3 4 6 8)",
    )
    parser.add_argument("--runs", type=int, default=25, help="timed runs after warm-up; the plan asks for >= 20")
    parser.add_argument(
        "--threads",
        type=int,
        default=export_module.SERVICE_INTRA_OP_THREADS,
        help="intra-op threads; defaults to the service's ONNX_THREADS",
    )
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the result but do not write it into docs/evidence — for proving the script works off-machine",
    )
    args = parser.parse_args()

    if args.runs < 20:
        parser.error("at least 20 timed runs, or the percentiles are noise")

    cfg = config_module.load(args.config)
    unsupported = [b for b in args.backbones if b not in model_module.SUPPORTED]
    if unsupported:
        parser.error(f"unsupported backbone(s) {unsupported}; available: {sorted(model_module.SUPPORTED)}")

    results = []
    sweep: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix="muraka-bench-") as tmp:
        workdir = Path(tmp)
        if args.sweep_threads is not None:
            counts = args.sweep_threads or [1, 2, 3, 4, 6, 8]
            print(f"sweeping ONNX_THREADS for {args.backbones[0]} ...", flush=True)
            try:
                sweep = sweep_threads(args.backbones[0], cfg, workdir, runs=args.runs, counts=counts)
            except export_module.ParityError as error:
                print(f"\nEXPORT/PARITY FAILED during the sweep: {error}", file=sys.stderr)
                return 1
        for backbone in args.backbones:
            print(f"measuring {backbone} ...", flush=True)
            try:
                result = measure(backbone, cfg, workdir, runs=args.runs, threads=args.threads)
            except export_module.ParityError as error:
                # Not a slow backbone - a broken measurement. Loud, and nonzero.
                print(f"\nEXPORT/PARITY FAILED for {backbone}: {error}", file=sys.stderr)
                return 1
            results.append(result)
            verdict = "under" if result["passed"] else "OVER"
            print(
                f"  {backbone}: p50 {result['latency']['batch_ms_p50']} ms, "
                f"p95 {result['latency']['batch_ms_p95']} ms — {verdict} the {THRESHOLD_MS} ms budget"
            )

    report = {
        "measured_at": date.today().isoformat(),
        "what": (
            "CPU inference latency of the exported ONNX graph for each candidate backbone, "
            f"at {cfg.data.image_size}px, one photograph per call."
        ),
        "why": (
            "NFR2 requires CPU inference at or under 500 ms per image, and the recipe's backbone "
            "choice should be bounded by measurement rather than by argument. The service classifies "
            "a whole patch lattice for one photograph in a single call, so the per-image figure is that call."
        ),
        "caveat": CAVEAT,
        "machine": machine_string(),
        "intra_op_threads": args.threads,
        "timed_runs": args.runs,
        "threshold_ms": THRESHOLD_MS,
        "backbones": results,
    }
    if sweep:
        report["thread_sweep"] = {
            "backbone": args.backbones[0],
            "why": (
                "The service sets ONNX_THREADS=2 (deploy/docker-compose.yml). onnxruntime's default is "
                "one thread per core, which on this machine is a materially faster and undeployed "
                "configuration. This sweep is what makes the setting's cost explicit."
            ),
            "results": sweep,
        }

    body = json.dumps(report, indent=2)
    if args.dry_run:
        print("\n--dry-run: not written\n")
        print(body)
        return 0

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(body + "\n", encoding="utf-8")
    print(f"\nwrote {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
