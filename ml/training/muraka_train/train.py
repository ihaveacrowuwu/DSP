"""The training loop, with the reproducibility NFR16 asks for.

"Config-driven, seeded, with metrics logged per run" is a testable claim, and the way it
is kept true here is that **every** source of randomness is seeded from `run.seed` —
Python, NumPy, Torch, the DataLoader's shuffle generator and the oversampling sampler —
and every number the run produces lands in `metrics.csv` and `summary.json` beside the
checkpoint. Two runs of the same config must agree, and `tests/test_reproducibility.py`
asserts it rather than trusting it.

The test split is **not touched** by anything in this module when
`data.test_split_locked` is set. Val drives every decision; test is opened once, by
`scripts/evaluate.py`, at the end. That discipline is worth more to the project than a
tenth of a point of accuracy.
"""

from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from torch import nn

from . import data as data_module
from . import metrics as metrics_module
from . import model as model_module


def seed_everything(seed: int, deterministic: bool) -> None:
    """Seed every generator this pipeline draws from."""
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    if deterministic:
        torch.backends.cudnn.deterministic = True
        torch.backends.cudnn.benchmark = False
        # `warn_only`: several MPS and CPU kernels have no deterministic
        # implementation, and refusing to run would make the flag unusable on the
        # machine the project actually trains on. The warning is the honest signal.
        torch.use_deterministic_algorithms(True, warn_only=True)


@dataclass
class EpochResult:
    epoch: int
    train_loss: float
    val_loss: float
    val_metrics: metrics_module.Metrics
    seconds: float
    backbone_trainable: bool


class Trainer:
    def __init__(self, config, loaders: dict[str, torch.utils.data.DataLoader], *, log: bool = True) -> None:
        self.config = config
        self.loaders = loaders
        self.log = log
        self.device = model_module.resolve_device(config.training.device)
        seed_everything(config.seed, config.deterministic)

        self.model = model_module.build(config).to(self.device)
        self.optimiser = model_module.build_optimiser(self.model, config)
        model_module.set_backbone_trainable(self.model, config, False)

        weight = None
        if config.imbalance.get("class_weighted_loss"):
            examples = loaders["train"].dataset.examples  # type: ignore[attr-defined]
            weight = data_module.class_weights(
                examples,
                len(config.data.class_labels),
                float(config.imbalance.get("bleached_loss_multiplier", 1.0)),
            ).to(self.device)
        self.criterion = nn.CrossEntropyLoss(
            weight=weight, label_smoothing=config.training.label_smoothing
        )

        self.history: list[EpochResult] = []
        self.best_score: float | None = None
        self.best_epoch = -1
        self.best_state: dict[str, torch.Tensor] | None = None

    # ── the loop ────────────────────────────────────────────────────────────

    def fit(self) -> list[EpochResult]:
        config = self.config
        patience_left = config.training.patience

        for epoch in range(1, config.training.epochs + 1):
            if epoch == config.training.unfreeze_epoch:
                model_module.set_backbone_trainable(self.model, config, True)
                self._say(f"epoch {epoch}: backbone unfrozen at lr {config.training.backbone_lr}")

            started = time.perf_counter()
            train_loss = self._train_one_epoch()
            val_loss, val_metrics = self.evaluate(self.loaders["val"])
            elapsed = time.perf_counter() - started

            backbone_trainable = epoch >= config.training.unfreeze_epoch
            result = EpochResult(epoch, train_loss, val_loss, val_metrics, elapsed, backbone_trainable)
            self.history.append(result)

            score = val_metrics.get(config.training.monitor)
            improved = (
                self.best_score is None
                or (config.training.mode == "max" and score > self.best_score)
                or (config.training.mode == "min" and score < self.best_score)
            )
            if improved:
                self.best_score, self.best_epoch = score, epoch
                # Kept on the CPU: a checkpoint holding MPS tensors cannot be loaded on
                # a machine without MPS, which is most machines.
                self.best_state = {k: v.detach().cpu().clone() for k, v in self.model.state_dict().items()}
                patience_left = config.training.patience
            else:
                patience_left -= 1

            self._say(
                f"epoch {epoch:>3}  train_loss {train_loss:.4f}  val_loss {val_loss:.4f}  "
                f"{config.training.monitor} {score:.4f}"
                f"{'  *' if improved else ''}  ({elapsed:.1f}s)"
            )

            if patience_left <= 0:
                self._say(
                    f"early stop: {config.training.monitor} has not improved for "
                    f"{config.training.patience} epochs (best {self.best_score:.4f} at epoch {self.best_epoch})"
                )
                break

        if self.best_state is not None:
            self.model.load_state_dict(self.best_state)
        return self.history

    def _train_one_epoch(self) -> float:
        self.model.train()
        total, seen = 0.0, 0
        for images, targets in self.loaders["train"]:
            images, targets = images.to(self.device), targets.to(self.device)
            self.optimiser.zero_grad(set_to_none=True)
            loss = self.criterion(self.model(images), targets)
            loss.backward()
            if self.config.training.grad_clip:
                nn.utils.clip_grad_norm_(self.model.parameters(), self.config.training.grad_clip)
            self.optimiser.step()
            total += float(loss.detach()) * images.size(0)
            seen += images.size(0)
        return total / max(seen, 1)

    @torch.no_grad()
    def evaluate(self, loader) -> tuple[float, metrics_module.Metrics]:
        self.model.eval()
        total, seen = 0.0, 0
        all_targets, all_predictions, all_scores = [], [], []
        for images, targets in loader:
            images, targets = images.to(self.device), targets.to(self.device)
            logits = self.model(images)
            total += float(self.criterion(logits, targets)) * images.size(0)
            seen += images.size(0)
            probabilities = torch.softmax(logits, dim=1)
            all_targets.append(targets.cpu().numpy())
            all_predictions.append(logits.argmax(dim=1).cpu().numpy())
            # Column 1 is bleached, guaranteed by the config's service cross-check.
            all_scores.append(probabilities[:, 1].cpu().numpy())

        targets_array = np.concatenate(all_targets) if all_targets else np.array([], dtype=np.int64)
        predictions_array = np.concatenate(all_predictions) if all_predictions else np.array([], dtype=np.int64)
        scores_array = np.concatenate(all_scores) if all_scores else None
        computed = metrics_module.compute(
            targets_array, predictions_array, self.config.data.class_labels, scores_array
        )
        return total / max(seen, 1), computed

    # ── outputs ─────────────────────────────────────────────────────────────

    def save(self, extra: dict[str, object] | None = None) -> Path:
        """Write the checkpoint, per-epoch CSV and run summary. Returns the directory."""
        out = self.config.output_dir
        out.mkdir(parents=True, exist_ok=True)

        torch.save(
            {
                "state_dict": self.model.state_dict(),
                "config": self.config.raw,
                "best_epoch": self.best_epoch,
                "best_score": self.best_score,
                "class_labels": list(self.config.data.class_labels),
            },
            out / "best.pt",
        )

        # Per-epoch CSV, because a learning curve in the project needs the whole series
        # and not just the final number.
        header = ["epoch", "train_loss", "val_loss", "seconds", "backbone_trainable"]
        metric_keys = sorted(
            k for k, v in (self.history[0].val_metrics.as_dict().items() if self.history else [])
            if isinstance(v, (int, float))
        )
        with (out / "metrics.csv").open("w", encoding="utf-8") as handle:
            handle.write(",".join(header + [f"val_{k}" for k in metric_keys]) + "\n")
            for row in self.history:
                flat = row.val_metrics.as_dict()
                values = [
                    str(row.epoch), f"{row.train_loss:.6f}", f"{row.val_loss:.6f}",
                    f"{row.seconds:.3f}", str(row.backbone_trainable).lower(),
                ]
                values += [f"{float(flat[k]):.6f}" for k in metric_keys]
                handle.write(",".join(values) + "\n")

        summary: dict[str, object] = {
            "run": self.config.name,
            "seed": self.config.seed,
            "deterministic": self.config.deterministic,
            "device_requested": self.config.training.device,
            "device_used": str(self.device),
            "backbone": self.config.model["backbone"],
            "epochs_run": len(self.history),
            "epochs_configured": self.config.training.epochs,
            "monitor": self.config.training.monitor,
            "best_epoch": self.best_epoch,
            "best_score": self.best_score,
            "class_labels": list(self.config.data.class_labels),
            "final_val": self.history[-1].val_metrics.as_dict() if self.history else {},
            "best_val": (
                self.history[self.best_epoch - 1].val_metrics.as_dict()
                if 0 < self.best_epoch <= len(self.history) else {}
            ),
            "total_seconds": round(sum(r.seconds for r in self.history), 2),
        }
        if extra:
            summary.update(extra)
        with (out / "summary.json").open("w", encoding="utf-8") as handle:
            json.dump(summary, handle, indent=2)
            handle.write("\n")

        self._say(f"wrote {out}/best.pt, metrics.csv, summary.json")
        return out

    def _say(self, message: str) -> None:
        if self.log:
            print(message, flush=True)
