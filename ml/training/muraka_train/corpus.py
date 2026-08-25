"""Fetch the training corpus, prove it is the right one, and record what arrived.

The recipe names a dataset; it does not name a copy of it. Between the two sits a
download that can go wrong in ways nothing downstream would notice:

* **A different corpus.** HuggingFace repositories are mutable. A re-download months
  later can bring more images, fewer images, or a re-cut split, and the run that
  produced the project's headline number would no longer be repeatable. So the split
  totals and per-class counts from the dataset card are asserted here, and a mismatch
  stops the fetch rather than being written into a log.
* **A different layout.** `data.folder_map` translates `CORAL`/`CORAL_BL` onto this
  project's labels. If the snapshot nests those folders one level deeper, `data.py`
  raises "contained no images" an hour into a session rather than at the fetch.
* **Silent corruption.** A truncated image decodes to something; it does not raise.

What answers all three is a manifest: every file's SHA-256, sorted, written to a small
committed text file. It is the project's provenance evidence — the one line a reader
needs to know the corpus that produced the numbers is the corpus they downloaded — and
it costs one pass over 768 MB.

Anonymity is deliberate and not incidental. `snapshot_download` is called with
`token=False`, which stops it consulting a cached login: the project forbids anything
that needs an account, and a fetch that quietly succeeds because *this* machine happens
to be logged in would break that promise on any other machine.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

REPO_ID = "NMFS-OSI/NOAA-PIFSC-ESD-CORAL-Bleaching-Dataset"

# The dataset card's own figures, read 2026-08-21 and recorded against Q6 in
# docs/08-scope-risks-decisions.md. These are the assertion, not a guess: they are also
# what `configs/baseline.yaml` was written against — its stated "4,541 healthy against
# 2,751 bleached" is this table's train row.
EXPECTED_COUNTS: dict[str, dict[str, int]] = {
    "train": {"CORAL": 4541, "CORAL_BL": 2751},
    "val": {"CORAL": 973, "CORAL_BL": 589},
    "test": {"CORAL": 974, "CORAL_BL": 591},
}

# Requested by the dataset card, owed by D63. Printed on success so the fetch itself
# hands over the obligation rather than leaving it in a document nobody re-reads.
CITATION = "Pacific Islands Fisheries Science Center (2025). Ecosystem Sciences Division (ESD)"

DISCLAIMER = (
    "NOAA provides this product as-is; the user assumes responsibility for its use. "
    "The Department of Commerce seal may not be used to imply endorsement."
)

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

# huggingface_hub keeps its own bookkeeping inside the target directory. It is not part
# of the corpus, it differs between machines, and hashing it would make the manifest
# machine-specific — which is the opposite of what a manifest is for.
_IGNORED_PARTS = {".cache", ".huggingface"}


class CorpusError(RuntimeError):
    """The corpus on disk is not the corpus the recipe was written against."""


@dataclass(frozen=True)
class Verification:
    """What was found, per split and class, alongside what was expected."""

    counts: dict[str, dict[str, int]]

    @property
    def totals(self) -> dict[str, int]:
        return {split: sum(classes.values()) for split, classes in self.counts.items()}

    def as_dict(self) -> dict[str, object]:
        return {"counts": self.counts, "totals": self.totals, "images": sum(self.totals.values())}


def download(target: Path, *, repo_id: str = REPO_ID) -> Path:
    """Fetch the dataset snapshot into `target`, anonymously.

    Imported here rather than at module scope so the verification and manifest paths —
    which are what the tests exercise — need neither the dependency nor a network.
    """
    from huggingface_hub import snapshot_download

    target = Path(target)
    target.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=repo_id,
        repo_type="dataset",
        local_dir=str(target),
        # No token, ever. See the module docstring.
        token=False,
    )
    return target


def verify(root: Path, *, expected: dict[str, dict[str, int]] | None = None) -> Verification:
    """Count the images per split and class and require the card's numbers.

    Raises `CorpusError` on the first disagreement, with the full table in the message —
    a partial download and a re-cut split look identical from a single number.
    """
    root = Path(root)
    expected = expected or EXPECTED_COUNTS
    if not root.is_dir():
        raise CorpusError(f"{root} does not exist. Run the fetch without --verify-only first.")

    counts: dict[str, dict[str, int]] = {}
    problems: list[str] = []

    for split, classes in expected.items():
        split_dir = root / split
        if not split_dir.is_dir():
            problems.append(f"missing split directory {split}/")
            continue
        counts[split] = {}
        for folder, wanted in classes.items():
            directory = split_dir / folder
            if not directory.is_dir():
                problems.append(f"missing class directory {split}/{folder}/")
                counts[split][folder] = 0
                continue
            found = sum(1 for p in directory.rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
            counts[split][folder] = found
            if found != wanted:
                problems.append(f"{split}/{folder}: found {found} images, expected {wanted}")

    if problems:
        raise CorpusError(
            "the corpus on disk is not the one the recipe was written against:\n  "
            + "\n  ".join(problems)
            + "\n\nThe expected figures come from the dataset card (docs/08, Q6) and match "
            "configs/baseline.yaml. A corpus that differs would train a model whose numbers "
            "cannot be reproduced from the published dataset, so the fetch stops here."
        )
    return Verification(counts=counts)


def iter_corpus_files(root: Path):
    """Every file that is part of the corpus, in a stable order."""
    root = Path(root)
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if _IGNORED_PARTS.intersection(relative.parts):
            continue
        yield path, relative


def build_manifest(root: Path) -> str:
    """`<sha256>  <relative path>` per file, sorted by path, newline-terminated.

    Sorted by path rather than by walk order because directory iteration order is a
    filesystem property: an unsorted manifest would differ between two identical trees on
    two machines and prove nothing.
    """
    lines = []
    for path, relative in iter_corpus_files(root):
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        lines.append(f"{digest.hexdigest()}  {relative.as_posix()}")
    return "\n".join(lines) + "\n" if lines else ""


def write_manifest(root: Path, destination: Path) -> tuple[Path, str, int]:
    """Write the manifest and return `(path, its own sha256, file count)`.

    The manifest's own digest is what makes provenance quotable in one line: a report can
    cite a single hash instead of a ten-thousand-line file.
    """
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    body = build_manifest(root)
    destination.write_text(body, encoding="utf-8", newline="\n")
    digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
    return destination, digest, body.count("\n") if body else 0
