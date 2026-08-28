"""Metrics arithmetic, pinned against numbers worked out by hand.

The headline metric is F2 on the bleached class - recall weighted four times as heavily
as precision, because a missed bleaching event costs more than a false alarm. That is a
scientific decision the project defends, so the arithmetic behind it is checked rather
than assumed, and checked against values a reader can verify with a calculator.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from muraka_train import metrics as m

LABELS = ("healthy", "bleached")


def test_confusion_matrix_has_truth_in_rows_and_prediction_in_columns():
    # Two healthy, both right. Three bleached: two right, one called healthy.
    targets = np.array([0, 0, 1, 1, 1])
    predictions = np.array([0, 0, 1, 1, 0])
    matrix = m.confusion_matrix(targets, predictions, 2)
    assert matrix.tolist() == [[2, 0], [1, 2]]
    # Transposing it would silently swap precision and recall everywhere.
    assert matrix[1, 0] == 1, "a bleached reef called healthy belongs at [bleached, healthy]"


def test_accuracy_and_recall_are_derived_from_one_matrix():
    targets = np.array([0, 0, 1, 1, 1])
    predictions = np.array([0, 0, 1, 1, 0])
    result = m.compute(targets, predictions, LABELS)
    assert result.accuracy == pytest.approx(4 / 5)
    assert result.per_class["bleached"]["recall"] == pytest.approx(2 / 3)
    assert result.per_class["bleached"]["precision"] == pytest.approx(1.0)
    assert result.per_class["healthy"]["recall"] == pytest.approx(1.0)
    assert result.per_class["healthy"]["precision"] == pytest.approx(2 / 3)


def test_f2_weights_recall_four_times_as_heavily_as_precision():
    # precision 1.0, recall 0.5 - F1 and F2 must differ, and F2 must be the lower one
    # because the missing half is recall.
    f1 = m.fbeta(1.0, 0.5, 1.0)
    f2 = m.fbeta(1.0, 0.5, 2.0)
    assert f1 == pytest.approx(2 / 3)
    # F2 = 5-P-R / (4P + R) = 5-0.5 / 4.5
    assert f2 == pytest.approx(5 * 1.0 * 0.5 / (4 * 1.0 + 0.5))
    assert f2 < f1, "F2 must punish poor recall harder than F1 does"


def test_f2_rewards_recall_over_precision_at_the_same_f1():
    # Two models with mirror-image precision/recall have the same F1 by construction.
    # F2 must prefer the one that finds more bleaching.
    cautious = m.fbeta(0.9, 0.6, 1.0)
    sensitive = m.fbeta(0.6, 0.9, 1.0)
    assert cautious == pytest.approx(sensitive)
    assert m.fbeta(0.6, 0.9, 2.0) > m.fbeta(0.9, 0.6, 2.0)


def test_a_model_that_never_predicts_bleached_scores_zero_not_nan():
    # The degenerate failure this project cares about: call everything healthy and
    # accuracy still looks respectable on an imbalanced set.
    targets = np.array([0] * 8 + [1] * 2)
    predictions = np.zeros(10, dtype=int)
    result = m.compute(targets, predictions, LABELS)
    assert result.accuracy == pytest.approx(0.8)
    assert result.f2_by_class["bleached"] == 0.0
    assert result.per_class["bleached"]["recall"] == 0.0
    assert not np.isnan(result.macro_f1)
    # And this is why F2-bleached is the monitored metric rather than accuracy: 0.8
    # against 0.0 is the difference between the two.
    assert result.macro_f1 < result.accuracy


def test_perfect_predictions_score_one_everywhere():
    targets = np.array([0, 1, 0, 1])
    result = m.compute(targets, targets.copy(), LABELS)
    assert result.accuracy == 1.0
    assert result.macro_f1 == pytest.approx(1.0)
    assert result.f2_by_class["bleached"] == pytest.approx(1.0)


def test_support_counts_the_truth_not_the_predictions():
    targets = np.array([0, 0, 0, 1])
    predictions = np.array([1, 1, 1, 1])
    result = m.compute(targets, predictions, LABELS)
    assert result.per_class["healthy"]["support"] == 3
    assert result.per_class["bleached"]["support"] == 1


def test_roc_auc_is_one_when_the_scores_separate_the_classes():
    targets = np.array([0, 0, 1, 1])
    scores = np.array([0.1, 0.2, 0.8, 0.9])
    result = m.compute(targets, np.array([0, 0, 1, 1]), LABELS, scores)
    assert result.roc_auc == pytest.approx(1.0)


def test_roc_auc_is_a_half_when_every_score_is_identical():
    # All-ties is the case a naive rank-sum gets wrong, and a model that outputs one
    # constant is a real failure mode worth scoring honestly.
    targets = np.array([0, 0, 1, 1])
    scores = np.array([0.5, 0.5, 0.5, 0.5])
    result = m.compute(targets, np.zeros(4, dtype=int), LABELS, scores)
    assert result.roc_auc == pytest.approx(0.5)


def test_roc_auc_is_zero_when_the_scores_are_exactly_inverted():
    targets = np.array([0, 0, 1, 1])
    scores = np.array([0.9, 0.8, 0.2, 0.1])
    result = m.compute(targets, np.array([1, 1, 0, 0]), LABELS, scores)
    assert result.roc_auc == pytest.approx(0.0)


def test_metrics_can_be_looked_up_by_the_name_a_config_monitors():
    targets = np.array([0, 1, 0, 1])
    result = m.compute(targets, targets.copy(), LABELS)
    # `early_stopping.monitor: val_f2_bleached` must resolve, or a run dies at epoch one.
    assert result.get("val_f2_bleached") == pytest.approx(1.0)
    assert result.get("f2_bleached") == pytest.approx(1.0)
    assert result.get("val_macro_f1") == pytest.approx(1.0)
    with pytest.raises(KeyError):
        result.get("val_not_a_metric")
