package sonder.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.app.ConnectionSource;
import sonder.shell.enrichment.EnrichmentClient;
import sonder.shell.enrichment.EnrichmentServant;
import sonder.shell.enrichment.EnrichmentServer;
import sonder.shell.outbox.Backoff;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.projection.FeedProjection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Утверждения гейта фазы 7, проверенные по отдельности.
 *
 * <p>Гейт говорит: «падение слушателя не теряет событий; дубликаты
 * безвредны; порядок внутри агрегата соблюдён». Первое и третье
 * проверяются здесь; второе — в {@code FeedProjectionIT}, где повтор
 * события не удваивает ленту.
 *
 * <p>Формулировки гейта нарочно проверяются отдельными тестами, а не
 * «заодно»: утверждение, доказанное побочно, легко потерять при
 * переписывании того теста, ради которого оно писалось.
 */
class PipelineGateIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    private static EnrichmentServer server;
    private static EnrichmentClient client;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
        server = EnrichmentServer.start(
                new EnrichmentServant(FirebirdSupport::connect), "127.0.0.1", 0, null);
        client = EnrichmentClient.connect(server.getIor());
    }

    @AfterAll
    static void stopEnrichment() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void seed() throws Exception {
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

    private static void enqueue(String aggregateId, String type, String payload)
            throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            Outbox.append(c, new OutboxEvent(aggregateId, type, payload, "t-1"));
            c.commit();
        }
    }

    private static long feedRows() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM feed_entries");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static long pending() throws SQLException {
        try (Connection c = connect()) {
            return Outbox.pendingCount(c);
        }
    }

    /**
     * Соединение, которое умирает ровно на коммите.
     *
     * <p>Так выглядит падение слушателя в самый неудобный момент: работа
     * сделана, транзакция не закрыта. Подменять здесь нечего — это
     * настоящее соединение, у которого отобран последний шаг.
     */
    private static ConnectionSource dyingOnCommit() {
        return () -> {
            Connection real = connect();
            InvocationHandler h = (proxy, method, args) -> {
                if ("commit".equals(method.getName())) {
                    throw new SQLException("слушатель упал до коммита");
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        };
    }

    /**
     * ГЕЙТ, ПУНКТ ПЕРВЫЙ. Падение до коммита не теряет событий и не
     * оставляет половины проекции.
     *
     * <p>Именно ради этого свойства источник правды — строка в таблице, а
     * не факт вызова. Проверять его надо на настоящей транзакции: рассуждение
     * «ну она же откатится» ровно так же звучало бы и в системе, где
     * проекция пишется другим соединением.
     */
    @Test
    @DisplayName("падение до коммита не теряет событие и не оставляет следа")
    void crashBeforeCommitLosesNothing() throws Exception {
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");

        OutboxDrainer dying = new OutboxDrainer(dyingOnCommit(),
                new FeedProjection(client.service()), new Backoff(), 32);

        assertThrows(SQLException.class, () -> dying.drainOnce(T0),
                "падение на коммите не дошло до вызывающего");

        assertEquals(1, pending(),
                "событие исчезло из очереди вместе с упавшим слушателем");
        assertEquals(0, feedRows(),
                "проекция уцелела без коммита: значит, писалась мимо транзакции");

        // И тот же дренаж, но живой, доводит дело до конца.
        OutboxDrainer alive = new OutboxDrainer(FirebirdSupport::connect,
                new FeedProjection(client.service()), new Backoff(), 32);
        assertEquals(1, alive.drainOnce(T0.plusSeconds(1)).getPublished(),
                "после падения событие не переиграно");
        assertEquals(1, feedRows(), "проекция не построена при повторе");
        assertEquals(0, pending());
    }

    /**
     * ГЕЙТ, ПУНКТ ТРЕТИЙ. Порядок внутри агрегата.
     *
     * <p>Проверяется не «обработчик позван по возрастанию id» — это можно
     * было бы подсмотреть и в реализации, — а НАБЛЮДАЕМОЕ следствие:
     * создание и удаление одного поста, приехавшие вместе, оставляют
     * ленту пустой. Переставь их местами, и удаление отработает по
     * пустому месту, а создание положит строку навсегда.
     */
    @Test
    @DisplayName("создание и удаление одного поста применяются по порядку")
    void orderWithinAggregateIsKept() throws Exception {
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");
        enqueue("p-1", "post.deleted", "{\"deletedBy\":\"u-1\"}");

        OutboxDrainer drainer = new OutboxDrainer(FirebirdSupport::connect,
                new FeedProjection(client.service()), new Backoff(), 32);
        OutboxDrainer.Result r = drainer.drainOnce(T0);

        assertEquals(2, r.getPublished(), "обработаны не оба события");
        assertEquals(0, feedRows(),
                "пост остался в ленте: удаление применилось раньше создания");
    }

    /** Обработчик видит события одного агрегата в порядке их появления. */
    @Test
    @DisplayName("обработчик получает события агрегата в порядке очереди")
    void handlerSeesAggregateEventsInOrder() throws Exception {
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");
        enqueue("p-1", "post.deleted", "{\"deletedBy\":\"u-1\"}");
        enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");

        List<String> seen = new ArrayList<>();
        OutboxDrainer drainer = new OutboxDrainer(FirebirdSupport::connect,
                (c, record) -> seen.add(record.getType()), new Backoff(), 32);
        drainer.drainOnce(T0);

        assertEquals(java.util.Arrays.asList(
                        "post.created", "post.deleted", "post.created"), seen,
                "порядок внутри агрегата не соблюдён");
    }

    /**
     * Порядок держится и через границу пачек: вторая пачка продолжает
     * первую, а не начинает заново.
     */
    @Test
    @DisplayName("порядок держится и на границе пачек")
    void orderSurvivesBatchBoundary() throws Exception {
        for (int i = 0; i < 5; i++) {
            enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");
        }

        List<Long> seen = new ArrayList<>();
        OutboxDrainer drainer = new OutboxDrainer(FirebirdSupport::connect,
                (c, record) -> seen.add(record.getId()), new Backoff(), 2);
        drainer.drainOnce(T0);
        drainer.drainOnce(T0);
        drainer.drainOnce(T0);

        List<Long> sorted = new ArrayList<>(seen);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, seen, "пачки разобраны не по возрастанию");
        assertEquals(5, seen.size(), "разобрано не всё");
        assertTrue(new java.util.TreeSet<>(seen).size() == 5,
                "строка отдана дважды");
    }
}
