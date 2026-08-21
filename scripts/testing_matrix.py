#!/usr/bin/env python3
"""Keep `TESTING.md` honest about the tests it claims exist.

`docs/07-requirements.md` asks for a document linking FR/NFR IDs to test names to
results, so the project's testing chapter can be assembled from evidence rather than
memory. A hand-written table like that has one predictable failure mode: a test gets
renamed or deleted and the table keeps citing it, so the project claims coverage that
no longer exists. This repository has already had documents drift from the code —
`CLAUDE.md` warns about it — and a traceability matrix is the worst possible place
for it to happen, because its entire value is being trustworthy.

So every test name `TESTING.md` cites in backticks is checked against the tests that
actually exist:

    scripts/testing_matrix.py --list     every test the repository defines, by suite
    scripts/testing_matrix.py --check    fail if TESTING.md cites one that is gone

`--check` is what runs in `make lint`. It deliberately does NOT run the suites: this
answers "does this test exist", not "does it pass". Conflating the two would make the
check need Docker, a simulator and an emulator, and a traceability check nobody can
run is a traceability check nobody runs.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Build outputs contain copies of test sources; counting them would inflate every
# number and make a deleted test look present.
# `.venv` matters as much as `build`: the ML virtualenv contains pytest's own test
# suite, which counted as 11,917 Python "tests" the first time this ran.
EXCLUDED_PARTS = {
    "build", "DerivedData", ".gradle", "node_modules", ".git",
    ".venv", "venv", "site-packages", "__pycache__",
}


def _walk(base: Path, pattern: str) -> list[Path]:
    if not base.exists():
        return []
    return [
        p
        for p in base.rglob(pattern)
        if not EXCLUDED_PARTS & set(p.parts)
    ]


def collect() -> dict[str, set[str]]:
    """Every test name the repository defines, grouped by suite."""
    found: dict[str, set[str]] = {}

    # Go: `func TestFoo(t *testing.T)` and benchmarks.
    go = set()
    for path in _walk(ROOT / "backend", "*_test.go"):
        go |= set(re.findall(r"^func ((?:Test|Benchmark|Fuzz)\w+)", path.read_text(), re.M))
    found["go"] = go

    # Python: pytest functions in the ML service and in scripts/.
    py = set()
    for base in (ROOT / "ml", ROOT / "scripts", ROOT / "backend"):
        for path in _walk(base, "*.py"):
            py |= set(re.findall(r"^def (test_\w+)", path.read_text(), re.M))
    found["python"] = py

    # Web: Vitest `it('...')` / `test('...')`. The name IS the sentence, so it is
    # matched verbatim rather than as an identifier.
    web = set()
    for path in _walk(ROOT / "web", "*.test.ts"):
        text = path.read_text()
        web |= set(re.findall(r"(?:it|test)\(\s*'([^']+)'", text))
        web |= set(re.findall(r'(?:it|test)\(\s*"([^"]+)"', text))
    found["web"] = web

    # Kotlin: JUnit methods. Unit tests name themselves with backtick-quoted
    # sentences; the instrumented tests cannot, because Android's instrumentation
    # runner will not accept a method name containing spaces — so both forms are
    # collected by anchoring on `@Test` rather than on the naming style. Anchoring on
    # backticks alone silently lost all twelve instrumented tests.
    kotlin = set()
    for path in _walk(ROOT / "android", "*.kt"):
        if "src/test" not in path.as_posix() and "src/androidTest" not in path.as_posix():
            continue
        for chunk in path.read_text().split("@Test")[1:]:
            match = re.search(r"fun\s+(?:`([^`]+)`|(\w+))\s*\(", chunk)
            if match:
                kotlin.add(match.group(1) or match.group(2))
    found["kotlin"] = kotlin

    # Swift: XCTest methods.
    swift = set()
    for base in (ROOT / "ios" / "MurakaTests", ROOT / "ios" / "MurakaUITests"):
        for path in _walk(base, "*.swift"):
            swift |= set(re.findall(r"func (test\w+)", path.read_text()))
    found["swift"] = swift

    return found


# A citation is a backticked token that looks like a test name in one of the five
# conventions. Prose in backticks (file paths, flags, types) must not be mistaken for
# a citation, which is why each pattern is anchored to a naming convention rather
# than matching any backticked text.
CITATION_PATTERNS = [
    re.compile(r"`((?:Test|Benchmark|Fuzz)[A-Z]\w*)`"),  # Go
    re.compile(r"`(test_[a-z0-9_]+)`"),                  # Python
    re.compile(r"`(test[A-Z]\w*)`"),                     # Swift
    re.compile(r"`([a-z][^`]{12,}?)`"),                  # Kotlin / web sentences
]


# Backticks are also how this document writes paths, shell commands and pragmas. None
# of those is a citation, and reporting them buries the one line that matters.
NOT_A_TEST_NAME = re.compile(r"[/=]|^(?:make|cd|scripts|docs|python3?) ")


def citations(text: str) -> set[str]:
    """Test names cited by TESTING.md, ignoring fenced code blocks."""
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    out: set[str] = set()
    for pattern in CITATION_PATTERNS:
        out |= set(pattern.findall(text))
    return {name for name in out if not NOT_A_TEST_NAME.search(name)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="print every test the repository defines")
    parser.add_argument("--check", action="store_true", help="fail if TESTING.md cites a test that does not exist")
    args = parser.parse_args()
    if not (args.list or args.check):
        parser.error("choose --list or --check")

    found = collect()

    if args.list:
        for suite in sorted(found):
            print(f"\n── {suite} ({len(found[suite])}) ──")
            for name in sorted(found[suite]):
                print(f"  {name}")
        print(f"\ntotal: {sum(len(v) for v in found.values())}")
        return 0

    doc = ROOT / "TESTING.md"
    if not doc.exists():
        print("TESTING.md does not exist", file=sys.stderr)
        return 1

    every = set().union(*found.values())
    cited = citations(doc.read_text())

    # A citation only has to match SOME suite: the matrix cites Go, Python, Swift,
    # Kotlin and web names in one table, and which language a name belongs to is not
    # something the citation itself declares.
    missing = sorted(name for name in cited if name not in every)

    # Sentences that are prose rather than citations are the expected false positive
    # of the Kotlin/web pattern, so an unmatched citation is reported, not fatal,
    # unless it looks unambiguously like a test identifier.
    identifier = re.compile(r"^(?:Test|Benchmark|Fuzz)[A-Z]|^test[_A-Z]")
    fatal = [name for name in missing if identifier.match(name)]
    prose = [name for name in missing if not identifier.match(name)]

    print(f"TESTING.md cites {len(cited)} names; the repository defines {len(every)} tests")
    for suite in sorted(found):
        print(f"  {suite}: {len(found[suite])}")

    # Tally the matrix rows. The prose summary at the top of the document quoted a
    # coverage figure that was written before the table was filled in and was wrong by
    # a factor of five, so the count is computed here rather than remembered.
    glyphs = {"✅": "automated", "◐": "partial", "✋": "manual only", "○": "no evidence"}
    rows = re.findall(r"^\| (FR\d+|NFR\d+) \|.*\| ([✅◐✋○]) \|$", doc.read_text(), re.M)
    if rows:
        tally: dict[str, int] = {}
        for _, glyph in rows:
            tally[glyph] = tally.get(glyph, 0) + 1
        print(f"\nmatrix: {len(rows)} requirements")
        for glyph, label in glyphs.items():
            print(f"  {glyph} {label}: {tally.get(glyph, 0)}")

    if prose:
        print(f"\n{len(prose)} backticked phrases matched no test (prose, or a renamed sentence-style test):")
        for name in prose:
            print(f"  ? {name}")

    if fatal:
        print(f"\n{len(fatal)} cited test(s) DO NOT EXIST:", file=sys.stderr)
        for name in fatal:
            print(f"  ✗ {name}", file=sys.stderr)
        print("\nEither the test was renamed or deleted, or TESTING.md is wrong.", file=sys.stderr)
        return 1

    print("\nevery cited test identifier exists")
    return 0


if __name__ == "__main__":
    sys.exit(main())
