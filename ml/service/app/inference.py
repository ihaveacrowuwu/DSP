"""Patch-grid coral condition inference.

Pipeline:
  1. tile the photo into a grid of square patches over its centre crop
  2. classify every patch (batched) as healthy or bleached
  3. aggregate: severity = bleached fraction; image label from a severity threshold

The NOAA training data consists of square coral samples rather than whole reef
scenes, so tiling is what lets a model trained on those samples say something
useful about a citizen's wide photo. Per-patch results are returned so the
dashboard can draw an overlay and a researcher can see *where* the model looked.
"""

from __future__ import annotations

import hashlib
import io
import logging
import time
from dataclasses import dataclass
from typing import Any

import numpy as np
from PIL import Image, ImageOps

from .config import Settings

log = logging.getLogger(__name__)

HEALTHY = "healthy"
BLEACHED = "bleached"

# Class index -> label. Must match the training label order; a mismatch here is
# the classic silent-accuracy bug, so it is asserted against the model metadata
# when a real model is loaded.
CLASS_LABELS = (HEALTHY, BLEACHED)


@dataclass
class PatchPrediction:
    row: int
    col: int
    label: str
    confidence: float


@dataclass
class Assessment:
    label: str
    confidence: float
    severity: float
    patch_grid: int
    patches: list[PatchPrediction]
    model_version: str
    inference_ms: int
    fake: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "confidence": round(self.confidence, 4),
            "severity": round(self.severity, 4),
            "patch_grid": self.patch_grid,
            "patches": [
                {
                    "row": p.row,
                    "col": p.col,
                    "label": p.label,
                    "confidence": round(p.confidence, 4),
                }
                for p in self.patches
            ],
            "model_version": self.model_version,
            "inference_ms": self.inference_ms,
            "fake": self.fake,
        }


def tile_patches(image: Image.Image, grid: int, size: int, overlap: float = 0.0) -> list[tuple[int, int, Image.Image]]:
    """Split the centre square of an image into grid x grid patches.

    ``overlap`` (0..0.9) grows each patch beyond its cell so neighbouring patches
    share context, which smooths the overlay without changing the grid geometry.
    """
    width, height = image.size
    square = min(width, height)
    step = square / grid
    offset_x = (width - square) / 2
    offset_y = (height - square) / 2

    pad = step * max(0.0, min(overlap, 0.9)) / 2
    patches: list[tuple[int, int, Image.Image]] = []

    for row in range(grid):
        for col in range(grid):
            left = offset_x + col * step - pad
            top = offset_y + row * step - pad
            right = left + step + 2 * pad
            bottom = top + step + 2 * pad

            # Clamp to the image so edge patches stay valid.
            box = (
                int(max(0, round(left))),
                int(max(0, round(top))),
                int(min(width, round(right))),
                int(min(height, round(bottom))),
            )
            if box[2] - box[0] < 2 or box[3] - box[1] < 2:
                continue

            patch = image.crop(box).resize((size, size), Image.BILINEAR)
            patches.append((row, col, patch))

    return patches


class Classifier:
    """Wraps the ONNX session, or fakes it deterministically when no model exists."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._session = None
        self._input_name = ""
        self._model_version = settings.effective_model_version

        if settings.fake_mode:
            log.warning(
                "ML service running in FAKE_MODE: predictions are deterministic stubs, not model output"
            )
            return

        self._load_model()

    # ---------------------------------------------------------------- model load

    def _load_model(self) -> None:
        # Imported lazily so fake mode does not require onnxruntime at all.
        import onnxruntime as ort

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = self.settings.onnx_threads
        opts.inter_op_num_threads = 1
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        # CPU only: the deployment target has no GPU.
        self._session = ort.InferenceSession(
            self.settings.model_path, sess_options=opts, providers=["CPUExecutionProvider"]
        )
        self._input_name = self._session.get_inputs()[0].name

        meta = self._session.get_modelmeta().custom_metadata_map or {}
        if meta.get("model_version"):
            self._model_version = meta["model_version"]
        if meta.get("class_labels"):
            declared = tuple(x.strip() for x in meta["class_labels"].split(","))
            if declared != CLASS_LABELS:
                raise RuntimeError(
                    f"model class order {declared} does not match service expectation {CLASS_LABELS}; "
                    "refusing to serve to avoid silently inverted labels"
                )

        log.info(
            "loaded ONNX model path=%s version=%s input=%s",
            self.settings.model_path,
            self._model_version,
            self._input_name,
        )

    # ---------------------------------------------------------------- properties

    @property
    def model_version(self) -> str:
        return self._model_version

    @property
    def is_fake(self) -> bool:
        return self.settings.fake_mode

    @property
    def ready(self) -> bool:
        return self.settings.fake_mode or self._session is not None

    # ---------------------------------------------------------------- inference

    def _preprocess(self, patches: list[Image.Image]) -> np.ndarray:
        """Convert patches into a normalised NCHW float32 batch.

        This must mirror the training transform byte for byte; the golden-file
        test in tests/ guards the pairing.
        """
        mean = np.array(self.settings.normalise_mean, dtype=np.float32).reshape(1, 3, 1, 1)
        std = np.array(self.settings.normalise_std, dtype=np.float32).reshape(1, 3, 1, 1)

        batch = np.stack([np.asarray(p.convert("RGB"), dtype=np.float32) for p in patches])
        batch = batch.transpose(0, 3, 1, 2) / 255.0
        return ((batch - mean) / std).astype(np.float32)

    def classify(self, image_bytes: bytes) -> Assessment:
        started = time.perf_counter()

        image = Image.open(io.BytesIO(image_bytes))
        # Honour EXIF rotation before tiling, else patch coordinates are wrong.
        image = ImageOps.exif_transpose(image).convert("RGB")

        grid = self.settings.patch_grid
        tiles = tile_patches(image, grid, self.settings.input_size, self.settings.patch_overlap)
        if not tiles:
            raise ValueError("image too small to tile")

        if self.settings.fake_mode:
            probabilities = self._fake_probabilities(image_bytes, len(tiles))
        else:
            probabilities = self._run(([t[2] for t in tiles]))

        patches: list[PatchPrediction] = []
        bleached = 0
        confidences: list[float] = []

        for (row, col, _), probs in zip(tiles, probabilities):
            index = int(np.argmax(probs))
            label = CLASS_LABELS[index]
            confidence = float(probs[index])
            if label == BLEACHED:
                bleached += 1
            confidences.append(confidence)
            patches.append(PatchPrediction(row=row, col=col, label=label, confidence=confidence))

        severity = bleached / len(patches)
        label = BLEACHED if severity >= self.settings.bleached_label_threshold else HEALTHY

        # Image-level confidence: mean confidence of the patches that decided the
        # label, so a mostly-healthy reef is not reported with bleached certainty.
        deciding = [p.confidence for p in patches if p.label == label]
        confidence = float(np.mean(deciding)) if deciding else float(np.mean(confidences))

        return Assessment(
            label=label,
            confidence=confidence,
            severity=severity,
            patch_grid=grid,
            patches=patches,
            model_version=self._model_version,
            inference_ms=int((time.perf_counter() - started) * 1000),
            fake=self.settings.fake_mode,
        )

    def _run(self, patches: list[Image.Image]) -> np.ndarray:
        assert self._session is not None
        batch = self._preprocess(patches)
        logits = self._session.run(None, {self._input_name: batch})[0]
        return _softmax(np.asarray(logits, dtype=np.float32))

    def _fake_probabilities(self, image_bytes: bytes, count: int) -> np.ndarray:
        """Deterministic stand-in predictions.

        Derived from the image's own hash so the same photo always yields the same
        result: mobile, web and API tests can assert on fixed values, and demos
        are reproducible. Patterned (contiguous bleached region) rather than
        uniform noise so overlays look plausible.
        """
        digest = hashlib.sha256(image_bytes).digest()
        seed = int.from_bytes(digest[:8], "big")
        rng = np.random.default_rng(seed)

        # Bleached share between 0 and ~0.8, stable per image.
        target = (digest[8] / 255.0) * 0.8
        bleached_count = int(round(target * count))

        probs = np.zeros((count, 2), dtype=np.float32)
        order = rng.permutation(count)
        bleached_set = set(order[:bleached_count].tolist())

        for i in range(count):
            strength = 0.60 + rng.random() * 0.39
            if i in bleached_set:
                probs[i] = [1.0 - strength, strength]
            else:
                probs[i] = [strength, 1.0 - strength]
        return probs


def _softmax(logits: np.ndarray) -> np.ndarray:
    shifted = logits - logits.max(axis=1, keepdims=True)
    exp = np.exp(shifted)
    return exp / exp.sum(axis=1, keepdims=True)
