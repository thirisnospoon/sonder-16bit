package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.outbox.Backoff;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.outbox.OutboxPump;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Политика дренажа: сколько кругов делать за один заход.
 *
 * <p>Проверяется вызовом, а не ожиданием тика. Расписание — забота
 * фреймворка; здесь предметом является решение «полная пачка означает,
 * что очередь не разобрана, и надо идти на второй круг», и его надо
 * уметь проверить, не засыпая.
 */
class OutboxPumpIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");
    private static final int BATCH = 4;

    private final AtomicInteger handled = new AtomicInteger();
    private OutboxDrainer.Handler handler;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        handled.set(0);
        handler = (c, record) -> handled.incrementAndGet();
        try (Connection c = connect()) {
            wipe(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, nick, display_name, role, status,"
                            + " password_hash, version, created_at)"
                            + " VALUES ('u-1', 'u1', 'Автор', 'USER', 'ACTIVE',"
                            + " 'x', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(T0));
                ps.executeUpdate();
            }
        }
    }

    private void enqueue(int count) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            for (int i = 0; i < count; i++) {
                Outbox.append(c, new OutboxEvent(
                        "u-1", "user.registered", "{}", "t-" + i));
            }
            c.commit();
        }
    }

    private OutboxPump pump(int maxRounds) {
        OutboxDrainer drainer = new OutboxDrainer(
                FirebirdSupport::connect, handler, new Backoff(), BATCH);
        return new OutboxPump(drainer, BATCH, maxRounds);
    }

    /**
     * Завал разгребается за один заход, а не по пачке в тик. Иначе тысяча
     * событий при пачке в тридцать две и секундном интервале разбиралась
     * бы полминуты, в основном простаивая.
     */
    @Test
    @DisplayName("полные пачки идут подряд, пока очередь не разобрана")
    void fullBatchesContinue() throws Exception {
        enqueue(10);

        OutboxPump.Result r = pump(8).pumpOnce(T0);

        assertEquals(10, r.getPublished(), "разобрана не вся очередь");
        assertEquals(10, handled.get(), "обработчик позван не на всех");
        // 4 + 4 + 2: третий круг вернул неполную пачку и стал последним.
        assertEquals(3, r.getRounds(), "кругов сделано не столько, сколько нужно");
        assertFalse(r.isMoreLikely(), "очередь разобрана, а заход говорит иначе");
    }

    /**
     * Заход обязан кончаться. Без предела один поток намертво занят
     * очередью, которую пополняют быстрее, чем он разгребает, и ни
     * метрики, ни остановка приложения до него не достучатся.
     */
    @Test
    @DisplayName("заход упирается в предел кругов и говорит об этом")
    void stopsAtLimit() throws Exception {
        enqueue(20);

        OutboxPump.Result r = pump(2).pumpOnce(T0);

        assertEquals(2, r.getRounds(), "предел кругов не соблюдён");
        assertEquals(8, r.getPublished(), "за два круга разобрано не две пачки");
        assertTrue(r.isMoreLikely(),
                "очередь не разобрана, а заход об этом молчит");

        try (Connection c = connect()) {
            assertEquals(12, Outbox.pendingCount(c), "в очереди осталось не то");
        }
    }

    /** Неполная пачка означает дно очереди: второй круг стоил бы зря. */
    @Test
    @DisplayName("неполная пачка останавливает заход сразу")
    void partialBatchStops() throws Exception {
        enqueue(2);

        OutboxPump.Result r = pump(8).pumpOnce(T0);

        assertEquals(1, r.getRounds(), "сделан лишний круг к базе");
        assertEquals(2, r.getPublished());
    }

    /** Пустая очередь: один круг, ничего не сделано, и это не отказ. */
    @Test
    @DisplayName("пустая очередь стоит одного круга и не считается отказом")
    void emptyQueue() throws Exception {
        OutboxPump.Result r = pump(8).pumpOnce(T0);

        assertEquals(1, r.getRounds());
        assertEquals(0, r.getPublished());
        assertEquals(0, r.getFailed());
        assertFalse(r.isMoreLikely());
        assertEquals(0, handled.get(), "обработчик позван на пустой очереди");
    }

    /**
     * Отказ обработчика не останавливает заход: строки, которые он не
     * взял, отложены, и следующая пачка приходит НЕПОЛНОЙ — иначе заход
     * крутился бы по одним и тем же неудачным строкам до предела кругов.
     */
    @Test
    @DisplayName("отложенные строки не возвращаются в тот же заход")
    void failedRowsDoNotSpin() throws Exception {
        handler = (c, record) -> {
            throw new IllegalStateException("ядовитое " + record.getId());
        };
        enqueue(10);

        OutboxPump.Result r = pump(8).pumpOnce(T0);

        assertEquals(0, r.getPublished());
        assertEquals(10, r.getFailed(), "не все строки получили попытку");
        assertEquals(3, r.getRounds(),
                "заход крутился по уже отложенным строкам");

        try (Connection c = connect()) {
            assertTrue(Outbox.claim(c, 32, T0).isEmpty(),
                    "отложенные строки всё ещё выдаются в тот же момент времени");
        }
    }

    /** Сколько раз обработчик увидел одну и ту же строку за заход. */
    @Test
    @DisplayName("за заход каждая строка отдаётся обработчику один раз")
    void eachRowOnce() throws Exception {
        final java.util.List<Long> seen = new java.util.ArrayList<>();
        handler = (c, record) -> seen.add(record.getId());
        enqueue(9);

        pump(8).pumpOnce(T0);

        assertEquals(9, seen.size(), "строк роздано не столько, сколько было");
        assertEquals(9, new java.util.TreeSet<>(seen).size(),
                "строка отдана обработчику дважды за один заход");
    }
}
