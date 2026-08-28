"""Backbone construction and the two-stage freeze schedule.

`torchvision` rather than `timm`: the recipe names EfficientNet-B0 and ConvNeXt-Tiny as
the candidates, torchvision has both with ImageNet weights, and it is already a
dependency of nothing else here that would need pinning twice. `timm` becomes worth its
weight only if the ablation list grows.

The freeze schedule is the reason this file exists rather than one line in `train.py`.
Fine-tuning a pretrained backbone from the first step lets a randomly initialised head
push large gradients through features that took a GPU-month to learn. So the head trains
alone first, and the backbone joins later at a much lower rate.
"""

from __future__ import annotations

import torch
from torch import nn
from torchvision import models

# Each entry: constructor, weights enum, and the attribute holding the classifier.
SUPPORTED = {
    "efficientnet_b0": (models.efficientnet_b0, models.EfficientNet_B0_Weights.IMAGENET1K_V1, "classifier"),
    "efficientnet_v2_s": (models.efficientnet_v2_s, models.EfficientNet_V2_S_Weights.IMAGENET1K_V1, "classifier"),
    "convnext_tiny": (models.convnext_tiny, models.ConvNeXt_Tiny_Weights.IMAGENET1K_V1, "classifier"),
    "mobilenet_v3_large": (models.mobilenet_v3_large, models.MobileNet_V3_Large_Weights.IMAGENET1K_V1, "classifier"),
    "resnet18": (models.resnet18, models.ResNet18_Weights.IMAGENET1K_V1, "fc"),
}


def build(config) -> nn.Module:
    """A backbone with its classifier replaced to emit `num_classes` logits."""
    name = str(config.model["backbone"])
    if name not in SUPPORTED:
        raise ValueError(f"unsupported backbone {name!r}; available: {sorted(SUPPORTED)}")

    constructor, weights, head_attr = SUPPORTED[name]
    pretrained = str(config.model.get("pretrained", "")).lower() in {"imagenet", "true", "1"}
    model = constructor(weights=weights if pretrained else None)

    num_classes = int(config.model["num_classes"])
    drop_rate = float(config.model.get("drop_rate", 0.0))
    head = getattr(model, head_attr)

    if isinstance(head, nn.Sequential):
        # torchvision wraps these heads as Dropout + Linear; replace in place so the
        # rest of the sequential (pooling, flatten) is preserved.
        linear_index = next(i for i, m in reversed(list(enumerate(head))) if isinstance(m, nn.Linear))
        in_features = head[linear_index].in_features
        layers = list(head)
        layers[linear_index] = nn.Linear(in_features, num_classes)
        for index, module in enumerate(layers):
            if isinstance(module, nn.Dropout):
                layers[index] = nn.Dropout(p=drop_rate)
        setattr(model, head_attr, nn.Sequential(*layers))
    else:
        in_features = head.in_features
        setattr(model, head_attr, nn.Sequential(nn.Dropout(p=drop_rate), nn.Linear(in_features, num_classes)))

    return model


def head_parameters(model: nn.Module, config) -> list[nn.Parameter]:
    _, _, head_attr = SUPPORTED[str(config.model["backbone"])]
    return list(getattr(model, head_attr).parameters())


def set_backbone_trainable(model: nn.Module, config, trainable: bool) -> None:
    """Freeze or unfreeze everything except the classifier head."""
    head = set(id(p) for p in head_parameters(model, config))
    for parameter in model.parameters():
        if id(parameter) not in head:
            parameter.requires_grad_(trainable)


def build_optimiser(model: nn.Module, config) -> torch.optim.Optimizer:
    """Two parameter groups, so the backbone can be unfrozen without a new optimiser.

    Creating the optimiser once with both groups - the backbone group present from the
    start but frozen - means the momentum buffers survive the unfreeze. Rebuilding it at
    the unfreeze epoch would reset Adam's state and produce a visible loss spike that
    looks like a bug in the schedule.
    """
    head = head_parameters(model, config)
    head_ids = {id(p) for p in head}
    backbone = [p for p in model.parameters() if id(p) not in head_ids]
    return torch.optim.AdamW(
        [
            {"params": head, "lr": config.training.head_lr},
            {"params": backbone, "lr": config.training.backbone_lr},
        ],
        weight_decay=config.training.weight_decay,
    )


def resolve_device(requested: str) -> torch.device:
    """Honour the request where possible, and say so when falling back.

    The recipe asks for `mps`. A machine without it - CI, a Linux box, a reviewer's
    laptop - must still be able to run the pipeline, so this degrades rather than
    failing. It never silently *upgrades*: asking for cpu gets cpu.
    """
    wanted = requested.lower()
    if wanted == "mps":
        if torch.backends.mps.is_available():
            return torch.device("mps")
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if wanted == "cuda":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device("cpu")
