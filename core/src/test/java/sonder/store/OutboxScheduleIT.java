package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Дренаж запускается САМ.
 *
 * <p>Единственный тест во всём проекте, который ждёт. Все прочие зовут
 * дренаж явно — и потому ни один из них не заметил бы, что расписание не
 * настроено вовсе. Проверяемое здесь свойство временнóе по существу:
 * «событие доходит без того, чтобы его кто-то толкал», и подменить
 * ожидание тут нечем.
 *
 * <p>Ожидание при этом не «поспать и посмотреть», а опрос до срока: тест
 * заканчивается, как только условие выполнено, и падает по сроку с
 * внятной причиной. Разница существенная — фиксированный сон либо
 * замедляет прогон, либо начинает мигать на медленной машине.
 *
 * <p>Интервал опроса здесь укорочен до двухсот миллисекунд. В бою он
 * секунда, и ждать её в тесте незачем: проверяется, что расписание
 * работает, а не какое у него число.
 */
@SpringBootTest(classes = Application.class)
// Контекст закрывается вместе с классом, и это не гигиена, а исправление
// найденного дефекта. Spring КЭШИРУЕТ контексты между классами тестов:
// поднятое здесь расписание с интервалом в двести миллисекунд продолжало
// работать до конца прогона и разбирало очередь в чужих тестах. Упал при
// этом SchemaIT — класс, к дренажу отношения не имеющий вовсе.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext
                .ClassMode.AFTER_CLASS)
class OutboxScheduleIT {

    /** Сколько ждать доставки. Двадцать интервалов: медленно, но конечно. */
    private static final long DEADLINE_MS = 4000;

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty("sonder.it.jdbcUrl", ""));
        registry.add("spring.datasource.username",
                () -> System.getProperty("sonder.it.user", "sysdba"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("sonder.it.password", "masterkey"));
        // Здесь дренаж, наоборот, ВКЛЮЧЁН — ради него всё и затеяно.
        registry.add("sonder.outbox.enabled", () -> "true");
        registry.add("sonder.outbox.poll-ms", () -> "200");
        registry.add("sonder.outbox.initial-delay-ms", () -> "100");
    }

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seed() throws Exception {
        assumeTrue(!System.getProperty("sonder.it.jdbcUrl", "").isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");

        try (Connection c = dataSource.getConnection()) {
            FirebirdSupport.wipe(c);
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

    private long feedRows() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM feed_entries");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private long pending() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return Outbox.pendingCount(c);
        }
    }

    @Test
    @DisplayName("положенное в очередь доходит до ленты без посторонней помощи")
    void queueDrainsByItself() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Outbox.append(c, new OutboxEvent(
                    "p-1", "post.created", "{\"authorId\":\"u-1\"}", "t-1"));
            c.commit();
        }
        assertEquals(1, pending(), "событие не легло в очередь");

        long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
        long rows = 0;
        while (System.nanoTime() < deadline) {
            rows = feedRows();
            if (rows > 0) {
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(rows > 0,
                "за " + DEADLINE_MS + " мс событие не доехало до ленты: "
                        + "расписание дренажа не работает, и в бою очередь "
                        + "копилась бы молча");
        assertEquals(0, pending(), "событие доехало, но осталось в очереди");
    }
}
