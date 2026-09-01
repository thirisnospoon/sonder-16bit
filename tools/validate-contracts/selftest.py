#!/usr/bin/env python3
"""
Самопроверка валидатора контрактов.

Валидатор, который всегда зелёный, бесполезен и опасен: он создаёт видимость
проверки. Здесь в копию контрактов по одному вносятся дефекты, и проверяется,
что валидатор на каждом падает и говорит по делу.

Каждый случай ниже — это реальная ошибка, которая иначе размножилась бы
кодогенерацией на четыре языка.

Выход: 0 — валидатор ловит все дефекты, 1 — какой-то пропускает.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = Path(__file__).parent / "validate.py"

Case = tuple[str, str, str, str, str]  # имя, файл, что искать, чем заменить, ожидаемое в выводе

CASES: list[Case] = [
    (
        "повторяющийся код отказа",
        "contracts/errors/errors.yaml",
        "  - code: POST_BODY_EMPTY",
        "  - code: NICK_FORMAT_INVALID",
        "повторяется",
    ),
    (
        "несуществующая категория",
        "contracts/errors/errors.yaml",
        "    category: CONFLICT\n    description: \"Ник уже занят\"",
        "    category: НЕТ_ТАКОЙ\n    description: \"Ник уже занят\"",
        "несуществующую категорию",
    ),
    (
        "не указано, кто решает",
        "contracts/errors/errors.yaml",
        "    description: \"Нельзя подписаться на себя\"\n    decided_by: core",
        "    description: \"Нельзя подписаться на себя\"",
        "не указано, кто его решает",
    ),
    (
        "код в нижнем регистре",
        "contracts/errors/errors.yaml",
        "  - code: SELF_FOLLOW",
        "  - code: self_follow",
        "ВЕРХНЕМ_РЕГИСТРЕ",
    ),
    (
        "необязательное поле в состоянии",
        "contracts/soap/decider-v1.wsdl",
        '<xs:element name="postsLastHour"    type="xs:int"/>',
        '<xs:element name="postsLastHour"    type="xs:int" minOccurs="0"/>',
        "необязательным",
    ),
    (
        "операция без ответа",
        "contracts/soap/decider-v1.wsdl",
        '<wsdl:operation name="CreatePost">\n      <wsdl:input  message="tns:CreatePostIn"/>\n      <wsdl:output message="tns:DecisionOut"/>\n    </wsdl:operation>',
        '<wsdl:operation name="CreatePost">\n      <wsdl:input  message="tns:CreatePostIn"/>\n    </wsdl:operation>',
        "нет output",
    ),
    (
        "текстовое поле IDL объявлено как string",
        "contracts/idl/enrichment-v1.idl",
        "wstring   body;",
        "string    body;",
        "кириллицу не принимает",
    ),
    (
        "битая ссылка в OpenAPI",
        "contracts/openapi/social-v1.yaml",
        '$ref: "#/components/schemas/FeedPage"',
        '$ref: "#/components/schemas/NoSuchThing"',
        "не разрешается",
    ),
    (
        "операция OpenAPI без operationId",
        "contracts/openapi/social-v1.yaml",
        "      operationId: getFeed\n",
        "",
        "без operationId",
    ),
    (
        "граница домена разошлась с подсказкой OpenAPI",
        "contracts/domain/limits.yaml",
        "  post_body_max_len:\n    value: 1000",
        "  post_body_max_len:\n    value: 900",
        "Интерфейс разрешит",
    ),
    (
        "исчез код INSUFFICIENT_CONTEXT",
        "contracts/errors/errors.yaml",
        "  - code: INSUFFICIENT_CONTEXT",
        "  - code: SOMETHING_ELSE",
        "обязан решаться ядром",
    ),
]


def run_validator(root: Path) -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, str(root / "tools/validate-contracts/validate.py")],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main() -> int:
    # Сначала убеждаемся, что на неиспорченных контрактах валидатор зелёный.
    rc, out = run_validator(ROOT)
    if rc != 0:
        print("ИСХОДНЫЕ КОНТРАКТЫ НЕ ПРОХОДЯТ ВАЛИДАЦИЮ — сначала почини их")
        print(out)
        return 1
    print("ok - исходные контракты валидны")

    failures = 0
    for idx, (name, rel, needle, replacement, expect) in enumerate(CASES, 1):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / "repo"
            shutil.copytree(ROOT / "contracts", work / "contracts")
            shutil.copytree(ROOT / "tools", work / "tools")

            target = work / rel
            text = target.read_bytes().decode("utf-8")
            if needle not in text:
                print(f"not ok {idx} - {name}: не нашёл, что портить в {rel}")
                failures += 1
                continue
            target.write_bytes(text.replace(needle, replacement, 1).encode("utf-8"))

            rc, out = run_validator(work)

            if rc == 0:
                print(f"not ok {idx} - {name}: валидатор ПРОПУСТИЛ дефект")
                failures += 1
            elif expect.lower() not in out.lower():
                print(f"not ok {idx} - {name}: упал, но не по делу")
                print(f"#   ожидал в выводе: {expect}")
                for line in out.splitlines():
                    if line.strip().startswith("✗"):
                        print(f"#   получил: {line.strip()}")
                failures += 1
            else:
                print(f"ok {idx} - {name}")

    print()
    print(f"1..{len(CASES)}")
    if failures:
        print(f"# ИТОГ: валидатор пропускает {failures} дефектов из {len(CASES)}")
        return 1
    print(f"# ИТОГ: валидатор ловит все {len(CASES)} дефектов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
