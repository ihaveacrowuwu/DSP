#!/usr/bin/env python3
"""Checks that the Android app, the iOS app and the protocol documents use the same words.

`mobile-shared/design-language.md` requires it in as many words:

    The status vocabulary shown to the contributor - the same sighting must not read
    "Analysing" on one platform and "Processing" on the other.

Nothing in either toolchain can enforce that: ktlint does not read Swift, SwiftLint does not
read Kotlin, and neither reads Markdown. So this does, and `make mobile-lint` runs it.

It also enforces the rule underneath the vocabulary - D21, that the client may assert
"waiting to upload" and "uploading" and nothing else. A status meaning "delivered" appearing
in either app is the exact bug the whole sync design exists to prevent, and it is far easier
to add one by accident than to notice it later.

Exit code 0 when the two platforms agree, 1 otherwise.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

KOTLIN = ROOT / "android/core/model/src/main/kotlin/mv/muraka/core/model/SyncState.kt"
SWIFT = ROOT / "ios/Muraka/Domain/Model/SightingDisplayStatus.swift"

# Words that would be a client claiming something only the server can know. "Checking..." is
# the honest alternative and is deliberately absent from this list.
FORBIDDEN = ("synced", "uploaded", "saved to server", "delivered", "backed up")

# What the client MAY assert, because each is a fact about its own queue rather than a claim
# about the server: two are in flight, and the third is "we tried, repeatedly, and stopped".
CLIENT_ASSERTABLE = ("Waiting to upload", "Uploading", "Could not upload")


def kotlin_labels(text: str) -> list[str]:
    """Every `("...", true/false)` label in the SightingDisplayStatus enum, in order."""
    body = text.split("enum class SightingDisplayStatus(", 1)[-1]
    return re.findall(r'^\s*[A-Z_]+\("([^"]+)",', body, flags=re.MULTILINE)


def swift_labels(text: str) -> list[str]:
    """Every `case .foo: "..."` in the `label` computed property, in order."""
    match = re.search(r"var label: String \{.*?\n    \}", text, flags=re.DOTALL)
    if not match:
        return []
    return re.findall(r'case \.\w+: "([^"]+)"', match.group(0))


def main() -> int:
    problems: list[str] = []

    for path in (KOTLIN, SWIFT):
        if not path.exists():
            problems.append(f"missing: {path.relative_to(ROOT)}")

    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1

    android = kotlin_labels(KOTLIN.read_text(encoding="utf-8"))
    ios = swift_labels(SWIFT.read_text(encoding="utf-8"))

    if not android:
        problems.append("could not read any status labels out of the Kotlin enum")
    if not ios:
        problems.append("could not read any status labels out of the Swift enum")

    if android != ios:
        problems.append(
            "the two apps disagree about the status vocabulary:\n"
            f"  android: {android}\n"
            f"  ios    : {ios}\n"
            "  Both must change together — see mobile-shared/design-language.md."
        )

    for label in android + ios:
        lowered = label.lower()
        if any(word in lowered for word in FORBIDDEN) and label not in CLIENT_ASSERTABLE:
            problems.append(
                f'"{label}" claims the server has accepted something.\n'
                f"  The client may only say {CLIENT_ASSERTABLE};\n"
                "  everything past that is the server's answer or 'Checking…'. See D21."
            )

    if problems:
        print("status vocabulary check FAILED\n", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"status vocabulary agrees across both apps ({len(android)} statuses)")
    for label in android:
        marker = "client" if label in CLIENT_ASSERTABLE else "server"
        print(f"  [{marker}] {label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
