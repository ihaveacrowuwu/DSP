#!/usr/bin/env python3
"""Train a patch classifier from a recipe.

    python3 scripts/train.py --config configs/baseline.yaml --data-root ../datasets/noaa
    python3 scripts/train.py --config configs/baseline.yaml --synthetic   # verify the pipeline

`--synthetic` exists so the pipeline can be exercised without the corpus: it trains on
seeded generated images and proves the loop, the metrics, early stopping, the export and
the reproducibility all work. **No metric from a synthetic run says anything about
coral** and the summary it writes records `synthetic: true` so a figure cannot be quoted
by mistake.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from muraka_train import config as config_module
from muraka_train import data as data_module
from muraka_train import export as export_module
from muraka_train.train import Trainer


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", required=True)
    parser.add_argument("--data-root", help="root holding train/ val/ test/ subdirectories")
    parser.add_argument("--synthetic", action="store_true", help="seeded generated data; verifies the pipeline only")
    parser.add_argument("--synthetic-size", type=int, default=320, help="training examples when --synthetic")
    parser.add_argument("--epochs", type=int, help="override training.epochs, for a quick check")
    parser.add_argument("--output-dir", help="override run.output_dir")
    parser.add_argument("--export-onnx", action="store_true", help="export and parity-check after training")
    parser.add_argument(
        "--no-pretrained",
        action="store_true",
        help="random init instead of ImageNet weights; lets a pipeline check run with no network",
    )
    parser.add_argument("--model-version", help="version string embedded in the ONNX metadata")
    args = parser.parse_args()

    if not args.synthetic and not args.data_root:
        parser.error("give --data-root, or --synthetic to verify the pipeline without the corpus")

    cfg = config_module.load(args.config, data_root=args.data_root)
    if args.epochs:
        object.__setattr__(cfg.training, "epochs", args.epochs)
        # Keep the schedule coherent: an unfreeze epoch past the end would never fire.
        if cfg.training.unfreeze_epoch >= args.epochs:
            object.__setattr__(cfg.training, "unfreeze_epoch", max(1, args.epochs // 3))
        object.__setattr__(cfg.training, "patience", max(2, args.epochs))
    if args.output_dir:
        object.__setattr__(cfg, "output_dir", Path(args.output_dir).resolve())
    if args.no_pretrained:
        cfg.model["pretrained"] = "none"

    sizes = None
    if args.synthetic:
        n = args.synthetic_size
        sizes = {"train": n, "val": max(64, n // 4), "test": max(64, n // 4)}
        print(f"SYNTHETIC RUN - generated data, seed {cfg.seed}. No metric here is about coral.")

    loaders = data_module.make_loaders(cfg, synthetic_sizes=sizes)
    print(f"{cfg.name}: {', '.join(f'{k}={len(v.dataset)}' for k, v in loaders.items())}")

    trainer = Trainer(cfg, loaders)
    print(f"device: {trainer.device} (requested {cfg.training.device})")
    trainer.fit()

    extra: dict[str, object] = {"synthetic": bool(args.synthetic), "data_source": cfg.data.source}
    if args.synthetic:
        extra["warning"] = "synthetic data: these metrics describe the pipeline, not coral"

    if args.export_onnx:
        path = export_module.export(trainer.model, cfg, model_version=args.model_version)
        extra["onnx"] = str(path)
        extra["onnx_parity_max_abs_diff"] = export_module.parity(trainer.model, path, cfg)
        extra["onnx_metadata"] = export_module.read_metadata(path)
        extra["cpu_latency"] = export_module.cpu_latency(path, cfg)
        print(f"exported {path}; parity {extra['onnx_parity_max_abs_diff']:.2e}")

    trainer.save(extra)
    return 0


if __name__ == "__main__":
    sys.exit(main())
