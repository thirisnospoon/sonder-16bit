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
  * коды, которые обязано возвращать ядро, не назначены оболочке и наоборот;
  * длины VARCHAR и списки CHECK в миграциях совпадают с границами и
    перечислениями контракта — иначе ядро примет то, чего база не сохранит.

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
            entry.get("decided_by") in ("core", "core-runtime", "shell"),
        )
        check(
            f"errors.yaml: код {code} без описания",
            bool(str(entry.get("description", "")).strip()),
        )
        check(
            f"errors.yaml: код {code} должен быть в ВЕРХНЕМ_РЕГИСТРЕ",
            bool(re.fullmatch(r"[A-Z][A-Z0-9_]*", code)),
        )
        # Повторяемость кода необязательна, но если объявлена — булева.
        # Строка «true» здесь тихо стала бы истиной в одном языке и
        # ложью в другом.
        if "retryable" in entry:
            check(
                f"errors.yaml: retryable у кода {code} должен быть true или "
                f"false, а не {entry['retryable']!r}",
                isinstance(entry["retryable"], bool),
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


# -------------------------------------------------------------------- openapi
def validate_openapi() -> None:
    paths = sorted((CONTRACTS / "openapi").glob("*.yaml"))
    check("нет ни одного OpenAPI в contracts/openapi", bool(paths))

    for path in paths:
        rel = path.relative_to(ROOT)
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            fail(f"{rel}: не разбирается как YAML: {exc}")
            continue

        check(f"{rel}: нет секции paths", bool(doc.get("paths")))
        check(f"{rel}: нет версии в info", bool(doc.get("info", {}).get("version")))

        # Каждая операция обязана иметь operationId: из него генерируются
        # имена функций клиента, и без него генератор придумает своё.
        methods = ("get", "post", "put", "delete", "patch")
        seen_ids: set[str] = set()
        for route, item in (doc.get("paths") or {}).items():
            for method in methods:
                op = (item or {}).get(method)
                if not op:
                    continue
                op_id = op.get("operationId")
                check(f"{rel}: {method.upper()} {route} без operationId", bool(op_id))
                if op_id:
                    check(f"{rel}: operationId {op_id} повторяется",
                          op_id not in seen_ids)
                    seen_ids.add(op_id)
                check(f"{rel}: {method.upper()} {route} без responses",
                      bool(op.get("responses")))

        # Все ссылки должны разрешаться: битая ссылка ломает кодогенерацию,
        # но при беглом чтении незаметна.
        def walk(node, where: str):
            if isinstance(node, dict):
                ref = node.get("$ref")
                if isinstance(ref, str):
                    checks_bump()
                    if not ref.startswith("#/"):
                        fail(f"{rel}: внешняя ссылка {ref} в {where}")
                    else:
                        cur = doc
                        for part in ref[2:].split("/"):
                            if isinstance(cur, dict) and part in cur:
                                cur = cur[part]
                            else:
                                fail(f"{rel}: ссылка {ref} не разрешается ({where})")
                                break
                for k, v in node.items():
                    walk(v, f"{where}.{k}")
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    walk(v, f"{where}[{i}]")

        walk(doc, "root")


# ------------------------------------------------------------------- limits
def validate_limits() -> None:
    """Границы домена и подсказки OpenAPI обязаны совпадать.

    Расхождение — это ситуация, где интерфейс разрешает пользователю ввести
    то, что ядро потом отвергнет. Пользователь видит отказ уже после отправки,
    и виновата в этом рассинхронизация двух чисел, а не он.
    """
    path = CONTRACTS / "domain" / "limits.yaml"
    if not path.exists():
        fail(f"нет {path.relative_to(ROOT)}")
        return

    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    limits = doc.get("limits", {})
    check("limits.yaml: нет ни одной границы", bool(limits))

    for name, spec in limits.items():
        check(f"limits.yaml: у границы {name} нет значения", "value" in spec)
        check(f"limits.yaml: граница {name} не положительна",
              isinstance(spec.get("value"), int) and spec["value"] > 0)
        check(f"limits.yaml: граница {name} без описания",
              bool(str(spec.get("description", "")).strip()))

    # Сверка с OpenAPI по ссылкам вида Schema.field.maxLength.
    api_path = CONTRACTS / "openapi" / "social-v1.yaml"
    if not api_path.exists():
        return
    api = yaml.safe_load(api_path.read_text(encoding="utf-8"))
    schemas = (api.get("components") or {}).get("schemas") or {}

    for name, spec in limits.items():
        ref = spec.get("openapi")
        if not ref:
            continue
        checks_bump()
        parts = ref.split(".")
        if len(parts) != 3:
            fail(f"limits.yaml: ссылка {ref} у {name} не вида Схема.поле.атрибут")
            continue
        schema_name, field, attr = parts
        schema = schemas.get(schema_name)
        if not schema:
            fail(f"limits.yaml: в OpenAPI нет схемы {schema_name} (ссылка у {name})")
            continue
        props = schema.get("properties") or {}
        prop = props.get(field)
        if prop is None:
            fail(f"limits.yaml: в схеме {schema_name} нет поля {field} (ссылка у {name})")
            continue
        actual = prop.get(attr)
        if actual != spec["value"]:
            fail(
                f"граница {name} = {spec['value']}, а в OpenAPI "
                f"{schema_name}.{field}.{attr} = {actual}. Интерфейс разрешит "
                f"пользователю то, что ядро отвергнет"
            )


# ----------------------------------------------------------------------- main
def _strip_block_comments(text: str) -> str:
    """Убрать /* ... */, сохранив нумерацию строк.

    Нужно потому, что в блочном комментарии шапки миграции лежит ПРИМЕР
    аннотации. Первая редакция проверки его и нашла, пожаловавшись на
    перечисление с именем «Имя». Замена на пробелы, а не вырезание: номера
    строк в сообщениях должны оставаться настоящими.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in text[i:j]))
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def _tables(text: str) -> list[tuple[str, int, int, str]]:
    """Определения таблиц: имя, первая строка, последняя, текст.

    Область поиска CHECK — таблица, а не файл. Имена колонок в разных
    таблицах совпадают: `status` есть и у пользователя, и у поста, а
    перечисления у них разные. Поиск по всему файлу нашёл бы первое и
    сравнил не с тем.
    """
    lines = text.splitlines()
    result = []
    start = None
    name = ""
    for i, line in enumerate(lines):
        m = re.match(r"\s*CREATE\s+TABLE\s+(\w+)", line, re.I)
        if m:
            start = i
            name = m.group(1)
            continue
        if start is not None and re.match(r"\s*\)\s*;", line):
            result.append((name, start, i, "\n".join(lines[start:i + 1])))
            start = None
    return result


def validate_migrations() -> None:
    """Схема БД против контракта.

    Миграции — четвёртый потребитель тех же чисел, что limits.yaml отдаёт
    в Pascal, TypeScript и OpenAPI. Разойтись они могут ровно так же
    молча, а последствие хуже: ядро примет строку, которую база не
    сохранит, и отказ придёт после того, как пользователю сказали «готово».

    Сверяется по аннотациям в самом SQL:

        limit: имя_границы    длина VARCHAR на следующей строке
        enum: ИмяТипа         список в CHECK этой же таблицы

    Аннотация, а не угадывание по имени колонки: имя в базе и имя границы
    в контракте совпадать не обязаны, и додумывать связь между ними —
    верный способ получить проверку, которая молчит.
    """
    mig_dir = ROOT / "core" / "src" / "main" / "resources" / "db" / "migration"
    if not mig_dir.exists():
        return

    limits_path = CONTRACTS / "domain" / "limits.yaml"
    limits = {}
    if limits_path.exists():
        doc = yaml.safe_load(limits_path.read_text(encoding="utf-8")) or {}
        limits = {k: v.get("value") for k, v in (doc.get("limits") or {}).items()}

    enums: dict[str, set[str]] = {}
    for wsdl_path in sorted((CONTRACTS / "soap").glob("*.wsdl")):
        root = ET.parse(wsdl_path).getroot()
        for st in root.iter(f"{{{XS}}}simpleType"):
            name = st.get("name")
            restriction = st.find(f"{{{XS}}}restriction")
            if not name or restriction is None:
                continue
            values = {e.get("value") for e in restriction.findall(f"{{{XS}}}enumeration")}
            if values:
                enums[name] = values

    files = sorted(mig_dir.glob("V*.sql"))
    check("нет ни одной миграции в core/src/main/resources/db/migration",
          bool(files))

    for path in files:
        rel = path.relative_to(ROOT)
        raw = path.read_text(encoding="utf-8")
        text = _strip_block_comments(raw)
        lines = text.splitlines()
        tables = _tables(text)

        def table_of(line_no: int) -> tuple[str, str]:
            for name, a, b, body in tables:
                if a <= line_no <= b:
                    return name, body
            return "", ""

        for i, line in enumerate(lines):
            m = re.search(r"--\s*limit:\s*([a-z0-9_]+)", line)
            if m:
                name = m.group(1)
                check(
                    f"{rel}:{i+1}: граница {name} не объявлена в limits.yaml",
                    name in limits,
                )
                nxt = lines[i + 1] if i + 1 < len(lines) else ""
                sizes = re.findall(r"VARCHAR\s*\(\s*(\d+)\s*\)", nxt, re.I)
                check(
                    f"{rel}:{i+2}: за аннотацией limit: {name} нет VARCHAR(n)",
                    bool(sizes),
                )
                if sizes and name in limits:
                    check(
                        f"{rel}:{i+2}: VARCHAR({sizes[0]}) расходится с "
                        f"{name} = {limits[name]} из limits.yaml — ядро и база "
                        f"разрешат разное",
                        int(sizes[0]) == int(limits[name]),
                    )

            m = re.search(r"--\s*enum:\s*(\w+)", line)
            if m:
                name = m.group(1)
                check(
                    f"{rel}:{i+1}: перечисление {name} не объявлено в WSDL",
                    name in enums,
                )
                if name not in enums:
                    continue

                nxt = lines[i + 1] if i + 1 < len(lines) else ""
                mc = re.match(r"\s*(\w+)\s+VARCHAR", nxt, re.I)
                check(
                    f"{rel}:{i+2}: за аннотацией enum: {name} нет колонки VARCHAR",
                    bool(mc),
                )
                if not mc:
                    continue
                col = mc.group(1)

                tname, body = table_of(i)
                check(f"{rel}:{i+1}: аннотация enum: {name} вне определения таблицы",
                      bool(body))
                if not body:
                    continue

                mck = re.search(
                    r"CHECK\s*\(\s*" + re.escape(col) + r"\s+IN\s*\(([^)]*)\)",
                    body, re.I,
                )
                check(
                    f"{rel}: у {tname}.{col} нет CHECK со списком значений — "
                    f"без него в колонку ляжет что угодно",
                    bool(mck),
                )
                if not mck:
                    continue
                got = {v.strip().strip("'") for v in mck.group(1).split(",")}
                check(
                    f"{rel}: CHECK у {tname}.{col} перечисляет {sorted(got)}, "
                    f"а контракт {name} — {sorted(enums[name])}",
                    got == enums[name],
                )


# --------------------------------------------------------------------- events
def validate_events() -> None:
    """Каталог событий против того, что ядро на самом деле порождает.

    Событие — контракт между ядром и всем, что живёт после него: очередью,
    проекциями, лентой, SSE. В WSDL оно описано только СТРУКТУРНО — тип
    строкой, поля парами, — и иначе быть не может: перечисли типы в
    контракте вызова, и всякое новое событие ломало бы протокол.

    Отсюда отдельный каталог и эта сверка. Ядро порождает события
    строковыми литералами; разойтись каталог с ними может молча, и узнать
    об этом предстояло бы проекции, которая перестала понимать половину
    очереди.

    Читается не «примерно похожий» текст, а конкретные вызовы EmitEvent и
    EmitField в dmdecide.pas: имена там литеральные, и по-другому событие
    не порождается.
    """
    path = CONTRACTS / "events" / "events.yaml"
    if not path.exists():
        fail(f"нет {path.relative_to(ROOT)}")
        return

    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    declared = doc.get("events", [])
    check("events.yaml: нет ни одного события", bool(declared))

    by_type: dict[str, dict] = {}
    for spec in declared:
        etype = spec.get("type")
        check(f"events.yaml: у события нет типа: {spec}", bool(etype))
        if not etype:
            continue
        check(f"events.yaml: тип {etype} объявлен дважды", etype not in by_type)
        check(f"events.yaml: у события {etype} нет агрегата",
              bool(spec.get("aggregate")))
        check(f"events.yaml: у события {etype} нет описания",
              bool(str(spec.get("description", "")).strip()))
        for field in spec.get("fields", []):
            check(f"events.yaml: поле события {etype} без имени",
                  bool(field.get("name")))
            check(f"events.yaml: поле {etype}.{field.get('name')} без описания",
                  bool(str(field.get("description", "")).strip()))
        by_type[etype] = spec

    core = ROOT / "dosnode" / "src" / "domain" / "dmdecide.pas"
    if not core.exists():
        fail(f"нет {core.relative_to(ROOT)}: сверять каталог не с чем")
        return

    emitted: dict[str, list[str]] = {}
    current: str | None = None
    for line in core.read_text(encoding="utf-8").splitlines():
        m = re.search(r"EmitEvent\(\s*A\s*,\s*D\s*,\s*'([^']+)'", line)
        if m:
            current = m.group(1)
            emitted.setdefault(current, [])
            continue
        m = re.search(r"EmitField\(\s*A\s*,\s*Ev\s*,\s*'([^']+)'", line)
        if m and current:
            emitted[current].append(m.group(1))

    check("dmdecide.pas: не нашлось ни одного порождаемого события — "
          "сверка была бы пустой", bool(emitted))

    for etype in sorted(set(emitted) - set(by_type)):
        fail(f"ядро порождает событие {etype}, а events.yaml о нём не знает")
    for etype in sorted(set(by_type) - set(emitted)):
        fail(f"events.yaml объявляет событие {etype}, а ядро его не порождает")

    for etype in sorted(set(emitted) & set(by_type)):
        want = [f["name"] for f in by_type[etype].get("fields", [])]
        got = emitted[etype]
        check(f"события {etype}: поля разошлись, ядро кладёт {got}, "
              f"каталог объявляет {want}", sorted(want) == sorted(got))


def main() -> int:
    if not CONTRACTS.exists():
        print(f"нет каталога {CONTRACTS}", file=sys.stderr)
        return 1

    errors_doc = validate_errors()
    validate_wsdl(errors_doc)
    validate_idl()
    validate_openapi()
    validate_limits()
    validate_migrations()
    validate_events()

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
