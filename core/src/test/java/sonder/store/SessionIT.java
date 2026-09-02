package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.auth.Passwords;
import sonder.shell.auth.SessionStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сессии против настоящей базы.
 *
 * <p>Часы здесь подаются параметром, а не берутся из {@code Instant.now()}
 * внутри. Иначе проверить истечение можно было бы только ожиданием, и тест
 * либо длился бы двенадцать часов, либо проверял не то.
 */
class SessionIT extends FirebirdSupport {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

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
                            + " ?, 0, ?)")) {
                ps.setString(1, Passwords.hash("тайна"));
                ps.setTimestamp(2, Timestamp.from(NOW));
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("выданная сессия опознаёт пользователя")
    void openAndResolve() throws Exception {
        String token;
        try (Connection c = connect()) {
            token = SessionStore.open(c, "u-1", NOW);
        }
        try (Connection c = connect()) {
            assertEquals("u-1", SessionStore.userOf(c, token, NOW));
        }
    }

    /**
     * Истёкшая сессия не опознаётся. Проверяется сдвигом часов, а не
     * ожиданием: тест, который ждёт двенадцать часов, не запускают.
     */
    @Test
    @DisplayName("истёкшая сессия не опознаётся")
    void expiredSessionRejected() throws Exception {
        String token;
        try (Connection c = connect()) {
            token = SessionStore.open(c, "u-1", NOW);
        }
        Instant afterTtl = NOW.plus(SessionStore.TTL).plusSeconds(1);
        try (Connection c = connect()) {
            assertNull(SessionStore.userOf(c, token, afterTtl),
                    "сессия пережила свой срок");
            // За секунду до срока — ещё жива. Граница проверяется с обеих
            // сторон: иначе «истекает» могло бы означать «истекает сразу».
            assertEquals("u-1", SessionStore.userOf(c, token,
                    NOW.plus(SessionStore.TTL).minusSeconds(1)));
        }
    }

    /**
     * Несуществующий и истёкший токены отвечают ОДИНАКОВО. Различие в
     * ответах сообщало бы, существовал ли токен, — то есть подтверждало бы
     * угаданный.
     */
    @Test
    @DisplayName("несуществующий токен неотличим от истёкшего")
    void unknownTokenIndistinguishable() throws Exception {
        try (Connection c = connect()) {
            assertNull(SessionStore.userOf(c, "нет-такого-токена", NOW));
            assertNull(SessionStore.userOf(c, "", NOW));
            assertNull(SessionStore.userOf(c, null, NOW));
        }
    }

    @Test
    @DisplayName("отозванная сессия перестаёт действовать")
    void revoked() throws Exception {
        String token;
        try (Connection c = connect()) {
            token = SessionStore.open(c, "u-1", NOW);
            assertTrue(SessionStore.revoke(c, token));
            assertNull(SessionStore.userOf(c, token, NOW),
                    "отозванная сессия всё ещё действует");
            assertFalse(SessionStore.revoke(c, token),
                    "повторный отзыв отчитался об успехе");
        }
    }

    @Test
    @DisplayName("две сессии одного пользователя различны и обе действуют")
    void twoSessionsCoexist() throws Exception {
        try (Connection c = connect()) {
            String a = SessionStore.open(c, "u-1", NOW);
            String b = SessionStore.open(c, "u-1", NOW);
            assertNotEquals(a, b, "две сессии получили один токен");
            assertEquals("u-1", SessionStore.userOf(c, a, NOW));
            assertEquals("u-1", SessionStore.userOf(c, b, NOW));
        }
    }

    /**
     * Уборка истёкшего — отдельная операция. Удаление на чтении
     * превратило бы безобидный запрос в запись, а под нагрузкой — в запись
     * на каждый запрос.
     */
    @Test
    @DisplayName("уборка удаляет только истёкшее")
    void purgeRemovesOnlyExpired() throws Exception {
        try (Connection c = connect()) {
            SessionStore.open(c, "u-1", NOW.minus(Duration.ofDays(1)));
            String fresh = SessionStore.open(c, "u-1", NOW);

            assertEquals(1, SessionStore.purgeExpired(c, NOW),
                    "убрано не одно истёкшее");
            assertEquals("u-1", SessionStore.userOf(c, fresh, NOW),
                    "уборка задела действующую сессию");
        }
    }

    /**
     * Пароль в базе лежит хэшем. Проверка тривиальная и именно поэтому
     * нужная: хранение пароля как есть — самая частая и самая дорогая
     * ошибка в этой области, и выглядит она в коде совершенно невинно.
     */
    @Test
    @DisplayName("в базе лежит хэш, а не пароль")
    void passwordStoredHashed() throws Exception {
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT password_hash FROM users WHERE id = 'u-1'")) {
            assertTrue(rs.next());
            String stored = rs.getString(1);
            assertNotEquals("тайна", stored, "пароль сохранён как есть");
            assertTrue(stored.startsWith("$2a$"), "это не BCrypt: " + stored);
            assertTrue(Passwords.matches("тайна", stored));
            assertFalse(Passwords.matches("не тайна", stored));
        }
    }

    /**
     * Хэш помещается в колонку. Обрезанный хэш перестал бы совпадать с
     * паролем, и выглядело бы это как «пользователь забыл пароль».
     */
    @Test
    @DisplayName("хэш помещается в колонку целиком")
    void hashFitsColumn() throws Exception {
        String hash = Passwords.hash("длинный пароль с кириллицей и пробелами");
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET password_hash = ? WHERE id = 'u-1'")) {
                ps.setString(1, hash);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT password_hash FROM users WHERE id = 'u-1'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(hash, rs.getString(1),
                        "хэш вернулся не тем, чем был записан");
            }
        }
    }
}
