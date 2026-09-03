#!/usr/bin/env python3
"""Типы TypeScript и маршруты из OpenAPI.

Руками эти типы не пишутся, и это не удобство, а правило проекта: тип,
набранный по описанию, есть второй экземпляр описания — расходится он
молча, а обнаруживается в бою. Тот же довод, что и у Pascal-стабов из
WSDL и у кодов ошибок из errors.yaml.

Порождается два файла:

  api.ts     — типы схем и объявление операций (метод, путь, тело, ответ)
  paths.ts   — образцы адресов клиента, собранные из тех же путей

Генератор НЕ УМЕЕТ всего OpenAPI и не должен: он умеет ровно те формы,
которые встречаются в этом контракте, и на всякой другой ОСТАНАВЛИВАЕТСЯ
с объяснением. Молчаливое `unknown` вместо непонятой формы — та самая
тихая потеря типа, ради которой генератор и написан.
"""
from __future__ import annotations

import pathlib
import re
import sys
from typing import Any

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "contracts" / "openapi" / "social-v1.yaml"
OUT_DIR = ROOT / "web" / "src" / "generated"

HEADER = """/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/openapi/social-v1.yaml
 * Генератор: tools/gen-api-types/gen_api_types.py
 * Перегенерация: ./sonder codegen
 */
"""


class Unsupported(SystemExit):
    """Форма, которую генератор не понимает. Молчать о ней нельзя."""


def fail(what: str) -> None:
    raise Unsupported("генератор не умеет: " + what)


def ts_name(name: str) -> str:
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", name):
        fail("имя схемы не годится в идентификатор TypeScript: " + name)
    return name


def prop_key(name: str) -> str:
    """Имя поля в объектном типе."""
    if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", name):
        return name
    return "'" + name.replace("'", "\\'") + "'"


def ref_name(ref: str) -> str:
    prefix = "#/components/schemas/"
    if not ref.startswith(prefix):
        fail("ссылка не на схему: " + ref)
    return ts_name(ref[len(prefix) :])


def type_of(schema: dict[str, Any], indent: str) -> str:
    """Тип схемы. Именованные ссылки остаются ссылками, а не разворачиваются."""
    if "$ref" in schema:
        return ref_name(schema["$ref"])

    if "allOf" in schema:
        # Пересечение: `A & { ... }`. Так в контракте описан Me.
        parts = [type_of(part, indent) for part in schema["allOf"]]
        return " & ".join(parts)

    if "oneOf" in schema or "anyOf" in schema:
        fail("oneOf/anyOf в схеме: разберите её на именованные варианты")

    kind = schema.get("type")

    if kind == "string":
        if "enum" in schema:
            return " | ".join("'" + str(v) + "'" for v in schema["enum"])
        return "string"
    if kind == "integer" or kind == "number":
        return "number"
    if kind == "boolean":
        return "boolean"
    if kind == "array":
        items = schema.get("items")
        if items is None:
            fail("массив без items")
        return "readonly " + type_of(items, indent) + "[]"
    if kind == "object" or "properties" in schema:
        return object_type(schema, indent)

    fail("схема без понятного типа: " + repr(schema)[:120])
    return ""  # недостижимо, нужно для проверки типов


def object_type(schema: dict[str, Any], indent: str) -> str:
    properties: dict[str, Any] = schema.get("properties", {})
    if not properties:
        # Объект без свойств — это либо забытое описание, либо «что
        # угодно». И то и другое здесь ошибка: клиент, получивший
        # `Record<string, unknown>`, теряет проверку типов целиком.
        fail("объект без properties")

    required = set(schema.get("required", []))
    inner = indent + "  "
    lines = ["{"]
    for name, value in properties.items():
        optional = "" if name in required else "?"
        lines.extend(comment(value.get("description"), inner))
        lines.append(
            inner
            + "readonly "
            + prop_key(name)
            + optional
            + ": "
            + type_of(value, inner)
        )
    lines.append(indent + "}")
    return "\n".join(lines)


def comment(description: Any, indent: str) -> list[str]:
    """Описание из контракта целиком.

    Обрезанное по первой строке описание хуже отсутствующего: оно
    обрывается на середине фразы и выглядит так, будто это и есть всё,
    что о поле известно.
    """
    if not description:
        return []
    lines = [line.rstrip() for line in str(description).strip().splitlines()]
    if len(lines) == 1:
        return [indent + "/** " + lines[0] + " */"]
    out = [indent + "/**"]
    out.extend(indent + " * " + line if line else indent + " *" for line in lines)
    out.append(indent + " */")
    return out


def emit_schemas(schemas: dict[str, Any]) -> list[str]:
    out: list[str] = []
    for name, schema in schemas.items():
        alias = ts_name(name)
        out.extend(comment(schema.get("description"), ""))
        out.append("export type " + alias + " = " + type_of(schema, "") + "\n")
    return out


METHODS = ("get", "post", "put", "patch", "delete")


def json_schema(body: dict[str, Any] | None) -> dict[str, Any] | None:
    if body is None:
        return None
    content = body.get("content")
    if content is None:
        return None
    if "application/json" not in content:
        # Ленту событий отдаёт text/event-stream; тела у неё нет.
        return None
    return content["application/json"].get("schema")


def success_schema(responses: dict[str, Any]) -> dict[str, Any] | None:
    for code in ("200", "201", "202"):
        if code in responses:
            return json_schema(responses[code])
    if "204" in responses:
        return None
    fail("у операции нет успешного ответа: " + repr(sorted(responses))[:80])
    return None


def emit_operations(
    paths: dict[str, Any],
) -> tuple[list[str], list[str], list[str]]:
    """Объявления операций, образцы адресов и методы."""
    operations: list[str] = []
    routes: list[str] = []
    methods: list[str] = []

    for path, item in paths.items():
        for method in METHODS:
            spec = item.get(method)
            if spec is None:
                continue
            operation_id = spec.get("operationId")
            if operation_id is None:
                fail("операция без operationId: " + method + " " + path)
            name = ts_name(operation_id)

            body = json_schema(spec.get("requestBody"))
            reply = success_schema(spec.get("responses", {}))

            operations.append("  /** " + method.upper() + " " + path + " */")
            operations.append("  " + name + ": {")
            operations.append("    readonly method: '" + method.upper() + "'")
            operations.append("    readonly path: '" + path + "'")
            operations.append(
                "    readonly body: "
                + ("null" if body is None else type_of(body, "    "))
            )
            operations.append(
                "    readonly reply: "
                + ("null" if reply is None else type_of(reply, "    "))
            )
            operations.append("  }")

            routes.append("  " + name + ": '" + path + "',")
            methods.append("  " + name + ": '" + method.upper() + "',")

    return operations, routes, methods


def build() -> dict[pathlib.Path, str]:
    document = yaml.safe_load(SOURCE.read_text(encoding="utf-8"))
    schemas = document.get("components", {}).get("schemas", {})
    if not schemas:
        fail("в контракте нет ни одной схемы")
    paths = document.get("paths", {})
    if not paths:
        fail("в контракте нет ни одного пути")

    operations, routes, methods = emit_operations(paths)

    api = [HEADER, "\n".join(emit_schemas(schemas))]
    api.append(
        "/**\n"
        " * Операции контракта: метод, путь, тело запроса и тело ответа.\n"
        " *\n"
        " * Клиент строится ПО ЭТОМУ объявлению, а не по строкам в коде.\n"
        " * Путь, набранный руками, — второй экземпляр контракта, и\n"
        " * расходится он молча: запрос уходит по адресу, которого нет.\n"
        " */\n"
        "export interface Operations {"
    )
    api.extend(operations)
    api.append("}\n")
    api.append("export type OperationName = keyof Operations\n")

    routes_file = [
        HEADER,
        "/**\n"
        " * Образцы адресов, объявленные контрактом.\n"
        " *\n"
        " * Отсюда их берёт клиент; маршруты страниц объявляются отдельно —\n"
        " * они принадлежат вебу, а не контракту.\n"
        " */\n"
        "export const API_PATHS = {",
        "\n".join(routes),
        "} as const\n",
        "/**\n"
        " * Метод каждой операции.\n"
        " *\n"
        " * ЗНАЧЕНИЕМ, а не только типом: клиенту метод нужен в рантайме, и\n"
        " * набранная рядом таблица была бы вторым экземпляром контракта —\n"
        " * тем самым, против которого весь этот генератор и написан.\n"
        " */\n"
        "export const API_METHODS = {",
        "\n".join(methods),
        "} as const\n",
    ]

    return {
        OUT_DIR / "api.ts": "\n".join(api),
        OUT_DIR / "paths.ts": "\n".join(routes_file),
    }


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    changed = 0
    for path, text in build().items():
        relative = path.relative_to(ROOT).as_posix()
        previous = path.read_bytes() if path.exists() else b""
        data = text.encode("utf-8")
        if previous == data:
            print("  " + relative + "  без изменений")
            continue
        path.write_bytes(data)
        print("  " + relative + "  изменён")
        changed += 1

    document = yaml.safe_load(SOURCE.read_text(encoding="utf-8"))
    schemas = len(document.get("components", {}).get("schemas", {}))
    operations = sum(
        1
        for item in document.get("paths", {}).values()
        for method in METHODS
        if method in item
    )
    print(
        "схем: %d, операций: %d, файлов изменено: %d"
        % (schemas, operations, changed)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
