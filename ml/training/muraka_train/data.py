"""Datasets and transforms.

Two sources, deliberately:

**`folder`** — the real one. `root/<split>/<folder>/*.jpg`, with `data.folder_map`
translating the dataset's own directory names (`CORAL`, `CORAL_BL`) onto this project's
labels. The mapping lives in the config rather than in code because it belongs to the
dataset, not to the recipe.

**`synthetic`** — generated, seeded, and the reason the pipeline could be verified end to
end before the real corpus was downloaded. It is not a stand-in for evaluation and no
metric from it means anything about coral. What it does prove is that the loop trains,
the metrics compute, early stopping fires, the ONNX export matches PyTorch and the same
seed gives the same numbers — which is all of NFR16 except the data.

The synthetic images are teal-vs-bone with a patch texture, which is learnable but not
trivially so: a run that reaches 1.00 accuracy in one epoch would mean the task is
degenerate and the pipeline is proving nothing.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import transforms

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


@dataclass(frozen=True)
class Example:
    path: Path | None
    label: int
    # Only for synthetic examples: the seed that generates the image.
    synthetic_seed: int | None = None


class CoralDataset(Dataset):
    """Images and integer labels, in the class order the service expects."""

    def __init__(self, examples: list[Example], transform, image_size: int) -> None:
        self.examples = examples
        self.transform = transform
        self.image_size = image_size

    def __len__(self) -> int:
        return len(self.examples)

    def __getitem__(self, index: int):
        example = self.examples[index]
        if example.synthetic_seed is not None:
            image = _synthetic_image(example.synthetic_seed, example.label, self.image_size)
        else:
            image = Image.open(example.path).convert("RGB")
        return self.transform(image), example.label


def _synthetic_image(seed: int, label: int, size: int) -> Image.Image:
    """A seeded, learnable image: teal for healthy, bone for bleached, plus noise.

    The signal is the channel balance, and the noise is strong enough that a model has
    to generalise rather than memorise — otherwise a verification run would hit a
    perfect score immediately and tell us nothing about the pipeline.
    """
    rng = np.random.default_rng(seed)
    base = np.array([18, 120, 108], dtype=np.float32) if label == 0 else np.array([232, 226, 214], dtype=np.float32)
    canvas = np.tile(base, (size, size, 1))
    # Blocky texture, so the model sees structure rather than a flat colour.
    blocks = rng.normal(0.0, 26.0, size=(8, 8, 3)).astype(np.float32)
    canvas += np.kron(blocks, np.ones((size // 8 + 1, size // 8 + 1, 1)))[:size, :size, :]
    canvas += rng.normal(0.0, 14.0, size=(size, size, 3)).astype(np.float32)
    return Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8), mode="RGB")


def build_transforms(config) -> tuple[object, object]:
    """`(train, eval)` transforms.

    The eval transform must match what the service does to a patch before inference:
    resize to the model's input and normalise with the shared statistics. Anything else
    here is augmentation and applies to training only.
    """
    size = config.data.image_size
    mean, std = list(config.normalise_mean), list(config.normalise_std)
    aug = config.augmentation

    train_steps: list[object] = []
    crop = aug.get("random_resized_crop")
    if crop:
        train_steps.append(
            transforms.RandomResizedCrop(
                size, scale=tuple(crop.get("scale", (0.8, 1.0))), ratio=tuple(crop.get("ratio", (0.9, 1.1)))
            )
        )
    else:
        train_steps.append(transforms.Resize((size, size)))
    if aug.get("horizontal_flip"):
        train_steps.append(transforms.RandomHorizontalFlip(float(aug["horizontal_flip"])))
    if aug.get("vertical_flip"):
        # Reef photographs have no canonical "up" — a vertical flip is as valid a view
        # as the original, which is not true of most image datasets.
        train_steps.append(transforms.RandomVerticalFlip(float(aug["vertical_flip"])))
    if aug.get("rotation_degrees"):
        train_steps.append(transforms.RandomRotation(float(aug["rotation_degrees"])))
    jitter = aug.get("color_jitter")
    if jitter:
        # Underwater colour casts shift enormously with depth and light, so this matters
        # more here than in a typical vision task.
        train_steps.append(
            transforms.ColorJitter(
                brightness=float(jitter.get("brightness", 0)),
                contrast=float(jitter.get("contrast", 0)),
                saturation=float(jitter.get("saturation", 0)),
                hue=float(jitter.get("hue", 0)),
            )
        )
    train_steps += [transforms.ToTensor(), transforms.Normalize(mean, std)]

    eval_steps = [transforms.Resize((size, size)), transforms.ToTensor(), transforms.Normalize(mean, std)]
    return transforms.Compose(train_steps), transforms.Compose(eval_steps)


def discover_folder_split(root: Path, split: str, config) -> list[Example]:
    """Read `root/<split>/<folder>/*` using the config's folder map."""
    split_dir = root / split
    if not split_dir.is_dir():
        raise FileNotFoundError(f"no '{split}' split under {root}")

    label_index = {label: index for index, label in enumerate(config.data.class_labels)}
    examples: list[Example] = []
    for folder, label in sorted(config.data.folder_map.items()):
        directory = split_dir / folder
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*")):
            if path.suffix.lower() in IMAGE_SUFFIXES:
                examples.append(Example(path=path, label=label_index[label]))
    if not examples:
        raise FileNotFoundError(
            f"{split_dir} contained no images under {sorted(config.data.folder_map)}. "
            "Check data.folder_map against the dataset's own directory names."
        )
    # Sorted, then shuffled by a fixed seed: filesystem order is not reproducible
    # across machines, and an unshuffled class-ordered list makes batches degenerate.
    rng = np.random.default_rng(config.seed + _stable_hash(split))
    rng.shuffle(examples)
    return examples


def synthetic_split(split: str, count: int, config) -> list[Example]:
    """A seeded synthetic split, imbalanced the way the real one is."""
    rng = np.random.default_rng(config.seed + _stable_hash(split))
    # Roughly 62/38 healthy to bleached, matching the real training split's
    # 4,541 / 2,751 — so the imbalance handling is exercised rather than bypassed.
    labels = (rng.random(count) > 0.62).astype(int)
    base = _stable_hash(split) * 1_000_003 + config.seed
    return [Example(path=None, label=int(label), synthetic_seed=base + i) for i, label in enumerate(labels)]


def _stable_hash(text: str) -> int:
    """A hash that is the same in every process, unlike Python's salted `hash`."""
    return int.from_bytes(hashlib.sha256(text.encode()).digest()[:4], "big")


def make_loaders(config, *, synthetic_sizes: dict[str, int] | None = None) -> dict[str, DataLoader]:
    """Loaders for train/val/test, from the real corpus or synthetic data."""
    train_tf, eval_tf = build_transforms(config)

    if synthetic_sizes is not None:
        splits = {name: synthetic_split(name, size, config) for name, size in synthetic_sizes.items()}
    else:
        if config.data.root is None:
            raise ValueError(
                "no --data-root given and no synthetic sizes requested. The recipe names a "
                f"dataset ({config.data.source}) but not a path on this machine."
            )
        splits = {
            name: discover_folder_split(config.data.root, name, config)
            for name in ("train", "val", "test")
        }

    loaders: dict[str, DataLoader] = {}
    for name, examples in splits.items():
        is_train = name == "train"
        dataset = CoralDataset(examples, train_tf if is_train else eval_tf, config.data.image_size)
        sampler = None
        shuffle = is_train
        if is_train and config.imbalance.get("oversample_minority"):
            sampler = _balanced_sampler(examples, len(config.data.class_labels), config.seed)
            # A sampler and shuffle are mutually exclusive in DataLoader.
            shuffle = False
        loaders[name] = DataLoader(
            dataset,
            batch_size=config.training.batch_size,
            shuffle=shuffle,
            sampler=sampler,
            num_workers=config.data.num_workers if config.data.root else 0,
            drop_last=False,
            # Reproducibility: without a seeded generator, shuffling differs per run
            # and "same seed, same metrics" (NFR16) is not true.
            generator=torch.Generator().manual_seed(config.seed) if shuffle else None,
        )
    return loaders


def _balanced_sampler(examples: list[Example], num_classes: int, seed: int) -> WeightedRandomSampler:
    counts = np.bincount([e.label for e in examples], minlength=num_classes).astype(np.float64)
    counts[counts == 0] = 1.0
    weights = [1.0 / counts[e.label] for e in examples]
    return WeightedRandomSampler(
        weights=weights,
        num_samples=len(examples),
        replacement=True,
        generator=torch.Generator().manual_seed(seed),
    )


def class_weights(examples: list[Example], num_classes: int, bleached_multiplier: float = 1.0) -> torch.Tensor:
    """Inverse-frequency weights, with an extra push towards recall on bleached."""
    counts = np.bincount([e.label for e in examples], minlength=num_classes).astype(np.float64)
    counts[counts == 0] = 1.0
    weights = counts.sum() / (num_classes * counts)
    # Index 1 is bleached, guaranteed by the service cross-check in config.py.
    if num_classes == 2:
        weights[1] *= bleached_multiplier
    return torch.tensor(weights, dtype=torch.float32)
