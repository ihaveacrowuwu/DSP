"""Seaview Survey Maldives domain-gap evaluation.

The Coralscapes run measured transfer to Red Sea wide scenes. This measures
transfer to **Maldivian** imagery, which is the region the product is for and
the gap Chapter 7 named as its largest limitation.

The ground truth here is a different shape from Coralscapes': random point
annotations placed by expert human classifiers, not dense segmentation masks.
That changes what can be computed and it is worth being explicit about the cost.
A mask says what every pixel is; 100 points say what 100 pixels are. Coverage
fractions are therefore estimates with sampling error, not measurements, and a
patch cell holding two coral points is a far thinner claim than one holding
40,000 coral pixels. The compensation is that this is the right ocean, the right
coral community and the right water.

Pre-registered method. Every rule below was fixed and this file committed before
any model output was read:

- Deployed pipeline unchanged and identical to the Coralscapes run:
  PATCH_GRID=5, PATCH_OVERLAP=0, BLEACHED_LABEL_THRESHOLD=0.35, ONNX_THREADS=4.
  Nothing is tuned before, during or after.
- Bleached labels: BRA_BLC (branching bleached), MASE_BLC (massive/submassive/
  encrusting bleached). Healthy coral: every other label whose func_group is
  "Hard Coral". Everything else - algae, sand, soft coral, invertebrates, fish,
  transect hardware - is non-coral.
- The service tiles only the centre square, so only annotation points falling
  inside the centre square are used.
- An image is evaluable only if at least 10 expert points land in that square.
  Below that the ground truth is too thin to be worth scoring against.
- Image-level ground truth: bleached if at least one expert point in the centre
  square carries a bleached label. Reported alongside two stricter cuts (>=2
  points, and bleached >= 5% of the image's hard-coral points) as sensitivity
  checks, mirroring the Coralscapes 1/5/10% cuts.
- Patch-level ground truth: the same 5x5 tiling of the centre square. A cell is
  scored only if at least 2 hard-coral points fall inside it; the cell is
  bleached if any of those coral points is bleached, healthy otherwise.
- Cover fractions are computed from **random** points only. 532 of the 651
  bleached points in this partition were placed by targeted rather than random
  sampling, so a prevalence computed over all points would be inflated several
  times over. Targeted points are still expert evidence that bleached coral is
  present at that pixel, so they count towards presence and are excluded from
  fractions - and the two uses are reported separately for that reason.

Reproduce: start the stack (`make up`), fetch the partition per
`docs/evidence/datasets/seaview-partition-scope.md`, and run this file.
"""

import argparse
import csv
import collections
import io
import json
import statistics
import sys
import time
import urllib.request
import uuid
from pathlib import Path

from PIL import Image

ML_URL = "http://localhost:8010/classify"
GRID = 5
BLEACHED_LABELS = {"BRA_BLC", "MASE_BLC"}
CORAL_GROUP = "Hard Coral"
MIN_POINTS_PER_IMAGE = 10
MIN_CORAL_POINTS_PER_CELL = 2


def classify(jpg_bytes: bytes) -> dict:
    """Post one photograph to the deployed service, exactly as the API does."""
    boundary = uuid.uuid4().hex
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="eval.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'
    ).encode() + jpg_bytes + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        ML_URL,
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.loads(response.read())


def load_annotations(path: Path) -> dict:
    """Group the point annotations by image."""
    by_image = collections.defaultdict(list)
    with path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            by_image[row["quadratid"]].append(
                {
                    "x": int(row["x"]),
                    "y": int(row["y"]),
                    "label": row["label"],
                    "group": row["func_group"],
                    "method": row["method"],
                }
            )
    return by_image


def centre_window(width: int, height: int) -> tuple[int, int, int]:
    """The square the service tiles: centred, side = min(width, height)."""
    side = min(width, height)
    return (width - side) // 2, (height - side) // 2, side


def score_image(points: list[dict], width: int, height: int) -> dict | None:
    """Ground truth for one photograph, from the points inside the centre square."""
    x0, y0, side = centre_window(width, height)

    inside = []
    for point in points:
        # The dataset indexes from 1, top-left origin.
        px, py = point["x"] - 1, point["y"] - 1
        if x0 <= px < x0 + side and y0 <= py < y0 + side:
            inside.append({**point, "cx": px - x0, "cy": py - y0})

    if len(inside) < MIN_POINTS_PER_IMAGE:
        return None

    coral = [p for p in inside if p["group"] == CORAL_GROUP]
    bleached = [p for p in coral if p["label"] in BLEACHED_LABELS]

    # Fractions from random points only; presence from every point.
    random_pts = [p for p in inside if p["method"] == "random"]
    random_coral = [p for p in random_pts if p["group"] == CORAL_GROUP]
    random_bleached = [p for p in random_coral if p["label"] in BLEACHED_LABELS]

    step = side / GRID
    cells = []
    for row in range(GRID):
        for col in range(GRID):
            in_cell = [
                p
                for p in inside
                if int(p["cy"] // step) == row and int(p["cx"] // step) == col
            ]
            cell_coral = [p for p in in_cell if p["group"] == CORAL_GROUP]
            cell_bleached = [p for p in cell_coral if p["label"] in BLEACHED_LABELS]
            gt = None
            if len(cell_coral) >= MIN_CORAL_POINTS_PER_CELL:
                gt = "bleached" if cell_bleached else "healthy"
            cells.append(
                {
                    "row": row,
                    "col": col,
                    "gt": gt,
                    "points": len(in_cell),
                    "coral_points": len(cell_coral),
                    "bleached_points": len(cell_bleached),
                }
            )

    return {
        "points_in_square": len(inside),
        "coral_points": len(coral),
        "bleached_points": len(bleached),
        "random_points": len(random_pts),
        "random_coral_points": len(random_coral),
        "random_bleached_points": len(random_bleached),
        "non_coral_fraction": (
            1 - len(random_coral) / len(random_pts) if random_pts else None
        ),
        "bleached_share_of_coral": (
            len(random_bleached) / len(random_coral) if random_coral else None
        ),
        "cells": cells,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    by_image = load_annotations(args.annotations)
    print(f"annotations cover {len(by_image)} images", file=sys.stderr)

    paths = {p.stem: p for p in args.images.rglob("*.jpg")}
    print(f"found {len(paths)} jpgs on disk", file=sys.stderr)

    ids = sorted(set(by_image) & set(paths))
    if args.limit:
        ids = ids[: args.limit]
    print(f"{len(ids)} images have both annotations and pixels", file=sys.stderr)

    results = []
    latencies = []
    started = time.time()

    for index, quadrat_id in enumerate(ids, start=1):
        image = Image.open(paths[quadrat_id]).convert("RGB")
        truth = score_image(by_image[quadrat_id], image.width, image.height)
        if truth is None:
            continue

        # Re-encode to match what the API stores from a real contributor.
        buffer = io.BytesIO()
        image.save(buffer, "JPEG", quality=88)

        call_started = time.time()
        prediction = classify(buffer.getvalue())
        latencies.append((time.time() - call_started) * 1000)

        results.append(
            {
                "id": quadrat_id,
                "campaign": "2015" if quadrat_id.startswith("37") else "2017",
                "width": image.width,
                "height": image.height,
                "truth": truth,
                "pred": {
                    "label": prediction["label"],
                    "severity": prediction["severity"],
                    "confidence": prediction["confidence"],
                    "patches": prediction["patches"],
                    "model_version": prediction.get("model_version"),
                },
            }
        )

        if index % 50 == 0:
            elapsed = time.time() - started
            print(
                f"{index}/{len(ids)} scored={len(results)} "
                f"{elapsed:.0f}s elapsed",
                file=sys.stderr,
            )

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(
            {
                "model_version": results[0]["pred"]["model_version"] if results else None,
                "grid": GRID,
                "evaluable": len(results),
                "considered": len(ids),
                "latency_ms": {
                    "p50": round(statistics.median(latencies), 1) if latencies else None,
                    "p95": (
                        round(sorted(latencies)[int(len(latencies) * 0.95)], 1)
                        if len(latencies) > 20
                        else None
                    ),
                },
                "results": results,
            }
        )
    )
    print(f"wrote {args.out} ({len(results)} evaluable images)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
