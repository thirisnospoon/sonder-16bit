"""
The project speaks one language, and that language is English.

WHY THIS CHECK EXISTS. Sonder was written in Russian throughout: comments,
documents, error messages, test names, commit subjects. Moving it to
English is not one commit but a migration across 374 files, and a
migration without a ratchet slides back. One file gets a Russian comment
"just for now" while the rest is being translated, and nobody notices,
because the only thing watching is attention -- and attention has already
failed this project five times over bash identifiers alone.

WHAT IT WATCHES. Cyrillic characters, not "non-ASCII". The difference is
deliberate and load-bearing.

    The project's longest-running class of defect is bytes mistaken for
    characters: RPAD in Firebird padding by characters, PIC widths in the
    copybook counted as characters, `length()` in awk under a multibyte
    locale, the UTF-8 validator in the core. Every one of those is caught
    by fixtures whose text is MULTIBYTE. Replace them with ASCII and the
    tests go green for the wrong reason: in ASCII a byte is a character,
    and there is nothing left to confuse.

    So multibyte fixtures stay multibyte -- Greek, Japanese, accented
    Latin -- and only Cyrillic has to go. A check that banned non-ASCII
    would quietly gut the very tests that make this project worth
    reading.

HOW THE RATCHET WORKS. A baseline file records, per path, how many lines
still contain Cyrillic. The check demands the recorded number equal
reality -- exactly, in both directions:

    more than recorded  -> Russian was added; refuse
    fewer than recorded -> Russian was removed; refuse until the baseline
                           is updated in the same commit

The second half looks pedantic and is not. A ratchet that is never
tightened permits re-adding Russian later, up to the old number, in
silence. Requiring the exact figure keeps the baseline equal to reality
at every commit, so the diff of the baseline IS the record of progress.

    python ops/ci/check-language.py            check
    python ops/ci/check-language.py --update   rewrite the baseline

`--update` refuses to raise any number and refuses to add a path. Adding
Russian therefore requires editing the baseline by hand, where it is
visible in review -- which is the point.

WHEN THE MIGRATION ENDS the baseline is empty, and this check turns into
the plain statement it was always meant to be: no Cyrillic anywhere.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "ops" / "ci" / "language-baseline.tsv"

HEADER = [
    "# Lines still containing Cyrillic, per file. Generated; see",
    "# ops/ci/check-language.py. The count may only go down: this file is",
    "# a ratchet, and its diff is the record of the migration to English.",
    "#",
    "# path\tlines",
]

# The whole of the two Cyrillic blocks in the Basic Multilingual Plane.
# Ranges rather than a regexp with \p{Cyrillic}: Python's own `re` has no
# Unicode property syntax, and pulling in `regex` for one predicate would
# add a dependency to a check that must run anywhere.
CYRILLIC_RANGES = (
    (0x0400, 0x04FF),  # Cyrillic
    (0x0500, 0x052F),  # Cyrillic Supplement
    (0x2DE0, 0x2DFF),  # Cyrillic Extended-A
    (0xA640, 0xA69F),  # Cyrillic Extended-B
)


def is_cyrillic(ch: str) -> bool:
    code = ord(ch)
    return any(lo <= code <= hi for lo, hi in CYRILLIC_RANGES)


# Directories not worth walking: other people's code and generated
# output. The same list the shell-identifier check uses, and for the same
# reason -- there is nothing of ours in them.
SKIP = {".git", "node_modules", "target", "build", "dist",
        "test-results", "lighthouse", "out", "playwright-report"}


def source_files() -> list[str]:
    """Walk the tree rather than ask git.

    Git is absent from the image these Python checks run in, and its
    habit of escaping non-ASCII paths has already opened a hole in a
    neighbouring check -- it silently skipped files with Russian names,
    which in this project is exactly the set that matters. Walking the
    filesystem knows nothing of escaping and sees NEW files too, before
    they reach the index: for a check meant to catch a defect BEFORE the
    commit, that is the whole point.
    """
    found = []
    for path in sorted(ROOT.rglob("*")):
        if any(part in SKIP for part in path.parts):
            continue
        if not path.is_file():
            continue
        found.append(path.relative_to(ROOT).as_posix())
    return found


def count_cyrillic_lines(path: Path) -> int:
    """Lines with at least one Cyrillic character; -1 for binary files.

    Binary is decided by a NUL byte, not by extension: the golden line
    frames are `.bin` but the fuzz corpus has no extension at all, and a
    list of extensions is one more thing to keep in step with reality.
    """
    try:
        data = path.read_bytes()
    except OSError:
        return -1
    if b"\0" in data[:8000]:
        return -1
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        # Not UTF-8 and not obviously binary. It cannot hold Cyrillic in
        # any encoding this project uses, and guessing an encoding here
        # would be inventing information.
        return -1
    return sum(1 for line in text.splitlines() if any(map(is_cyrillic, line)))


def scan() -> dict[str, int]:
    counts: dict[str, int] = {}
    for rel in source_files():
        n = count_cyrillic_lines(ROOT / rel)
        if n > 0:
            counts[rel] = n
    return counts


def read_baseline() -> dict[str, int]:
    if not BASELINE.exists():
        return {}
    recorded: dict[str, int] = {}
    with BASELINE.open(encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            path, _, count = line.rpartition("\t")
            recorded[path] = int(count)
    return recorded


def write_baseline(counts: dict[str, int]) -> None:
    lines = list(HEADER)
    for path in sorted(counts):
        lines.append("{}\t{}".format(path, counts[path]))
    BASELINE.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))


def describe(counts: dict[str, int]) -> str:
    files = len(counts)
    lines = sum(counts.values())
    return "{} files, {} lines".format(files, lines)


def update() -> int:
    actual = scan()

    # First run: there is no baseline to regress against, so the scan IS
    # the baseline. Every later run compares against what was recorded
    # here, and can only lower it.
    if not BASELINE.exists():
        write_baseline(actual)
        print("baseline created: {}".format(describe(actual)))
        return 0

    recorded = read_baseline()

    added = sorted(set(actual) - set(recorded))
    raised = sorted(p for p in actual if p in recorded
                    and actual[p] > recorded[p])

    # --update is for recording progress, never for legitimising a
    # regression. Refusing here is what makes the check hard to
    # accidentally defeat: the easy path only points one way.
    if added or raised:
        for p in added:
            print("  new file with Cyrillic: {} ({} lines)"
                  .format(p, actual[p]), file=sys.stderr)
        for p in raised:
            print("  more Cyrillic than recorded: {} ({} -> {})"
                  .format(p, recorded[p], actual[p]), file=sys.stderr)
        print("--update only lowers numbers; add English, not Russian",
              file=sys.stderr)
        return 1

    write_baseline(actual)
    before = describe(recorded)
    after = describe(actual)
    print("baseline updated: {} -> {}".format(before, after))
    return 0


def check() -> int:
    actual = scan()
    recorded = read_baseline()

    problems = 0

    for path in sorted(set(actual) - set(recorded)):
        print("  Cyrillic in a file that should have none: {} ({} lines)"
              .format(path, actual[path]), file=sys.stderr)
        problems += 1

    for path in sorted(set(actual) & set(recorded)):
        if actual[path] > recorded[path]:
            print("  Russian added to {}: {} -> {} lines"
                  .format(path, recorded[path], actual[path]), file=sys.stderr)
            problems += 1
        elif actual[path] < recorded[path]:
            print("  {} improved to {} lines (baseline says {}) -- run "
                  "--update".format(path, actual[path], recorded[path]),
                  file=sys.stderr)
            problems += 1

    for path in sorted(set(recorded) - set(actual)):
        print("  {} is clean now (baseline says {} lines) -- run --update"
              .format(path, recorded[path]), file=sys.stderr)
        problems += 1

    if problems:
        print("baseline and reality disagree in {} places".format(problems),
              file=sys.stderr)
        return 1

    if not actual:
        print("no Cyrillic anywhere: the migration is complete")
        return 0

    print("still to translate: {}".format(describe(actual)))
    return 0


def main(argv: list[str]) -> int:
    if len(argv) > 1 and argv[1] == "--update":
        return update()
    if len(argv) > 1:
        print("usage: check-language.py [--update]", file=sys.stderr)
        return 2
    return check()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
