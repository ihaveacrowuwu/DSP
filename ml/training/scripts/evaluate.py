#!/usr/bin/env python3
"""Evaluate a checkpoint on the **test** split - the one that stays closed until the end.

`data.test_split_locked` is a discipline, not a mechanism: nothing stops someone reading
the test split early. What this script does is make the honest path the easy one, and
record in its output that the split was opened, when, and by which checkpoint.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from muraka_train import config as config_module
from muraka_train import data as data_module
from muraka_train import model as model_module
from muraka_train.train import Trainer


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--data-root")
    parser.add_argument("--synthetic", action="store_true")
    parser.add_argument("--synthetic-size", type=int, default=320)
    parser.add_argument("--split", default="test", choices=["val", "test"])
    args = parser.parse_args()

    cfg = config_module.load(args.config, data_root=args.data_root)
    sizes = None
    if args.synthetic:
        n = args.synthetic_size
        sizes = {"train": n, "val": max(64, n // 4), "test": max(64, n // 4)}
    loaders = data_module.make_loaders(cfg, synthetic_sizes=sizes)

    trainer = Trainer(cfg, loaders, log=False)
    state = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    trainer.model.load_state_dict(state["state_dict"])
    trainer.model.to(trainer.device)

    loss, computed = trainer.evaluate(loaders[args.split])
    report = {
        "split": args.split,
        "checkpoint": str(args.checkpoint),
        "loss": round(loss, 6),
        **computed.as_dict(),
    }
    print(json.dumps(report, indent=2))

    labels = cfg.data.class_labels
    print("\nconfusion matrix (rows = truth, columns = prediction)")
    print("            " + "".join(f"{l:>12}" for l in labels))
    for index, label in enumerate(labels):
        print(f"{label:>12}" + "".join(f"{v:>12}" for v in computed.confusion[index]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
