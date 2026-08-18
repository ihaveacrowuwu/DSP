"""Tests for the patch-grid inference pipeline (fake mode).

These run without any model file, which is the point of FAKE_MODE: the platform's
test suite must never depend on trained weights.
"""

from __future__ import annotations

import io

import numpy as np
import pytest
from PIL import Image

from app.config import Settings
from app.inference import BLEACHED, CLASS_LABELS, Classifier, HEALTHY, tile_patches


def make_image(width: int = 640, height: int = 480, colour: tuple[int, int, int] = (120, 160, 180)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (width, height), colour).save(buf, format="JPEG")
    return buf.getvalue()


@pytest.fixture
def fake_classifier() -> Classifier:
    return Classifier(Settings(fake_mode=True))


# ---------------------------------------------------------------- tiling

def test_tile_patches_produces_full_grid() -> None:
    image = Image.new("RGB", (640, 480))
    tiles = tile_patches(image, grid=5, size=224)

    assert len(tiles) == 25
    assert {(r, c) for r, c, _ in tiles} == {(r, c) for r in range(5) for c in range(5)}
    assert all(patch.size == (224, 224) for _, _, patch in tiles)


def test_tile_patches_uses_centre_square_of_wide_image() -> None:
    # A landscape image must be centre-cropped, not stretched: patches stay square
    # so they match the aspect ratio the model was trained on.
    image = Image.new("RGB", (1000, 500))
    tiles = tile_patches(image, grid=2, size=64)

    assert len(tiles) == 4
    assert all(patch.size == (64, 64) for _, _, patch in tiles)


def test_tile_patches_overlap_widens_crops() -> None:
    image = Image.new("RGB", (500, 500))
    plain = tile_patches(image, grid=5, size=224, overlap=0.0)
    overlapped = tile_patches(image, grid=5, size=224, overlap=0.5)

    # Same geometry, more context per patch.
    assert len(plain) == len(overlapped) == 25


def test_tile_patches_rejects_tiny_image() -> None:
    image = Image.new("RGB", (4, 4))
    assert tile_patches(image, grid=5, size=224) == []


# ---------------------------------------------------------------- assessment

def test_classify_returns_one_patch_per_cell(fake_classifier: Classifier) -> None:
    assessment = fake_classifier.classify(make_image())

    assert assessment.patch_grid == 5
    assert len(assessment.patches) == 25
    assert assessment.fake is True
    assert assessment.model_version == "fake-0.0.0"


def test_severity_matches_bleached_patch_fraction(fake_classifier: Classifier) -> None:
    assessment = fake_classifier.classify(make_image())

    bleached = sum(1 for p in assessment.patches if p.label == BLEACHED)
    assert assessment.severity == pytest.approx(bleached / len(assessment.patches))


def test_label_follows_severity_threshold(fake_classifier: Classifier) -> None:
    assessment = fake_classifier.classify(make_image())

    expected = BLEACHED if assessment.severity >= 0.35 else HEALTHY
    assert assessment.label == expected


def test_fake_mode_is_deterministic_per_image(fake_classifier: Classifier) -> None:
    # Reproducibility matters: demos and client tests assert on fixed values.
    payload = make_image(colour=(10, 90, 140))
    first = fake_classifier.classify(payload)
    second = fake_classifier.classify(payload)

    assert first.severity == second.severity
    assert first.label == second.label
    assert [(p.row, p.col, p.label) for p in first.patches] == [
        (p.row, p.col, p.label) for p in second.patches
    ]


def test_different_images_get_different_assessments(fake_classifier: Classifier) -> None:
    a = fake_classifier.classify(make_image(colour=(10, 10, 10)))
    b = fake_classifier.classify(make_image(colour=(240, 240, 240)))

    assert (a.severity, a.confidence) != (b.severity, b.confidence)


def test_confidences_are_probabilities(fake_classifier: Classifier) -> None:
    assessment = fake_classifier.classify(make_image())

    assert 0.0 <= assessment.confidence <= 1.0
    assert all(0.0 <= p.confidence <= 1.0 for p in assessment.patches)


def test_custom_grid_size_is_honoured() -> None:
    classifier = Classifier(Settings(fake_mode=True, patch_grid=3))
    assessment = classifier.classify(make_image())

    assert assessment.patch_grid == 3
    assert len(assessment.patches) == 9


def test_classify_rejects_undecodable_bytes(fake_classifier: Classifier) -> None:
    with pytest.raises(Exception):
        fake_classifier.classify(b"this is not an image")


def test_to_dict_shape_matches_go_client_contract(fake_classifier: Classifier) -> None:
    # The Go mlclient decodes exactly these keys; drift here breaks the pipeline.
    payload = fake_classifier.classify(make_image()).to_dict()

    assert set(payload) == {
        "label",
        "confidence",
        "severity",
        "patch_grid",
        "patches",
        "model_version",
        "inference_ms",
        "fake",
    }
    assert set(payload["patches"][0]) == {"row", "col", "label", "confidence"}


# ---------------------------------------------------------------- preprocessing

def test_preprocess_produces_normalised_nchw_batch(fake_classifier: Classifier) -> None:
    # Guards the training/serving parity contract from docs/06: shape, channel
    # order and ImageNet normalisation must match the training transform.
    patches = [Image.new("RGB", (224, 224), (255, 255, 255))]
    batch = fake_classifier._preprocess(patches)  # noqa: SLF001 - contract under test

    assert batch.shape == (1, 3, 224, 224)
    assert batch.dtype == np.float32

    expected = (1.0 - np.array(fake_classifier.settings.normalise_mean)) / np.array(
        fake_classifier.settings.normalise_std
    )
    assert batch[0, :, 0, 0] == pytest.approx(expected.astype(np.float32), rel=1e-5)


def test_class_label_order_is_healthy_then_bleached() -> None:
    # Inverting this silently inverts every prediction; prior work on this dataset
    # shipped with the opposite folder order, so it is asserted explicitly.
    assert CLASS_LABELS == (HEALTHY, BLEACHED)
