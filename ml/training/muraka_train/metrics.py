"""Classification metrics, computed from a confusion matrix.

Hand-written rather than imported, for one reason that matters to this project: the
headline metric is **F2 on the bleached class**, because a missed bleaching event costs
more than a false alarm. That weighting is a scientific decision, not a default, and it
belongs somewhere a reader can check it - with a test that pins the arithmetic against
numbers worked out by hand.

Everything is derived from the confusion matrix, so accuracy and per-class recall cannot
disagree with each other the way two library calls on two different arrays can.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


@dataclass
class Metrics:
    """Everything the evaluation section reports, from one pass over the predictions."""

    confusion: np.ndarray
    labels: tuple[str, ...]
    accuracy: float
    macro_f1: float
    per_class: dict[str, dict[str, float]] = field(default_factory=dict)
    f2_by_class: dict[str, float] = field(default_factory=dict)
    roc_auc: float | None = None

    def as_dict(self) -> dict[str, object]:
        out: dict[str, object] = {
            "accuracy": round(self.accuracy, 6),
            "macro_f1": round(self.macro_f1, 6),
            "confusion": self.confusion.tolist(),
            "labels": list(self.labels),
        }
        for label, scores in self.per_class.items():
            for name, value in scores.items():
                out[f"{name}_{label}"] = round(value, 6)
        for label, value in self.f2_by_class.items():
            out[f"f2_{label}"] = round(value, 6)
        if self.roc_auc is not None:
            out["roc_auc"] = round(self.roc_auc, 6)
        return out

    def get(self, key: str) -> float:
        """Look up a metric by the name a config's `monitor` would use."""
        flat = self.as_dict()
        # `val_` / `test_` prefixes are added by the caller that owns the split, so a
        # monitor of `val_f2_bleached` resolves against `f2_bleached` here.
        for prefix in ("val_", "test_", "train_", ""):
            if key.startswith(prefix):
                candidate = key[len(prefix):]
                if candidate in flat:
                    value = flat[candidate]
                    if isinstance(value, (int, float)):
                        return float(value)
        raise KeyError(f"no metric named {key!r}; available: {sorted(k for k, v in flat.items() if isinstance(v, (int, float)))}")


def confusion_matrix(targets: np.ndarray, predictions: np.ndarray, num_classes: int) -> np.ndarray:
    """Rows are truth, columns are prediction."""
    matrix = np.zeros((num_classes, num_classes), dtype=np.int64)
    # Counted with bincount rather than a Python loop, so a full test split is instant.
    flat = targets.astype(np.int64) * num_classes + predictions.astype(np.int64)
    counts = np.bincount(flat, minlength=num_classes * num_classes)
    matrix += counts.reshape(num_classes, num_classes)
    return matrix


def fbeta(precision: float, recall: float, beta: float) -> float:
    """F-beta from precision and recall. Zero when both are zero, not NaN."""
    if precision <= 0 and recall <= 0:
        return 0.0
    beta_sq = beta * beta
    denominator = beta_sq * precision + recall
    if denominator == 0:
        return 0.0
    return (1 + beta_sq) * precision * recall / denominator


def compute(
    targets: np.ndarray,
    predictions: np.ndarray,
    labels: tuple[str, ...],
    probabilities: np.ndarray | None = None,
) -> Metrics:
    """Every reported metric, from truth and predictions.

    `probabilities` is the positive-class score, used only for ROC-AUC; it is optional
    because a threshold-free metric is not always available.
    """
    num_classes = len(labels)
    matrix = confusion_matrix(targets, predictions, num_classes)
    total = matrix.sum()
    correct = np.trace(matrix)
    accuracy = float(correct / total) if total else 0.0

    per_class: dict[str, dict[str, float]] = {}
    f2_by_class: dict[str, float] = {}
    f1_scores = []
    for index, label in enumerate(labels):
        true_positive = float(matrix[index, index])
        predicted = float(matrix[:, index].sum())
        actual = float(matrix[index, :].sum())
        precision = true_positive / predicted if predicted else 0.0
        recall = true_positive / actual if actual else 0.0
        per_class[label] = {
            "precision": precision,
            "recall": recall,
            "support": actual,
        }
        f1_scores.append(fbeta(precision, recall, 1.0))
        # F2 weights recall four times as heavily as precision: a bleaching event that
        # goes unreported is a worse outcome than one a researcher has to dismiss.
        f2_by_class[label] = fbeta(precision, recall, 2.0)

    roc_auc = None
    if probabilities is not None and num_classes == 2 and len(np.unique(targets)) == 2:
        roc_auc = _roc_auc(targets, probabilities)

    return Metrics(
        confusion=matrix,
        labels=labels,
        accuracy=accuracy,
        macro_f1=float(np.mean(f1_scores)) if f1_scores else 0.0,
        per_class=per_class,
        f2_by_class=f2_by_class,
        roc_auc=roc_auc,
    )


def _roc_auc(targets: np.ndarray, scores: np.ndarray) -> float:
    """ROC-AUC via the rank-sum identity, ties averaged.

    The Mann-Whitney form rather than trapezoidal integration over a threshold sweep:
    it is exact, handles ties correctly, and is three lines instead of thirty.
    """
    positives = targets == 1
    n_pos = int(positives.sum())
    n_neg = int((~positives).sum())
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(len(scores), dtype=np.float64)
    ranks[order] = np.arange(1, len(scores) + 1)
    # Average the ranks within groups of equal score, or tied scores would bias the sum.
    unique, inverse, counts = np.unique(scores, return_inverse=True, return_counts=True)
    if len(unique) != len(scores):
        sums = np.zeros(len(unique))
        np.add.at(sums, inverse, ranks)
        ranks = (sums / counts)[inverse]
    rank_sum = ranks[positives].sum()
    return float((rank_sum - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg))
