# What next

The resume point. Updated at the end of a session, read at the start of
the next one.

**State on 2026-09-05:** `./sonder verify` green, branch `main`, published
at https://github.com/thirisnospoon/sonder-16bit

---

## 1. The migration to English is the current work

Everything in the repository is to be English: comments, identifiers,
documents, ADRs, user-visible strings, test names, commit subjects
([ADR-0023](adr/0023-english-only.md)). Russian survives only in
conversation, which leaves no trace here.

**Where it stands.** Ask the ratchet, do not guess:

```bash
./sonder check-language
```

It prints what is left. At the last commit: **364 files, 21 016 lines**
(from 374 / 21 410 at the start).

### The method, and why it is this one

**Translate whole areas, not layers.** An earlier plan was to do
identifiers, then strings, then comments. Wrong: in one Python file the
identifiers and the comments explain each other, and splitting the work
means touching every file twice and reviewing it twice.

**Tighten the ratchet in the same commit.** `check-language` demands the
baseline equal reality exactly, in both directions, so:

```bash
./sonder check-language --update     # after translating, before committing
```

**New code is English even inside files still in Russian.** This looks
odd during the migration and is the only ordering that cannot slide back.
The check caught its own author on the first run doing otherwise.

**Cyrillic is banned; multibyte is not.** The oldest defect class here is
bytes mistaken for characters, and every test that catches it needs
MULTIBYTE fixtures. Turning a two-byte-per-letter name into an ASCII one
makes those tests pass for the wrong reason: there a byte IS a character,
and nothing is left to confuse. Fixtures become Greek, Japanese or
accented Latin, and the expected byte counts are recomputed with them.
Files where this matters:

* `report/test/make-fixture.py` and `ops/ci/report-test.sh` -- the digest
  golden numbers (367 bytes / 217 characters / 1.69 bytes per character)
  are computed from the fixture text and must be recomputed with it;
* `dosnode/tools/mkcases.pas` -- the domain corpus deliberately contains
  Cyrillic bodies at the length boundary; any replacement must keep two
  bytes per character or the boundary case stops being one;
* `core/src/test/java/sonder/irc/IrcCastTest.java` -- the byte-wise split
  of a post into protocol lines;
* `web/src/test/*` -- anywhere a length is asserted.

**Contracts before their generated files.** Translating a generated file
directly is undone by the next `./sonder codegen` and caught by
`check-drift`. Translate `contracts/**`, regenerate, commit both.

**Translating text breaks whatever matches on that text.** Learned the
hard way within an hour: `contracts/errors/errors.yaml` was translated,
and two cases of the contract validator's own selftest went red -- they
inject a defect by finding an exact Russian line and could no longer find
it. The message was honest ("nothing found to corrupt") because that
selftest was written to say so, which is the only reason it was noticed
at all. Before translating a file, ask who greps it:

```bash
grep -rIn "a fragment about to be translated" --exclude-dir=.git .
```

Places that match on text rather than on structure: `tools/validate-contracts/selftest.py`,
everything under `ops/ci/falsify/`, the golden assertions in
`ops/ci/report-test.sh`, and the e2e specs in `web/e2e/`.

**One shim exists and must be removed at the end.** `cmd_check_drift` in
`sonder` accepts both the Russian and the English spelling of a
generator's verdict line, because generators cross over one at a time and
a pattern knowing only one of the two would silently stop seeing half the
files. When the last Russian generator is gone, drop the Russian half.

### Suggested order of areas

| Area | Notes |
|---|---|
| ~~`contracts/domain/limits.yaml` + `gen_limits.py`~~ | done; the pattern to copy |
| ~~`contracts/errors/errors.yaml`~~ | done; regenerates `ErrorCode.java` |
| remaining generators in `tools/` | `gen_errors`, `gen_api_types`, `gen_events`, `gen_copybook`, `wsdl2pas`, `gen_reclen` (418 lines, the largest) |
| `ops/ci/*.py` checks | `check-shell`, `check-layers`, `check-budgets` -- self-contained, Cyrillic identifiers throughout |
| remaining contracts | `events.yaml`, `openapi/social-v1.yaml`, `soap/decider-v1.wsdl`, `idl/`, `reports/digest-v1.yaml` |
| shell: `sonder`, `ops/ci/*.sh`, `dosnode/build/*.sh` | developer-facing output; Cyrillic bash FUNCTION names to rename |
| Pascal: `dosnode/src/**`, `dosnode/tests/**` | comments and TAP test names |
| Java: `core/src/**` | comments, log lines, a few user-visible refusal details |
| Web: `web/src/**`, `web/e2e/**` | **product text the user sees**; e2e assertions must move with it |
| `docs/**` incl. all ADRs | the largest prose: about 4 400 lines |
| commit messages | last, by a single history rewrite |

### Rewriting the commit messages

Done once, at the end, with `git filter-branch --msg-filter` (the recipe
is in this session's history; `git-filter-repo` is not installed).
Rewriting changes every hash, which is acceptable while the repository
has no forks -- check first:

```bash
gh repo view thirisnospoon/sonder-16bit --json forkCount,stargazerCount
```

---

## 2. The GitHub side is settled, and the remote is the only backup

The author email throughout the history is `ben.limitedvision@gmail.com`
and no commit message names any tool.

Getting there took more than a force-push, and the difference is worth
remembering. Rewriting history and force-pushing leaves the OLD objects
reachable on GitHub by exact SHA -- measured at the time, not assumed:
`gh api .../commits/<old sha>` still returned the previous address. Only
deleting the repository and pushing into a fresh one actually removes
them. Verified afterwards the same way: the old SHA is gone and the
server-side history holds exactly one address.

If history is rewritten again -- translating the commit messages will do
it -- the same applies: a force-push hides, a fresh repository removes.

**The working tree is not a backup, and this is not hypothetical.** On
2026-09-06 the project directory was found emptied down to a single
folder that Docker had just re-created as a bind-mount point. Only
`pascal` was affected; sibling directories were intact; WSL's `/tmp` had
been cleared as well and containers showed `Exited (255)`, the signature
of a daemon restart. The cause was never established -- the project's own
scripts delete nothing but `mktemp` directories and their own `out/`.

Recovery cost nothing because everything was pushed: `git clone` restored
146 commits and 389 files, and the only loss was one uncommitted edit to
this file. **Commit and push at every green `verify`,** not at the end of
a session.

One thing the clone needs on a Windows mount, or every file shows as
modified:

```bash
git config core.fileMode false
```

---

## 3. Closed, for the record

**The fuzzing gate is met**: 38.97 hours against the 24 required, 6.31 of
them under DOSBox against 6 required, no violations. That was the last
open gate of phases 0-10. The `sonder-native` container has been stopped;
nothing needs it any more.

---

## 4. The second opinion still covers two operations of seven

Machinery is in place and works
([ADR-0022](adr/0022-domain-second-opinion.md)): `createPost` and
`registerUser`, 1261 cases, agreement on all.

Remaining: **`createComment`, `deletePost`, `followUser`,
`unfollowUser`, `banUser`.** Take them by the KIND of rule they add, not
alphabetically:

| Operation | What it brings that is new |
|---|---|
| `deletePost` | ownership and **idempotency**: a repeated delete is accepted and emits no event |
| `banUser` | **role ranks** (`RoleOutranks`): a moderator does not ban an admin |
| `createComment` | the context of **someone else's object**: the post exists, is visible, is not deleted |
| `followUser` / `unfollowUser` | self-follow and the target's context |

Per operation, half an hour, the machinery identical:

1. `dosnode/tools/mkcases.pas` -- add `EmitXxx` and `XxxCases`, write
   `contracts/generated/domain/<operation>.tsv`. Watch the loop bounds at
   zero length: `for I := 0 to Len - 1` with `Len = 0` and type `Word`
   gives `0..65535` and has already crashed this program once.
2. `ops/ci/gen-domain-cases.sh` -- add the name to the list.
3. `dosnode/prolog/<operation>.pl` -- rules written AFRESH from the
   contract and the ADRs, not copied from the Pascal. Shared predicates
   come from `rules.pl`; if one is missing, add it there rather than
   duplicating.
4. `ops/ci/domain-crosscheck.sh` -- one `run_op` call.
5. `ops/ci/falsify/domain-crosscheck.sh` -- a defect characteristic of
   that operation (broken idempotency for `deletePost`, swapped ranks for
   `banUser`).
6. `./sonder verify`, then commit. **Drift fails until the commit** --
   that is by design: a generated file belongs under version control.

Check the corpus balance before committing, or whole branches stay
unexercised:

```bash
awk -F'\t' '!/^#/ {print $NF}' contracts/generated/domain/<file>.tsv | sort | uniq -c | sort -rn
```

---

## 5. Proposed, not started

**SHA-256 in 8086 assembly with signed commands.** Closes a real hole:
the core trusts the shell's word that a command comes from user u-1 and
has no way to check. With a signature it checks for itself. NIST vectors.
Whether assembly beats Pascal there is settled by measurement, not
belief. Estimate 5 d.

**An NNTP gateway.** Differs from IRC not in carrier but in the SHAPE of
the data: Usenet is articles with threads and `References`, not a stream
of lines. The domain would have to serve the same posts in another form.
Authentication of its own (`AUTHINFO`), so privacy is untouched. ~4 d.

**CRC-16 frames over a WebSocket.** The browser would speak the same wire
format as the DOS node and be checked by the same golden frames. Proves
the frame codec is ignorant of its carrier.

---

## 6. Small things, noticed and deferred

* **The digest prints every author** -- 545 rows on real data, no top-N.
  A product decision, not a defect; not to be changed unasked.
* **A missed night is not caught up** (the machine was off). Recorded in
  [ADR-0020](adr/0020-digest-on-a-schedule.md) as an accepted cost.
* **15 falsifications have no script of their own** -- injecting a defect
  there means changing a contract, a threshold or an image. Listed in
  `ops/ci/falsify/run.sh`, each verified by hand once. This is exactly
  the class that rots: `TcLog` in the layer falsification did.
* **Coverage of the domain rules on `i8086-msdos` has no number** -- an
  open question, recorded in [ENGINEERING.md](ENGINEERING.md).

---

## 7. Bringing the system up after a break

```bash
./sonder up                # the whole compose; image build order inside
./sonder verify            # every check, minutes
./sonder falsify           # fast falsifications, seconds
./sonder falsify-slow      # plus those needing images and a running stack
```

The IRC gateway listens on `127.0.0.1:6667`; join with a real client:

```bash
irssi -c 127.0.0.1 -p 6667 -w <password> -n <nick>
```

What is written in `#feed` becomes a post and goes through the Pascal
core; the feed arrives there by the same fan-out that serves the browser.
