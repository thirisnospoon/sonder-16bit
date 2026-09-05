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

It prints what is left. At the last commit: **368 files, 21 267 lines**
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
MULTIBYTE fixtures. Turning `Андрей` into `Andrew` makes those tests pass
for the wrong reason -- in ASCII a byte is a character. Fixtures become
Greek, Japanese or accented Latin, and the expected byte counts are
recomputed. Files where this matters:

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

**One shim exists and must be removed at the end.** `cmd_check_drift` in
`sonder` accepts both `изменён|без изменений` and `changed|unchanged`,
because generators cross over one at a time. When the last Russian
generator is gone, drop the Russian half.

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

## 2. Outstanding on the GitHub side

The author email was rewritten to `ben.limitedvision@gmail.com` and the
co-author trailers were stripped, then force-pushed. **But old objects
remain reachable on GitHub by exact SHA** -- measured, not assumed:

```bash
gh api repos/thirisnospoon/sonder-16bit/commits/3353ec3
```

still returns the old address. To be rid of them the repository has to be
deleted and re-created, and the token lacks the `delete_repo` scope. One
action is needed from the owner:

```bash
gh auth refresh -s delete_repo      # then the repo can be recreated
```

or delete it in the web UI and push again.

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
