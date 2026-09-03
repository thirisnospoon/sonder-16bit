#!/usr/bin/env python3
"""Типы доменных событий из каталога событий.

Клиент подписывается на ИМЕНОВАННЫЕ события SSE: сервер шлёт их с
именем, равным типу события, и слушатель ставится на каждое имя
отдельно. Список имён, набранный в коде, — второй экземпляр каталога:
новое событие уедет в браузер и не будет услышано никем, причём молча,
потому что необработанное именованное событие ничем себя не проявляет.

Порождается один файл: web/src/generated/events.ts — перечень типов,
поля каждого и разметка полезной нагрузки.
"""
from __future__ import annotations

import pathlib
import re
import sys
from typing import Any

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "contracts" / "events" / "events.yaml"
OUT = ROOT / "web" / "src" / "generated" / "events.ts"

HEADER = """/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/events/events.yaml
 * Генератор: tools/gen-api-types/gen_events.py
 * Перегенерация: ./sonder codegen
 */
"""


def fail(what: str) -> None:
    raise SystemExit("генератор событий: " + what)


def comment(description: Any, indent: str) -> list[str]:
    """Описание из каталога, разложенное по строкам.

    Длинное описание одной строкой нечитаемо ровно в том месте, где его
    читают, — рядом с полем в подсказке редактора.
    """
    if not description:
        return []
    words = str(description).split()
    if not words:
        return []
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if current == "" else current + " " + word
        if len(indent) + 3 + len(candidate) > 76:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    if len(lines) == 1:
        return [indent + "/** " + lines[0] + " */"]
    out = [indent + "/**"]
    out.extend(indent + " * " + line for line in lines)
    out.append(indent + " */")
    return out


def key(name: str) -> str:
    if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", name):
        return name
    return "'" + name + "'"


def build() -> str:
    document = yaml.safe_load(SOURCE.read_text(encoding="utf-8"))
    events = document.get("events")
    if not events:
        fail("в каталоге нет ни одного события")

    types: list[str] = []
    payloads: list[str] = []
    names: list[str] = []

    for event in events:
        kind = event.get("type")
        if not kind:
            fail("событие без типа")
        fields = event.get("fields") or []
        aggregate = event.get("aggregate")
        if not aggregate:
            fail("событие " + kind + " без имени агрегата")

        names.append("  '" + kind + "',")
        types.append("  | '" + kind + "'")

        lines = ["  " + key(kind) + ": {"]
        for line in reversed(comment(event.get("description"), "  ")):
            lines.insert(0, line)
        # Идентификатор агрегата приезжает отдельным полем конверта, а не
        # внутри полезной нагрузки: так его кладёт ядро.
        lines.append("    readonly " + key(str(aggregate)) + ": string")
        for field in fields:
            name = field.get("name") if isinstance(field, dict) else field
            if not name:
                fail("поле без имени в событии " + kind)
            note = field.get("description") if isinstance(field, dict) else None
            lines.extend(comment(note, "    "))
            lines.append("    readonly " + key(str(name)) + ": string")
        lines.append("  }")
        payloads.append("\n".join(lines))

    out = [HEADER]
    out.append(
        "/**\n"
        " * Типы доменных событий.\n"
        " *\n"
        " * Список нужен в РАНТАЙМЕ: слушатель SSE ставится на каждое имя\n"
        " * отдельно, и событие, на которое никто не подписан, приходит и\n"
        " * пропадает молча.\n"
        " */\n"
        "export const EVENT_TYPES = ["
    )
    out.append("\n".join(names))
    out.append("] as const\n")
    out.append("export type EventType =\n" + "\n".join(types) + "\n")
    out.append(
        "/**\n"
        " * Полезная нагрузка каждого события.\n"
        " *\n"
        " * Событие несёт ИДЕНТИЧНОСТЬ, а не копию агрегата: тела поста\n"
        " * и отображаемого имени здесь нет, они читаются из проекций.\n"
        " */\n"
        "export interface EventPayloads {"
    )
    out.append("\n".join(payloads))
    out.append("}\n")
    return "\n".join(out)


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = build().encode("utf-8")
    relative = OUT.relative_to(ROOT).as_posix()
    previous = OUT.read_bytes() if OUT.exists() else b""
    if previous == text:
        print("  " + relative + "  без изменений")
        changed = 0
    else:
        OUT.write_bytes(text)
        print("  " + relative + "  изменён")
        changed = 1

    document = yaml.safe_load(SOURCE.read_text(encoding="utf-8"))
    print(
        "событий: %d, файлов изменено: %d"
        % (len(document.get("events", [])), changed)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
