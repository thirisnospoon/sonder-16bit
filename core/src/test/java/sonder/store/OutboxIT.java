package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.outbox.OutboxRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Очередь исходящих событий против настоящего Firebird.
 *
 * <p>Здесь проверяются два утверждения, на которых стоит весь конвейер, и
 * оба таковы, что «вроде работает» ничего не значит.
 *
 * <p><b>Первое: событие и изменение агрегата коммитятся вместе или не
 * коммитятся вовсе.</b> Если между ними есть окно, система будет считать
 * действие совершённым, а мир о нём не узнает — и проявится это под
 * нагрузкой, на одной команде из тысячи.
 *
 * <p><b>Второе: два потребителя берут РАЗНЫЕ строки.</b> Без
 * {@code SKIP LOCKED} второй встал бы на строке первого, и параллельный
 * дренаж стал бы последовательным, притворяясь параллельным. Метрики при
 * этом выглядели бы прилично.
 */
class OutboxIT extends FirebirdSupport {

    /**
     * Время, от которого считает очередь. Здесь оно неподвижно: этот
     * модуль проверяет примитивы хранения, а отсрочку повтора — дренажёр
     * в {@link OutboxDrainerIT}, и там время двигают намеренно.
     */
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    /** Срок в прошлом: строка снова доступна сразу. */
    private static final Instant PAST = NOW.minusSeconds(1);

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = connect()) {
            wipe(c);
        }
    }

    private static void insertUser(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, 'Автор', 'USER', 'ACTIVE', 'x', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static void insertPost(Connection c, String id, String author)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO posts (id, author_id, body, status, version, created_at)"
                        + " VALUES (?, ?, 'текст', 'VISIBLE', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, author);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static long count(String table) throws SQLException {
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    @DisplayName("агрегат и событие коммитятся вместе")
    void committedTogether() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            insertUser(c, "u-1");
            insertPost(c, "p-1", "u-1");
            Outbox.append(c, new OutboxEvent("p-1", "post.created",
                    "{\"authorId\":\"u-1\"}", "t-1"));
            c.commit();
        }
        assertEquals(1, count("posts"), "пост не сохранился");
        assertEquals(1, count("outbox"), "событие не сохранилось");
    }

    /**
     * Откат обязан унести и событие. Событие, пережившее откат агрегата, —
     * это сообщение миру о том, чего не произошло, и восстановить из него
     * правду уже нельзя.
     */
    @Test
    @DisplayName("откат уносит и агрегат, и событие")
    void rolledBackTogether() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            insertUser(c, "u-2");
            insertPost(c, "p-2", "u-2");
            Outbox.append(c, new OutboxEvent("p-2", "post.created", "{}", "t-2"));
            c.rollback();
        }
        assertEquals(0, count("posts"), "пост пережил откат");
        assertEquals(0, count("outbox"), "событие пережило откат");
    }

    @Test
    @DisplayName("взятая строка помечается опубликованной и больше не выдаётся")
    void publishedOnce() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            insertUser(c, "u-3");
            Outbox.append(c, new OutboxEvent("u-3", "user.registered", "{}", "t-3"));
            c.commit();
        }

        long id;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            List<OutboxRecord> batch = Outbox.claim(c, Outbox.DEFAULT_BATCH, NOW);
            assertEquals(1, batch.size(), "не выдалась единственная строка");
            id = batch.get(0).getId();
            assertTrue(id > 0, "идентификатор не выдан базой");
            assertEquals("user.registered", batch.get(0).getType());
            Outbox.markPublished(c, id, NOW);
            c.commit();
        }

        try (Connection c = connect()) {
            c.setAutoCommit(false);
            assertTrue(Outbox.claim(c, Outbox.DEFAULT_BATCH, NOW).isEmpty(),
                    "опубликованная строка выдалась снова");
            assertEquals(0, Outbox.pendingCount(c));
            c.commit();
        }
    }

    @Test
    @DisplayName("неудачная публикация считается, строка остаётся в очереди")
    void failureCounted() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            insertUser(c, "u-4");
            Outbox.append(c, new OutboxEvent("u-4", "user.registered", "{}", null));
            c.commit();
        }

        long id;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            id = Outbox.claim(c, 1, NOW).get(0).getId();
            // Срок в прошлом: здесь проверяется, что строка не выпала из
            // очереди, а не отсрочка — она в OutboxDrainerIT.
            Outbox.recordFailure(c, id, PAST);
            c.commit();
        }

        try (Connection c = connect()) {
            c.setAutoCommit(false);
            List<OutboxRecord> again = Outbox.claim(c, 1, NOW);
            assertEquals(1, again.size(), "строка выпала из очереди после неудачи");
            assertEquals(1, again.get(0).getAttempts(), "попытка не посчиталась");
            c.commit();
        }
    }

    /**
     * Главная проверка модуля. Два потребителя берут пачки одновременно и
     * обязаны получить непересекающиеся множества.
     *
     * <p>Проверяется именно НЕПЕРЕСЕЧЕНИЕ, а не «второй что-то получил»:
     * без {@code SKIP LOCKED} второй либо повис бы, либо взял те же строки,
     * и обе беды выглядят как рабочая система, пока событие не уйдёт дважды.
     */
    @Test
    @DisplayName("два потребителя берут непересекающиеся строки")
    void concurrentConsumersGetDisjointRows() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            insertUser(c, "u-5");
            for (int i = 0; i < 10; i++) {
                Outbox.append(c, new OutboxEvent("u-5", "user.touched",
                        "{\"n\":" + i + "}", "t-5"));
            }
            c.commit();
        }

        try (Connection first = connect(); Connection second = connect()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            List<OutboxRecord> a = Outbox.claim(first, 4, NOW);
            List<OutboxRecord> b = Outbox.claim(second, 4, NOW);

            assertFalse(a.isEmpty(), "первый потребитель не взял ничего");
            assertFalse(b.isEmpty(),
                    "второй потребитель не взял ничего — похоже, SKIP LOCKED "
                            + "не сработал и он ждал строк первого");

            Set<Long> ids = new HashSet<>();
            for (OutboxRecord r : a) {
                assertTrue(ids.add(r.getId()), "первый выдал дубликат");
            }
            for (OutboxRecord r : b) {
                assertTrue(ids.add(r.getId()),
                        "строка " + r.getId() + " досталась обоим — событие уйдёт дважды");
            }
            assertEquals(a.size() + b.size(), ids.size());
            assertNotEquals(0, ids.size());

            first.rollback();
            second.rollback();
        }
    }

    @Test
    @DisplayName("размер пачки соблюдается")
    void batchSizeRespected() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            insertUser(c, "u-6");
            for (int i = 0; i < 10; i++) {
                Outbox.append(c, new OutboxEvent("u-6", "user.touched", "{}", null));
            }
            c.commit();
        }

        try (Connection c = connect()) {
            c.setAutoCommit(false);
            assertEquals(3, Outbox.claim(c, 3, NOW).size(),
                    "пачка больше запрошенной держит блокировки дольше нужного");
            c.rollback();
        }
    }
}
