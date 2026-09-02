package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.ErrorCode;
import sonder.shell.app.CommandRunner;
import sonder.shell.app.VersionConflict;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.store.PostStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гейт фазы 6: конфликт версий приводит к переигрыванию команды.
 *
 * <p>Утверждение проверяется целиком, а не по частям. Отдельно «UPDATE с
 * условием по версии затрагивает ноль строк» — арифметика, которая ничего
 * не значит; отдельно «прогонщик повторяет попытку» — тоже. Значение имеет
 * связка: чужое изменение НЕ ТЕРЯЕТСЯ, а команда принимает решение заново,
 * глядя на новое состояние.
 *
 * <p>Потеря чужого изменения — самый дорогой вид дефекта здесь: никакой
 * ошибки не происходит, обе стороны получают «готово», а одно из действий
 * бесследно исчезает. Найти это по логам потом невозможно.
 */
class OptimisticLockIT extends FirebirdSupport {

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
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES ('p-1', 'u-1', 'текст',"
                            + " 'VISIBLE', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        }
    }

    private static int versionOf(String postId) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version FROM posts WHERE id = ?")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static String statusOf(String postId) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM posts WHERE id = ?")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void bumpElsewhere(String postId) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            PostStore.PostState st = PostStore.load(c, postId);
            try {
                PostStore.updateStatus(c, postId, "VISIBLE", st.getVersion());
            } catch (VersionConflict e) {
                throw new IllegalStateException("постороннее изменение не прошло", e);
            }
            c.commit();
        }
    }

    private static CommandRunner runner() {
        return new CommandRunner(FirebirdSupport::connect);
    }

    private static CommandRunner runner(int attempts) {
        return new CommandRunner(FirebirdSupport::connect, attempts);
    }

    @Test
    @DisplayName("без конкурента команда проходит с первой попытки")
    void succeedsWithoutContention() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        runner().run(c -> {
            attempts.incrementAndGet();
            PostStore.PostState st = PostStore.load(c, "p-1");
            assertTrue(st.exists());
            PostStore.updateStatus(c, "p-1", "DELETED", st.getVersion());
            Outbox.append(c, new OutboxEvent("p-1", "post.deleted", "{}", "t-1"));
            return null;
        });

        assertEquals(1, attempts.get(), "команда переигралась без причины");
        assertEquals("DELETED", statusOf("p-1"));
        assertEquals(1, versionOf("p-1"), "версия не выросла");
    }

    /**
     * Главная проверка гейта. Между загрузкой и записью вклинивается чужое
     * изменение; команда обязана переиграться, а чужое изменение — уцелеть.
     */
    @Test
    @DisplayName("чужое изменение между загрузкой и записью вызывает переигрывание")
    void replaysOnConflict() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        runner().run(c -> {
            int n = attempts.incrementAndGet();
            PostStore.PostState st = PostStore.load(c, "p-1");

            // Ровно один раз, на первой попытке, вклиниваемся чужой
            // транзакцией. Она коммитится и двигает версию.
            if (n == 1) {
                try {
                    bumpElsewhere("p-1");
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            PostStore.updateStatus(c, "p-1", "DELETED", st.getVersion());
            Outbox.append(c, new OutboxEvent("p-1", "post.deleted", "{}", "t-2"));
            return null;
        });

        assertEquals(2, attempts.get(),
                "команда не переигралась — значит записала поверх чужого изменения");
        assertEquals("DELETED", statusOf("p-1"));
        // Версия выросла дважды: чужим изменением и нашим.
        assertEquals(2, versionOf("p-1"),
                "чужое изменение потерялось: версия выросла не на два");
    }

    /**
     * Откат неудавшейся попытки обязан унести и событие. Иначе в очереди
     * останется сообщение о действии, которого не было.
     */
    @Test
    @DisplayName("событие неудавшейся попытки не остаётся в очереди")
    void failedAttemptLeavesNoEvent() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        runner().run(c -> {
            int n = attempts.incrementAndGet();
            PostStore.PostState st = PostStore.load(c, "p-1");
            // Событие пишется ДО того, как обнаружится конфликт, — как и
            // было бы в настоящей команде.
            Outbox.append(c, new OutboxEvent("p-1", "post.deleted",
                    "{\"attempt\":" + n + "}", "t-3"));
            if (n == 1) {
                try {
                    bumpElsewhere("p-1");
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            PostStore.updateStatus(c, "p-1", "DELETED", st.getVersion());
            return null;
        });

        assertEquals(2, attempts.get());
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM outbox")) {
            rs.next();
            assertEquals(1, rs.getLong(1),
                    "в очереди осталось событие от откаченной попытки");
        }
    }

    /**
     * Попытки конечны. Бесконечный повтор под нагрузкой — живая блокировка:
     * команда крутится, ресурсы тратятся, а снаружи это выглядит как
     * медленная система, а не как отказ.
     */
    @Test
    @DisplayName("при непрерывном конфликте попытки исчерпываются отказом")
    void givesUpAfterMaxAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        VersionConflict thrown = assertThrows(VersionConflict.class, () ->
                runner(3).run(c -> {
                    attempts.incrementAndGet();
                    PostStore.PostState st = PostStore.load(c, "p-1");
                    // Конкурент вклинивается КАЖДЫЙ раз.
                    try {
                        bumpElsewhere("p-1");
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    PostStore.updateStatus(c, "p-1", "DELETED", st.getVersion());
                    return null;
                }));

        assertEquals(3, attempts.get(), "попыток сделано не столько, сколько задано");
        assertEquals("p-1", thrown.getAggregateId());
        assertEquals("VISIBLE", statusOf("p-1"),
                "команда всё-таки записала, хотя должна была сдаться");

        // Отказ повторяем клиентом: так помечена категория в контракте.
        assertEquals(ErrorCode.STATE_VERSION_CONFLICT, CommandRunner.conflictCode());
        assertTrue(CommandRunner.conflictCode().retryable(),
                "конфликт версий обязан быть повторяемым: иначе клиенту "
                        + "нечего делать с отказом, который сам пройдёт");
        assertFalse(CommandRunner.conflictCode().decidedByCore(),
                "конфликт версий решает оболочка: ядро о хранилище не знает");
    }

    @Test
    @DisplayName("несуществующий пост — это состояние, а не ошибка")
    void missingPostIsState() throws Exception {
        try (Connection c = connect()) {
            PostStore.PostState st = PostStore.load(c, "нет-такого");
            assertFalse(st.exists(), "несуществующий пост объявлен существующим");
            assertEquals("нет-такого", st.getPostId());
        }
    }

    @Test
    @DisplayName("ноль попыток отвергается при создании прогонщика")
    void zeroAttemptsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommandRunner(FirebirdSupport::connect, 0));
    }
}
