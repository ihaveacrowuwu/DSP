"""The corpus on disk must be the corpus the recipe was written against.

A download is the one step in the training track with no downstream detector. Every
other mistake surfaces: a bad config fails `config.load`, a bad export fails the parity
check, a bad graph fails the batch-dimension test. A corpus that quietly gained, lost or
re-cut images trains perfectly well and produces a number nobody can reproduce.

So these tests cover the two things `corpus.py` promises — that a wrong tree is refused
rather than logged, and that the manifest is a function of the bytes and nothing else.
None of them touch the network.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

TRAINING_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TRAINING_ROOT))

from muraka_train import corpus as corpus_module

# Small enough to build in a temp directory, same shape as the real thing.
TINY = {"train": {"CORAL": 3, "CORAL_BL": 2}, "val": {"CORAL": 2, "CORAL_BL": 1}}


def _build(root: Path, counts: dict[str, dict[str, int]]) -> Path:
    for split, classes in counts.items():
        for folder, count in classes.items():
            directory = root / split / folder
            directory.mkdir(parents=True, exist_ok=True)
            for index in range(count):
                (directory / f"{folder}_{index}.PNG").write_bytes(f"{split}/{folder}/{index}".encode())
    return root


def test_the_expected_counts_are_the_split_totals_from_the_dataset_card():
    # 7,292 / 1,562 / 1,565 and 10,419 images, read from the card on 2026-08-21 (docs/08,
    # Q6). If someone edits the table, this is what says the edit was not a typo.
    totals = {split: sum(classes.values()) for split, classes in corpus_module.EXPECTED_COUNTS.items()}
    assert totals == {"train": 7292, "val": 1562, "test": 1565}
    assert sum(totals.values()) == 10419


def test_the_recipe_and_the_expected_counts_name_the_same_folders():
    import yaml

    folder_map = yaml.safe_load((TRAINING_ROOT / "configs" / "baseline.yaml").read_text())["data"]["folder_map"]
    for classes in corpus_module.EXPECTED_COUNTS.values():
        assert set(classes) == set(folder_map), (
            "data.folder_map and the verification table must name the same directories, "
            "or a corpus can pass verification and then load no images"
        )


def test_a_correct_tree_verifies_and_reports_its_counts(tmp_path):
    _build(tmp_path, TINY)
    result = corpus_module.verify(tmp_path, expected=TINY)
    assert result.counts == TINY
    assert result.totals == {"train": 5, "val": 3}
    assert result.as_dict()["images"] == 8


def test_a_short_split_is_refused_rather_than_reported(tmp_path):
    short = {"train": {"CORAL": 3, "CORAL_BL": 1}, "val": {"CORAL": 2, "CORAL_BL": 1}}
    _build(tmp_path, short)
    with pytest.raises(corpus_module.CorpusError) as error:
        corpus_module.verify(tmp_path, expected=TINY)
    # The message must name the split and both numbers: "verification failed" alone
    # cannot distinguish a partial download from a re-cut split.
    assert "train/CORAL_BL: found 1 images, expected 2" in str(error.value)


def test_a_missing_class_directory_is_refused(tmp_path):
    _build(tmp_path, TINY)
    for path in (tmp_path / "val" / "CORAL_BL").iterdir():
        path.unlink()
    (tmp_path / "val" / "CORAL_BL").rmdir()
    with pytest.raises(corpus_module.CorpusError, match=r"missing class directory val/CORAL_BL/"):
        corpus_module.verify(tmp_path, expected=TINY)


def test_a_missing_root_says_what_to_do_about_it(tmp_path):
    with pytest.raises(corpus_module.CorpusError, match="--verify-only"):
        corpus_module.verify(tmp_path / "absent", expected=TINY)


def test_non_image_files_do_not_count_towards_a_split(tmp_path):
    # The snapshot ships a README and .gitattributes beside the imagery, and the hub
    # writes its own bookkeeping. None of it is an image.
    _build(tmp_path, TINY)
    (tmp_path / "train" / "CORAL" / "notes.txt").write_text("not an image")
    assert corpus_module.verify(tmp_path, expected=TINY).counts == TINY


def test_the_same_tree_produces_the_same_manifest(tmp_path):
    first = _build(tmp_path / "a", TINY)
    second = _build(tmp_path / "b", TINY)
    assert corpus_module.build_manifest(first) == corpus_module.build_manifest(second)


def test_one_changed_byte_changes_the_manifest(tmp_path):
    root = _build(tmp_path, TINY)
    before = corpus_module.build_manifest(root)
    target = root / "train" / "CORAL" / "CORAL_0.PNG"
    target.write_bytes(target.read_bytes() + b"\x00")
    assert corpus_module.build_manifest(root) != before


def test_the_manifest_ignores_the_hub_s_own_bookkeeping(tmp_path):
    # huggingface_hub writes .cache/huggingface inside the target directory. It differs
    # between machines, so hashing it would make the manifest machine-specific.
    root = _build(tmp_path, TINY)
    before = corpus_module.build_manifest(root)
    bookkeeping = root / ".cache" / "huggingface" / "download"
    bookkeeping.mkdir(parents=True)
    (bookkeeping / "somefile.metadata").write_text("machine specific")
    assert corpus_module.build_manifest(root) == before


def test_writing_the_manifest_returns_a_quotable_digest(tmp_path):
    root = _build(tmp_path / "corpus", TINY)
    destination = tmp_path / "manifests" / "noaa.sha256"
    path, digest, files = corpus_module.write_manifest(root, destination)

    assert path == destination and path.is_file()
    assert files == 8
    body = path.read_text(encoding="utf-8")
    assert len(body.splitlines()) == 8
    # Sorted by path, so two identical trees on two machines give one digest.
    assert [line.split("  ", 1)[1] for line in body.splitlines()] == sorted(
        line.split("  ", 1)[1] for line in body.splitlines()
    )
    import hashlib

    assert digest == hashlib.sha256(body.encode("utf-8")).hexdigest()
