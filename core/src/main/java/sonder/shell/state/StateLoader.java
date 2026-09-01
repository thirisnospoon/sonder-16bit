package sonder.shell.state;

import sonder.contract.decider.ActorContext;
import sonder.contract.decider.FollowContext;
import sonder.contract.decider.NickContext;
import sonder.contract.decider.PostContext;
import sonder.contract.decider.PostStatus;
import sonder.contract.decider.Role;
import sonder.contract.decider.TargetUserContext;
import sonder.contract.decider.UserStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Загрузка состояния, которое операция объявила необходимым.
 *
 * <p><b>Это самое опасное место в оболочке.</b> Ядро решает ТОЛЬКО по
 * тому, что ему прислали: если контекст заполнен не весь, оно вынесет
 * формально корректное и фактически неверное решение, и обнаружится это на
 * данных, а не в тестах. Риск записан как R5 и живёт с самого начала.
 *
 * <p>Поэтому здесь нет ни одного поля, оставленного «по умолчанию».
 * Незаполненное поле — это ноль, а ноль у счётчика означает «постов за час
 * не было», а не «мы не считали». Ядро эти два случая различает: оно
 * требует счётчик неотрицательным и отказывает с INSUFFICIENT_CONTEXT,
 * если пришло отрицательное. Тест пользуется тем же различием — он
 * выставляет счётчики в −1 ПЕРЕД загрузкой и требует, чтобы после неё они
 * стали неотрицательными. Забытое поле так становится видимым.
 *
 * <p>Перечень необходимого не выдуман здесь заново: он лежит в
 * {@code contracts/generated/operations.json}, порождённом из WSDL, и тест
 * сверяется именно с ним. Список в двух местах разошёлся бы — как уже
 * расходились границы, коды и переводы строк.
 *
 * <p>Часы подаются параметром. Окно «за последний час» иначе нельзя ни
 * проверить, ни воспроизвести.
 */
public final class StateLoader {

    /** Окно, за которое считается частота. Совпадает с именем границы
     *  {@code posts_per_hour} в контракте: час есть час. */
    public static final Duration RATE_WINDOW = Duration.ofHours(1);

    private StateLoader() {
    }

    /**
     * Состояние действующего лица.
     *
     * <p>Отсутствующий пользователь — не исключение: ядро различает
     * «пользователя нет» по пустому идентификатору и отвечает
     * INSUFFICIENT_CONTEXT. Бросать здесь значило бы решать за ядро.
     */
    public static ActorContext loadActor(Connection c, String userId, Instant now)
            throws SQLException {
        ActorContext actor = new ActorContext();
        actor.setUserId("");
        actor.setRole(Role.USER);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setPostsLastHour(-1);
        actor.setCommentsLastHour(-1);

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role, status FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Идентификатор пуст: для ядра это «состояние неполно».
                    return actor;
                }
                actor.setUserId(userId);
                actor.setRole(Role.fromValue(rs.getString(1)));
                actor.setStatus(UserStatus.fromValue(rs.getString(2)));
            }
        }

        Timestamp since = Timestamp.from(now.minus(RATE_WINDOW));
        actor.setPostsLastHour(countSince(c,
                "SELECT COUNT(*) FROM posts WHERE author_id = ? AND created_at > ?",
                userId, since));
        actor.setCommentsLastHour(countSince(c,
                "SELECT COUNT(*) FROM comments WHERE author_id = ? AND created_at > ?",
                userId, since));
        return actor;
    }

    private static int countSince(Connection c, String sql,
                                  String userId, Timestamp since)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setTimestamp(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Состояние поста.
     *
     * <p>Версия обязательна: по ней ядро решает, а оболочка потом пишет с
     * проверкой, что версия не сдвинулась. Отрицательная версия для ядра —
     * признак незаполненного состояния, и потому она здесь исходная.
     */
    public static PostContext loadPost(Connection c, String postId) throws SQLException {
        PostContext post = new PostContext();
        post.setExists(false);
        post.setPostId("");
        post.setAuthorId("");
        post.setStatus(PostStatus.VISIBLE);
        post.setVersion(-1);

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT author_id, status, version FROM posts WHERE id = ?")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Несуществующий пост — законное состояние, а не отказ.
                    // Ядро отвечает на него POST_NOT_FOUND, и это его дело.
                    post.setVersion(0);
                    return post;
                }
                post.setExists(true);
                post.setPostId(postId);
                post.setAuthorId(rs.getString(1));
                post.setStatus(PostStatus.fromValue(rs.getString(2)));
                post.setVersion(rs.getInt(3));
            }
        }
        return post;
    }

    public static TargetUserContext loadTarget(Connection c, String userId)
            throws SQLException {
        TargetUserContext target = new TargetUserContext();
        target.setExists(false);
        target.setUserId("");
        target.setRole(Role.USER);
        target.setStatus(UserStatus.ACTIVE);
        target.setVersion(-1);

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role, status, version FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    target.setVersion(0);
                    return target;
                }
                target.setExists(true);
                target.setUserId(userId);
                target.setRole(Role.fromValue(rs.getString(1)));
                target.setStatus(UserStatus.fromValue(rs.getString(2)));
                target.setVersion(rs.getInt(3));
            }
        }
        return target;
    }

    /**
     * Занят ли ник.
     *
     * <p>Сравнение без учёта регистра: «Andrey» и «andrey» — один и тот же
     * занятый ник. Ядро при этом регистр НЕ приводит и «Andrey» отвергает
     * по форме; приведение — работа оболочки, и вот она.
     */
    public static NickContext loadNick(Connection c, String nick) throws SQLException {
        NickContext ctx = new NickContext();
        ctx.setTaken(false);
        if (nick == null) {
            return ctx;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM users WHERE LOWER(nick) = LOWER(?)")) {
            ps.setString(1, nick);
            try (ResultSet rs = ps.executeQuery()) {
                ctx.setTaken(rs.next());
            }
        }
        return ctx;
    }

    public static FollowContext loadFollow(Connection c, String followerId,
                                           String targetId) throws SQLException {
        FollowContext ctx = new FollowContext();
        ctx.setAlreadyFollowing(false);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM follows WHERE follower_id = ? AND target_id = ?")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                ctx.setAlreadyFollowing(rs.next());
            }
        }
        return ctx;
    }
}
