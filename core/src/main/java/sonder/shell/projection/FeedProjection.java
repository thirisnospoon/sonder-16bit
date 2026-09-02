package sonder.shell.projection;

import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxRecord;
import sonder.shell.outbox.Payloads;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Лента: проекция из событий очереди.
 *
 * <p><b>Фанаут на запись.</b> На каждое {@code post.created} кладётся по
 * строке в ленту каждого подписчика автора — и самого автора. Обратный
 * подход, собирать ленту на чтение соединением с подписками, дешевле
 * пишет и дороже читает, а читают ленту на порядок чаще, чем пишут.
 *
 * <p><b>Идемпотентность не пожелание, а требование.</b> Между обработкой
 * события и коммитом система может упасть, и тогда событие приедет второй
 * раз — не «может быть», а приедет: источник правды это строка очереди, а
 * не факт вызова. Поэтому вставка идёт через {@code MERGE}: строка,
 * которая уже есть, не вставляется второй раз, а удаление повторяемо по
 * определению. {@code UPDATE OR INSERT} тут не годится — он принимает
 * только {@code VALUES}, а раскладывать надо выборку.
 *
 * <p><b>Подписки — свои.</b> Фанаут соединяется с {@code feed_subscriptions},
 * которую эта же проекция и ведёт из событий {@code follow.created} и
 * {@code follow.removed}. Таблица {@code follows} принадлежит {@code core},
 * и читать её отсюда нельзя: {@code events} строит проекции только на том,
 * чем владеет сам
 * ([ADR-0016](../../../../../../docs/adr/0016-events-owns-its-data.md)).
 *
 * <p><b>Тело и время берутся из write-модели.</b> Событие несёт
 * идентичность, а не копию агрегата (см. {@code contracts/events/events.yaml}):
 * в {@code post.created} нет ни тела, ни времени, и читать их надо из
 * {@code posts}. Копия в полезной нагрузке была бы вторым источником
 * правды, который однажды разойдётся с первым.
 *
 * <p><b>Про неизвестные события.</b> Очередь несёт и то, что ленте не
 * нужно. Молча пропускать всё подряд нельзя: так проекция однажды
 * перестанет замечать событие, которое ей как раз нужно. Поэтому
 * пропускаемое перечислено явно в {@link #IGNORED}, а
 * {@code FeedProjectionIT} сверяет объединение обработанного и
 * пропускаемого с каталогом событий — событие, заведённое в ядре и не
 * упомянутое здесь, красит сборку.
 */
public final class FeedProjection implements OutboxDrainer.Handler {

    /**
     * Сколько постов дотягивать в ленту при новой подписке.
     *
     * <p>Не вся история: страница ленты по контракту не длиннее
     * пятидесяти, и подписка на плодовитого автора не должна оборачиваться
     * неограниченной записью. Двести — четыре полные страницы: достаточно,
     * чтобы лента не выглядела пустой, и мало, чтобы одна подписка не
     * стала самой дорогой операцией системы.
     */
    public static final int BACKFILL_LIMIT = 200;

    /** Что лента обрабатывает. */
    public static final Set<String> HANDLED =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "post.created", "post.deleted",
                    "follow.created", "follow.removed")));

    /**
     * Что лента пропускает намеренно.
     *
     * <p>Каждое — с причиной, иначе список превращается в «всё
     * остальное», а это уже не решение, а умолчание.
     *
     * <ul>
     *   <li>{@code user.registered} — у нового пользователя нет ни постов,
     *       ни подписок, и класть в ленту нечего;
     *   <li>{@code user.banned} — блокировка запрещает создавать новое, а
     *       написанное раньше из лент не изымается: это решение ядра, и
     *       проекция его не переигрывает;
     *   <li>{@code comment.created} — лента состоит из постов, комментарии
     *       читаются на странице поста.
     * </ul>
     */
    public static final Set<String> IGNORED =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "user.registered", "user.banned", "comment.created")));

    @Override
    public void handle(Connection c, OutboxRecord record) throws Exception {
        String type = record.getType();
        if ("post.created".equals(type)) {
            onPostCreated(c, record);
        } else if ("post.deleted".equals(type)) {
            onPostDeleted(c, record);
        } else if ("follow.created".equals(type)) {
            onFollowCreated(c, record);
        } else if ("follow.removed".equals(type)) {
            onFollowRemoved(c, record);
        } else if (!IGNORED.contains(type)) {
            // Не «на всякий случай»: событие, о котором лента не знает
            // ничего, — это либо новое событие без решения, что с ним
            // делать, либо опечатка в типе. И то и другое лучше увидеть
            // отказом, чем тишиной. Дренажёр засчитает попытку и отложит
            // повтор, а строка останется в очереди.
            throw new IllegalStateException(
                    "лента не знает события " + type
                            + ": добавь его в HANDLED или в IGNORED с причиной");
        }
    }

    /**
     * Пост создан: строка в ленту автора и каждого его подписчика.
     *
     * <p>Одним запросом, а не выборкой подписчиков в память и вставкой по
     * одному: подписчиков может быть много, и круг к базе на каждого
     * превратил бы один пост в тысячу обменов.
     */
    private void onPostCreated(Connection c, OutboxRecord record)
            throws Exception {
        String postId = record.getAggregateId();
        String authorId = Payloads.field(record.getPayload(), "authorId");
        if (authorId == null) {
            throw new IllegalStateException(
                    "в post.created нет authorId: раскладывать пост некому");
        }

        // Автор видит свой пост в своей ленте.
        try (PreparedStatement ps = c.prepareStatement(
                "MERGE INTO feed_entries fe USING ("
                        + " SELECT CAST(? AS VARCHAR(40)) AS owner_id,"
                        + " p.id AS post_id, p.author_id, p.created_at"
                        + " FROM posts p"
                        + " WHERE p.id = ? AND p.status = 'VISIBLE') src"
                        + " ON (fe.owner_id = src.owner_id AND fe.post_id = src.post_id)"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + " (owner_id, post_id, author_id, created_at)"
                        + " VALUES (src.owner_id, src.post_id, src.author_id, src.created_at)")) {
            ps.setString(1, authorId);
            ps.setString(2, postId);
            ps.executeUpdate();
        }

        // И каждый, кто на него подписан. Подписки берутся из СВОЕЙ
        // проекции, а не из таблицы follows: та принадлежит core, а
        // events строит проекции только на том, чем владеет сам
        // (ADR-0016).
        try (PreparedStatement ps = c.prepareStatement(
                "MERGE INTO feed_entries fe USING ("
                        + " SELECT s.follower_id AS owner_id,"
                        + " p.id AS post_id, p.author_id, p.created_at"
                        + " FROM posts p"
                        + " JOIN feed_subscriptions s ON s.target_id = p.author_id"
                        + " WHERE p.id = ? AND p.status = 'VISIBLE') src"
                        + " ON (fe.owner_id = src.owner_id AND fe.post_id = src.post_id)"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + " (owner_id, post_id, author_id, created_at)"
                        + " VALUES (src.owner_id, src.post_id, src.author_id, src.created_at)")) {
            ps.setString(1, postId);
            ps.executeUpdate();
        }
    }

    /** Пост удалён: из всех лент, где он был. */
    private void onPostDeleted(Connection c, OutboxRecord record)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM feed_entries WHERE post_id = ?")) {
            ps.setString(1, record.getAggregateId());
            ps.executeUpdate();
        }
    }

    /**
     * Подписка заведена: в ленту подписчика попадают недавние посты того,
     * на кого подписались.
     *
     * <p>Без этого лента наполнялась бы только новыми постами и после
     * подписки выглядела бы пустой, пока автор не напишет следующий.
     */
    private void onFollowCreated(Connection c, OutboxRecord record)
            throws Exception {
        String followerId = record.getAggregateId();
        String targetId = Payloads.field(record.getPayload(), "targetUserId");
        if (targetId == null) {
            throw new IllegalStateException(
                    "в follow.created нет targetUserId: дотягивать нечего");
        }

        // Сперва граф: он и есть то, чем фанаут будет пользоваться
        // дальше. Дотягивание прежних постов — следствие, а не причина.
        try (PreparedStatement ps = c.prepareStatement(
                "MERGE INTO feed_subscriptions s USING ("
                        + " SELECT CAST(? AS VARCHAR(40)) AS follower_id,"
                        + " CAST(? AS VARCHAR(40)) AS target_id"
                        + " FROM rdb$database) src"
                        + " ON (s.follower_id = src.follower_id"
                        + " AND s.target_id = src.target_id)"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + " (follower_id, target_id, seen_at)"
                        + " VALUES (src.follower_id, src.target_id,"
                        + " CURRENT_TIMESTAMP)")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = c.prepareStatement(
                "MERGE INTO feed_entries fe USING ("
                        + " SELECT CAST(? AS VARCHAR(40)) AS owner_id,"
                        + " p.id AS post_id, p.author_id, p.created_at"
                        + " FROM posts p"
                        + " WHERE p.author_id = ? AND p.status = 'VISIBLE'"
                        + " ORDER BY p.created_at DESC, p.id DESC"
                        + " ROWS " + BACKFILL_LIMIT + ") src"
                        + " ON (fe.owner_id = src.owner_id AND fe.post_id = src.post_id)"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + " (owner_id, post_id, author_id, created_at)"
                        + " VALUES (src.owner_id, src.post_id, src.author_id, src.created_at)")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            ps.executeUpdate();
        }
    }

    /**
     * Подписка снята: из ленты подписчика уходят посты этого автора.
     *
     * <p>Снимается по паре (владелец, автор), а не соединением с
     * {@code posts}: автор записан в строке ленты именно для этого.
     */
    private void onFollowRemoved(Connection c, OutboxRecord record)
            throws Exception {
        String followerId = record.getAggregateId();
        String targetId = Payloads.field(record.getPayload(), "targetUserId");
        if (targetId == null) {
            throw new IllegalStateException(
                    "в follow.removed нет targetUserId: снимать нечего");
        }

        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM feed_subscriptions WHERE follower_id = ?"
                        + " AND target_id = ?")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM feed_entries WHERE owner_id = ? AND author_id = ?")) {
            ps.setString(1, followerId);
            ps.setString(2, targetId);
            ps.executeUpdate();
        }
    }
}
