#!/usr/bin/env python3
"""
Генератор Pascal-стабов из WSDL для таргета i8086-msdos.

Существующие генераторы порождают код для языков с динамическими массивами,
исключениями и сборщиком мусора. Здесь ничего этого нет, поэтому генератор
свой.

Что учитывается из среды:

  * Тип string в диалекте TP7 не длиннее 255 байт, а тело поста длиннее.
    Все строки представлены записью TStr — указатель в арену и длина.

  * Динамических массивов нет. Повторяющиеся элементы становятся односвязным
    списком с узлами из арены: это же позволяет собирать их потоково, не зная
    заранее количества.

  * Часть имён XSD совпадает с ключевыми словами Pascal. Такие поля получают
    подчёркивание в конце, и это отмечается комментарием, чтобы связь с
    контрактом не терялась.

Кроме кода генерируется contracts/generated/operations.json — машиночитаемый
перечень того, какое состояние объявила каждая операция. По нему тест на
стороне оболочки проверяет, что она заполняет всё объявленное; без этого
ядро однажды примет решение по неполным данным (docs/RISKS.md, R5).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
WSDL_PATH = ROOT / "contracts" / "soap" / "decider-v1.wsdl"
PAS_OUT = ROOT / "dosnode" / "src" / "generated" / "dcdtypes.pas"
JSON_OUT = ROOT / "contracts" / "generated" / "operations.json"

XS = "http://www.w3.org/2001/XMLSchema"
WSDL = "http://schemas.xmlsoap.org/wsdl/"

# Ключевые слова Pascal, которые нельзя использовать как имена полей.
RESERVED = {
    "and", "array", "begin", "case", "const", "div", "do", "downto", "else",
    "end", "file", "for", "function", "goto", "if", "in", "label", "mod",
    "nil", "not", "object", "of", "or", "packed", "procedure", "program",
    "record", "repeat", "set", "shl", "shr", "string", "then", "to", "type",
    "unit", "until", "uses", "var", "while", "with", "xor", "interface",
    "implementation", "inline", "unation",
}

# Скалярные типы XSD → Pascal.
SCALARS = {
    "string": "TStr",
    "boolean": "Boolean",
    "int": "LongInt",
    "long": "Int64",
    "short": "Integer",
}


class Model:
    def __init__(self) -> None:
        self.enums: dict[str, list[str]] = {}
        self.aliases: dict[str, str] = {}     # simpleType без enumeration
        self.records: list[tuple[str, list[dict]]] = []
        self.operations: list[dict] = []


def local(name: str) -> str:
    return name.split(":")[-1] if name else ""


def pas_type_name(xsd_name: str) -> str:
    return "T" + xsd_name[0].upper() + xsd_name[1:]


def field_name(name: str) -> tuple[str, bool]:
    """Возвращает имя поля и признак того, что оно было переименовано."""
    if name.lower() in RESERVED:
        return name + "_", True
    return name, False


def parse_schema(schema: ET.Element, model: Model) -> None:
    # Перечисления и псевдонимы.
    for st in schema.findall(f"{{{XS}}}simpleType"):
        name = st.get("name")
        if not name:
            continue
        restriction = st.find(f"{{{XS}}}restriction")
        if restriction is None:
            continue
        values = [e.get("value") for e in restriction.findall(f"{{{XS}}}enumeration")]
        if values:
            model.enums[name] = [v for v in values if v]
        else:
            base = local(restriction.get("base", "string"))
            model.aliases[name] = SCALARS.get(base, "TStr")


def resolve(type_ref: str, model: Model) -> str:
    t = local(type_ref)
    if t in model.enums:
        return pas_type_name(t)
    if t in model.aliases:
        return model.aliases[t]
    if t in SCALARS:
        return SCALARS[t]
    return pas_type_name(t)


def collect_fields(container: ET.Element, model: Model) -> list[dict]:
    seq = container.find(f"{{{XS}}}sequence")
    if seq is None:
        return []
    fields: list[dict] = []
    for el in seq.findall(f"{{{XS}}}element"):
        raw = el.get("name") or ""
        name, renamed = field_name(raw)
        repeated = el.get("maxOccurs") == "unbounded"
        optional = el.get("minOccurs") == "0"
        fields.append({
            "xsd_name": raw,
            "name": name,
            "renamed": renamed,
            "type": resolve(el.get("type", "xs:string"), model),
            "repeated": repeated,
            "optional": optional,
        })
    return fields


def build(model: Model) -> None:
    tree = ET.parse(WSDL_PATH)
    root = tree.getroot()
    schema = root.find(f"{{{WSDL}}}types/{{{XS}}}schema")
    if schema is None:
        raise SystemExit("в WSDL нет встроенной схемы")

    parse_schema(schema, model)

    for ct in schema.findall(f"{{{XS}}}complexType"):
        name = ct.get("name")
        if name:
            model.records.append((pas_type_name(name), collect_fields(ct, model)))

    # Элементы верхнего уровня со встроенным типом — это запросы и ответы.
    for el in schema.findall(f"{{{XS}}}element"):
        name = el.get("name")
        inline = el.find(f"{{{XS}}}complexType")
        if name and inline is not None:
            model.records.append((pas_type_name(name), collect_fields(inline, model)))

    # Операции и объявленное ими состояние.
    for op in root.findall(f".//{{{WSDL}}}portType/{{{WSDL}}}operation"):
        op_name = op.get("name")
        if not op_name:
            continue
        request = f"{op_name}Request"
        parts = dict(model.records).get(pas_type_name(request), [])
        context_parts = [
            {"element": f["xsd_name"], "type": f["type"]}
            for f in parts
            if f["type"].endswith("Context")
        ]
        model.operations.append({
            "operation": op_name,
            "soapAction": f"urn:sonder:decider:v1:{op_name}",
            "request": request,
            "requiredContext": context_parts,
        })


BANNER = """{ СГЕНЕРИРОВАНО. Не править руками.

  Источник:      contracts/soap/decider-v1.wsdl
  Генератор:     tools/wsdl2pas/wsdl2pas.py
  Перегенерация: ./sonder codegen

  Правка этого файла будет затёрта, а расхождение с контрактом поймано
  проверкой дрейфа в CI.
}"""


def emit_pascal(model: Model) -> str:
    o: list[str] = [BANNER, "", "unit DcdTypes;", "", "{$MODE TP}", "",
                    "interface", "", "uses", "  TcStr;", "",
                    "{ TStr берётся из TcStr, а не объявляется здесь.",
                    "  Вокабуляр принадлежит фреймворку: если бы генератор",
                    "  объявлял свой тип строки, каждый сгенерированный модуль",
                    "  имел бы несовместимый с остальными. }", "", "type"]

    for name, values in model.enums.items():
        pas = pas_type_name(name)
        members = ", ".join(f"{name}_{v}" for v in values)
        o.append(f"  {pas} = ({members});")
    o.append("")

    # Предварительные объявления указателей на списки.
    listed: set[str] = set()
    for _, fields in model.records:
        for f in fields:
            if f["repeated"]:
                listed.add(f["type"])
    for t in sorted(listed):
        base = t[1:] if t.startswith("T") else t
        o.append(f"  P{base}Node = ^T{base}Node;")
    if listed:
        o.append("")

    ordered = topo_sort(model)
    for name in ordered:
        fields = dict(model.records)[name]
        o.append(f"  {name} = record")
        for f in fields:
            comment = ""
            if f["renamed"]:
                comment = f"   {{ в контракте: {f['xsd_name']} }}"
            elif f["optional"]:
                comment = "   { необязательное }"
            if f["repeated"]:
                base = f["type"][1:] if f["type"].startswith("T") else f["type"]
                o.append(f"    {f['name']}: P{base}Node;"
                         f"   {{ список, узлы из арены }}")
            else:
                o.append(f"    {f['name']}: {f['type']};{comment}")
        if not fields:
            o.append("    Reserved: Byte;   { тип без полей }")
        o.append("  end;")
        o.append("")

    for t in sorted(listed):
        base = t[1:] if t.startswith("T") else t
        o.append(f"  T{base}Node = record")
        o.append(f"    Value: {t};")
        o.append(f"    Next: P{base}Node;")
        o.append("  end;")
        o.append("")

    o.append("const")
    o.append("  { Имена значений перечислений: по ним идёт разбор и сборка XML. }")
    for name, values in model.enums.items():
        pas = pas_type_name(name)
        items = ", ".join(f"'{v}'" for v in values)
        o.append(f"  {name}Names: array[{pas}] of PChar = ({items});")
    o.append("")

    o.append("  { Операции контракта. }")
    o.append(f"  OperationCount = {len(model.operations)};")
    for op in model.operations:
        o.append(f"  Op_{op['operation']} = '{op['operation']}';")
    o.append("")

    o.append("implementation")
    o.append("")
    o.append("end.")
    return "\n".join(o) + "\n"


def topo_sort(model: Model) -> list[str]:
    """Записи объявляются после тех, на которые ссылаются."""
    by_name = dict(model.records)
    order: list[str] = []
    seen: set[str] = set()

    def visit(name: str, stack: tuple[str, ...] = ()) -> None:
        if name in seen or name not in by_name:
            return
        if name in stack:
            return  # цикл: разрывается указателем на узел списка
        for f in by_name[name]:
            if not f["repeated"]:
                visit(f["type"], stack + (name,))
        seen.add(name)
        order.append(name)

    for name, _ in model.records:
        visit(name)
    return order


def write(path: Path, text: str) -> bool:
    data = text.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == data:
        return False
    path.write_bytes(data)
    return True


def main() -> int:
    if not WSDL_PATH.exists():
        print(f"нет {WSDL_PATH}", file=sys.stderr)
        return 1

    model = Model()
    build(model)

    changed = 0
    if write(PAS_OUT, emit_pascal(model)):
        changed += 1
        print(f"  {PAS_OUT.relative_to(ROOT)}  изменён")
    else:
        print(f"  {PAS_OUT.relative_to(ROOT)}  без изменений")

    manifest = {
        "_comment": (
            "СГЕНЕРИРОВАНО из contracts/soap/decider-v1.wsdl. "
            "Перечень состояния, объявленного каждой операцией. Тест на "
            "стороне оболочки обязан проверять, что она заполняет всё "
            "перечисленное: иначе ядро решит по неполным данным (R5)."
        ),
        "source": "contracts/soap/decider-v1.wsdl",
        "operations": model.operations,
    }
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if write(JSON_OUT, text):
        changed += 1
        print(f"  {JSON_OUT.relative_to(ROOT)}  изменён")
    else:
        print(f"  {JSON_OUT.relative_to(ROOT)}  без изменений")

    print(f"записей: {len(model.records)}, перечислений: {len(model.enums)}, "
          f"операций: {len(model.operations)}, файлов изменено: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
