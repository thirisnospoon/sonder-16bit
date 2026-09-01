package sonder.shell.store;

import sonder.shell.app.VersionConflict;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Запись решений о пользователях и подписках.
 *
 * <p>Сырой SQL там, где нужны точное условие по версии и число затронутых
 * строк как ответ на вопрос «была ли гонка», и там, где речь о множестве,
 * а не об объекте с жизненным циклом (ADR-0013).
 *
 * <p>Соединение параметром: запись решения и запись события обязаны
 * попасть в одну транзакцию.
 */
public final class UserStore {

    private UserStore() {
    }

    public static void insert(Connection c, String id, String nick,
                              String displayName, String passwordHash,
                              Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, ?, 'USER', 'ACTIVE', ?, 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, displayName);
            ps.setString(4, passwordHash);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    /**
     * Сменить статус, если версия не сдвинулась.
     *
     * <p>Условие по версии стоит в самом UPDATE: проверка отдельным
     * запросом оставила бы окно между чтением и записью.
     */
    public static void updateStatus(Connection c, String userId, String status,
                                    int expectedVersion)
            throws SQLException, VersionConflict {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET status = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?")) {
            ps.setString(1, status);
            ps.setString(2, userId);
            ps.setInt(3, expectedVersion);
            if (ps.executeUpdate() == 0) {
                throw new VersionConflict(userId, expectedVersion);
            }
        }
    }

    /** Идентификатор по нику, без учёта регистра. {@code null}, если нет. */
    public static String idByNick(Connection c, String nick) throws SQLException {
        if (nick == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM users WHERE LOWER(nick) = LOWER(?)")) {
            ps.setString(1, nick);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Завести подписку.
     *
     * <p>Версии здесь нет и не нужно: подписка — не объект с жизненным
     * циклом, а факт её существования. Гонку ловит первичный ключ, и это
     * дешевле и вернее, чем версия у ребра графа.
     */
    public static void addFollow(Connection c, String followerId,
                                 String targetId, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO follows (follower_id, target_id, created_at)"
                        + " VALUES (?, ?, ?)")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    public static boolean removeFollow(Connection c, String followerId,
                                       String targetId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM follows WHERE follower_id = ? AND target_id = ?")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            return ps.executeUpdate() > 0;
        }
    }
}
