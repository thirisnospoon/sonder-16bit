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
SRV_OUT = ROOT / "dosnode" / "src" / "generated" / "dcdsrv.pas"
JSON_OUT = ROOT / "contracts" / "generated" / "operations.json"

XS = "http://www.w3.org/2001/XMLSchema"
WSDL = "http://schemas.xmlsoap.org/wsdl/"

# Пространство имён контракта. Читается из самого WSDL при разборе:
# вписанное сюда строкой оно стало бы вторым экземпляром того, что уже
# объявлено, и разошлось бы при первой же смене версии.
TARGET_NS = ""

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
    global TARGET_NS
    tree = ET.parse(WSDL_PATH)
    root = tree.getroot()
    TARGET_NS = root.get("targetNamespace") or ""
    if not TARGET_NS:
        raise SystemExit("в WSDL нет targetNamespace: ответ ноды остался бы "
                         "без пространства имён, и разобрать его было бы "
                         "нечем")
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


SRV_HEADER = """{ СГЕНЕРИРОВАНО. Не править руками.

  Источник:      contracts/soap/decider-v1.wsdl
  Генератор:     tools/wsdl2pas/wsdl2pas.py
  Перегенерация: ./sonder codegen

  Серверная сторона: разбор полей команды и запись решения. Java —
  клиент SOAP, NODE-7 — сервер (ADR-0011), поэтому здесь именно разбор
  запроса и запись ответа, а не наоборот.

  Отдельный модуль, а не добавка к DcdTypes: типы нужны всем, кто трогает
  контракт, а разбор конверта — только ноде. Тянуть TcSoap туда, где
  нужен один TStr, незачем.

  Разбор идёт по паре «группа, поле», а не по дереву: каждая операция в
  контракте объявляет группы из скалярных полей, глубже не заходит ни
  одна (см. TcSoap). Форма этих процедур повторяет ту, что была написана
  руками в tstsoap до появления генератора.

  С НЕРАЗОБРАННЫМ ЗНАЧЕНИЕМ НЕ ДЕЛАЕТСЯ НИЧЕГО МОЛЧА. Поле, которого нет
  в контракте, и поле, значение которого не разбирается, различаются и
  возвращаются вызывающему. Оставить в записи ноль и продолжить значило
  бы решить по неполным данным — тот самый R5.
}"""


def enum_parsers(model: Model) -> list[str]:
    """Разбор перечислений по литералам, а не поиском в таблице имён.

    Таблица RoleNames хранит PChar, и сравнение с TStr потребовало бы ещё
    одного примитива в TcStr. Литералы генерируются, читаются и
    проверяются одинаково хорошо, а лишней сущности не заводят.
    """
    o: list[str] = []
    for name, values in model.enums.items():
        pas = pas_type_name(name)
        o.append(f"function Parse{name}(const S: TStr; var V: {pas}): Boolean;")
        o.append("begin")
        o.append(f"  Parse{name} := True;")
        for v in values:
            o.append(f"  if StrEqPas(S, '{v}') then")
            o.append(f"  begin V := {name}_{v}; Exit; end;")
        o.append(f"  Parse{name} := False;")
        o.append("end;")
        o.append("")
    return o


def assign_scalar(target: str, ftype: str, model: Model, ind: str) -> list[str]:
    """Присваивание одного скалярного поля из TStr."""
    if ftype == "TStr":
        return [f"{ind}{target} := Value;"]
    if ftype == "Boolean":
        return [
            f"{ind}if StrEqPas(Value, 'true') then {target} := True",
            f"{ind}else if StrEqPas(Value, 'false') then {target} := False",
            f"{ind}else Res := foBadValue;",
        ]
    if ftype in ("LongInt", "Integer"):
        lo, hi = ("-2147483647", "2147483647") if ftype == "LongInt" \
                 else ("-32767", "32767")
        return [
            f"{ind}if StrToInt64(Value, Tmp) and (Tmp >= {lo})",
            f"{ind}   and (Tmp <= {hi}) then",
            f"{ind}  {target} := {ftype}(Tmp)",
            f"{ind}else",
            f"{ind}  Res := foBadValue;",
        ]
    if ftype == "Int64":
        return [
            f"{ind}if StrToInt64(Value, Tmp) then",
            f"{ind}  {target} := Tmp",
            f"{ind}else",
            f"{ind}  Res := foBadValue;",
        ]
    enum_name = ftype[1:] if ftype.startswith("T") else ftype
    if enum_name in model.enums:
        return [
            f"{ind}if not Parse{enum_name}(Value, {target}) then",
            f"{ind}  Res := foBadValue;",
        ]
    return [f"{ind}Res := foBadValue;   {{ тип {ftype} генератор не умеет }}"]


def decision_writer(model: Model) -> list[str]:
    by_name = dict(model.records)
    dec = by_name.get("TDecision", [])
    ev = by_name.get("TDomainEvent", [])

    def one(indent: str, name: str, acc: str, ftype: str) -> list[str]:
        if ftype == "Boolean":
            return [f"{indent}SoapElementBool(W, '{name}', {acc});"]
        if ftype == "TStr":
            return [f"{indent}SoapElement(W, '{name}', {acc});"]
        if ftype in ("LongInt", "Integer", "Int64"):
            return [f"{indent}SoapElementInt(W, '{name}', {acc});"]
        return []

    # Вложенный повторяемый список внутри события — поля полезной
    # нагрузки. Без них событие уезжает пустым, и вся цепочка после
    # ядра остаётся без данных: проекция ленты не найдёт автора поста.
    # Раньше генератор их не писал вовсе, и заметил это только сквозной
    # прогон — ни один тест не пропускал вывод настоящего писателя
    # через настоящий разборщик.
    nested = [g for g in ev if g["repeated"]]

    o: list[str] = []
    o.append("procedure WriteDecision(var W: TSoapWriter;")
    o.append("                        const ResponseName: string;")
    o.append("                        const D: TDecision);")
    o.append("var")
    o.append("  Node: PDomainEventNode;")
    if nested:
        o.append("  Leaf: PEventFieldNode;")
    o.append("begin")
    # Корень тела ответа обязан нести пространство имён контракта.
    # Без него связыватель на другой стороне не находит ни одного поля и
    # отдаёт пустое решение — «не принято» без кода отказа. Найдено
    # сквозным прогоном, не чтением.
    o.append(f"  SoapOpenNs(W, ResponseName, '{TARGET_NS}');")
    for f in dec:
        if not f["repeated"]:
            o.extend(one("  ", f["xsd_name"], f"D.{f['name']}", f["type"]))
    for f in dec:
        if not f["repeated"]:
            continue
        o.append(f"  Node := D.{f['name']};")
        o.append("  while Node <> nil do")
        o.append("  begin")
        o.append(f"    SoapOpen(W, '{f['xsd_name']}');")
        for g in ev:
            if g["repeated"]:
                continue
            o.extend(one("    ", g["xsd_name"],
                         f"Node^.Value.{g['name']}", g["type"]))
        for g in nested:
            # Тип уже приходит именем записи: P...Node — форма только
            # для объявления списка, а не для поиска в модели.
            leaf = by_name.get(g["type"], [])
            if not leaf:
                raise SystemExit("не найдена запись " + g["type"]
                                 + ": событие уехало бы без полезной нагрузки")
            o.append(f"    Leaf := Node^.Value.{g['name']};")
            o.append("    while Leaf <> nil do")
            o.append("    begin")
            o.append(f"      SoapOpen(W, '{g['xsd_name']}');")
            for h in leaf:
                if h["repeated"]:
                    continue
                o.extend(one("      ", h["xsd_name"],
                             f"Leaf^.Value.{h['name']}", h["type"]))
            o.append(f"      SoapClose(W, '{g['xsd_name']}');")
            o.append("      Leaf := Leaf^.Next;")
            o.append("    end;")
        o.append(f"    SoapClose(W, '{f['xsd_name']}');")
        o.append("    Node := Node^.Next;")
        o.append("  end;")
    o.append("  SoapClose(W, ResponseName);")
    o.append("end;")
    o.append("")
    return o


def emit_server(model: Model) -> str:
    by_name = dict(model.records)
    requests = [n for n, _ in model.records if n.endswith("Request")]

    o: list[str] = [
        SRV_HEADER, "", "unit DcdSrv;", "", "{$MODE TP}", "{$R-}", "",
        "interface", "", "uses", "  TcStr, TcSoap, DcdTypes;", "",
        "const",
        "  { Пространство имён контракта. Отсюда, а не строкой в каждом",
        "    месте: корень тела ответа обязан его объявлять, иначе",
        "    связыватель на другой стороне не найдёт ни одного поля. }",
        f"  DeciderNs = '{TARGET_NS}';", "",
        "type",
        "  { Что стало с полем, пришедшим в конверте. }",
        "  TFillOutcome = (",
        "    foOk,        { поле известно и разобрано }",
        "    foUnknown,   { такого поля нет в контракте }",
        "    foBadValue   { поле есть, а значение не разбирается }",
        "  );", "",
    ]

    for req in requests:
        op = req[1:-7]
        o.append(f"function Fill{op}(var Req: {req};")
        o.append(f"                 const Group, Field, Value: TStr): TFillOutcome;")
    o.append("")
    o.append("{ Запись решения. Одна на все операции: решение по контракту")
    o.append("  всегда TDecision, различается только имя элемента ответа. }")
    o.append("procedure WriteDecision(var W: TSoapWriter;")
    o.append("                        const ResponseName: string;")
    o.append("                        const D: TDecision);")
    o.append("")
    o.append("implementation")
    o.append("")

    o.extend(enum_parsers(model))

    for req in requests:
        op = req[1:-7]
        fields = by_name.get(req, [])
        o.append(f"function Fill{op}(var Req: {req};")
        o.append(f"                 const Group, Field, Value: TStr): TFillOutcome;")
        o.append("var")
        o.append("  Res: TFillOutcome;")
        o.append("  Tmp: Int64;")
        o.append("begin")
        o.append("  Res := foOk;")

        first = True
        for f in fields:
            ftype = f["type"]
            group = by_name.get(ftype)
            kw = "if" if first else "else if"
            if group is not None and not f["repeated"]:
                o.append(f"  {kw} StrEqPas(Group, '{f['xsd_name']}') then")
                o.append("  begin")
                inner_first = True
                for g in group:
                    if g["repeated"]:
                        continue
                    ikw = "if" if inner_first else "else if"
                    o.append(f"    {ikw} StrEqPas(Field, '{g['xsd_name']}') then")
                    o.append("    begin")
                    o.extend(assign_scalar(f"Req.{f['name']}.{g['name']}",
                                           g["type"], model, "      "))
                    o.append("    end")
                    inner_first = False
                if inner_first:
                    o.append("    Res := foUnknown;")
                else:
                    o.append("    else")
                    o.append("      Res := foUnknown;")
                o.append("  end")
            else:
                o.append(f"  {kw} (Group.Len = 0) and "
                         f"StrEqPas(Field, '{f['xsd_name']}') then")
                o.append("  begin")
                o.extend(assign_scalar(f"Req.{f['name']}", ftype, model, "    "))
                o.append("  end")
            first = False

        if first:
            o.append("  Res := foUnknown;")
        else:
            o.append("  else")
            o.append("    Res := foUnknown;")
        o.append(f"  Fill{op} := Res;")
        o.append("end;")
        o.append("")

    o.extend(decision_writer(model))
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

    if write(SRV_OUT, emit_server(model)):
        changed += 1
        print(f"  {SRV_OUT.relative_to(ROOT)}  изменён")
    else:
        print(f"  {SRV_OUT.relative_to(ROOT)}  без изменений")

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
