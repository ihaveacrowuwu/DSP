"""Load a run configuration, and refuse to run one that contradicts the server.

The training recipe and the serving code hold the same three facts in two different
places, and if they ever disagree the system does not crash — it quietly returns wrong
answers:

* **Class order.** The model emits logits in a fixed order and the service reads index 0
  as `healthy`. Swap the training order and every prediction inverts, with full
  confidence. `baseline.yaml` says so in a comment; a comment cannot fail a build.
* **Normalisation.** Train on ImageNet statistics and serve on different ones and the
  model sees images it was never trained on. Accuracy degrades quietly rather than
  visibly, which is the worst failure mode for a project whose whole argument is that
  experts correct the model.
* **Image size.** The service tiles a photograph into `patch_grid²` patches and resizes
  each to the model's input; a mismatch is silent letterboxing.

So the config is validated against `ml/service/app` at load time. This is deliberately
not a lint rule: it must fail the run that would produce a bad model, not a checkout.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

# The service is a sibling package, not an installed dependency, so its constants are
# read by importing it from source. Importing rather than re-declaring is the point:
# a copy would be one more thing to drift.
_SERVICE_ROOT = Path(__file__).resolve().parent.parent.parent / "service"


class ConfigError(RuntimeError):
    """A configuration that must not be trained from."""


def _service_constants() -> tuple[tuple[str, ...], tuple[float, ...], tuple[float, ...]]:
    """`(class_labels, normalise_mean, normalise_std)` as the service sees them."""
    if str(_SERVICE_ROOT) not in sys.path:
        sys.path.insert(0, str(_SERVICE_ROOT))
    try:
        from app.config import Settings  # type: ignore[import-not-found]
        from app.inference import CLASS_LABELS  # type: ignore[import-not-found]
    except ImportError as err:  # pragma: no cover - depends on the checkout
        raise ConfigError(
            f"cannot import the serving code from {_SERVICE_ROOT}: {err}. "
            "Training must agree with serving, so it will not run without checking."
        ) from err
    settings = Settings()
    return tuple(CLASS_LABELS), tuple(settings.normalise_mean), tuple(settings.normalise_std)


@dataclass(frozen=True)
class DataConfig:
    source: str
    class_labels: tuple[str, ...]
    folder_map: dict[str, str]
    image_size: int
    num_workers: int
    use_provided_splits: bool
    test_split_locked: bool
    # Set by --data-root at run time; the config file names a dataset, not a path.
    root: Path | None = None


@dataclass(frozen=True)
class TrainingConfig:
    device: str
    epochs: int
    batch_size: int
    head_lr: float
    backbone_lr: float
    unfreeze_epoch: int
    weight_decay: float
    label_smoothing: float
    grad_clip: float
    monitor: str
    patience: int
    mode: str
    amp: bool


@dataclass(frozen=True)
class RunConfig:
    name: str
    seed: int
    deterministic: bool
    output_dir: Path
    data: DataConfig
    training: TrainingConfig
    model: dict[str, Any]
    imbalance: dict[str, Any]
    augmentation: dict[str, Any]
    preprocessing: dict[str, Any]
    evaluation: dict[str, Any]
    export: dict[str, Any]
    raw: dict[str, Any] = field(repr=False, default_factory=dict)

    @property
    def normalise_mean(self) -> tuple[float, ...]:
        return tuple(self.preprocessing["normalise_mean"])

    @property
    def normalise_std(self) -> tuple[float, ...]:
        return tuple(self.preprocessing["normalise_std"])


def load(path: str | Path, *, data_root: str | Path | None = None, check_service: bool = True) -> RunConfig:
    """Read a YAML recipe, validate it, and cross-check it against the service."""
    path = Path(path)
    with path.open(encoding="utf-8") as handle:
        raw = yaml.safe_load(handle)

    for section in ("run", "data", "model", "training", "preprocessing", "export"):
        if section not in raw:
            raise ConfigError(f"{path}: missing required section '{section}'")

    run, data, training = raw["run"], raw["data"], raw["training"]
    early = training.get("early_stopping", {})

    config = RunConfig(
        name=run["name"],
        seed=int(run["seed"]),
        deterministic=bool(run.get("deterministic", True)),
        # Relative to the config file's parent's parent (training/), so a recipe can be
        # run from anywhere without its outputs landing in the caller's directory.
        output_dir=(path.parent.parent / run["output_dir"]).resolve(),
        data=DataConfig(
            source=data["source"],
            class_labels=tuple(data["class_labels"]),
            folder_map=dict(data.get("folder_map", {})),
            image_size=int(data["image_size"]),
            num_workers=int(data.get("num_workers", 4)),
            use_provided_splits=bool(data.get("use_provided_splits", True)),
            test_split_locked=bool(data.get("test_split_locked", True)),
            root=Path(data_root).resolve() if data_root else None,
        ),
        training=TrainingConfig(
            device=str(training.get("device", "cpu")),
            epochs=int(training["epochs"]),
            batch_size=int(training["batch_size"]),
            head_lr=float(training["head_lr"]),
            backbone_lr=float(training["backbone_lr"]),
            unfreeze_epoch=int(training["unfreeze_epoch"]),
            weight_decay=float(training.get("weight_decay", 0.0)),
            label_smoothing=float(training.get("label_smoothing", 0.0)),
            grad_clip=float(training.get("grad_clip", 0.0)),
            monitor=str(early.get("monitor", "val_macro_f1")),
            patience=int(early.get("patience", 8)),
            mode=str(early.get("mode", "max")),
            amp=bool(training.get("amp", False)),
        ),
        model=dict(raw["model"]),
        imbalance=dict(raw.get("imbalance", {})),
        augmentation=dict(raw.get("augmentation", {})),
        preprocessing=dict(raw["preprocessing"]),
        evaluation=dict(raw.get("evaluation", {})),
        export=dict(raw["export"]),
        raw=raw,
    )

    _validate(config)
    if check_service:
        _cross_check_with_service(config)
    return config


def _validate(config: RunConfig) -> None:
    if len(config.data.class_labels) != int(config.model["num_classes"]):
        raise ConfigError(
            f"data.class_labels has {len(config.data.class_labels)} entries but "
            f"model.num_classes is {config.model['num_classes']}"
        )
    if config.training.unfreeze_epoch >= config.training.epochs:
        raise ConfigError(
            f"training.unfreeze_epoch ({config.training.unfreeze_epoch}) is not before "
            f"training.epochs ({config.training.epochs}); the backbone would never unfreeze"
        )
    if config.training.mode not in {"max", "min"}:
        raise ConfigError(f"early_stopping.mode must be 'max' or 'min', not {config.training.mode!r}")
    for key in ("normalise_mean", "normalise_std"):
        if len(config.preprocessing[key]) != 3:
            raise ConfigError(f"preprocessing.{key} must have three channels")
    if any(value <= 0 for value in config.normalise_std):
        raise ConfigError("preprocessing.normalise_std must be positive in every channel")
    unmapped = set(config.data.folder_map.values()) - set(config.data.class_labels)
    if unmapped:
        raise ConfigError(f"data.folder_map maps to labels that are not in class_labels: {sorted(unmapped)}")


def _cross_check_with_service(config: RunConfig) -> None:
    """The check this module exists for. See the module docstring."""
    labels, mean, std = _service_constants()

    if tuple(config.data.class_labels) != labels:
        raise ConfigError(
            f"class order disagrees with the service: training would emit {config.data.class_labels} "
            f"but ml/service/app/inference.py reads {labels}. Training in this order would invert "
            "every prediction, confidently. Fix the config, not the service."
        )

    def differs(a: tuple[float, ...], b: tuple[float, ...]) -> bool:
        return len(a) != len(b) or any(abs(x - y) > 1e-9 for x, y in zip(a, b))

    if differs(config.normalise_mean, mean) or differs(config.normalise_std, std):
        raise ConfigError(
            "normalisation disagrees with the service: training would use "
            f"mean={config.normalise_mean} std={config.normalise_std}, the service uses "
            f"mean={mean} std={std}. The model would be served images it never saw in training."
        )
