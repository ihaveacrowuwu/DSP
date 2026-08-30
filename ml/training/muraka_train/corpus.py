"""Fetch the training corpus, prove it is the right one, and record what arrived.

The recipe names a dataset; it does not name a copy of it. Between the two sits a
download that can go wrong in ways nothing downstream would notice:

* **A different corpus.** HuggingFace repositories are mutable. A re-download months
  later can bring more images, fewer images, or a re-cut split, and the run that
  produced this project's headline number would no longer be repeatable. So the split
  totals and per-class counts from the dataset card are asserted here, and a mismatch
  stops the fetch rather than being written into a log.
* **A different layout.** `data.folder_map` translates `CORAL`/`CORAL_BL` onto this
  project's labels. If the snapshot nests those folders one level deeper, `data.py`
  raises "contained no images" an hour into a session rather than at the fetch.
* **Silent corruption.** A truncated image decodes to something; it does not raise.

What answers all three is a manifest: every file's SHA-256, sorted, written to a small
committed text file. It is the provenance evidence - the one line a reader needs to
know the corpus that produced the numbers is the corpus they downloaded - and it costs
one pass over 768 MB.

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

# The dataset card's published figures. `verify()` asserts the tree on disk matches
# these exactly; they also match the class balance `configs/baseline.yaml` is sized for.
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
# machine-specific - which is the opposite of what a manifest is for.
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


def backoff_delays(attempts: int, *, base: float = 30.0, cap: float = 900.0) -> list[float]:
    """Seconds to wait before each retry: 30s, 60s, 120s, ... capped at 15 minutes.

    Anonymous downloads of this corpus **do** get rate limited - 10,419 files is a lot of
    requests from one IP, and HuggingFace answers with a 429 partway through suggesting an
    `HF_TOKEN`. The project forbids one (constraint 2), so waiting is the whole strategy.
    The first delay is deliberately long: a limiter that has just fired is not going to
    forgive a retry two seconds later, and hammering it extends the ban.
    """
    return [min(base * (2**i), cap) for i in range(attempts)]


def download(
    target: Path,
    *,
    repo_id: str = REPO_ID,
    max_workers: int = 4,
    attempts: int = 10,
    on_retry=None,
) -> Path:
    """Fetch the dataset snapshot into `target`, anonymously, resuming and backing off.

    Three choices here are all consequences of having no token:

    * **`max_workers=4`**, not the library's 8. Concurrency is what trips the limiter, and
      a download that finishes slowly beats one that 429s at 8% and has to be nursed.
    * **Retries with a long backoff.** `snapshot_download` skips files already on disk, so
      a retry resumes rather than restarts - the 429 is a pause, not a loss.
    * **Xet disabled.** The rate limit observed on this corpus came from the
      `xet-read-token` endpoint, which does a token exchange *per file*; the plain CDN
      path makes far fewer such calls. Set before the import, because the flag is read at
      module load.

    Imported inside the function rather than at module scope so the verification and
    manifest paths - which are what the tests exercise - need neither the dependency nor a
    network.
    """
    import os

    os.environ.setdefault("HF_HUB_DISABLE_XET", "1")

    from huggingface_hub import snapshot_download
    from huggingface_hub.errors import HfHubHTTPError

    target = Path(target)
    target.mkdir(parents=True, exist_ok=True)

    delays = backoff_delays(attempts)
    last_error: Exception | None = None

    for attempt, delay in enumerate(delays, start=1):
        error: Exception | None = None
        try:
            snapshot_download(
                repo_id=repo_id,
                repo_type="dataset",
                local_dir=str(target),
                # No token, ever. See the module docstring.
                token=False,
                max_workers=max_workers,
            )
        except HfHubHTTPError as raised:
            status = getattr(getattr(raised, "response", None), "status_code", None)
            # A 429 is worth waiting out. A 404 or a 401 is not: those mean the repo id is
            # wrong or the dataset stopped being public, and retrying six times just makes
            # the failure take an hour to report.
            if status is not None and status != 429:
                raise
            error = raised

        # Completeness decides whether to retry - NOT whether `snapshot_download`
        # returned. When the rate limit reaches the metadata call, the library logs
        # "Returning existing local_dir ... as remote repo cannot be accessed" and
        # returns **successfully** with a partial tree. Trusting that return is how a
        # 7%-downloaded corpus gets handed to a trainer, and the resulting model would be
        # trained on whichever classes happened to arrive first.
        try:
            verify(target)
            return target
        except CorpusError as incomplete:
            error = error or incomplete
            last_error = error

        if attempt == len(delays):
            break
        if on_retry:
            on_retry(attempt, len(delays), delay, error)
        import time

        time.sleep(delay)

    raise CorpusError(
        f"the download was rate limited and did not finish after {len(delays)} attempts. "
        "Every file already fetched is kept, so re-running resumes rather than restarts - "
        "wait a while and run the same command again. Do NOT set HF_TOKEN to work around "
        "this: the corpus is public and the project does not use API-key services. "
        "Last error: "
        f"{last_error}"
    ) from last_error


def verify(root: Path, *, expected: dict[str, dict[str, int]] | None = None) -> Verification:
    """Count the images per split and class and require the card's numbers.

    Raises `CorpusError` on the first disagreement, with the full table in the message  -
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
            + "\n\nThe expected figures come from the dataset card and match "
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

    The manifest's own digest is what makes provenance quotable in one line: cite a
    single hash instead of a ten-thousand-line file.
    """
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    body = build_manifest(root)
    destination.write_text(body, encoding="utf-8", newline="\n")
    digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
    return destination, digest, body.count("\n") if body else 0
