#!/usr/bin/env python3
"""
Генерация числовых границ домена из contracts/domain/limits.yaml.

Границы решает ядро, поэтому они живут в контракте домена, а не в OpenAPI.
В веб-контракте те же числа присутствуют как подсказка для интерфейса, и
расхождение между ними — это ситуация, где клиент подсказывает пользователю
одно, а ядро отказывает по другому. Валидатор контрактов сверяет их
отдельной проверкой.

Цели:
  Pascal — dosnode/src/generated/dmlimits.inc, где границы применяются;
  TS     — web/src/generated/limits.ts, где они подсказывают интерфейсу.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("нужен pyyaml", file=sys.stderr)
    sys.exit(64)

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "contracts" / "domain" / "limits.yaml"

BANNER = [
    "СГЕНЕРИРОВАНО. Не править руками.",
    "",
    "Источник: contracts/domain/limits.yaml",
    "Генератор: tools/gen-limits/gen_limits.py",
    "Перегенерация: ./sonder codegen",
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
    out.append("{ Границы домена. Применяются ядром — здесь они не подсказка,")
    out.append("  а правило: превышение даёт отказ с кодом из errors.yaml. }")
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
    out.append("// Подсказка для интерфейса, а не правило: решение всё равно")
    out.append("// принимает ядро. Проверка на клиенте — удобство пользователя.")
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
        print(f"нет {SRC}", file=sys.stderr)
        return 1

    doc = yaml.safe_load(SRC.read_text(encoding="utf-8"))

    targets = [
        (ROOT / "dosnode/src/generated/dmlimits.inc", pascal(doc)),
        (ROOT / "web/src/generated/limits.ts", typescript(doc)),
    ]

    changed = 0
    for path, text in targets:
        mark = "изменён" if write(path, text) else "без изменений"
        if mark == "изменён":
            changed += 1
        print(f"  {path.relative_to(ROOT)}  {mark}")

    print(f"границ: {len(doc['limits'])}, файлов изменено: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
