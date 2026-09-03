package sonder.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sonder.shell.enrichment.EnrichmentClient;
import sonder.shell.enrichment.EnrichmentServant;
import sonder.shell.enrichment.EnrichmentServer;
import sonder.shell.outbox.Backoff;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.outbox.OutboxPump;
import sonder.shell.projection.FeedProjection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Замер: рассасывается ли отставание.
 *
 * <p><b>Это замер, а не тест, и запускается отдельно</b> —
 * {@code ./sonder drain-bench}. Тесты обязаны укладываться в секунды и
 * отвечать «да» или «нет»; здесь вопрос другой: «за сколько разберётся
 * завал и с какой скоростью». Ответ — число, и держать его в общем
 * прогоне значило бы либо замедлить прогон, либо занизить завал до
 * бессмысленного.
 *
 * <p>Гейт фазы 7 требует, чтобы отставание в десять тысяч событий
 * рассасывалось. Проверяется именно это: очередь набивается целиком, а
 * потом разбирается насосом до дна — ровно так, как разбиралась бы после
 * простоя потребителя.
 *
 * <p><b>Никаких подмен.</b> Обогащение настоящее, через ORB; проекция
 * настоящая; база настоящая. Замер, сделанный на заглушках, измеряет
 * скорость заглушек.
 *
 * <p><b>И соединения настоящие — из пула.</b> Первая редакция замера
 * брала соединения тем же способом, что и тесты: новое на каждый вызов.
 * Вышло двадцать три события в секунду при измеренном потолке в пятьсот,
 * и мерило это не конвейер, а установку соединения с Firebird — по
 * несколько десятков миллисекунд на каждый вызов обогащения. В бою там
 * пул, и замер обязан брать оттуда же, иначе он меряет обвязку.
 *
 * <p>Размер завала задаётся {@code -Dsonder.bench.events}: чтобы можно
 * было и померить десять тысяч, и быстро проверить, что сам замер
 * работает.
 */
@Tag("bench")
class DrainBenchIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    /** Сколько событий кладём в завал. */
    private static final int EVENTS =
            Integer.getInteger("sonder.bench.events", 10000);

    /** Сколько разных постов их порождают. */
    private static final int POSTS = 200;

    /** Сколько подписчиков у автора: фанаут множит записи в ленту. */
    private static final int FOLLOWERS = 5;

    private static EnrichmentServer server;
    private static EnrichmentClient client;
    private static HikariDataSource pool;

    /**
     * Пул — тот же, что поднимает приложение. Размер задан явно: замер,
     * зависящий от умолчания библиотеки, начнёт менять число вместе с
     * обновлением зависимости.
     */
    private static HikariDataSource pool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getProperty("sonder.it.jdbcUrl", ""));
        cfg.setUsername(System.getProperty("sonder.it.user", "sysdba"));
        cfg.setPassword(System.getProperty("sonder.it.password", "masterkey"));
        cfg.setDriverClassName("org.firebirdsql.jdbc.FBDriver");
        cfg.setMaximumPoolSize(10);
        cfg.setPoolName("bench");
        return new HikariDataSource(cfg);
    }

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
        pool = pool();
        server = EnrichmentServer.start(
                new EnrichmentServant(pool::getConnection), "127.0.0.1", 0, null);
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
        if (pool != null) {
            pool.close();
        }
    }

    private static void addUser(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, ?, 'USER', 'ACTIVE', 'x', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setString(3, id);
            ps.setTimestamp(4, Timestamp.from(T0));
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("завал разбирается до дна, и это сколько-то событий в секунду")
    void backlogDrains() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            wipe(c);
            addUser(c, "u-author");
            for (int i = 0; i < FOLLOWERS; i++) {
                addUser(c, "u-f" + i);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO feed_subscriptions (follower_id, target_id,"
                                + " seen_at) VALUES (?, 'u-author', ?)")) {
                    ps.setString(1, "u-f" + i);
                    ps.setTimestamp(2, Timestamp.from(T0));
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES (?, 'u-author', ?, 'VISIBLE',"
                            + " 0, ?)")) {
                for (int i = 0; i < POSTS; i++) {
                    ps.setString(1, "p-" + i);
                    ps.setString(2, "тело поста " + i);
                    ps.setTimestamp(3, Timestamp.from(T0.plusSeconds(i)));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }

        long filledAt = System.nanoTime();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            for (int i = 0; i < EVENTS; i++) {
                Outbox.append(c, new OutboxEvent("p-" + (i % POSTS),
                        "post.created",
                        "{\"authorId\":\"u-author\"}", "t-" + i));
            }
            c.commit();
        }
        long fillMs = (System.nanoTime() - filledAt) / 1_000_000L;

        try (Connection c = connect()) {
            assertEquals(EVENTS, Outbox.pendingCount(c), "завал набран не весь");
        }

        OutboxDrainer drainer = new OutboxDrainer(pool::getConnection,
                new FeedProjection(client.service()), new Backoff(),
                Outbox.DEFAULT_BATCH);
        // Кругов за заход столько же, сколько в бою: замер должен мерить
        // то, что работает, а не удобную для числа настройку.
        OutboxPump pump = new OutboxPump(drainer, Outbox.DEFAULT_BATCH,
                OutboxPump.DEFAULT_MAX_ROUNDS);

        long started = System.nanoTime();
        int published = 0;
        int failed = 0;
        int passes = 0;
        while (true) {
            OutboxPump.Result r = pump.pumpOnce(Instant.now());
            published += r.getPublished();
            failed += r.getFailed();
            passes++;
            if (r.getRounds() == 1 && r.getPublished() == 0 && r.getFailed() == 0) {
                break;
            }
        }
        long drainMs = (System.nanoTime() - started) / 1_000_000L;

        long pending;
        long feedRows;
        try (Connection c = connect()) {
            pending = Outbox.pendingCount(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM feed_entries");
                 java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                feedRows = rs.getLong(1);
            }
        }

        System.out.println();
        System.out.println("==============================================");
        System.out.println(" Рассасывание отставания");
        System.out.println("==============================================");
        System.out.println("  событий в завале:   " + EVENTS);
        System.out.println("  постов:             " + POSTS);
        System.out.println("  подписчиков:        " + FOLLOWERS);
        System.out.println("  набор завала:       " + fillMs + " мс");
        System.out.println("  разбор:             " + drainMs + " мс");
        System.out.println("  заходов насоса:     " + passes);
        System.out.println("  опубликовано:       " + published);
        System.out.println("  отказов:            " + failed);
        System.out.println("  строк в лентах:     " + feedRows);
        if (drainMs > 0) {
            System.out.println("  событий в секунду:  "
                    + (published * 1000L / drainMs));
        }
        System.out.println();

        assertEquals(0, failed, "в завале нашлись отказы: замер меряет не то");
        assertEquals(EVENTS, published, "разобрано не всё");
        assertEquals(0, pending, "очередь не разобрана до дна");
        // Фанаут: автор плюс подписчики на каждый РАЗНЫЙ пост. Повторные
        // события того же поста идемпотентны и строк не добавляют.
        assertEquals((long) POSTS * (FOLLOWERS + 1), feedRows,
                "в лентах не столько строк, сколько должен дать фанаут");
        assertTrue(drainMs > 0, "разбор занял нулевое время: мерить нечего");
    }
}
