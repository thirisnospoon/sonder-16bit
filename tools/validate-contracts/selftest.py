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
    (
        "повторяемость кода задана строкой, а не булевым",
        "contracts/errors/errors.yaml",
        "    retryable: true",
        "    retryable: \"true\"",
        "должен быть true или false",
    ),
    # Схема БД — четвёртый потребитель тех же чисел. Разойтись она может
    # так же молча, а последствие хуже: ядро примет строку, которую база
    # не сохранит, и отказ придёт после «готово».
    (
        "длина колонки разошлась с границей контракта",
        "core/src/main/resources/db/migration/V1__baseline.sql",
        "  display_name   VARCHAR(60)",
        "  display_name   VARCHAR(50)",
        "расходится с display_name_max_len",
    ),
    (
        "в CHECK потерялось значение перечисления",
        "core/src/main/resources/db/migration/V1__baseline.sql",
        "CHECK (role IN ('USER', 'MODERATOR', 'ADMIN'))",
        "CHECK (role IN ('USER', 'ADMIN'))",
        "а контракт Role",
    ),
    (
        "у колонки перечисления нет CHECK вовсе",
        "core/src/main/resources/db/migration/V1__baseline.sql",
        "  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BANNED', 'DELETED'))",
        "  CONSTRAINT ck_users_status CHECK (LENGTH(status) > 0)",
        "нет CHECK со списком значений",
    ),
    (
        "ядро порождает событие, которого нет в каталоге",
        "contracts/events/events.yaml",
        "  - type: post.deleted",
        "  - type: post.erased",
        "а events.yaml о нём не знает",
    ),
    (
        "каталог объявляет поле, которого ядро не кладёт",
        "contracts/events/events.yaml",
        "      - name: authorId\n        description: Автор поста. По нему ищутся подписчики при фанауте.",
        "      - name: authorId\n        description: Автор поста. По нему ищутся подписчики при фанауте.\n      - name: body\n        description: Тело поста, которого в событии на самом деле нет.",
        "поля разошлись",
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
            # Миграции валидатор тоже сверяет с контрактом, поэтому их
            # копия нужна так же, как копия contracts. Каталог может ещё
            # не существовать — на ранних фазах его не было.
            migrations = ROOT / "core/src/main/resources/db/migration"
            if migrations.exists():
                shutil.copytree(migrations,
                                work / "core/src/main/resources/db/migration")
            # Каталог событий сверяется с тем, что порождает ядро, поэтому
            # его исходник нужен здесь по той же причине, что и миграции.
            # Без него проверка не молчит, а честно отказывается сверять —
            # и самопроверка тогда видит «упал, но не по делу».
            core = ROOT / "dosnode/src/domain/dmdecide.pas"
            if core.exists():
                (work / "dosnode/src/domain").mkdir(parents=True, exist_ok=True)
                shutil.copy2(core, work / "dosnode/src/domain/dmdecide.pas")

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
