# ADR-0023. The project speaks English, and a ratchet keeps it that way

**Status:** Accepted
**Date:** 2026-09-05
**Supersedes / superseded by:** —

## Context

Sonder was written in Russian from the first commit: comments, documents,
ADRs, error messages, test names, commit subjects. That was a deliberate
choice at the time and it served the project well — the comments in this
codebase are unusually dense, and writing them in a first language is why
they are dense rather than perfunctory.

The project is now public and meant to be read by people who do not read
Russian. Measured before deciding anything: **21 410 lines carrying
Cyrillic across 374 of 385 files**, plus 141 commit messages. That is not
one commit. It is a migration.

**A migration without a ratchet slides back.** One file gets a Russian
comment "just for now" while the rest is being translated; nobody
notices, because the only thing watching is attention — and attention has
already failed this project five times over bash identifiers alone
(`ops/ci/check-shell.py`).

## Decision

**Every artefact of the project is in English**: code comments,
identifiers, documents, ADRs, user-visible strings, test names, commit
messages. Russian remains only in conversation between the people
working on it, which leaves no trace in the repository.

**The migration is guarded by a ratchet**, `./sonder check-language`,
which runs in `verify` from the first day — before a single line is
translated. `ops/ci/language-baseline.tsv` records how many lines still
hold Cyrillic per file, and the number must equal reality **in both
directions**:

| observed vs recorded | meaning | verdict |
|---|---|---|
| more | Russian was added | refuse |
| fewer | something was translated, ratchet not tightened | refuse until `--update` |

The second half looks like pedantry and is not. A ratchet nobody tightens
permits putting Russian back later, up to the old number, in silence.
Requiring the exact figure keeps the baseline equal to reality at every
commit, so **the diff of the baseline is the record of the migration**.

`--update` refuses to raise any number and refuses to add a path. Adding
Russian therefore requires editing the baseline by hand, where it is
visible in review.

**Cyrillic is banned; multibyte text is not.** This distinction is
load-bearing, and getting it wrong would quietly destroy the project's
most valuable tests.

The longest-running class of defect here is bytes mistaken for
characters: `RPAD` in Firebird padding by characters, `PIC` widths in the
copybook counted as characters, `length()` in awk under a multibyte
locale, the UTF-8 validator in the core, the record-length check on
Brainfuck ([ADR-0019](0019-reclen-second-opinion.md)). Every one of those
is caught by fixtures whose text is **multibyte**. Replace them with
ASCII and the tests go green for the wrong reason: in ASCII a byte is a
character, and there is nothing left to confuse.

So multibyte fixtures stay multibyte and stop being Russian — Greek,
Japanese, accented Latin — with the expected byte counts recomputed. A
check that banned non-ASCII would have gutted exactly the tests worth
keeping.

## Consequences

**What gets easier.**

The project becomes readable by the people it is now published to. New
code is English from the moment this ADR lands, including new code in
files that are still Russian — which looks odd during the migration and
is the only ordering that cannot regress.

**What it costs.**

The translation itself: 21 410 lines of dense technical prose, done in
verified slices rather than one pass. Commit history has to be rewritten
at the end, which changes every hash — acceptable while the repository
has no forks, and not later.

**What it does not do.**

The ratchet counts lines, not quality. It cannot tell a translated
comment from a deleted one, and a file whose Russian was simply removed
passes as readily as one whose Russian was rendered into English. That is
what review is for; the check only guarantees the direction.

## How it is verified

`./sonder check-language` in `verify`, from before the first translated
line.

Falsification (`ops/ci/falsify/language.sh`, in the fast suite) covers
both directions of the ratchet and the obvious escape:

1. a **new file** carrying Russian — the commonest way it returns;
2. Russian **added** to a file already counted;
3. Russian **removed** with the baseline left stale;
4. `--update` invoked to **legitimise a regression** — it must refuse.
