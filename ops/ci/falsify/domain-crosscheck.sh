#!/usr/bin/env bash
# Ловит ли второе мнение расхождение с ядром.
#
# Согласие двух реализаций — сильное утверждение ровно настолько,
# насколько сверка способна показать несогласие. Здесь во второе мнение
# по одному вносятся дефекты четырёх разных родов, и каждый обязан
# покраснеть.
#
# Все четыре — настоящие ошибки понимания правил, а не опечатки:
#
#   1. ПЕРЕПУТАН ПРИОРИТЕТ. Проверка текста раньше проверки статуса.
#      Заблокированный с пустым постом получит POST_BODY_EMPTY вместо
#      ACTOR_BANNED. Обе реализации при этом «работают», и разница видна
#      только на пересечении двух условий.
#
#   2. ОСЛАБЛЕН РАЗБОР UTF-8. Снята проверка суррогатов: D800..DFFF
#      начинают считаться законными знаками. Это самая частая ошибка в
#      рукописных разборщиках UTF-8 — и самая тихая. Правило общее, и
#      дефект обязан покраснеть на ОБЕИХ операциях.
#
#   3. СДВИНУТ ПРЕДЕЛ НА ЕДИНИЦУ. Строгое сравнение вместо нестрогого в
#      пределе частоты: двадцатый пост за час проходит.
#
#   4. НИК ПРОВЕРЯЕТСЯ КАК ИДЕНТИФИКАТОР. Набор знаков у них разный:
#      дефис законен в идентификаторе и незаконен в нике. Реализация,
#      взявшая одну проверку на оба, примет «u-andrey» за ник.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
POST="$ROOT/dosnode/prolog/createpost.pl"
COMMON="$ROOT/dosnode/prolog/rules.pl"
KEEP_POST="$(mktemp)"
KEEP_COMMON="$(mktemp)"

cd "$ROOT" || exit 1

cp "$POST" "$KEEP_POST"
cp "$COMMON" "$KEEP_COMMON"
restore() {
  cp "$KEEP_POST" "$POST"
  cp "$KEEP_COMMON" "$COMMON"
}
trap 'restore; rm -f "$KEEP_POST" "$KEEP_COMMON"' EXIT

bash ops/ci/domain-crosscheck.sh > /tmp/f-dom-0.log 2>&1 \
  || { echo "  БАЗА КРАСНАЯ"; sed -n '$p' /tmp/f-dom-0.log; exit 1; }

# --- 1. Приоритет: текст раньше статуса -------------------------------
python3 - "$POST" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_text(encoding="utf-8")
banned = """decide(_, Status, _, _, _, _, _, _, rejected('ACTOR_BANNED')) :-
    banned_or_gone(Status), !.
"""
text = """decide(_, _, _, _, _, Body, MaxLen, _, rejected(Code)) :-
    text_verdict(Body, MaxLen, V),
    V \\= ok, !,
    text_code(V, Code).
"""
assert banned in t and text in t, "правила изменились, правь фальсификацию"
t = t.replace(banned, "", 1).replace(text, text + banned, 1)
p.write_text(t, encoding="utf-8", newline="")
PY
bash ops/ci/domain-crosscheck.sh > /tmp/f-dom-1.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ ПЕРЕПУТАННОМ ПРИОРИТЕТЕ"; exit 1; }
grep -aq "случай" /tmp/f-dom-1.log \
  || { echo "  упало, но не на расхождении случаев"; exit 1; }
restore

# --- 2. Суррогаты объявлены законными ---------------------------------
sed -i 's/;  B =:= 0xED -> B2 =< 0x9F/;  B =:= 0xED -> true/' "$COMMON"
grep -q "B =:= 0xED -> true" "$COMMON" \
  || { echo "  ДЕФЕКТ НЕ ВНЕСЁН: разбор UTF-8 изменился"; exit 1; }
bash ops/ci/domain-crosscheck.sh > /tmp/f-dom-2.log 2>&1 \
  && { echo "  ЗЕЛЕНО, КОГДА СУРРОГАТЫ СЧИТАЮТСЯ ЗАКОННЫМИ"; exit 1; }
restore

# --- 3. Предел частоты сдвинут на единицу -----------------------------
sed -i "s/    Posts >= Rate, !\./    Posts > Rate, !./" "$POST"
grep -q "Posts > Rate" "$POST" \
  || { echo "  ДЕФЕКТ НЕ ВНЕСЁН: правило предела изменилось"; exit 1; }
bash ops/ci/domain-crosscheck.sh > /tmp/f-dom-3.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ СДВИНУТОМ НА ЕДИНИЦУ ПРЕДЕЛЕ"; exit 1; }
restore

# --- 4. Дефис объявлен законным в нике --------------------------------
sed -i "s/^lower_alnum(0'_)\./lower_alnum(0'_).\nlower_alnum(0'-)./" "$COMMON"
grep -q "lower_alnum(0'-)" "$COMMON" \
  || { echo "  ДЕФЕКТ НЕ ВНЕСЁН: набор знаков ника изменился"; exit 1; }
bash ops/ci/domain-crosscheck.sh > /tmp/f-dom-4.log 2>&1 \
  && { echo "  ЗЕЛЕНО, КОГДА ДЕФИС ЗАКОНЕН В НИКЕ"; exit 1; }

echo "  приоритет, суррогаты, предел на единицу и набор знаков ника ловятся"
