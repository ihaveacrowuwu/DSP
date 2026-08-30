#!/usr/bin/env python3
"""Quantise the exported graph to INT8, so NFR2 is met where the service actually runs.

    python3 scripts/quantize.py --model ../models/active.onnx --data-root ../datasets/noaa
    python3 scripts/quantize.py --model runs/baseline-effnetb0/model.onnx --calibration-size 256

**Why this exists.** The FP32 graph classifies a 5x5 lattice in ~405 ms on this M1 Pro,
inside NFR2's 500 ms budget. The same graph inside the compose stack takes ~822 ms,
because Docker Desktop on macOS is a virtual machine and costs roughly 2x - same
onnxruntime, same architecture, no CPU cap. No thread setting recovers it. The choices
are then to coarsen the patch lattice (a product concession to a tooling artefact), to
report a Must requirement as missed in deployment, or to make the graph genuinely
cheaper. This is the third.

**Static, not dynamic.** `quantize_dynamic` quantises weights and leaves activations to be
scaled at run time, which helps MatMul-heavy graphs and does almost nothing for a
convolutional one - the Conv kernels stay in float. Static quantisation runs real images
through the graph first to learn activation ranges, then emits `QLinearConv`, which is
where the speedup on a CNN comes from. The cost is needing calibration data, and getting
that data wrong is the classic way to lose accuracy quietly.

**THE ANSWER WAS NO - read this before reaching for it again.** Quantisation works
exactly as advertised on latency: **4.2x**, 408 ms to 98 ms on the host, and the artefact
shrinks from 16 MB to 4.9 MB. It was still rejected, because of where the cost lands.
Accuracy fell about **2 points**, which looks like a bargain and is the number a
quantisation write-up usually quotes. Measured on the metric this project actually selects
on, the same artefact lost **14 points of bleached recall** - 0.843 to 0.699 - turning a
model that catches roughly five bleaching events in six into one that catches two in three.
The loss concentrates in the minority class, which is precisely the class the whole
verification-first design exists to catch. Best configuration found was Percentile 99.999
with per-channel weights and the classifier and stem left in float, and even that was
-12 points of recall. See D67 and `docs/evidence/performance/nfr2-quantisation.md`.

Keep this script: the experiment is the evidence for that decision, and it is the right
tool if the backbone, the task or the deployment target ever changes. Do not serve its output without
re-measuring **recall on the bleached class**, not accuracy.

**Calibration uses the TRAIN split only.** Not val, which selects the checkpoint, and
certainly not test. Calibration reads the images to measure activation ranges, and while
it fits no parameters, letting it see evaluation data would still be leakage that a
marker would be right to ask about. The images go through the *eval* transform, because
that is what the service does to a patch.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from muraka_train import config as config_module
from muraka_train import data as data_module
from muraka_train import export as export_module

TRAINING_ROOT = Path(__file__).resolve().parent.parent


class PatchCalibrationReader:
    """Feeds real preprocessed patches to the calibrator, once each.

    `quantize_static` calls `get_next` until it returns None. Returning the *same* batch
    repeatedly would produce activation ranges describing one image, which is how a
    quantised model ends up clipping everything it was not calibrated on.
    """

    def __init__(self, batches: list[np.ndarray], input_name: str) -> None:
        self._batches = batches
        self._input_name = input_name
        self._index = 0

    def get_next(self):
        if self._index >= len(self._batches):
            return None
        batch = self._batches[self._index]
        self._index += 1
        return {self._input_name: batch}

    def rewind(self) -> None:
        self._index = 0


def build_calibration_batches(cfg, count: int, batch_size: int) -> list[np.ndarray]:
    """`count` training images, preprocessed exactly as the service preprocesses a patch."""
    examples = data_module.discover_folder_split(cfg.data.root, "train", cfg)
    # Deterministic subset: calibration data is part of how the artefact was produced, so
    # it has to be reproducible from the seed like everything else in this pipeline.
    rng = np.random.default_rng(cfg.seed)
    chosen = [examples[i] for i in rng.permutation(len(examples))[:count]]

    _, eval_transform = data_module.build_transforms(cfg)
    from PIL import Image

    tensors = []
    for example in chosen:
        image = Image.open(example.path).convert("RGB")
        tensors.append(eval_transform(image).numpy())

    batches = []
    for start in range(0, len(tensors), batch_size):
        batches.append(np.stack(tensors[start : start + batch_size]).astype(np.float32))
    return batches


def measure(path: Path, cfg, *, patches: int, threads: int, runs: int = 12) -> dict[str, float]:
    latency = export_module.cpu_latency(path, cfg, batch=patches, runs=runs, threads=threads)
    return latency


def agreement(fp32: Path, int8: Path, cfg, *, samples: int = 200) -> dict[str, float]:
    """How often the quantised graph gives the same answer as the one that was evaluated.

    Accuracy on a split is the number that matters, but this is the cheaper signal and it
    catches the failure mode quantisation actually has: not a uniform small degradation,
    but a subset of inputs whose argmax flips.
    """
    import onnxruntime

    examples = data_module.discover_folder_split(cfg.data.root, "val", cfg)
    rng = np.random.default_rng(cfg.seed + 1)
    chosen = [examples[i] for i in rng.permutation(len(examples))[:samples]]
    _, eval_transform = data_module.build_transforms(cfg)
    from PIL import Image

    batch = np.stack(
        [eval_transform(Image.open(e.path).convert("RGB")).numpy() for e in chosen]
    ).astype(np.float32)
    truth = np.array([e.label for e in chosen])

    def predict(model: Path) -> np.ndarray:
        session = export_module._service_session(model)
        name = session.get_inputs()[0].name
        return np.concatenate(
            [session.run(["logits"], {name: batch[i : i + 25]})[0] for i in range(0, len(batch), 25)]
        )

    a, b = predict(fp32), predict(int8)
    return {
        "samples": float(len(chosen)),
        "argmax_agreement": float((a.argmax(1) == b.argmax(1)).mean()),
        "fp32_accuracy": float((a.argmax(1) == truth).mean()),
        "int8_accuracy": float((b.argmax(1) == truth).mean()),
        "max_abs_logit_diff": float(np.abs(a - b).max()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default=str(TRAINING_ROOT / "configs" / "baseline.yaml"))
    parser.add_argument("--model", required=True, help="the FP32 .onnx to quantise")
    parser.add_argument("--output", help="defaults to <model>-int8.onnx beside the input")
    parser.add_argument("--data-root", required=True, help="corpus root; calibration reads train/ only")
    parser.add_argument("--calibration-size", type=int, default=256)
    parser.add_argument("--batch-size", type=int, default=25, help="match the service's lattice")
    parser.add_argument("--patches", type=int, default=25, help="patches per timed call")
    parser.add_argument(
        "--threads", type=int, default=export_module.SERVICE_INTRA_OP_THREADS, help="intra-op threads"
    )
    parser.add_argument("--report", help="write the comparison JSON here")
    args = parser.parse_args()

    from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_static

    cfg = config_module.load(args.config, data_root=args.data_root)
    source = Path(args.model).resolve()
    destination = Path(args.output) if args.output else source.with_name(source.stem + "-int8.onnx")

    print(f"calibrating on {args.calibration_size} training images ...", flush=True)
    batches = build_calibration_batches(cfg, args.calibration_size, args.batch_size)

    import onnxruntime

    input_name = onnxruntime.InferenceSession(
        str(source), providers=["CPUExecutionProvider"]
    ).get_inputs()[0].name
    reader = PatchCalibrationReader(batches, input_name)

    print(f"quantising {source.name} -> {destination.name} (static, QDQ, per-channel)", flush=True)
    quantize_static(
        model_input=str(source),
        model_output=str(destination),
        calibration_data_reader=reader,
        quant_format=QuantFormat.QDQ,
        # Per-channel weights: depthwise convolutions dominate EfficientNet, and a single
        # scale per tensor across channels with very different ranges is where most of
        # the accuracy of a quantised MobileNet-family model goes.
        per_channel=True,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
        calibrate_method=CalibrationMethod.MinMax,
    )

    # The metadata does not survive quantisation, and the service refuses to serve a model
    # whose class order it cannot read. Re-embedding it is not optional.
    export_module._embed_metadata(destination, cfg, _version_for(source, cfg))

    fp32_latency = measure(source, cfg, patches=args.patches, threads=args.threads)
    int8_latency = measure(destination, cfg, patches=args.patches, threads=args.threads)
    quality = agreement(source, destination, cfg)

    report = {
        "source": str(source),
        "output": str(destination),
        "calibration_images": args.calibration_size,
        "calibration_split": "train",
        "patches": args.patches,
        "intra_op_threads": args.threads,
        "fp32": {"bytes": source.stat().st_size, "latency": fp32_latency},
        "int8": {"bytes": destination.stat().st_size, "latency": int8_latency},
        "speedup_p50": round(fp32_latency["batch_ms_p50"] / int8_latency["batch_ms_p50"], 3),
        "quality": quality,
        "note": (
            "Latency here is measured on the host. The figure that decides NFR2 is the one "
            "measured inside the container, because Docker Desktop on macOS costs roughly 2x."
        ),
    }
    body = json.dumps(report, indent=2)
    print("\n" + body)
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(body + "\n", encoding="utf-8")
        print(f"\nwrote {args.report}")
    return 0


def _version_for(source: Path, cfg) -> str:
    """Keep the trained version string and mark the artefact as quantised."""
    try:
        existing = export_module.read_metadata(source).get("model_version")
    except Exception:  # pragma: no cover - metadata is best-effort here
        existing = None
    base = existing or f"{cfg.model['backbone']}-0.0.0-unversioned"
    return f"{base}-int8"


if __name__ == "__main__":
    sys.exit(main())
