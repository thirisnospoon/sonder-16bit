#!/usr/bin/env python3
"""
Генерация кодов отказа из contracts/errors/errors.yaml на три языка.

Один список кодов существует в четырёх местах — YAML и три языка — и разойтись
они не имеют права: расхождение означает, что оболочка вернёт клиенту код,
которого ядро не знает, или наоборот. Поэтому три файла порождаются, а не
пишутся руками, и правка сгенерированного ловится проверкой дрейфа в CI.

Особенности целей:

  Pascal — include-файл с константами. Ни enum, ни классов: коды передаются
           по линии как строки, а сравнение идёт побайтно. Дополнительно
           генерируется функция поиска категории по коду.

  Java   — enum с категорией и http-статусом. Именно enum, а не строки:
           опечатка обязана ломать компиляцию.

  TS     — union строковых литералов и таблица категорий. Union, а не enum:
           коды приходят с сервера строками, и union проверяет их на границе.
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
SRC = ROOT / "contracts" / "errors" / "errors.yaml"

BANNER_LINES = [
    "СГЕНЕРИРОВАНО. Не править руками.",
    "",
    "Источник: contracts/errors/errors.yaml",
    "Генератор: tools/gen-errors/gen_errors.py",
    "Перегенерация: ./sonder codegen",
    "",
    "Правка этого файла будет затёрта, а расхождение с источником",
    "поймано проверкой дрейфа в CI.",
]


def write(path: Path, text: str) -> bool:
    """Пишет байтами с LF. Возвращает True, если содержимое изменилось."""
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
    out.append("{ Коды отказа передаются по линии строками и сравниваются")
    out.append("  побайтно, поэтому здесь константы, а не перечислимый тип. }")
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
    out.append("  { Число кодов: полезно для проверки полноты таблиц. }")
    out.append(f"  ERR_CODE_COUNT = {len(doc['codes'])};")
    out.append("")
    out.append("  { Длина самого длинного кода. Тест сверяет её с MaxErrCodeLen")
    out.append("    из TcResult: добавление длинного кода не должно молча")
    out.append("    обрезаться при присваивании в TErrCode. }")
    out.append(f"  ERR_MAX_CODE_LEN = {max(len(c['code']) for c in doc['codes'])};")
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
    out.append(" * Коды отказа. Enum, а не строки: опечатка обязана ломать компиляцию.")
    out.append(" *")
    out.append(" * <p>{@code decidedByCore} говорит, кто принимает решение. Код с")
    out.append(" * {@code true} оболочка возвращать не имеет права — это ответ ядра,")
    out.append(" * и дублирование правила в Java означало бы два места, где живёт")
    out.append(" * одна и та же логика.")
    out.append(" */")
    out.append("public enum ErrorCode {")
    out.append("")

    cats = doc["categories"]
    last = len(doc["codes"]) - 1
    for i, entry in enumerate(doc["codes"]):
        cat = entry["category"]
        http = cats[cat]["http"]
        retryable = str(cats[cat]["retryable"]).lower()
        core = str(entry["decided_by"] == "core").lower()
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
    out.append("// Union строковых литералов, а не enum: коды приходят с сервера")
    out.append("// строками, и union проверяет их ровно на границе.")
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
        print(f"нет {SRC}", file=sys.stderr)
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
            mark = "изменён"
        else:
            mark = "без изменений"
        print(f"  {path.relative_to(ROOT)}  {mark}")

    print(f"кодов: {len(doc['codes'])}, файлов изменено: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
