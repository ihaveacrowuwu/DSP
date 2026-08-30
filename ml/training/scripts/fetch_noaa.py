#!/usr/bin/env python3
"""Fetch the NOAA-PIFSC bleaching corpus, verify it, and write its manifest.

    python3 scripts/fetch_noaa.py                  # download, verify, manifest
    python3 scripts/fetch_noaa.py --verify-only    # re-verify an existing copy, no network

Roughly 768 MB into `ml/datasets/noaa`, which `.gitignore` excludes - imagery is never
stageable. The manifest it writes to `ml/training/manifests/noaa.sha256` **is** committed:
it is small, and it is the evidence that the corpus behind the numbers is the corpus a
reader would download.

The dataset carries a requested citation and a NOAA as-is disclaimer; both are printed
on success so they are not lost.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from muraka_train import corpus as corpus_module

TRAINING_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TARGET = TRAINING_ROOT.parent / "datasets" / "noaa"
DEFAULT_MANIFEST = TRAINING_ROOT / "manifests" / "noaa.sha256"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", default=str(DEFAULT_TARGET), help="where the corpus lands")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST), help="manifest to write")
    parser.add_argument("--repo-id", default=corpus_module.REPO_ID)
    parser.add_argument(
        "--workers",
        type=int,
        default=4,
        help="concurrent downloads; above ~4 the anonymous rate limiter tends to fire",
    )
    parser.add_argument("--verify-only", action="store_true", help="skip the download; verify what is on disk")
    parser.add_argument("--no-manifest", action="store_true", help="verify without rewriting the manifest")
    args = parser.parse_args()

    target = Path(args.target).resolve()

    if not args.verify_only:
        print(f"downloading {args.repo_id} -> {target} (anonymous, no token)")
        print(f"{args.workers} workers, resumes on restart; a 429 is waited out, not tokened around")

        def announce(attempt: int, total: int, delay: float, error: Exception) -> None:
            print(
                f"\n  rate limited (attempt {attempt}/{total}). Waiting {delay:.0f}s and resuming; "
                f"files already fetched are kept.\n  {str(error).splitlines()[0]}",
                flush=True,
            )

        try:
            corpus_module.download(
                target, repo_id=args.repo_id, max_workers=args.workers, on_retry=announce
            )
        except corpus_module.CorpusError as error:
            print(f"\nDOWNLOAD INCOMPLETE\n{error}", file=sys.stderr)
            return 1

    try:
        verification = corpus_module.verify(target)
    except corpus_module.CorpusError as error:
        print(f"\nVERIFICATION FAILED\n{error}", file=sys.stderr)
        return 1

    print("\nverified against the dataset card:")
    print(json.dumps(verification.as_dict(), indent=2))

    if not args.no_manifest:
        path, digest, files = corpus_module.write_manifest(target, Path(args.manifest))
        print(f"\nmanifest: {files} files -> {path}")
        print(f"manifest sha256: {digest}")

    print(f"\nCite as: {corpus_module.CITATION}")
    print(f"Disclaimer owed wherever these numbers are published: {corpus_module.DISCLAIMER}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
