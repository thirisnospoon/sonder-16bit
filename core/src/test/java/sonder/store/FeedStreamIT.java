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
import sonder.shell.projection.FeedProjection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Порядок рассылки относительно коммита.
 *
 * <p>Контракт операции {@code subscribe} говорит: поток «питается из того
 * же конвейера outbox, что и проекции, поэтому клиент не увидит события
 * раньше, чем оно попадёт в чтение». Это утверждение о ПОРЯДКЕ, и
 * проверить его можно только одним способом: заглянуть в базу из
 * получателя рассылки, СВОИМ соединением. Незакоммиченную транзакцию
 * чужое соединение не видит — значит, если оттуда видно строку ленты,
 * коммит уже был.
 *
 * <p>Настоящих открытых соединений здесь нет: они проверяются по HTTP.
 * Здесь предмет — момент вызова, и подменять его нечем.
 */
class FeedStreamIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    /** Получатель рассылки, который смотрит в базу своим соединением. */
    static final class Spy implements OutboxDrainer.Published {
        final List<String> seen = new ArrayList<>();
        /** Сколько строк ленты было видно СНАРУЖИ в момент рассылки. */
        long feedRowsAtCall = -1;
        RuntimeException throwThis;

        @Override
        public void onPublished(List<OutboxRecord> records) {
            for (OutboxRecord r : records) {
                seen.add(r.getType() + ":" + r.getAggregateId());
            }
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM feed_entries");
                 ResultSet rs = ps.executeQuery()) {
                feedRowsAtCall = rs.next() ? rs.getLong(1) : -1;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            if (throwThis != null) {
                throw throwThis;
            }
        }
    }

    private Spy spy;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        spy = new Spy();
        try (Connection c = connect()) {
            wipe(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, nick, display_name, role, status,"
                            + " password_hash, version, created_at)"
                            + " VALUES ('u-1', 'andrey', 'Андрей', 'USER',"
                            + " 'ACTIVE', 'x', 0, ?)")) {
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

    private OutboxDrainer drainer(OutboxDrainer.Handler handler) {
        return new OutboxDrainer(FirebirdSupport::connect, handler,
                new Backoff(), 32, spy);
    }

    private static void enqueue(String aggregateId, String type, String payload)
            throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            Outbox.append(c, new OutboxEvent(aggregateId, type, payload, "t-1"));
            c.commit();
        }
    }

    /**
     * ГЛАВНАЯ ПРОВЕРКА. В момент рассылки строка ленты уже видна снаружи
     * — значит, коммит был раньше. Позови рассылку из обработчика, и
     * счётчик показал бы ноль: чужое соединение незакоммиченного не видит.
     */
    @Test
    @DisplayName("рассылка зовётся после коммита, а не внутри транзакции")
    void notifiedAfterCommit() throws Exception {
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");

        drainer(new FeedProjection()).drainOnce(T0);

        assertEquals(java.util.Arrays.asList("post.created:p-1"), spy.seen,
                "рассылка получила не то");
        assertEquals(1, spy.feedRowsAtCall,
                "в момент рассылки лента снаружи была пуста: значит, звали "
                        + "внутри транзакции, и клиент пошёл бы читать "
                        + "новость, которой ещё нет");
    }

    /** Отказавшие строки в рассылку не попадают: их никто не публиковал. */
    @Test
    @DisplayName("отказавшее событие не рассылается")
    void failedNotNotified() throws Exception {
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");

        OutboxDrainer.Result r = drainer((c, record) -> {
            throw new IllegalStateException("ядовитое");
        }).drainOnce(T0);

        assertEquals(1, r.getFailed());
        assertTrue(spy.seen.isEmpty(),
                "клиенту разослали то, что не было опубликовано");
    }

    /** Пустой круг не тревожит получателя лишним вызовом. */
    @Test
    @DisplayName("пустой круг рассылку не зовёт")
    void emptyRoundIsQuiet() throws Exception {
        drainer(new FeedProjection()).drainOnce(T0);

        assertEquals(-1, spy.feedRowsAtCall, "рассылка позвана впустую");
        assertTrue(spy.seen.isEmpty());
    }

    /**
     * Отказ рассылки не отказ дренажа. Событие уже записано, повторять
     * его нельзя: повтор прогнал бы обработчик второй раз ради того,
     * чтобы кому-то дошло уведомление.
     */
    @Test
    @DisplayName("отказ рассылки не откатывает и не повторяет событие")
    void notifyFailureDoesNotBreakDrain() throws Exception {
        spy.throwThis = new IllegalStateException("соединение оборвалось");
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");

        OutboxDrainer.Result r = drainer(new FeedProjection()).drainOnce(T0);

        assertEquals(1, r.getPublished(), "отказ рассылки сорвал публикацию");
        try (Connection c = connect()) {
            assertEquals(0, Outbox.pendingCount(c),
                    "событие вернулось в очередь из-за неудачной рассылки");
        }
        assertFalse(spy.seen.isEmpty());
    }
}
