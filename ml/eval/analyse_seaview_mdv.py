"""Turn the Seaview Maldives run into the numbers the project quotes.

Kept separate from the run itself so the expensive part - 1,612 calls to a
CPU-only classifier - is done once and every question is asked of the same
stored predictions. Nothing here touches the model or the thresholds.

Everything reported is either a count or derived from counts that are printed
alongside it, so any figure quoted in the project can be traced back to how many
images or cells produced it. Where a denominator is small the script says so
rather than printing a ratio that looks as solid as one drawn from thousands.
"""

import argparse
import collections
import json
import math
import statistics
from pathlib import Path


def rate(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def fmt(value: float | None, places: int = 3) -> str:
    return "n/a" if value is None else f"{value:.{places}f}"


def wilson(successes: int, total: int, z: float = 1.96) -> tuple[float, float] | None:
    """A 95% interval on a proportion.

    Reported because the bleached class here is small: a recall of 0.9 over ten
    positives and one over three hundred are not the same claim, and a bare
    ratio hides which one is on offer.
    """
    if not total:
        return None
    p = successes / total
    denom = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / denom
    margin = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denom
    return max(0.0, centre - margin), min(1.0, centre + margin)


def prf(tp: int, fp: int, fn: int) -> dict:
    precision = rate(tp, tp + fp)
    recall = rate(tp, tp + fn)
    f2 = None
    if precision is not None and recall is not None and (4 * precision + recall):
        f2 = 5 * precision * recall / (4 * precision + recall)
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": precision,
        "recall": recall,
        "f2": f2,
        "recall_ci95": wilson(tp, tp + fn),
    }


def spearman(xs: list[float], ys: list[float]) -> float | None:
    """Rank correlation, so a monotone but non-linear relation still registers."""
    if len(xs) < 3:
        return None

    def ranks(values: list[float]) -> list[float]:
        order = sorted(range(len(values)), key=lambda i: values[i])
        out = [0.0] * len(values)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
                j += 1
            shared = (i + j) / 2 + 1
            for k in range(i, j + 1):
                out[order[k]] = shared
            i = j + 1
        return out

    rx, ry = ranks(xs), ranks(ys)
    mx, my = statistics.mean(rx), statistics.mean(ry)
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    den = math.sqrt(sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry))
    return num / den if den else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()

    data = json.loads(args.results.read_text())
    results = data["results"]

    print(f"model            : {data['model_version']}")
    print(f"images evaluable : {len(results)} of {data['considered']} considered")
    print(f"latency          : {data['latency_ms']}")
    print()

    # ---------------------------------------------------------------- image level
    print("== Image level ==")
    print("An image is positive when the model calls it bleached.")
    print()

    # The last two cuts exist to be comparable with the Coralscapes run, which
    # called an image bleached when bleached classes covered >=5% of the centre
    # square's *area*. Points sample area uniformly, so bleached points as a
    # share of all points estimates that same quantity; bleached as a share of
    # coral points is a different and much more sensitive question, and the two
    # are reported apart because conflating them would make the Maldivian recall
    # look worse than the Red Sea recall for reasons of definition alone.
    cuts = {
        ">=1 bleached point": lambda t: t["bleached_points"] >= 1,
        ">=2 bleached points": lambda t: t["bleached_points"] >= 2,
        ">=5% of coral points": lambda t: (
            t["bleached_share_of_coral"] is not None
            and t["bleached_share_of_coral"] >= 0.05
        ),
        ">=1% of area (all points)": lambda t: (
            t["random_points"]
            and t["random_bleached_points"] / t["random_points"] >= 0.01
        ),
        ">=5% of area (all points)": lambda t: (
            t["random_points"]
            and t["random_bleached_points"] / t["random_points"] >= 0.05
        ),
    }

    for name, is_bleached in cuts.items():
        tp = fp = fn = tn = 0
        for r in results:
            truth = is_bleached(r["truth"])
            pred = r["pred"]["label"] == "bleached"
            if truth and pred:
                tp += 1
            elif truth and not pred:
                fn += 1
            elif pred:
                fp += 1
            else:
                tn += 1
        m = prf(tp, fp, fn)
        ci = m["recall_ci95"]
        print(
            f"{name:<22} n={tp+fn+fp+tn:<5} positives={tp+fn:<4} "
            f"recall={fmt(m['recall'])} "
            f"({'-' if ci is None else f'{ci[0]:.2f}-{ci[1]:.2f}'}) "
            f"precision={fmt(m['precision'])} F2={fmt(m['f2'])} "
            f"tp={tp} fn={fn} fp={fp} tn={tn}"
        )
    print()

    called_bleached = sum(1 for r in results if r["pred"]["label"] == "bleached")
    print(
        f"the model called {called_bleached} of {len(results)} images bleached "
        f"({100*called_bleached/len(results):.1f}%)"
    )
    severities = [r["pred"]["severity"] for r in results]
    print(
        f"severity: median {statistics.median(severities):.3f}, "
        f"mean {statistics.mean(severities):.3f}, "
        f"at 1.0 for {sum(1 for s in severities if s >= 0.999)} images"
    )
    print()

    # ---------------------------------------------------------------- what severity tracks
    print("== What severity actually tracks ==")
    paired = [
        r
        for r in results
        if r["truth"]["non_coral_fraction"] is not None
        and r["truth"]["bleached_share_of_coral"] is not None
    ]
    if paired:
        sev = [r["pred"]["severity"] for r in paired]
        noncoral = [r["truth"]["non_coral_fraction"] for r in paired]
        bleach = [r["truth"]["bleached_share_of_coral"] for r in paired]
        print(f"n = {len(paired)} images with both fractions computable")
        print(f"  severity vs non-coral fraction   Spearman {fmt(spearman(sev, noncoral))}")
        print(f"  severity vs bleached share       Spearman {fmt(spearman(sev, bleach))}")
    print()

    # ---------------------------------------------------------------- patch level
    print("== Patch level, cells with expert coral points ==")
    tp = fp = fn = tn = 0
    for r in results:
        by_cell = {(p["row"], p["col"]): p for p in r["pred"]["patches"]}
        for cell in r["truth"]["cells"]:
            if cell["gt"] is None:
                continue
            pred = by_cell.get((cell["row"], cell["col"]))
            if pred is None:
                continue
            predicted_bleached = pred["label"] == "bleached"
            if cell["gt"] == "bleached":
                tp += predicted_bleached
                fn += not predicted_bleached
            else:
                fp += predicted_bleached
                tn += not predicted_bleached
    m = prf(tp, fp, fn)
    ci = m["recall_ci95"]
    print(
        f"scored cells={tp+fp+fn+tn} bleached={tp+fn} "
        f"recall={fmt(m['recall'])} "
        f"({'-' if ci is None else f'{ci[0]:.2f}-{ci[1]:.2f}'}) "
        f"precision={fmt(m['precision'])} F2={fmt(m['f2'])}"
    )
    print(f"  tp={tp} fn={fn} fp={fp} tn={tn}")
    print(
        f"  healthy-coral cells called bleached: {fp} of {fp+tn} "
        f"({fmt(rate(fp, fp+tn), 3)})"
    )
    print()

    # ---------------------------------------------------------------- non-coral cells
    print("== Cells with no expert coral point at all ==")
    noncoral_cells = noncoral_called = 0
    for r in results:
        by_cell = {(p["row"], p["col"]): p for p in r["pred"]["patches"]}
        for cell in r["truth"]["cells"]:
            # Cells holding points, none of which are coral: the model has no
            # class for what is actually there.
            if cell["points"] >= 2 and cell["coral_points"] == 0:
                pred = by_cell.get((cell["row"], cell["col"]))
                if pred:
                    noncoral_cells += 1
                    noncoral_called += pred["label"] == "bleached"
    print(
        f"cells sampled and containing no coral: {noncoral_cells}; "
        f"called bleached: {noncoral_called} ({fmt(rate(noncoral_called, noncoral_cells), 3)})"
    )
    print()

    # ---------------------------------------------------------------- campaigns
    print("== By campaign ==")
    by_campaign = collections.defaultdict(list)
    for r in results:
        by_campaign[r["campaign"]].append(r)
    for campaign, rows in sorted(by_campaign.items()):
        positives = sum(1 for r in rows if r["truth"]["bleached_points"] >= 1)
        called = sum(1 for r in rows if r["pred"]["label"] == "bleached")
        sev = statistics.median(r["pred"]["severity"] for r in rows)
        print(
            f"{campaign}: n={len(rows):<5} expert-bleached images={positives:<4} "
            f"model-bleached={called:<5} median severity={sev:.3f}"
        )
    print()
    print(
        "Note: expert 'bleached' marks currently-bleaching tissue, which is transient.\n"
        "A count of it two years apart is not a before/after measure of a bleaching\n"
        "event: coral that died in the event is labelled dead-coral-with-algae by the\n"
        "later survey, not bleached."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
