package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.outbox.Backoff;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.outbox.OutboxRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дренаж очереди исходящих.
 *
 * <p>Проверяется не «событие уехало», а поведение на отказах: одна
 * упавшая строка не уносит пачку, не оставляет за собой половины записи
 * и не возвращается в очередь немедленно. Всё три — то, из-за чего
 * очереди ломаются в проде, и ни одно не видно на удачном пути.
 *
 * <p>Время везде параметром. Очередь с отсрочками, берущая часы у себя,
 * проверялась бы ожиданием, а ожидание в тесте — это не проверка.
 */
class OutboxDrainerIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    /** Обработчик, который пишет в ту же транзакцию и умеет падать. */
    static final class Recording implements OutboxDrainer.Handler {
        final List<Long> seen = new ArrayList<>();
        /** Идентификаторы, на которых падать. */
        final List<Long> poison = new ArrayList<>();
        /** Писать ли след в базу до падения. */
        boolean writesBeforeFailing;

        @Override
        public void handle(Connection c, OutboxRecord record) throws Exception {
            seen.add(record.getId());

            if (writesBeforeFailing || !poison.contains(record.getId())) {
                // Проекция, которую пишет настоящий обработчик. Таблицы
                // проекций ещё нет, поэтому след кладётся в комментарии:
                // важна не таблица, а то, что запись идёт в ТУ ЖЕ
                // транзакцию и обязана уехать вместе с ней или никак.
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO comments (id, post_id, author_id, body,"
                                + " version, created_at)"
                                + " VALUES (?, 'p-1', 'u-1', ?, 0, ?)")) {
                    ps.setString(1, "след-" + record.getId());
                    ps.setString(2, "событие " + record.getId());
                    ps.setTimestamp(3, Timestamp.from(T0));
                    ps.executeUpdate();
                }
            }

            if (poison.contains(record.getId())) {
                throw new IllegalStateException("ядовитое событие " + record.getId());
            }
        }
    }

    private Recording handler;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        handler = new Recording();
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
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES ('p-1', 'u-1', 'текст',"
                            + " 'VISIBLE', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(T0));
                ps.executeUpdate();
            }
        }
    }

    /** Кладёт события в очередь и возвращает их идентификаторы по порядку. */
    private List<Long> enqueue(int count) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            for (int i = 1; i <= count; i++) {
                Outbox.append(c, new OutboxEvent(
                        "p-1", "post.created", "{\"n\":" + i + "}", "t-" + i));
            }
            c.commit();
        }
        List<Long> ids = new ArrayList<>();
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM outbox ORDER BY id")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private OutboxDrainer drainer(Backoff backoff, int batch) {
        return new OutboxDrainer(FirebirdSupport::connect, handler, backoff, batch);
    }

    private OutboxDrainer drainer() {
        return drainer(new Backoff(), Outbox.DEFAULT_BATCH);
    }

    private static int attemptsOf(long id) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT attempts FROM outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static Timestamp nextAttemptOf(long id) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT next_attempt_at FROM outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1) : null;
            }
        }
    }

    private static long tracesInDb() throws SQLException {
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM comments")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    @DisplayName("пачка разбирается по порядку и помечается опубликованной")
    void drainsInOrder() throws Exception {
        List<Long> ids = enqueue(3);

        OutboxDrainer.Result r = drainer().drainOnce(T0);

        assertEquals(3, r.getClaimed());
        assertEquals(3, r.getPublished());
        assertEquals(0, r.getFailed());
        assertEquals(ids, handler.seen, "порядок не по id");

        try (Connection c = connect()) {
            assertEquals(0, Outbox.pendingCount(c), "очередь не разобрана");
        }
        assertEquals(3, tracesInDb(), "обработчик записал не всё");
    }

    @Test
    @DisplayName("опубликованное не выдаётся во второй раз")
    void publishedNotRedelivered() throws Exception {
        enqueue(2);
        drainer().drainOnce(T0);
        handler.seen.clear();

        OutboxDrainer.Result again = drainer().drainOnce(T0.plusSeconds(1));

        assertEquals(0, again.getClaimed());
        assertTrue(handler.seen.isEmpty(), "событие уехало дважды");
    }

    /**
     * Одно ядовитое событие не останавливает очередь. Иначе оно
     * выглядело бы как «система встала», а не как «одно событие плохое».
     */
    @Test
    @DisplayName("упавшая строка не уносит пачку")
    void poisonDoesNotStopBatch() throws Exception {
        List<Long> ids = enqueue(3);
        handler.poison.add(ids.get(1));

        OutboxDrainer.Result r = drainer().drainOnce(T0);

        assertEquals(3, r.getClaimed());
        assertEquals(2, r.getPublished());
        assertEquals(1, r.getFailed());
        assertEquals(3, handler.seen.size(), "обработчик вызван не на всех");

        try (Connection c = connect()) {
            assertEquals(1, Outbox.pendingCount(c),
                    "в очереди осталась не одна строка");
        }
        assertEquals(1, attemptsOf(ids.get(1)), "попытка не засчитана");
        assertEquals(0, attemptsOf(ids.get(0)), "попытка засчитана удачной строке");
    }

    /**
     * ГЛАВНАЯ ПРОВЕРКА. Обработчик успел записать проекцию и упал.
     * Записанное обязано исчезнуть: при повторе оно легло бы вторым
     * экземпляром, а до повтора система выглядела бы обработавшей
     * событие, которое числится необработанным.
     */
    @Test
    @DisplayName("упавший обработчик не оставляет за собой половины записи")
    void failedHandlerLeavesNothing() throws Exception {
        List<Long> ids = enqueue(2);
        handler.poison.add(ids.get(0));
        handler.writesBeforeFailing = true;

        OutboxDrainer.Result r = drainer().drainOnce(T0);

        assertEquals(1, r.getPublished());
        assertEquals(1, r.getFailed());

        assertEquals(1, tracesInDb(),
                "след упавшей строки уцелел: при повторе он ляжет дважды");

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM comments WHERE id = ?")) {
            ps.setString(1, "след-" + ids.get(0));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "откат до точки не сработал");
            }
        }

        // Отсчёт попытки пережил откат: иначе ядовитое событие вернулось
        // бы в следующую пачку с прежним счётчиком.
        assertEquals(1, attemptsOf(ids.get(0)), "откат унёс отсчёт попытки");
    }

    /**
     * Отсрочка держит строку. Без неё неудачная строка попадала бы в
     * следующую же пачку, и потребитель молотил бы по ней с полной
     * скоростью дренажа.
     */
    @Test
    @DisplayName("упавшая строка не выдаётся раньше срока и выдаётся после")
    void backoffHoldsRow() throws Exception {
        List<Long> ids = enqueue(1);
        handler.poison.add(ids.get(0));

        Backoff tenMinutes = new Backoff(Duration.ofMinutes(10), Duration.ofHours(1));
        drainer(tenMinutes, 8).drainOnce(T0);

        Timestamp notBefore = nextAttemptOf(ids.get(0));
        assertNotNull(notBefore, "срок повтора не записан");
        assertEquals(T0.plus(Duration.ofMinutes(10)), notBefore.toInstant(),
                "срок повтора не тот, что дала отсрочка");

        handler.seen.clear();
        OutboxDrainer.Result tooEarly = drainer(tenMinutes, 8)
                .drainOnce(T0.plus(Duration.ofMinutes(9)));
        assertEquals(0, tooEarly.getClaimed(), "строка выдана раньше срока");
        assertTrue(handler.seen.isEmpty(), "обработчик позван раньше срока");

        OutboxDrainer.Result inTime = drainer(tenMinutes, 8)
                .drainOnce(T0.plus(Duration.ofMinutes(10)));
        assertEquals(1, inTime.getClaimed(), "строка не выдана после срока");
        assertEquals(2, attemptsOf(ids.get(0)), "вторая попытка не засчитана");
    }

    /** Удачная публикация срок повтора не оставляет. */
    @Test
    @DisplayName("успешная строка уходит без срока повтора")
    void successLeavesNoDeadline() throws Exception {
        List<Long> ids = enqueue(1);
        drainer().drainOnce(T0);

        assertNull(nextAttemptOf(ids.get(0)), "у опубликованной строки есть срок");
        assertEquals(0, attemptsOf(ids.get(0)), "успеху засчитана попытка");
    }

    /** Размер пачки соблюдается: блокировки не держатся дольше нужного. */
    @Test
    @DisplayName("за круг берётся не больше пачки")
    void batchIsRespected() throws Exception {
        enqueue(5);

        OutboxDrainer.Result first = drainer(new Backoff(), 2).drainOnce(T0);
        assertEquals(2, first.getClaimed());
        assertTrue(first.isFull(2), "полная пачка не распознана");

        OutboxDrainer.Result second = drainer(new Backoff(), 2).drainOnce(T0);
        assertEquals(2, second.getClaimed());

        OutboxDrainer.Result third = drainer(new Backoff(), 2).drainOnce(T0);
        assertEquals(1, third.getClaimed());
        assertTrue(!third.isFull(2), "неполная пачка сочтена полной");
    }
}
