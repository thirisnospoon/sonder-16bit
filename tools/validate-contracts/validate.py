#!/usr/bin/env python3
"""
Проверка контрактов. Запускается в CI и ломает сборку.

Контракты — источник правды для четырёх языков сразу, поэтому ошибка в них
дороже ошибки в коде: она размножается кодогенерацией. Проверяется то, что
нельзя выразить схемой:

  * коды отказа уникальны, категории существуют, решающая сторона указана;
  * каждая операция WSDL объявляет и команду, и состояние, и решение;
  * ни одно текстовое поле IDL не объявлено как string — CORBA не примет
    кириллицу и упадёт в рантайме (docs/PHASE0-FINDINGS.md, F-09);
  * коды, которые обязано возвращать ядро, не назначены оболочке и наоборот.

Выход: 0 — контракты согласованы, 1 — нет.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    import yaml
except ImportError:
    print("нужен pyyaml: pip install pyyaml", file=sys.stderr)
    sys.exit(64)

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"

XS = "http://www.w3.org/2001/XMLSchema"
WSDL = "http://schemas.xmlsoap.org/wsdl/"

problems: list[str] = []
checks = 0


def fail(msg: str) -> None:
    problems.append(msg)


def check(msg: str, condition: bool) -> None:
    global checks
    checks += 1
    if not condition:
        fail(msg)


# --------------------------------------------------------------------- errors
def validate_errors() -> dict:
    path = CONTRACTS / "errors" / "errors.yaml"
    if not path.exists():
        fail(f"нет {path.relative_to(ROOT)}")
        return {}

    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    categories = set(doc.get("categories", {}))
    check("errors.yaml: нет ни одной категории", bool(categories))

    seen: set[str] = set()
    for entry in doc.get("codes", []):
        code = entry.get("code", "<без кода>")
        check(f"errors.yaml: код {code} повторяется", code not in seen)
        seen.add(code)
        check(
            f"errors.yaml: код {code} ссылается на несуществующую категорию "
            f"{entry.get('category')!r}",
            entry.get("category") in categories,
        )
        check(
            f"errors.yaml: у кода {code} не указано, кто его решает",
            entry.get("decided_by") in ("core", "shell"),
        )
        check(
            f"errors.yaml: код {code} без описания",
            bool(str(entry.get("description", "")).strip()),
        )
        check(
            f"errors.yaml: код {code} должен быть в ВЕРХНЕМ_РЕГИСТРЕ",
            bool(re.fullmatch(r"[A-Z][A-Z0-9_]*", code)),
        )

    for cat, spec in doc.get("categories", {}).items():
        check(f"errors.yaml: у категории {cat} нет http-статуса", "http" in spec)
        check(f"errors.yaml: у категории {cat} не указана повторяемость",
              isinstance(spec.get("retryable"), bool))

    # Коды INTERNAL, которые обязано уметь возвращать ядро.
    required_core = {"INSUFFICIENT_CONTEXT"}
    core_codes = {e["code"] for e in doc.get("codes", []) if e.get("decided_by") == "core"}
    for rc in required_core:
        check(
            f"errors.yaml: код {rc} обязан решаться ядром — без него ядро "
            f"начнёт додумывать значения по умолчанию (R5)",
            rc in core_codes,
        )

    return doc


# ----------------------------------------------------------------------- wsdl
def validate_wsdl(error_doc: dict) -> None:
    paths = sorted((CONTRACTS / "soap").glob("*.wsdl"))
    check("нет ни одного WSDL в contracts/soap", bool(paths))

    for path in paths:
        rel = path.relative_to(ROOT)
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            fail(f"{rel}: не разбирается как XML: {exc}")
            continue

        root = tree.getroot()
        ops = root.findall(f".//{{{WSDL}}}portType/{{{WSDL}}}operation")
        check(f"{rel}: в portType нет ни одной операции", bool(ops))

        # Каждая операция обязана иметь и вход, и выход: решение возвращается
        # всегда, в том числе отказ.
        for op in ops:
            name = op.get("name")
            check(f"{rel}: у операции {name} нет input",
                  op.find(f"{{{WSDL}}}input") is not None)
            check(f"{rel}: у операции {name} нет output — решение возвращается "
                  f"всегда, включая отказ",
                  op.find(f"{{{WSDL}}}output") is not None)

        # Главное правило проекта: у команды обязано быть объявлено состояние.
        types = root.find(f"{{{WSDL}}}types")
        if types is None:
            fail(f"{rel}: нет секции types")
            continue

        elements = {
            e.get("name")
            for e in types.iter(f"{{{XS}}}element")
            if e.get("name")
        }
        for op in ops:
            name = op.get("name")
            if not name:
                continue
            req = f"{name}Request"
            check(
                f"{rel}: для операции {name} нет элемента {req}",
                req in elements,
            )

        # Ни одного необязательного поля в состоянии: пропуск должен ломаться
        # маршалингом, а не превращаться в тихое значение по умолчанию.
        for ct in types.iter(f"{{{XS}}}complexType"):
            ct_name = ct.get("name") or ""
            if not ct_name.endswith("Context"):
                continue
            for el in ct.iter(f"{{{XS}}}element"):
                if el.get("minOccurs") == "0":
                    fail(
                        f"{rel}: поле {el.get('name')} в {ct_name} объявлено "
                        f"необязательным. Состояние обязано приходить целиком, "
                        f"иначе ядро решит по неполным данным (R5)"
                    )
                checks_bump()


def checks_bump() -> None:
    global checks
    checks += 1


# ------------------------------------------------------------------------ idl
def validate_idl() -> None:
    paths = sorted((CONTRACTS / "idl").glob("*.idl"))
    check("нет ни одного IDL в contracts/idl", bool(paths))

    # Поле, чьё имя намекает на человеческий текст, не должно быть string:
    # тип string в CORBA байтовый и кириллицу не принимает (F-09).
    textual = re.compile(
        r"\b(name|nick|title|body|text|comment|reason|message|description|label)\b",
        re.IGNORECASE,
    )

    for path in paths:
        rel = path.relative_to(ROOT)
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            code = line.split("//", 1)[0].strip()
            if not code:
                continue
            m = re.match(r"^\s*(?:in\s+|out\s+|inout\s+)?string\s+(\w+)", code)
            if m and textual.search(m.group(1)):
                fail(
                    f"{rel}:{lineno}: поле {m.group(1)} объявлено как string. "
                    f"Тип string в CORBA байтовый и кириллицу не принимает — "
                    f"падает в рантайме на первом реальном пользователе. "
                    f"Нужен wstring (F-09)"
                )
            checks_bump()


# ----------------------------------------------------------------------- main
def main() -> int:
    if not CONTRACTS.exists():
        print(f"нет каталога {CONTRACTS}", file=sys.stderr)
        return 1

    errors_doc = validate_errors()
    validate_wsdl(errors_doc)
    validate_idl()

    print(f"проверок выполнено: {checks}")
    if problems:
        print(f"\nнайдено проблем: {len(problems)}\n")
        for p in problems:
            print(f"  ✗ {p}")
        return 1

    print("контракты согласованы")
    return 0


if __name__ == "__main__":
    sys.exit(main())
