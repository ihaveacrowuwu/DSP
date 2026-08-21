#!/usr/bin/env python3
"""Fail if the three patch-lattice implementations disagree about opacity.

The lattice is drawn three times — Vue, Jetpack Compose and UIKit — from one
specification in `mobile-shared/README.md` and `mobile-shared/design-tokens.json`:

    overlay:  0.28 + confidence × 0.42
    glyph:    0.45 + confidence × 0.55

Three implementations of one formula is three chances to drift, and no toolchain can
see across them: ktlint does not read Swift, SwiftLint does not read Kotlin, and
neither reads Vue. This is the same gap `check_status_vocabulary.py` exists to close
for the contributor-facing status words.

Drift here is not cosmetic. The overlay's range stops short of solid on purpose —
past roughly 0.7 the cells stop annotating the photograph and start replacing it, and
a researcher who cannot see the coral through the judgement cannot check the
judgement. That ceiling is the argument for having two formulas rather than one, so a
platform that quietly adopted the glyph range for its overlay would break the reason
the lattice is drawn at all while looking perfectly fine in a screenshot.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Each entry: label, path, and a regex per mode capturing (floor, range).
SOURCES = [
    (
        "vue (dashboard)",
        "web/src/components/PatchLattice.vue",
        {
            # A single ternary carries both: glyph first, then overlay.
            "glyph": r"'glyph'\s*\?\s*([\d.]+)\s*\+\s*confidence\s*\*\s*([\d.]+)",
            "overlay": r":\s*([\d.]+)\s*\+\s*confidence\s*\*\s*([\d.]+)\s*\n?\s*return",
        },
    ),
    (
        "kotlin (android)",
        "android/core/designsystem/src/main/kotlin/mv/muraka/core/designsystem/component/PatchLattice.kt",
        {
            "overlay": r"OVERLAY\s*->\s*\(([\d.]+)\s*\+\s*confidence[^*]*\*\s*([\d.]+)\)",
            "glyph": r"GLYPH\s*->\s*\(([\d.]+)\s*\+\s*confidence[^*]*\*\s*([\d.]+)\)",
        },
    ),
    (
        "swift (ios)",
        "ios/Muraka/Core/DesignSystem/PatchLatticeView.swift",
        {
            "overlay": r"case\s+\.overlay:\s*CGFloat\(([\d.]+)\s*\+\s*clamped\s*\*\s*([\d.]+)\)",
            "glyph": r"case\s+\.glyph:\s*CGFloat\(([\d.]+)\s*\+\s*clamped\s*\*\s*([\d.]+)\)",
        },
    ),
]

# The token file is JSON, so it is parsed rather than pattern-matched. The first version
# of this check regexed it and both patterns matched both variants, which made the spec
# look unreadable — a reminder that a regex over structured data is a bug waiting for a
# quiet day.
SPEC_PATH = "mobile-shared/design-tokens.json"
FORMULA = re.compile(r"^\s*([\d.]+)\s*\+\s*confidence\s*\*\s*([\d.]+)\s*$")


def extract_spec() -> dict[str, tuple[float, float] | None]:
    import json

    full = ROOT / SPEC_PATH
    if not full.exists():
        return {"overlay": None, "glyph": None}
    lattice = json.loads(full.read_text()).get("patchLattice", {})
    out: dict[str, tuple[float, float] | None] = {}
    for mode in ("overlay", "glyph"):
        match = FORMULA.match(str(lattice.get(mode, {}).get("opacity", "")))
        out[mode] = (float(match.group(1)), float(match.group(2))) if match else None
    return out


def extract(path: str, patterns: dict[str, str]) -> dict[str, tuple[float, float] | None]:
    full = ROOT / path
    if not full.exists():
        return {mode: None for mode in patterns}
    text = full.read_text()
    out: dict[str, tuple[float, float] | None] = {}
    for mode, pattern in patterns.items():
        found = re.findall(pattern, text, re.S)
        # More than one match is ambiguous, which is itself worth reporting rather
        # than silently taking the first.
        pairs = {(float(a), float(b)) for a, b in found}
        out[mode] = next(iter(pairs)) if len(pairs) == 1 else None
    return out


def main() -> int:
    found: dict[str, dict[str, tuple[float, float] | None]] = {}
    for label, path, patterns in SOURCES:
        found[label] = extract(path, patterns)
    found["spec (design-tokens.json)"] = extract_spec()

    unreadable = [
        f"{label} / {mode}"
        for label, modes in found.items()
        for mode, value in modes.items()
        if value is None
    ]

    print("patch lattice opacity, by implementation:")
    for label, modes in found.items():
        parts = []
        for mode in ("overlay", "glyph"):
            value = modes.get(mode)
            parts.append(
                f"{mode}={value[0]} + c×{value[1]}" if value else f"{mode}=NOT FOUND"
            )
        print(f"  {label:26} {'   '.join(parts)}")

    if unreadable:
        print(
            "\nCould not read the formula from: " + ", ".join(unreadable),
            file=sys.stderr,
        )
        print(
            "Either an implementation moved, or it was rewritten in a shape this check "
            "does not recognise. Both are worth a human look — an unreadable formula is "
            "an unchecked formula.",
            file=sys.stderr,
        )
        return 1

    problems = []
    for mode in ("overlay", "glyph"):
        values = {label: modes[mode] for label, modes in found.items()}
        distinct = set(values.values())
        if len(distinct) != 1:
            problems.append(f"{mode}: " + ", ".join(f"{k} = {v}" for k, v in values.items()))

    # And the two formulas must stay distinct: a platform that used one range for both
    # would agree with itself and still be wrong.
    agreed = {mode: next(iter({m[mode] for m in found.values()})) for mode in ("overlay", "glyph")}
    if not problems and agreed["overlay"] == agreed["glyph"]:
        problems.append(
            "overlay and glyph share one formula; there are deliberately two, because "
            "the overlay must stay clear of solid"
        )

    if problems:
        print("\nthe patch lattice has DRIFTED:", file=sys.stderr)
        for problem in problems:
            print(f"  ✗ {problem}", file=sys.stderr)
        print(
            "\nThe formulas are specified in mobile-shared/README.md. Whichever "
            "implementation is wrong, fix it there rather than changing the spec to "
            "match the drift.",
            file=sys.stderr,
        )
        return 1

    ceiling = agreed["overlay"][0] + agreed["overlay"][1]
    print(
        f"\nall {len(found)} sources agree; the overlay tops out at {ceiling:.2f}, "
        "leaving the photograph readable"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
