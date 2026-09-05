#!/usr/bin/env python3
"""
Generate the refusal codes from contracts/errors/errors.yaml into three
languages.

One list of codes exists in four places -- the YAML and three languages --
and they have no right to diverge: a divergence means the shell hands the
client a code the core has never heard of, or the reverse. So the three
files are generated rather than written, and editing a generated one is
caught by the drift check in CI.

What each target needs:

  Pascal -- an include file of constants. No enum, no classes: codes
            travel the line as strings and are compared byte by byte. A
            category lookup is generated alongside them.

  Java   -- an enum carrying the category and the HTTP status. An enum
            precisely, not strings: a typo must break the build.

  TS     -- a union of string literals plus a table of categories. A
            union rather than an enum: codes arrive from the server as
            strings, and a union checks them exactly at the boundary.
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
SRC = ROOT / "contracts" / "errors" / "errors.yaml"

BANNER_LINES = [
    "GENERATED. Do not edit by hand.",
    "",
    "Source:      contracts/errors/errors.yaml",
    "Generator:   tools/gen-errors/gen_errors.py",
    "Regenerate:  ./sonder codegen",
    "",
    "An edit to this file will be overwritten, and the divergence from",
    "the source caught by the drift check in CI.",
]


def write(path: Path, text: str) -> bool:
    """Write bytes with LF. True when the contents changed."""
    data = text.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == data:
        return False
    path.write_bytes(data)
    return True


def pascal(doc: dict) -> str:
    out: list[str] = []
    out.append("{ " + BANNER_LINES[0])
    for line in BANNER_LINES[1:]:
        out.append("  " + line if line else "")
    out.append("}")
    out.append("")
    out.append("{ Refusal codes travel the line as strings and are compared")
    out.append("  byte by byte, so these are constants, not an enumeration. }")
    out.append("")
    out.append("const")

    width = max(len(c["code"]) for c in doc["codes"]) + 4
    current_cat = None
    for entry in doc["codes"]:
        cat = entry["category"]
        if cat != current_cat:
            out.append("")
            out.append(f"  {{ --- {cat} --- }}")
            current_cat = cat
        name = "ERR_" + entry["code"]
        out.append(f"  {name:<{width + 4}} = '{entry['code']}';")

    out.append("")
    out.append("  { How many codes there are: useful for checking a table is complete. }")
    out.append(f"  ERR_CODE_COUNT = {len(doc['codes'])};")
    out.append("")
    out.append("  { Length of the longest code. A test compares it against")
    out.append("    MaxErrCodeLen in TcResult: adding a long code must not be")
    out.append("    silently truncated on assignment to TErrCode. }")
    out.append(f"  ERR_MAX_CODE_LEN = {max(len(c['code']) for c in doc['codes'])};")

    # Codes returned by the DECISION FUNCTION. The golden set must hold a
    # case for each; completeness is checked mechanically, or a code exists
    # only on paper. The core's infrastructure codes (decided_by:
    # core-runtime) stay out: they come from the runtime, not a decision.
    decision = [c["code"] for c in doc["codes"] if c.get("decided_by") == "core"]
    out.append("")
    out.append("  { Codes returned by the decision function. The golden set")
    out.append("    must hold a case for each of them: a code nothing")
    out.append("    produces exists only on paper. }")
    out.append(f"  ERR_DECISION_CODE_COUNT = {len(decision)};")
    out.append(f"  ErrDecisionCodes: array[1..{len(decision)}] of PChar = (")
    for i, code in enumerate(decision):
        sep = "," if i < len(decision) - 1 else ""
        out.append(f"    '{code}'{sep}")
    out.append("  );")
    out.append("")
    return "\n".join(out) + "\n"


def java(doc: dict) -> str:
    out: list[str] = []
    out.append("/*")
    for line in BANNER_LINES:
        out.append(" * " + line if line else " *")
    out.append(" */")
    out.append("package sonder.contract;")
    out.append("")
    out.append("/**")
    out.append(" * Refusal codes. An enum, not strings: a typo must break the build.")
    out.append(" *")
    out.append(" * <p>{@code decidedByCore} says who takes the decision. A code with")
    out.append(" * {@code true} is not the shell's to return -- it is the core's")
    out.append(" * answer, and restating the rule in Java would mean two places")
    out.append(" * holding one piece of logic.")
    out.append(" */")
    out.append("public enum ErrorCode {")
    out.append("")

    cats = doc["categories"]
    last = len(doc["codes"]) - 1
    for i, entry in enumerate(doc["codes"]):
        cat = entry["category"]
        http = cats[cat]["http"]
        # Retryability: a code's own field overrides its category's default.
        retryable = str(entry.get("retryable", cats[cat]["retryable"])).lower()
        core = str(entry["decided_by"].startswith("core")).lower()
        desc = " ".join(str(entry["description"]).split())
        sep = "," if i < last else ";"
        out.append(f"    /** {desc} */")
        out.append(
            f"    {entry['code']}(Category.{cat}, {http}, {retryable}, {core}){sep}"
        )
    out.append("")
    out.append("    public enum Category {")
    cat_names = list(cats)
    for i, c in enumerate(cat_names):
        sep = "," if i < len(cat_names) - 1 else ";"
        out.append(f"        /** {cats[c]['description']} */")
        out.append(f"        {c}{sep}")
    out.append("    }")
    out.append("")
    out.append("    private final Category category;")
    out.append("    private final int httpStatus;")
    out.append("    private final boolean retryable;")
    out.append("    private final boolean decidedByCore;")
    out.append("")
    out.append("    ErrorCode(Category category, int httpStatus,")
    out.append("              boolean retryable, boolean decidedByCore) {")
    out.append("        this.category = category;")
    out.append("        this.httpStatus = httpStatus;")
    out.append("        this.retryable = retryable;")
    out.append("        this.decidedByCore = decidedByCore;")
    out.append("    }")
    out.append("")
    out.append("    public Category category()    { return category; }")
    out.append("    public int httpStatus()       { return httpStatus; }")
    out.append("    public boolean retryable()    { return retryable; }")
    out.append("    public boolean decidedByCore() { return decidedByCore; }")
    out.append("}")
    return "\n".join(out) + "\n"


def typescript(doc: dict) -> str:
    out: list[str] = []
    out.append("/*")
    for line in BANNER_LINES:
        out.append(" * " + line if line else " *")
    out.append(" */")
    out.append("")
    out.append("// A union of string literals rather than an enum: codes arrive")
    out.append("// from the server as strings, and a union checks them at the edge.")
    out.append("export type ErrorCode =")
    for i, entry in enumerate(doc["codes"]):
        prefix = "  | " if i else "  | "
        out.append(f"{prefix}'{entry['code']}'")
    out.append("")
    out.append("export type ErrorCategory =")
    for c in doc["categories"]:
        out.append(f"  | '{c}'")
    out.append("")
    out.append("export interface ErrorMeta {")
    out.append("  readonly category: ErrorCategory")
    out.append("  readonly httpStatus: number")
    out.append("  readonly retryable: boolean")
    out.append("}")
    out.append("")
    out.append("export const ERROR_META: Readonly<Record<ErrorCode, ErrorMeta>> = {")
    cats = doc["categories"]
    for entry in doc["codes"]:
        cat = entry["category"]
        out.append(
            f"  {entry['code']}: {{ category: '{cat}', "
            f"httpStatus: {cats[cat]['http']}, "
            f"retryable: {str(cats[cat]['retryable']).lower()} }},"
        )
    out.append("}")
    out.append("")
    out.append("export const ALL_ERROR_CODES = Object.keys(ERROR_META) as ErrorCode[]")
    out.append("")
    return "\n".join(out)


def main() -> int:
    if not SRC.exists():
        print(f"missing {SRC}", file=sys.stderr)
        return 1

    doc = yaml.safe_load(SRC.read_text(encoding="utf-8"))

    targets = [
        (ROOT / "dosnode/src/generated/errcodes.inc", pascal(doc)),
        (ROOT / "core/src/main/java/sonder/contract/ErrorCode.java", java(doc)),
        (ROOT / "web/src/generated/errors.ts", typescript(doc)),
    ]

    changed = 0
    for path, text in targets:
        if write(path, text):
            changed += 1
            mark = "changed"
        else:
            mark = "unchanged"
        print(f"  {path.relative_to(ROOT)}  {mark}")

    print(f"codes: {len(doc['codes'])}, files changed: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
