"""NFR16: "Training runs shall be reproducible: config-driven, seeded, with metrics
logged per run." Its stated verification method is "re-run comparison", which is this
file.

Reproducibility is the kind of claim that is easy to assert and easy to be wrong about,
because there are more sources of randomness than anyone remembers: Python's `random`,
NumPy, Torch's global generator, the DataLoader's shuffle, and the weighted sampler used
for the minority class. Missing any one of them produces runs that are *nearly* the same,
which is worse than obviously different — the project would claim reproducibility and a
marker re-running it would get other numbers.

These runs are deliberately tiny (two epochs, a few hundred synthetic images, a small
backbone with no pretrained download) so the suite stays runnable. What they test is the
seeding, not the model.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
import torch

TRAINING_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TRAINING_ROOT))

from muraka_train import config as config_module
from muraka_train import data as data_module
from muraka_train import export as export_module
from muraka_train.train import Trainer

BASELINE = TRAINING_ROOT / "configs" / "baseline.yaml"
SIZES = {"train": 96, "val": 48, "test": 48}


def _tiny_config(tmp_path: Path, *, seed: int | None = None):
    """The baseline recipe, shrunk to something a test suite can run.

    `resnet18` with no pretrained weights: the point is determinism, and downloading
    20 MB of ImageNet weights to prove that two seeded runs agree would make the test
    depend on a network it does not need.
    """
    cfg = config_module.load(BASELINE)
    if seed is not None:
        object.__setattr__(cfg, "seed", seed)
    object.__setattr__(cfg, "output_dir", tmp_path / "run")
    object.__setattr__(cfg.training, "epochs", 2)
    object.__setattr__(cfg.training, "unfreeze_epoch", 1)
    object.__setattr__(cfg.training, "batch_size", 16)
    object.__setattr__(cfg.training, "patience", 5)
    object.__setattr__(cfg.training, "device", "cpu")
    object.__setattr__(cfg.data, "image_size", 64)
    object.__setattr__(cfg.data, "num_workers", 0)
    cfg.model["backbone"] = "resnet18"
    cfg.model["pretrained"] = "none"
    return cfg


def _run(tmp_path: Path, *, seed: int | None = None) -> dict:
    cfg = _tiny_config(tmp_path, seed=seed)
    loaders = data_module.make_loaders(cfg, synthetic_sizes=SIZES)
    trainer = Trainer(cfg, loaders, log=False)
    history = trainer.fit()
    return {
        "train_losses": [round(r.train_loss, 6) for r in history],
        "val_losses": [round(r.val_loss, 6) for r in history],
        "val_metrics": [r.val_metrics.as_dict() for r in history],
        "best_score": trainer.best_score,
    }


@pytest.mark.slow
def test_the_same_seed_gives_the_same_metrics(tmp_path):
    first = _run(tmp_path / "a")
    second = _run(tmp_path / "b")
    assert first["train_losses"] == second["train_losses"], "training diverged between two identical runs"
    assert first["val_losses"] == second["val_losses"]
    assert first["val_metrics"] == second["val_metrics"]
    assert first["best_score"] == second["best_score"]


@pytest.mark.slow
def test_a_different_seed_gives_different_metrics(tmp_path):
    # The other half. A pipeline that returns identical numbers for every seed is not
    # reproducible, it is broken — and it would pass the test above.
    baseline = _run(tmp_path / "a")
    altered = _run(tmp_path / "b", seed=1234)
    assert baseline["train_losses"] != altered["train_losses"], (
        "changing the seed changed nothing; the seed is probably not reaching the "
        "model initialisation or the data order"
    )


def test_the_synthetic_split_is_seeded_and_stable():
    cfg = config_module.load(BASELINE)
    first = data_module.synthetic_split("train", 200, cfg)
    second = data_module.synthetic_split("train", 200, cfg)
    assert [e.label for e in first] == [e.label for e in second]
    assert [e.synthetic_seed for e in first] == [e.synthetic_seed for e in second]
    # And the splits must not be identical to each other, or val would be train.
    val = data_module.synthetic_split("val", 200, cfg)
    assert [e.synthetic_seed for e in val] != [e.synthetic_seed for e in first]


def test_the_synthetic_split_keeps_the_real_datasets_imbalance():
    # The real training split is roughly 4,541 healthy to 2,751 bleached — 62/38. If the
    # synthetic split were balanced, the class weighting and oversampling would never be
    # exercised by a pipeline check.
    cfg = config_module.load(BASELINE)
    labels = [e.label for e in data_module.synthetic_split("train", 4000, cfg)]
    bleached = sum(labels) / len(labels)
    assert 0.30 < bleached < 0.46, f"bleached fraction {bleached:.3f} is not the dataset's imbalance"


def test_class_weights_favour_the_minority_and_apply_the_bleached_multiplier():
    cfg = config_module.load(BASELINE)
    examples = data_module.synthetic_split("train", 2000, cfg)
    plain = data_module.class_weights(examples, 2, bleached_multiplier=1.0)
    pushed = data_module.class_weights(examples, 2, bleached_multiplier=1.5)
    # Bleached is the minority, so it must carry more weight than healthy.
    assert plain[1] > plain[0]
    assert pushed[1] == pytest.approx(float(plain[1]) * 1.5)
    assert pushed[0] == pytest.approx(float(plain[0]))


def test_the_eval_transform_produces_what_the_service_will_feed_the_model():
    cfg = config_module.load(BASELINE)
    _, eval_tf = data_module.build_transforms(cfg)
    image = data_module._synthetic_image(0, 0, 480)
    tensor = eval_tf(image)
    # CHW, the model's input size, and normalised — the same shape and treatment
    # `inference.py` builds for each patch.
    assert tensor.shape == (3, cfg.data.image_size, cfg.data.image_size)
    assert tensor.dtype == torch.float32
    # Normalised, not raw 0..1: an un-normalised tensor stays inside [0, 1].
    assert tensor.min() < 0.0


@pytest.mark.slow
def test_the_exported_onnx_agrees_with_the_pytorch_model(tmp_path):
    """The deployment depends on this: the served artefact is the ONNX file, not the
    checkpoint the metrics were computed from."""
    cfg = _tiny_config(tmp_path, seed=7)
    loaders = data_module.make_loaders(cfg, synthetic_sizes=SIZES)
    trainer = Trainer(cfg, loaders, log=False)
    trainer.fit()

    path = export_module.export(trainer.model, cfg, output=tmp_path / "model.onnx", model_version="test-0.0.0")
    difference = export_module.parity(trainer.model, path, cfg)
    assert difference < float(cfg.export["parity_tolerance"])

    metadata = export_module.read_metadata(path)
    # The service refuses to load a model whose declared class order disagrees with its
    # own, so the order has to be *in* the file rather than implied by it.
    assert metadata["class_labels"] == "healthy,bleached"
    assert metadata["model_version"] == "test-0.0.0"
    assert metadata["image_size"] == str(cfg.data.image_size)


@pytest.mark.slow
def test_the_onnx_batch_dimension_is_dynamic(tmp_path):
    # The service classifies a whole 5x5 lattice in one call. A graph fixed to batch 1
    # would force 25 calls per photograph and blow the NFR2 budget.
    cfg = _tiny_config(tmp_path, seed=7)
    loaders = data_module.make_loaders(cfg, synthetic_sizes=SIZES)
    trainer = Trainer(cfg, loaders, log=False)

    import numpy as np
    import onnxruntime

    path = export_module.export(trainer.model, cfg, output=tmp_path / "model.onnx")
    session = onnxruntime.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    for batch in (1, 25):
        example = np.zeros((batch, 3, cfg.data.image_size, cfg.data.image_size), dtype=np.float32)
        logits = session.run(["logits"], {"input": example})[0]
        assert logits.shape == (batch, 2)
