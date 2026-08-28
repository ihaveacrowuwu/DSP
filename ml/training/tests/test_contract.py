"""Training must not disagree with serving.

Three facts are held in two places - the recipe and `ml/service/app` - and every one of
them fails *silently* when they diverge. A wrong class order does not crash; it inverts
every prediction with full confidence. Wrong normalisation does not crash; it degrades
accuracy invisibly. A wrong image size does not crash; it letterboxes.

`baseline.yaml` warns about all three in comments. These tests are what makes the
warnings enforceable, and they are deliberately in the *training* suite rather than a
lint rule: the thing to prevent is producing a bad model, not committing a bad file.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml

TRAINING_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TRAINING_ROOT))

from muraka_train import config as config_module

BASELINE = TRAINING_ROOT / "configs" / "baseline.yaml"


def test_the_baseline_recipe_loads_and_agrees_with_the_service():
    # The whole point: loading is what runs the cross-check.
    cfg = config_module.load(BASELINE)
    assert cfg.data.class_labels == ("healthy", "bleached")
    assert cfg.model["num_classes"] == len(cfg.data.class_labels)


def test_the_service_and_the_recipe_name_the_classes_in_the_same_order():
    labels, _, _ = config_module._service_constants()
    recipe = yaml.safe_load(BASELINE.read_text())["data"]["class_labels"]
    assert tuple(recipe) == labels, (
        "index 0 must mean the same thing to the trainer and the server; "
        "swapping these inverts every prediction"
    )


def test_the_service_and_the_recipe_normalise_identically():
    _, mean, std = config_module._service_constants()
    preprocessing = yaml.safe_load(BASELINE.read_text())["preprocessing"]
    assert tuple(preprocessing["normalise_mean"]) == mean
    assert tuple(preprocessing["normalise_std"]) == std


def _write(tmp_path: Path, mutate) -> Path:
    raw = yaml.safe_load(BASELINE.read_text())
    mutate(raw)
    path = tmp_path / "mutated.yaml"
    # The loader resolves output_dir relative to the config's parent's parent, so the
    # copy is nested to keep that behaviour identical to the real recipe.
    nested = tmp_path / "configs"
    nested.mkdir(exist_ok=True)
    path = nested / "mutated.yaml"
    path.write_text(yaml.safe_dump(raw))
    return path


def test_an_inverted_class_order_is_refused(tmp_path):
    def invert(raw):
        raw["data"]["class_labels"] = ["bleached", "healthy"]
        raw["data"]["folder_map"] = {"CORAL": "healthy", "CORAL_BL": "bleached"}

    with pytest.raises(config_module.ConfigError, match="class order disagrees"):
        config_module.load(_write(tmp_path, invert))


def test_mismatched_normalisation_is_refused(tmp_path):
    def retune(raw):
        raw["preprocessing"]["normalise_mean"] = [0.5, 0.5, 0.5]

    with pytest.raises(config_module.ConfigError, match="normalisation disagrees"):
        config_module.load(_write(tmp_path, retune))


def test_a_class_count_that_contradicts_the_labels_is_refused(tmp_path):
    def bad(raw):
        raw["model"]["num_classes"] = 3

    with pytest.raises(config_module.ConfigError, match="num_classes"):
        config_module.load(_write(tmp_path, bad))


def test_an_unfreeze_epoch_past_the_end_of_training_is_refused(tmp_path):
    # A schedule where the backbone never unfreezes trains a head on frozen features and
    # reports it as a fine-tuned model.
    def bad(raw):
        raw["training"]["unfreeze_epoch"] = raw["training"]["epochs"]

    with pytest.raises(config_module.ConfigError, match="unfreeze"):
        config_module.load(_write(tmp_path, bad))


def test_a_folder_map_pointing_at_an_unknown_label_is_refused(tmp_path):
    def bad(raw):
        raw["data"]["folder_map"] = {"CORAL": "healthy", "CORAL_BL": "bleeched"}

    with pytest.raises(config_module.ConfigError, match="folder_map"):
        config_module.load(_write(tmp_path, bad))


def test_a_zero_standard_deviation_is_refused(tmp_path):
    # Would divide by zero at normalisation time, producing inf and a silent NaN loss.
    def bad(raw):
        raw["preprocessing"]["normalise_std"] = [0.229, 0.0, 0.225]

    with pytest.raises(config_module.ConfigError):
        config_module.load(_write(tmp_path, bad), check_service=False)


def test_the_export_target_is_where_the_service_looks_for_a_model():
    raw = yaml.safe_load(BASELINE.read_text())
    # docker-compose mounts ml/models into the container as /app/models and the service
    # reads MODEL_PATH=/app/models/active.onnx, so the recipe must export to that name.
    assert raw["export"]["output"].endswith("models/active.onnx")
    assert raw["export"]["format"] == "onnx"


def test_the_benchmark_measures_the_threads_the_stack_deploys():
    """A latency benchmark must use the thread count the service will actually run.

    This is the check that D58's 381 ms figure needed and did not have. That number was a
    correct measurement of `onnxruntime`'s default thread count - one per core - while the
    stack shipped ONNX_THREADS=2, and the difference was 1.25x: the whole gap between
    "24% headroom" and a p95 over the 500 ms budget. Nothing failed, because nothing was
    comparing the two.
    """
    import re

    from muraka_train import export as export_module

    compose = (TRAINING_ROOT.parent.parent / "deploy" / "docker-compose.yml").read_text()
    # Read the value rather than the whole file: the compose file is not this test's
    # subject and a YAML parse would drag in the rest of the stack's schema.
    match = re.search(r"^\s*ONNX_THREADS:\s*\"?(\d+)\"?\s*$", compose, re.MULTILINE)
    assert match, "deploy/docker-compose.yml no longer sets ONNX_THREADS for the ml service"
    deployed = int(match.group(1))

    assert export_module.SERVICE_INTRA_OP_THREADS == deployed, (
        f"the benchmark measures {export_module.SERVICE_INTRA_OP_THREADS} intra-op threads but the "
        f"stack deploys {deployed}. Every NFR2 figure in the project would describe a configuration "
        "nobody runs. Change both together, and re-run scripts/bench_backbones.py."
    )
