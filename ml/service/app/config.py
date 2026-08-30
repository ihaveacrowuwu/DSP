"""Runtime configuration for the inference service.

Everything is environment-driven so the same image serves fake-mode development
and real-model deployment. No secrets and no external services are involved (the
project forbids API-key dependencies).
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env_bool(key: str, default: bool) -> bool:
    raw = os.getenv(key)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(key: str, default: int) -> int:
    try:
        return int(os.getenv(key, ""))
    except ValueError:
        return default


def _env_float(key: str, default: float) -> float:
    try:
        return float(os.getenv(key, ""))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    # --- serving mode
    # FAKE_MODE lets the whole platform (API, worker, dashboard, mobile) be built
    # and tested before any model exists, and keeps test suites model-free.
    fake_mode: bool = field(default_factory=lambda: _env_bool("FAKE_MODE", True))

    model_path: str = field(default_factory=lambda: os.getenv("MODEL_PATH", "models/active.onnx"))
    model_version: str = field(default_factory=lambda: os.getenv("MODEL_VERSION", ""))

    # --- patch-grid inference
    # Grid size and overlap are configurable because grid granularity is an
    # experiment we report on, not a fixed constant.
    patch_grid: int = field(default_factory=lambda: _env_int("PATCH_GRID", 5))
    patch_overlap: float = field(default_factory=lambda: _env_float("PATCH_OVERLAP", 0.0))
    input_size: int = field(default_factory=lambda: _env_int("INPUT_SIZE", 224))

    # Severity threshold that turns a bleached fraction into an image-level label.
    # Deliberately not 1/25: a single bleached patch must not condemn a reef.
    bleached_label_threshold: float = field(
        default_factory=lambda: _env_float("BLEACHED_LABEL_THRESHOLD", 0.35)
    )

    # --- preprocessing (must match training exactly; verified by a golden test)
    normalise_mean: tuple[float, float, float] = (0.485, 0.456, 0.406)
    normalise_std: tuple[float, float, float] = (0.229, 0.224, 0.225)

    max_upload_bytes: int = field(default_factory=lambda: _env_int("MAX_UPLOAD_BYTES", 12 * 1024 * 1024))
    onnx_threads: int = field(default_factory=lambda: _env_int("ONNX_THREADS", 2))
    log_level: str = field(default_factory=lambda: os.getenv("LOG_LEVEL", "INFO"))

    @property
    def effective_model_version(self) -> str:
        if self.model_version:
            return self.model_version
        return "fake-0.0.0" if self.fake_mode else "unknown"


settings = Settings()
