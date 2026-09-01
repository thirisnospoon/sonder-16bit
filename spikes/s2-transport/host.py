#!/usr/bin/env python3
"""
Спайк S2 — измеряющая сторона транспорта.

DOSBox подключается сюда TCP-сокетом (serial1=nullmodem), а с той стороны
линии сидит DOS-программа, которая просто отражает байты. Все замеры делаются
здесь: у хоста есть микросекундные часы, у DOS — тик BIOS в 55 мс.

Измеряем то, что реально определит потолок NODE-7:
  * пропускную способность в оба конца;
  * латентность кадра разных размеров;
  * целостность потока;
  * ограничивает ли DOSBox скорость по делителю UART вообще.

Результат — TAP на stdout и JSON с числами рядом.
"""

import json
import os
import random
import socket
import statistics
import sys
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("S2_PORT", "5300"))
EOT = b"\x04"

READY = 0x06

# 0x04 — признак конца, 0x06 — готовность DOS-стороны. В нагрузке их нет.
PAYLOAD_ALPHABET = bytes(b for b in range(7, 256))

results = {}
failures = 0
test_no = 0


def emit(line: str) -> None:
    print(line, flush=True)


def ok(name: str) -> None:
    global test_no
    test_no += 1
    emit(f"ok {test_no} - {name}")


def not_ok(name: str) -> None:
    global test_no, failures
    test_no += 1
    failures += 1
    emit(f"not ok {test_no} - {name}")


def diag(text: str) -> None:
    emit(f"# {text}")


def recv_exact(sock: socket.socket, n: int, timeout: float) -> bytes:
    """Читает ровно n байт или возвращает то, что успело прийти до таймаута."""
    buf = bytearray()
    deadline = time.monotonic() + timeout
    sock.settimeout(0.5)
    while len(buf) < n and time.monotonic() < deadline:
        try:
            chunk = sock.recv(min(65536, n - len(buf)))
        except socket.timeout:
            continue
        if not chunk:
            break
        buf.extend(chunk)
    return bytes(buf)


def make_payload(n: int) -> bytes:
    rnd = random.Random(20260901)
    return bytes(rnd.choice(PAYLOAD_ALPHABET) for _ in range(n))


def test_latency(sock: socket.socket, size: int, rounds: int):
    """Круговая задержка кадра заданного размера."""
    payload = make_payload(size)
    samples = []
    for _ in range(rounds):
        t0 = time.perf_counter()
        sock.sendall(payload)
        got = recv_exact(sock, size, timeout=15.0)
        t1 = time.perf_counter()
        if len(got) != size:
            return None, len(got)
        samples.append((t1 - t0) * 1000.0)
    samples.sort()
    return {
        "size": size,
        "rounds": rounds,
        "p50_ms": round(statistics.median(samples), 2),
        "p99_ms": round(samples[min(len(samples) - 1, int(len(samples) * 0.99))], 2),
        "min_ms": round(samples[0], 2),
        "max_ms": round(samples[-1], 2),
        "bytes_per_sec": round(size / (statistics.median(samples) / 1000.0)),
    }, size


def main() -> int:
    global failures

    divisor = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    nominal_baud = 115200 // divisor

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(1)
    srv.settimeout(60.0)

    # Сигнал обвязке, что сокет слушает.
    #
    # Проверять готовность подключением нельзя: пробное соединение займёт
    # единственный слот из listen(1), accept() вернёт его вместо DOSBox,
    # и обмен пойдёт в уже закрытый сокет. Ровно на это я потратил один прогон.
    marker = os.environ.get("S2_LISTENING_MARKER")
    if marker:
        with open(marker, "w", encoding="utf-8") as fh:
            fh.write(str(PORT))

    emit("1..6")
    diag(f"spike S2 host side, port {PORT}, divisor {divisor}, "
         f"nominal baud {nominal_baud}")

    try:
        sock, addr = srv.accept()
    except socket.timeout:
        not_ok("DOSBox не подключился к сокету за 60 с")
        emit("# ИТОГ: транспорт не поднялся")
        return 1

    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ok(f"DOSBox подключился с {addr[0]}")

    # Ждём объявления готовности от DOS-стороны.
    #
    # DOSBox открывает сокет на старте эмулятора, задолго до запуска программы,
    # а инициализация UART очищает FIFO. Всё, что отправлено до этого момента,
    # теряется молча: приёмник переполняется, а биты ошибок сбрасываются первым
    # же чтением LSR. Поэтому обмен начинается строго после байта READY.
    sock.settimeout(0.5)
    handshake_deadline = time.monotonic() + 120.0
    saw_ready = False
    junk = bytearray()
    while time.monotonic() < handshake_deadline and not saw_ready:
        try:
            chunk = sock.recv(4096)
        except socket.timeout:
            continue
        if not chunk:
            break
        for b in chunk:
            if b == READY:
                saw_ready = True
                break
            junk.append(b)

    if saw_ready:
        ok("DOS-сторона объявила готовность")
        if junk:
            diag(f"до готовности пришло мусора: {len(junk)} Б, "
                 f"{bytes(junk[:16]).hex(' ')}")
    else:
        not_ok("готовность от DOS-стороны не получена за 120 с")
        emit("# ИТОГ: рукопожатие не состоялось")
        return 1

    # --- 1. Целостность -----------------------------------------------------
    probe = make_payload(4096)
    sock.sendall(probe)
    echoed = recv_exact(sock, len(probe), timeout=90.0)
    if echoed == probe:
        ok("4096 байт вернулись без искажений")
    elif len(echoed) != len(probe):
        not_ok(f"вернулось {len(echoed)} байт из {len(probe)}")
    else:
        mismatches = sum(1 for a, b in zip(probe, echoed) if a != b)
        not_ok(f"байты искажены, расхождений {mismatches}")

    # --- 2. Латентность по размерам кадра -----------------------------------
    diag("латентность в оба конца по размеру кадра")
    per_frame = {}
    for size in (1, 64, 128, 256, 512, 1024, 2048):
        stats, got = test_latency(sock, size, rounds=20 if size > 1 else 50)
        if stats is None:
            not_ok(f"кадр {size} Б: вернулось {got} байт")
            break
        per_frame[size] = stats
        diag(f"  {size:>5} Б  p50 {stats['p50_ms']:>8.2f} мс  "
             f"p99 {stats['p99_ms']:>8.2f} мс  "
             f"{stats['bytes_per_sec']:>7} Б/с в оба конца")
    else:
        ok("латентность измерена для всех размеров кадра")

    results["per_frame"] = per_frame

    # --- 3. Пропускная способность на большом объёме -------------------------
    bulk_size = 32768
    bulk = make_payload(bulk_size)
    t0 = time.perf_counter()
    sock.sendall(bulk)
    got = recv_exact(sock, bulk_size, timeout=180.0)
    elapsed = time.perf_counter() - t0

    if len(got) == bulk_size:
        # Линия полнодуплексная: за elapsed каждое направление пронесло
        # bulk_size байт независимо от другого. Складывать направления
        # нельзя — получится вдвое завышенная скорость.
        per_dir = bulk_size / elapsed
        results["bulk"] = {
            "bytes_each_way": bulk_size,
            "seconds": round(elapsed, 3),
            "bytes_per_sec_per_direction": round(per_dir),
        }
        diag(f"{bulk_size // 1024} КБ в каждую сторону за {elapsed:.2f} с")
        diag(f"на направление: {per_dir:,.0f} Б/с")
        ok("объёмная передача прошла целиком")
    else:
        not_ok(f"объёмная передача: вернулось {len(got)} из {bulk_size}")

    # --- 4. Сверка с номинальной скоростью ----------------------------------
    # 8N1 — это 10 бит на байт, поэтому номинал в байтах равен baud/10.
    nominal_bps = nominal_baud / 10.0
    if "bulk" in results:
        measured = results["bulk"]["bytes_per_sec_per_direction"]
        ratio = measured / nominal_bps
        results["nominal_bytes_per_sec"] = round(nominal_bps)
        results["ratio_to_nominal"] = round(ratio, 2)
        diag(f"номинал для {nominal_baud} бод 8N1: {nominal_bps:,.0f} Б/с")
        diag(f"измерено / номинал = {ratio:.3f}")
        if ratio > 2.0:
            diag("ВЫВОД: DOSBox не ограничивает скорость по делителю UART")
        elif ratio > 0.9:
            diag("ВЫВОД: DOSBox честно держит номинальную скорость")
        else:
            diag("ВЫВОД: скорость ниже номинала, узкое место не в линии")
        ok("отношение к номинальной скорости посчитано")

    # --- 5. Фиксированные накладные расходы на кадр -------------------------
    # Если латентность кадра в 1 байт и в 128 байт совпадает, значит время
    # съедает не передача, а обслуживание порта. Это определяет минимальный
    # разумный размер кадра.
    if 1 in per_frame and 2048 in per_frame:
        floor_ms = per_frame[1]["p50_ms"]
        big = per_frame[2048]
        wire_ms = 2048 / nominal_bps * 1000.0
        results["fixed_overhead_ms"] = round(floor_ms, 2)
        diag(f"фиксированные накладные на круг: {floor_ms:.2f} мс")
        diag(f"кадр 2048 Б: {big['p50_ms']:.2f} мс при "
             f"{wire_ms:.2f} мс чистой передачи")
        breakeven = floor_ms / 1000.0 * nominal_bps
        results["min_useful_frame_bytes"] = round(breakeven)
        diag(f"кадр меньше ~{breakeven:.0f} Б целиком съедается накладными")
    else:
        not_ok("нечего сравнивать с номиналом")

    # --- завершение ---------------------------------------------------------
    sock.sendall(EOT)
    time.sleep(1.0)
    sock.close()
    srv.close()

    results["divisor"] = divisor
    results["nominal_baud"] = nominal_baud
    out = os.environ.get("S2_JSON", "s2-result.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2)
    diag(f"числа сохранены в {out}")

    if failures:
        emit(f"# ИТОГ: провалов {failures}")
    else:
        emit("# ИТОГ: хостовая часть S2 пройдена")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
