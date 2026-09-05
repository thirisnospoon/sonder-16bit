#!/usr/bin/env bash
# Does the language ratchet catch what it is there for?
#
# Four cases, covering both directions of the ratchet and one attempt to
# walk around it:
#
#   1. A NEW FILE WITH RUSSIAN. The commonest way Russian returns to a
#      translated project is in a file the baseline has never heard of.
#
#   2. RUSSIAN ADDED TO A FILE ALREADY COUNTED. More lines than recorded.
#
#   3. RUSSIAN REMOVED, BASELINE NOT UPDATED. Looks like pedantry and is
#      not: a ratchet nobody tightens permits putting Russian back, up to
#      the old number, in silence.
#
#   4. AN ATTEMPT TO LEGITIMISE A REGRESSION through --update. The easy
#      path must lead one way only, or the check is defeated by a single
#      command.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BASELINE="$ROOT/ops/ci/language-baseline.tsv"
PROBE="$ROOT/ops/ci/language-probe.txt"
VICTIM="$ROOT/ops/ci/language-victim.txt"
KEEP="$(mktemp)"

cd "$ROOT" || exit 1

cp "$BASELINE" "$KEEP"
trap 'cp "$KEEP" "$BASELINE"; rm -f "$KEEP" "$PROBE" "$VICTIM"' EXIT

run() { bash ./sonder check-language > "$1" 2>&1; }

run /tmp/f-lang-0.log || { echo "  BASELINE IS RED"; tail -3 /tmp/f-lang-0.log; exit 1; }

# --- 1. A new file with Russian ---------------------------------------
printf '\xd1\x80\xd1\x83\xd1\x81\xd1\x81\xd0\xba\xd0\xb8\xd0\xb9\n' > "$PROBE"
run /tmp/f-lang-1.log && { echo "  GREEN ON A NEW RUSSIAN FILE"; exit 1; }
grep -q "should have none" /tmp/f-lang-1.log \
  || { echo "  failed, but not on the new file"; exit 1; }
rm -f "$PROBE"

# --- 2. Russian added to a counted file --------------------------------
#
# The probe file is added to the baseline with a count of zero first, so
# it IS counted -- then given a Russian line. That is the regression the
# ratchet exists to refuse.
printf '\xd1\x80\xd1\x83\xd1\x81\xd1\x81\xd0\xba\xd0\xb8\xd0\xb9\n' > "$VICTIM"
python3 - "$BASELINE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
lines = p.read_text(encoding="utf-8").splitlines()
lines.append("ops/ci/language-victim.txt\t0")
p.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="")
PY
run /tmp/f-lang-2.log && { echo "  GREEN ON RUSSIAN ADDED"; exit 1; }
grep -q "Russian added" /tmp/f-lang-2.log \
  || { echo "  failed, but not on the addition"; exit 1; }

# --- 4. --update refuses to raise a number -----------------------------
#
# Checked here, while the defect is in place: the easy path must only
# ever point down.
if bash ./sonder check-language --update > /tmp/f-lang-4.log 2>&1; then
  echo "  --update LEGITIMISED A REGRESSION"
  exit 1
fi
grep -q "only lowers numbers" /tmp/f-lang-4.log \
  || { echo "  --update refused, but for the wrong reason"; exit 1; }

rm -f "$VICTIM"
cp "$KEEP" "$BASELINE"

# --- 3. Russian removed, baseline left stale ---------------------------
#
# One number is raised by one -- the same state as translating a line and
# forgetting to tighten the ratchet.
python3 - "$BASELINE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
out = []
done = False
for line in p.read_text(encoding="utf-8").splitlines():
    if not done and line.startswith("README.md\t"):
        path, count = line.split("\t")
        line = "{}\t{}".format(path, int(count) + 1)
        done = True
    out.append(line)
assert done, "README.md missing from the baseline"
p.write_text("\n".join(out) + "\n", encoding="utf-8", newline="")
PY
run /tmp/f-lang-3.log && { echo "  GREEN ON A STALE BASELINE"; exit 1; }
grep -q "run --update" /tmp/f-lang-3.log \
  || { echo "  failed, but not on the stale baseline"; exit 1; }

echo "  new file, addition, stale baseline and the --update escape are all caught"
