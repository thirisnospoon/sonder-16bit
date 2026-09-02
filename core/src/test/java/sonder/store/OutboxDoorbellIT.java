package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDoorbell;
import sonder.shell.outbox.OutboxEvent;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * Дверной звонок очереди.
 *
 * <p><b>Опрос здесь заведомо не при чём.</b> Интервал выставлен в минуту,
 * а ждём мы секунды: если событие доехало, разбудил его звонок и никто
 * другой. Так проверяется именно уведомление, а не «оно как-нибудь
 * доходит».
 *
 * <p>Ожидание опросом до срока, как и в {@code OutboxScheduleIT}: свойство
 * временнóе по существу, и подменить его нечем. Но срок здесь короче
 * интервала опроса ВО МНОГО РАЗ, и это делает проверку однозначной.
 */
@SpringBootTest(classes = Application.class)
// Контекст со своим слушателем и расписанием не должен переживать класс:
// Spring кэширует контексты, и живой фоновый дренаж уже однажды сломал
// соседний класс тестов.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxDoorbellIT {

    /** Заведомо меньше интервала опроса и заведомо больше круга к базе. */
    private static final long DEADLINE_MS = 5000;

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty("sonder.it.jdbcUrl", ""));
        registry.add("spring.datasource.username",
                () -> System.getProperty("sonder.it.user", "sysdba"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("sonder.it.password", "masterkey"));
        registry.add("sonder.outbox.enabled", () -> "true");
        // Минута опроса и минута до первого захода: за отведённые пять
        // секунд опрос не сработает ни разу.
        registry.add("sonder.outbox.poll-ms", () -> "60000");
        registry.add("sonder.outbox.initial-delay-ms", () -> "60000");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxDoorbell doorbell;

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

    @Test
    @DisplayName("звонок подписан на то самое событие, что шлёт триггер")
    void subscribedToTriggerEvent() throws IOException {
        assertTrue(doorbell.isSubscribed(),
                "звонок не подписался: останется только опрос, и заметить "
                        + "это можно будет только по задержкам");

        // Имя события живёт в двух местах — в триггере и в коде, — и
        // разойтись они могут молча: слушатель будет спать, а выглядеть
        // это будет как «звонок не работает».
        String migration = new String(Files.readAllBytes(Paths.get(
                        "src/main/resources/db/migration/V6__outbox_doorbell.sql")),
                StandardCharsets.UTF_8);
        assertTrue(migration.contains("'" + OutboxDoorbell.EVENT_NAME + "'"),
                "триггер шлёт не то событие, на которое подписан слушатель");
    }

    /**
     * ГЛАВНАЯ ПРОВЕРКА. Опрос выставлен на минуту, ждём пять секунд:
     * доехало — значит, разбудил звонок.
     */
    @Test
    @DisplayName("событие доходит раньше, чем сработал бы опрос")
    void ringWakesDrainBeforePoll() throws Exception {
        long ringsBefore = doorbell.getRings();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Outbox.append(c, new OutboxEvent(
                    "p-1", "post.created", "{\"authorId\":\"u-1\"}", "t-1"));
            c.commit();
        }

        long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
        long rows = 0;
        while (System.nanoTime() < deadline) {
            rows = feedRows();
            if (rows > 0) {
                break;
            }
            Thread.sleep(25);
        }

        assertTrue(rows > 0,
                "за " + DEADLINE_MS + " мс событие не доехало, а опрос стоит на "
                        + "минуту: значит, звонок не разбудил дренаж");
        assertTrue(doorbell.getRings() > ringsBefore,
                "лента наполнилась, но звонков не было: разбудил кто-то другой, "
                        + "и проверка ничего не значит");
        assertEquals(0, pending(), "событие доехало, но осталось в очереди");
    }

    private long pending() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return Outbox.pendingCount(c);
        }
    }
}
