#!/usr/bin/env python3
"""
Генерация разметки записи свода из contracts/reports/digest-v1.yaml.

Разметка существует в трёх местах — YAML, копибук COBOL и писатель на
Java — и разойтись они не имеют права. У файла фиксированной ширины нет
ни разделителей, ни заголовков: сдвиг на один байт означает, что всё
последующее прочитано НЕВЕРНО, но прочитано. Программа не заметит, отчёт
получится, и числа в нём будут неправдой.

Именно поэтому обе стороны порождаются, а правка сгенерированного
ловится проверкой дрейфа.

Цели:

  COBOL — копибук с уровнями 05 и PIC. Смещения не пишутся: их
          складывает сам компилятор, и записанное рядом число однажды
          разойдётся с настоящим.

  Java  — класс с одним методом, который собирает строку записи.
          Ширины берутся отсюда же, обрезание запрещено: поле, не
          влезшее в свою ширину, это отказ выгрузки, а не молча
          усечённая строка.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("нужен PyYAML", file=sys.stderr)
    raise SystemExit(2)

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "contracts" / "reports" / "digest-v1.yaml"
COPYBOOK = ROOT / "report" / "copybook" / "DIGEST.cpy"
JAVA = (ROOT / "core" / "src" / "main" / "java" / "sonder" / "report"
        / "DigestRecord.java")

ШАПКА = "ПОРОЖДЁННЫЙ ФАЙЛ. Правится contracts/reports/digest-v1.yaml."


def прочитать() -> dict:
    with SRC.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def проверить(doc: dict) -> list:
    """Сумма ширин обязана совпасть с объявленной длиной записи."""
    беды = []
    поля = doc["record"]["fields"]
    сумма = sum(int(f["bytes"]) for f in поля)
    объявлено = int(doc["record_bytes"])
    if сумма != объявлено:
        беды.append(
            "сумма ширин {} не равна record_bytes {}: разметка разъехалась"
            .format(сумма, объявлено))
    for f in поля:
        if f["type"] not in ("text", "num", "date"):
            беды.append("поле {}: неизвестный тип {}".format(f["name"], f["type"]))
        if f["type"] == "date" and int(f["bytes"]) != 8:
            беды.append("поле {}: дата это ровно 8 байт ГГГГММДД".format(f["name"]))
    return беды


def имя_cobol(имя: str) -> str:
    """camelCase -> COBOL-CASE. Иначе имена нечитаемы в копибуке."""
    из = []
    for c in имя:
        if c.isupper():
            из.append("-")
        из.append(c.upper())
    return "".join(из)


def копибук(doc: dict) -> str:
    поля = doc["record"]["fields"]
    строки = [
      "      *> {}".format(ШАПКА),
      "      *>",
      "      *> {}".format(doc["record"]["description"].strip()),
      "      *>",
      "      *> Длина записи: {} байт. Смещения складывает компилятор —".format(
          doc["record_bytes"]),
      "      *> записанные рядом числа однажды разойдутся с настоящими.",
      "       01  DIGEST-POST.",
    ]
    for f in поля:
        n = int(f["bytes"])
        pic = "X({})".format(n) if f["type"] == "text" else "9({})".format(n)
        строки.append("           05  {:<26} PIC {}.".format(
            имя_cobol(f["name"]), pic))
    строки.append("")
    return "\n".join(строки)


def java(doc: dict) -> str:
    поля = doc["record"]["fields"]
    из = [
        "package sonder.report;",
        "",
        "import java.nio.charset.StandardCharsets;",
        "",
        "/**",
        " * {}".format(ШАПКА),
        " *",
        " * <p>Одна строка выгрузки свода, ровно {} байт.".format(
            doc["record_bytes"]),
        " *",
        " * <p>ОБРЕЗАНИЕ ЗАПРЕЩЕНО. Поле, не влезшее в свою ширину, — это",
        " * отказ выгрузки, а не молча усечённая строка: усечение по байтам",
        " * рвёт UTF-8 посередине символа, и COBOL прочитает это как мусор,",
        " * не заметив.",
        " */",
        "public final class DigestRecord {",
        "",
        "    /** Длина записи в байтах. */",
        "    public static final int BYTES = {};".format(doc["record_bytes"]),
        "",
        "    private DigestRecord() {",
        "    }",
        "",
    ]
    for f in поля:
        из.append("    /** Ширина поля {} в байтах. */".format(f["name"]))
        из.append("    public static final int {}_BYTES = {};".format(
            f["name"].upper(), f["bytes"]))
        из.append("")

    аргументы = []
    for f in поля:
        тип = "String" if f["type"] == "text" else "long"
        if f["type"] == "date":
            тип = "String"
        аргументы.append("{} {}".format(тип, f["name"]))

    из.extend([
        "    /** Собрать строку записи. Возвращает ровно {} байт.".format(
            doc["record_bytes"]),
        "     *",
        "     * @throws IllegalArgumentException если поле не влезло",
        "     */",
        "    public static byte[] of({}) {{".format(",\n                            ".join(аргументы)),
        "        byte[] out = new byte[BYTES];",
        "        java.util.Arrays.fill(out, (byte) ' ');",
        "        int at = 0;",
    ])
    for f in поля:
        имя = f["name"]
        ширина = "{}_BYTES".format(имя.upper())
        if f["type"] == "text" or f["type"] == "date":
            из.append("        at = text(out, at, {}, {}, \"{}\");".format(
                имя, ширина, имя))
        else:
            из.append("        at = number(out, at, {}, {}, \"{}\");".format(
                имя, ширина, имя))
    из.extend([
        "        if (at != BYTES) {",
        "            throw new IllegalStateException(",
        "                    \"собрано \" + at + \" байт вместо \" + BYTES);",
        "        }",
        "        return out;",
        "    }",
        "",
        "    /** Текст влево, добивка пробелами. */",
        "    private static int text(byte[] out, int at, String value,",
        "                            int width, String field) {",
        "        byte[] raw = (value == null ? \"\" : value)",
        "                .getBytes(StandardCharsets.UTF_8);",
        "        if (raw.length > width) {",
        "            throw new IllegalArgumentException(",
        "                    \"поле \" + field + \": \" + raw.length",
        "                            + \" байт при ширине \" + width",
        "                            + \". Обрезать нельзя — разорвётся UTF-8\");",
        "        }",
        "        System.arraycopy(raw, 0, out, at, raw.length);",
        "        return at + width;",
        "    }",
        "",
        "    /** Число вправо, добивка нулями: так его читает PIC 9. */",
        "    private static int number(byte[] out, int at, long value,",
        "                              int width, String field) {",
        "        if (value < 0) {",
        "            throw new IllegalArgumentException(",
        "                    \"поле \" + field + \": отрицательное \" + value",
        "                            + \", а разметка без знака\");",
        "        }",
        "        String s = Long.toString(value);",
        "        if (s.length() > width) {",
        "            throw new IllegalArgumentException(",
        "                    \"поле \" + field + \": \" + s",
        "                            + \" не влезает в \" + width + \" знаков\");",
        "        }",
        "        int pad = width - s.length();",
        "        for (int i = 0; i < pad; i++) {",
        "            out[at + i] = '0';",
        "        }",
        "        byte[] raw = s.getBytes(StandardCharsets.US_ASCII);",
        "        System.arraycopy(raw, 0, out, at + pad, raw.length);",
        "        return at + width;",
        "    }",
        "}",
        "",
    ])
    return "\n".join(из)


def записать(путь: Path, текст: str) -> bool:
    путь.parent.mkdir(parents=True, exist_ok=True)
    новое = текст.encode("utf-8")
    if путь.exists() and путь.read_bytes() == новое:
        print("  {}  без изменений".format(путь.relative_to(ROOT)))
        return False
    путь.write_bytes(новое)
    print("  {}".format(путь.relative_to(ROOT)))
    return True


def main() -> int:
    doc = прочитать()
    беды = проверить(doc)
    if беды:
        for б in беды:
            print("  {}".format(б), file=sys.stderr)
        return 1

    изменено = 0
    изменено += записать(COPYBOOK, копибук(doc))
    изменено += записать(JAVA, java(doc))
    print("полей: {}, запись {} байт, файлов изменено: {}".format(
        len(doc["record"]["fields"]), doc["record_bytes"], изменено))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
