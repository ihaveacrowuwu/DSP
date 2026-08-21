"""ONNX export, with a parity check the deployment depends on.

Deployed inference is CPU-only ONNX (`ml/service`), so the artefact that matters is not
the checkpoint but the `.onnx` file. Two things can go wrong between them and neither
raises an error:

1. **The graph is subtly different.** Opset differences, a fused operator, a changed
   default — the export succeeds and the logits move. `verify_parity` runs the same fixed
   inputs through PyTorch and through onnxruntime and requires them to agree to
   `parity_tolerance`. Without it, "the model scored 0.9 in evaluation" is a statement
   about a file nobody serves.

2. **The class order is lost.** The service reads index 0 as `healthy` and refuses to
   load a model whose declared order disagrees — but only if the order is *declared*. So
   the labels are written into the ONNX metadata, which is what
   `inference.py` checks against `CLASS_LABELS`.

The export runs on **CPU** regardless of what trained the model. Tracing on MPS has
produced graphs with device-specific constants baked in, and the deployment target is
CPU anyway, so there is nothing to gain by tracing anywhere else.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import torch


class ParityError(RuntimeError):
    """The exported graph does not agree with the model that was evaluated."""


def export(model: torch.nn.Module, config, *, output: Path | None = None, model_version: str | None = None) -> Path:
    """Write the ONNX file and return its path."""
    settings = config.export
    destination = Path(output) if output else (config.output_dir / "model.onnx")
    destination.parent.mkdir(parents=True, exist_ok=True)

    model = model.eval().to("cpu")
    size = config.data.image_size
    example = torch.randn(1, 3, size, size)

    # The TorchScript-based exporter, deliberately. PyTorch 2.9 makes the
    # `torch.export`/dynamo path the default and warns about this one; switching would
    # change the emitted graph, and the whole reason `verify_parity` exists is that a
    # changed graph is a changed model. Move when there is a reason to, re-run the parity
    # check and the CPU latency figure, and record both — not as a drive-by upgrade.
    torch.onnx.export(
        model,
        example,
        str(destination),
        input_names=["input"],
        output_names=["logits"],
        # The service sends one batch of `patch_grid²` patches — 25 for a 5×5 grid — so
        # the batch dimension must be dynamic or serving would need one call per patch.
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=int(settings.get("opset", 17)),
        do_constant_folding=True,
    )

    _embed_metadata(destination, config, model_version)

    if settings.get("verify_parity", True):
        difference = parity(model, destination, config)
        tolerance = float(settings.get("parity_tolerance", 1e-4))
        if difference > tolerance:
            raise ParityError(
                f"ONNX logits differ from PyTorch by {difference:.3e}, tolerance {tolerance:.1e}. "
                "The exported graph is not the model that was evaluated — do not ship it."
            )
    return destination


def _embed_metadata(path: Path, config, model_version: str | None) -> None:
    """Write class order and version into the ONNX file's metadata."""
    import onnx

    graph = onnx.load(str(path))
    declared = dict(config.export.get("embed_metadata") or {})
    declared["class_labels"] = ",".join(config.data.class_labels)
    version = model_version or declared.get("model_version")
    if version:
        declared["model_version"] = str(version)
    else:
        # Better an obviously provisional version than a null the service cannot report.
        declared["model_version"] = f"{config.model['backbone']}-0.0.0-unversioned"
    declared["image_size"] = str(config.data.image_size)
    declared["normalise_mean"] = ",".join(str(v) for v in config.normalise_mean)
    declared["normalise_std"] = ",".join(str(v) for v in config.normalise_std)

    del graph.metadata_props[:]
    for key, value in declared.items():
        if value is None:
            continue
        entry = graph.metadata_props.add()
        entry.key, entry.value = str(key), str(value)
    onnx.save(graph, str(path))


def parity(model: torch.nn.Module, onnx_path: Path, config, *, batch: int = 4, seed: int = 0) -> float:
    """Largest absolute logit difference between PyTorch and onnxruntime."""
    import onnxruntime

    size = config.data.image_size
    generator = torch.Generator().manual_seed(seed)
    # A fixed input, not a random one per call: a parity check that uses fresh noise each
    # time cannot be reproduced when it fails.
    example = torch.randn(batch, 3, size, size, generator=generator)

    with torch.no_grad():
        expected = model.eval().to("cpu")(example).numpy()

    session = onnxruntime.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    actual = session.run(["logits"], {"input": example.numpy()})[0]

    if expected.shape != actual.shape:
        raise ParityError(f"shape mismatch: PyTorch {expected.shape} vs ONNX {actual.shape}")
    return float(np.abs(expected - actual).max())


def read_metadata(onnx_path: Path) -> dict[str, str]:
    """The metadata the service will read back."""
    import onnx

    graph = onnx.load(str(onnx_path))
    return {entry.key: entry.value for entry in graph.metadata_props}


def cpu_latency(onnx_path: Path, config, *, batch: int | None = None, runs: int = 20) -> dict[str, float]:
    """Per-image CPU latency for one patch batch — NFR2's ≤500 ms per image.

    The batch defaults to `patch_grid²`, because the service never classifies one patch:
    it sends the whole lattice for a photograph in a single call, and the per-image figure
    the requirement asks about is that call divided by the patches in it.
    """
    import onnxruntime

    grid = int(config.raw.get("data", {}).get("patch_grid", 5))
    count = batch or grid * grid
    size = config.data.image_size
    example = np.random.default_rng(0).standard_normal((count, 3, size, size), dtype=np.float32)

    session = onnxruntime.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    # Two warm-up calls: the first includes graph optimisation and arena allocation, and
    # reporting that as latency would overstate it several-fold.
    for _ in range(2):
        session.run(["logits"], {"input": example})

    import time

    samples = []
    for _ in range(runs):
        started = time.perf_counter()
        session.run(["logits"], {"input": example})
        samples.append((time.perf_counter() - started) * 1000)

    samples.sort()
    return {
        "patches": float(count),
        "batch_ms_p50": round(samples[len(samples) // 2], 2),
        "batch_ms_p95": round(samples[int(0.95 * (len(samples) - 1))], 2),
        "per_patch_ms_p50": round(samples[len(samples) // 2] / count, 3),
    }
