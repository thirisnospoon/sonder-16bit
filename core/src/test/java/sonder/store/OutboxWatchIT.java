package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.outbox.Backoff;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Застрявшее событие видно.
 *
 * <p>Проект отказался выкидывать ядовитые события по порогу: код,
 * решающий по числу выбросить данные, теряет их молча. Значит, вся
 * работа — сделать застрявшее видимым, и проверять надо именно это:
 * что счётчик отличает застрявшее от свежего и что называет самое
 * старое.
 *
 * <p>Попытки накапливаются НАСТОЯЩИМ дренажом, а не вписыванием числа в
 * колонку. Вписанное число проверяло бы запрос, а не то, что дренаж
 * действительно доводит ядовитую строку до порога.
 */
class OutboxWatchIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
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

    private static void enqueue(int count) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            for (int i = 0; i < count; i++) {
                Outbox.append(c, new OutboxEvent(
                        "u-1", "user.registered", "{}", "t-" + i));
            }
            c.commit();
        }
    }

    /** Дренаж, который всегда падает: так и накапливаются попытки. */
    private static OutboxDrainer poisonDrainer() {
        return new OutboxDrainer(FirebirdSupport::connect,
                (c, record) -> {
                    throw new IllegalStateException("ядовитое " + record.getId());
                },
                new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(1)), 32);
    }

    private static Outbox.Stuck stuck(int minAttempts) throws SQLException {
        try (Connection c = connect()) {
            return Outbox.stuck(c, minAttempts);
        }
    }

    @Test
    @DisplayName("свежая строка застрявшей не считается")
    void freshIsNotStuck() throws Exception {
        enqueue(3);

        assertEquals(0, stuck(1).getCount(),
                "строка без единой попытки сочтена застрявшей");
        assertEquals(-1, stuck(1).getOldestId(),
                "названо самое старое там, где застрявших нет");
    }

    /**
     * Попытки копятся настоящим дренажом. Каждый заход отодвигает строку
     * на секунду, поэтому время двигаем вперёд — иначе второй заход
     * ничего не возьмёт.
     */
    @Test
    @DisplayName("строка становится застрявшей, когда попыток набралось")
    void poisonBecomesStuck() throws Exception {
        enqueue(2);
        OutboxDrainer drainer = poisonDrainer();

        for (int attempt = 0; attempt < 5; attempt++) {
            OutboxDrainer.Result r =
                    drainer.drainOnce(T0.plus(Duration.ofSeconds(attempt * 2)));
            assertEquals(2, r.getFailed(), "заход " + attempt + " ничего не взял");
        }

        assertEquals(0, stuck(6).getCount(),
                "порог выше накопленного, а строки сочтены застрявшими");

        Outbox.Stuck s = stuck(5);
        assertEquals(2, s.getCount(), "застрявшие не посчитаны");
        assertTrue(s.getOldestId() > 0, "не названо самое старое");

        // Самое старое — то, что легло первым: порядок в очереди по id.
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MIN(id) FROM outbox");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(rs.getLong(1), s.getOldestId(),
                    "названо не самое старое");
        }
    }

    /** Опубликованное застрявшим не считается, сколько бы ни падало раньше. */
    @Test
    @DisplayName("опубликованное не считается застрявшим")
    void publishedIsNotStuck() throws Exception {
        enqueue(1);
        OutboxDrainer poison = poisonDrainer();
        for (int attempt = 0; attempt < 5; attempt++) {
            poison.drainOnce(T0.plus(Duration.ofSeconds(attempt * 2)));
        }
        assertEquals(1, stuck(5).getCount(), "строка не дошла до порога");

        OutboxDrainer good = new OutboxDrainer(FirebirdSupport::connect,
                (c, record) -> { }, new Backoff(), 32);
        good.drainOnce(T0.plus(Duration.ofSeconds(60)));

        assertEquals(0, stuck(5).getCount(),
                "опубликованная строка осталась застрявшей: сторож будет "
                        + "звать человека к тому, что уже прошло");
    }
}
