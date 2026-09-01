package sonder.shell.store;

import sonder.shell.app.VersionConflict;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Состояние поста: загрузка для ядра и запись решения.
 *
 * <p>Сырой SQL, а не JPA, и это осознанно: здесь нужны две вещи, которых у
 * ORM просить неудобно — точное условие по версии в UPDATE и число
 * затронутых строк как ответ на вопрос «была ли гонка». JPA умеет и то и
 * другое, но через исключение, а исключение как штатный результат читается
 * хуже, чем ноль строк.
 *
 * <p>Сущность {@link PostEntity} остаётся: через неё пойдут операции, где
 * важен жизненный цикл объекта. Разделение по природе операции, а не по
 * вкусу — так решено в ADR-0013.
 *
 * <p>Каждый метод принимает {@link Connection}: запись решения и запись
 * события обязаны попасть в одну транзакцию, и источник данных позволил бы
 * им разъехаться.
 */
public final class PostStore {

    private PostStore() {
    }

    /** Состояние поста в том виде, в каком его объявляет контракт операции. */
    public static final class PostState {
        private final boolean exists;
        private final String postId;
        private final String authorId;
        private final String status;
        private final int version;

        PostState(boolean exists, String postId, String authorId,
                  String status, int version) {
            this.exists = exists;
            this.postId = postId;
            this.authorId = authorId;
            this.status = status;
            this.version = version;
        }

        public static PostState missing(String postId) {
            return new PostState(false, postId, null, null, 0);
        }

        public boolean exists() {
            return exists;
        }

        public String getPostId() {
            return postId;
        }

        public String getAuthorId() {
            return authorId;
        }

        public String getStatus() {
            return status;
        }

        public int getVersion() {
            return version;
        }
    }

    /**
     * Загрузить состояние, объявленное операцией.
     *
     * <p>Несуществующий пост — не ошибка и не {@code null}: контракт
     * различает «нет объекта» и «объект есть», и ядро решает по этому
     * различию. Возвращать {@code null} значило бы заставить каждого
     * вызывающего изобрести то же различие заново, а кого-то — забыть.
     */
    public static PostState load(Connection c, String postId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT author_id, status, version FROM posts WHERE id = ?")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return PostState.missing(postId);
                }
                return new PostState(true, postId, rs.getString(1),
                        rs.getString(2), rs.getInt(3));
            }
        }
    }

    public static void insert(Connection c, String id, String authorId,
                              String body, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO posts (id, author_id, body, status, version, created_at)"
                        + " VALUES (?, ?, ?, ?, 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, authorId);
            ps.setString(3, body);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /**
     * Записать новый статус, если версия не сдвинулась.
     *
     * <p>Условие по версии стоит В САМОМ UPDATE, а не в отдельной проверке
     * перед ним. Проверка отдельным запросом оставляет окно между чтением
     * и записью — ровно то окно, ради закрытия которого всё и затевалось.
     *
     * <p>Ноль затронутых строк означает, что кто-то успел раньше. Это не
     * отказ базы и не исключительная ситуация: команда переигрывается с
     * новой загрузкой и новым вызовом ядра.
     */
    public static void updateStatus(Connection c, String postId,
                                    String status, int expectedVersion)
            throws SQLException, VersionConflict {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE posts SET status = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?")) {
            ps.setString(1, status);
            ps.setString(2, postId);
            ps.setInt(3, expectedVersion);
            if (ps.executeUpdate() == 0) {
                throw new VersionConflict(postId, expectedVersion);
            }
        }
    }
}
