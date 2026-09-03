"""
Нагрузочный профиль Sonder: где система насыщается и чем именно.

ЧТО ИМЕННО МЕРЯЕТСЯ. Запись в этой системе идёт через нульмодем к
шестнадцатибитному ядру: каждая команда — круговой обмен по линии в
11 520 бод. Спайк S2 измерил её отдельно: 11 503 Б/с и 13 мс постоянных
накладных на обмен, не зависящих от скорости. Отсюда предсказание —
порядка десяти команд в секунду на всё приложение (ADR-0011), — и
предсказание это надо ПОДТВЕРДИТЬ на живой системе, а не сослаться на
него.

Чтение линию не использует вовсе: лента читается из проекции. Поэтому
меряются оба пути, и контраст между ними — главный результат замера, а
не побочный.

ПОЧЕМУ МНОГО ПОЛЬЗОВАТЕЛЕЙ. Домен разрешает 20 постов в час на
человека (limits.yaml), и это правило ядра, а не помеха замеру: упершись
в него, мы измерили бы скорость отказов, а не потолок записи. Поэтому
пишущих заводится столько, чтобы правило не сработало ни у кого, и
каждый расходует свою квоту не до конца.

ПОЧЕМУ БЕЗ ЗАВИСИМОСТЕЙ. Стандартная библиотека и потоки. Нагрузка
здесь — десятки запросов в секунду, а не десятки тысяч: узкое место
измеряемое, а не измеряющее. Тащить ради этого асинхронный клиент
значило бы добавить в замер второй непонятный слой.
"""
import argparse
import http.client
import json
import queue
import ssl
import statistics
import sys
import threading
import time
from urllib.parse import urlparse

ПАРОЛЬ = "достаточно-длинный-пароль"


class Клиент:
    """Одно живое соединение. Keep-alive: иначе мерился бы TLS-хендшейк."""

    def __init__(self, база: str) -> None:
        части = urlparse(база)
        self.host = части.hostname or "localhost"
        self.port = части.port or (443 if части.scheme == "https" else 80)
        self.tls = части.scheme == "https"
        self.cookie: str | None = None
        self.conn: http.client.HTTPConnection | None = None

    def _соединение(self) -> http.client.HTTPConnection:
        if self.conn is None:
            if self.tls:
                # Сертификат состава самоподписанный — доверия ему нет и
                # быть не должно. Замер при этом обязан идти тем же
                # транспортом, что и жизнь: TLS стоит процессорного
                # времени, и мерить без него значит мерить не ту систему.
                ctx = ssl._create_unverified_context()
                self.conn = http.client.HTTPSConnection(
                    self.host, self.port, context=ctx, timeout=30
                )
            else:
                self.conn = http.client.HTTPConnection(
                    self.host, self.port, timeout=30
                )
        return self.conn

    def запрос(self, метод: str, путь: str, тело: dict | None = None):
        """Запрос с ОДНОЙ пересдачей на разорванном keep-alive.

        Соединение простаивает между уровнями нагрузки, и сервер вправе
        закрыть его сам. Первая же попытка по такому сокету получает
        «broken pipe» — запрос при этом НЕ УХОДИТ никуда, и считать это
        отказом системы неверно: так ведёт себя всякий клиент с
        постоянными соединениями, и всякий переоткрывает сокет молча.
        Именно так и вышло на первом полном замере: сотня «отказов» на
        первом уровне сразу после подготовки, все — с нулевым кодом.

        Пересдача РОВНО ОДНА и только на уровне соединения. Повторять
        запрос, который сервер получил и не ответил, нельзя: пост — не
        идемпотентная команда, и вторая попытка создала бы второй.
        """
        заголовки = {"Content-Type": "application/json"}
        if self.cookie:
            заголовки["Cookie"] = self.cookie
        данные = json.dumps(тело, ensure_ascii=False).encode() if тело else None

        for попытка in (1, 2):
            try:
                c = self._соединение()
                c.request(метод, путь, данные, заголовки)
                ответ = c.getresponse()
                текст = ответ.read()
                # Куку забираем сами: http.client не держит их, а
                # притаскивать ради одного заголовка cookiejar незачем.
                установка = ответ.getheader("Set-Cookie")
                if установка:
                    self.cookie = установка.split(";", 1)[0]
                return ответ.status, текст
            except Exception as beda:  # noqa: BLE001 — обрыв это тоже исход
                # Держать сломанное соединение значит превратить одну
                # неудачу в череду.
                self.conn = None
                if попытка == 2:
                    return 0, str(beda).encode()
        return 0, "недостижимо".encode()


def завести(база: str, сколько: int) -> list[Клиент]:
    """Пишущие с живыми сессиями.

    Регистрация — тоже команда, и идёт она через ядро. Поэтому
    подготовка сама по себе занимает время, и в измерение она не
    входит: считается только то, что происходит под нагрузкой.
    """
    метка = str(int(time.time()))[-6:]
    готовы: list[Клиент] = []
    for i in range(сколько):
        ник = f"b{метка}{i:04d}"
        k = Клиент(база)
        код, тело = k.запрос(
            "POST", "/api/users",
            {"nick": ник, "displayName": f"Нагрузка {i}", "password": ПАРОЛЬ},
        )
        if код != 201:
            print(f"  регистрация {ник}: {код} {тело[:120]!r}", file=sys.stderr)
            continue
        код, тело = k.запрос(
            "POST", "/api/auth/login", {"nick": ник, "password": ПАРОЛЬ}
        )
        if код != 204:
            print(f"  вход {ник}: {код} {тело[:120]!r}", file=sys.stderr)
            continue
        готовы.append(k)
    return готовы


class Итог:
    def __init__(self) -> None:
        self.времена: list[float] = []
        self.ok = 0
        self.предел = 0
        self.отказы = 0
        # Коды и образцы тел: «отказов 161» — это не сведение, а повод
        # для догадок. Отказ по коду ядра, отказ по таймауту и оборванное
        # соединение означают РАЗНОЕ, и различать их обязан замер, а не
        # тот, кто потом читает отчёт.
        self.коды: dict[int, int] = {}
        self.образцы: dict[int, str] = {}
        self.замок = threading.Lock()

    def записать(self, код: int, миллисекунды: float, тело: bytes = b"") -> None:
        with self.замок:
            self.времена.append(миллисекунды)
            self.коды[код] = self.коды.get(код, 0) + 1
            if код in (200, 201, 204):
                self.ok += 1
            elif код == 429:
                self.предел += 1
            else:
                self.отказы += 1
                if код not in self.образцы:
                    self.образцы[код] = тело[:200].decode("utf-8", "replace")


def прогон(клиенты: list[Клиент], потоков: int, секунд: float,
           запись: bool) -> tuple[Итог, float]:
    """Один уровень нагрузки.

    ПИШУЩИЕ БЕРУТСЯ ИЗ ОБЩЕЙ ОЧЕРЕДИ, а не закрепляются за потоком.
    Закреплённый поток расходует квоту ОДНОГО человека — двадцать постов
    в час, — и упирается в неё через пару секунд: дальше замер мерит
    скорость отказов 429, а вовсе не потолок записи. Так и вышло на
    первом прогоне: при четырёх потоках 60 успешных против 83
    отвергнутых.

    Очередь решает заодно и второе: клиент держит ОДНО соединение и
    потокобезопасным не является. Взял — попользовался — вернул: два
    потока не окажутся на одном сокете, а пишущие идут по кругу
    равномерно.
    """
    итог = Итог()
    конец = time.monotonic() + секунд
    старт = time.monotonic()

    свободные: queue.Queue[Клиент] = queue.Queue()
    for k in клиенты:
        свободные.put(k)

    def работа(номер: int) -> None:
        n = 0
        while time.monotonic() < конец:
            k = свободные.get()
            try:
                t0 = time.perf_counter()
                if запись:
                    код, тело = k.запрос(
                        "POST", "/api/posts",
                        {"body": f"нагрузка {номер}/{n} {time.time()}"},
                    )
                else:
                    код, тело = k.запрос("GET", "/api/feed?limit=20")
                итог.записать(код, (time.perf_counter() - t0) * 1000.0, тело)
            finally:
                свободные.put(k)
            n += 1

    нити = [threading.Thread(target=работа, args=(i,)) for i in range(потоков)]
    for н in нити:
        н.start()
    for н in нити:
        н.join()
    return итог, time.monotonic() - старт


def процентиль(значения: list[float], доля: float) -> float:
    if not значения:
        return 0.0
    по_порядку = sorted(значения)
    i = min(len(по_порядку) - 1, int(доля * len(по_порядку)))
    return по_порядку[i]


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="https://web:443")
    p.add_argument("--users", type=int, default=64)
    p.add_argument("--seconds", type=float, default=15.0)
    p.add_argument("--levels", default="1,2,4,8,16,32")
    p.add_argument("--mode", choices=["write", "read"], default="write")
    p.add_argument("--out", default="")
    args = p.parse_args()

    уровни = [int(x) for x in args.levels.split(",") if x.strip()]
    нужно = max(уровни)

    print(f"==> {args.mode}: готовим {args.users} пишущих на {args.url}")
    клиенты = завести(args.url, args.users)
    if len(клиенты) < нужно:
        print(f"ПРОВАЛ: живых сессий {len(клиенты)}, нужно минимум {нужно}",
              file=sys.stderr)
        return 1
    print(f"  готово: {len(клиенты)}")

    строки = []
    print()
    print(f"{'потоков':>8} {'запросов':>9} {'успех':>7} {'429':>5} "
          f"{'отказ':>6} {'зпс':>8} {'p50':>7} {'p95':>7} {'p99':>7}")
    for потоков in уровни:
        итог, прошло = прогон(клиенты, потоков, args.seconds, args.mode == "write")
        всего = len(итог.времена)
        зпс = итог.ok / прошло if прошло > 0 else 0.0
        p50 = процентиль(итог.времена, 0.50)
        p95 = процентиль(итог.времена, 0.95)
        p99 = процентиль(итог.времена, 0.99)
        print(f"{потоков:>8} {всего:>9} {итог.ok:>7} {итог.предел:>5} "
              f"{итог.отказы:>6} {зпс:>8.2f} {p50:>7.0f} {p95:>7.0f} {p99:>7.0f}")
        строки.append({
            "mode": args.mode,
            "concurrency": потоков,
            "requests": всего,
            "ok": итог.ok,
            "rate_limited": итог.предел,
            "failed": итог.отказы,
            "seconds": round(прошло, 3),
            "rps": round(зпс, 3),
            "p50_ms": round(p50, 1),
            "p95_ms": round(p95, 1),
            "p99_ms": round(p99, 1),
            "mean_ms": round(statistics.fmean(итог.времена), 1) if итог.времена else 0,
            "codes": dict(sorted(итог.коды.items())),
        })
        if итог.образцы:
            for код, образец in sorted(итог.образцы.items()):
                print(f"           код {код}: {образец}")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(строки, f, ensure_ascii=False, indent=1)
        print(f"\nзаписано: {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
