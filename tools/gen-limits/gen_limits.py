#!/usr/bin/env python3
"""
Generate the numeric domain limits from contracts/domain/limits.yaml.

The limits are decided by the core, so they live in the domain contract
rather than in OpenAPI. The same numbers appear in the web contract as a
hint for the interface, and a disagreement between the two is exactly the
situation where the client tells the user one thing and the core refuses
on another. The contract validator compares them in a check of its own.

Targets:
  Pascal -- dosnode/src/generated/dmlimits.inc, where the limits bite;
  TS     -- web/src/generated/limits.ts, where they only hint.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("pyyaml is required", file=sys.stderr)
    sys.exit(64)

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "contracts" / "domain" / "limits.yaml"

BANNER = [
    "GENERATED. Do not edit by hand.",
    "",
    "Source:      contracts/domain/limits.yaml",
    "Generator:   tools/gen-limits/gen_limits.py",
    "Regenerate:  ./sonder codegen",
]


def write(path: Path, text: str) -> bool:
    data = text.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == data:
        return False
    path.write_bytes(data)
    return True


def pascal(doc: dict) -> str:
    out = ["{ " + BANNER[0]]
    for line in BANNER[1:]:
        out.append("  " + line if line else "")
    out.append("}")
    out.append("")
    out.append("{ Domain limits. Enforced by the core -- here they are not a")
    out.append("  hint but a rule: exceeding one is a refusal carrying a code")
    out.append("  from errors.yaml. }")
    out.append("")
    out.append("const")

    limits = doc["limits"]
    width = max(len(k) for k in limits) + 4
    for name, spec in limits.items():
        pas = "LIM_" + name.upper()
        desc = " ".join(str(spec.get("description", "")).split())
        out.append(f"  {{ {desc} }}")
        out.append(f"  {pas:<{width + 4}} = {spec['value']};")
    out.append("")
    return "\n".join(out) + "\n"


def typescript(doc: dict) -> str:
    out = ["/*"]
    for line in BANNER:
        out.append(" * " + line if line else " *")
    out.append(" */")
    out.append("")
    out.append("// A hint for the interface, not a rule: the decision is the")
    out.append("// core's either way. Checking here is a courtesy to the user.")
    out.append("export const LIMITS = {")
    for name, spec in doc["limits"].items():
        camel = "".join(
            w if i == 0 else w.capitalize()
            for i, w in enumerate(name.split("_"))
        )
        out.append(f"  {camel}: {spec['value']},")
    out.append("} as const")
    out.append("")
    out.append("export type LimitName = keyof typeof LIMITS")
    out.append("")
    return "\n".join(out)


def main() -> int:
    if not SRC.exists():
        print(f"missing {SRC}", file=sys.stderr)
        return 1

    doc = yaml.safe_load(SRC.read_text(encoding="utf-8"))

    targets = [
        (ROOT / "dosnode/src/generated/dmlimits.inc", pascal(doc)),
        (ROOT / "web/src/generated/limits.ts", typescript(doc)),
    ]

    changed = 0
    for path, text in targets:
        mark = "changed" if write(path, text) else "unchanged"
        if mark == "changed":
            changed += 1
        print(f"  {path.relative_to(ROOT)}  {mark}")

    print(f"limits: {len(doc['limits'])}, files changed: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
