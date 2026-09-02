package sonder.shell.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.enrichment.Enrichment;
import sonder.enrichment.NotFound;
import sonder.enrichment.PostView;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxRecord;
import sonder.shell.outbox.Payloads;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 * <p><b>Проекция строится только на том, чем владеет {@code events}</b>
 * ([ADR-0016](../../../../../../docs/adr/0016-events-owns-its-data.md)), и
 * из этого следуют две разные тактики.
 *
 * <p>Подписки — свои: {@code feed_subscriptions} ведёт эта же проекция из
 * событий {@code follow.created} и {@code follow.removed}. Таблица
 * {@code follows} принадлежит {@code core}, и читать её отсюда нельзя.
 *
 * <p>Содержимое агрегата — вызовом по IIOP. Тело поста и время создания
 * принадлежат {@code core}: событие несёт идентичность, а не копию
 * агрегата, и копия в полезной нагрузке была бы вторым источником правды.
 * Недоступность {@code core} честно останавливает дренаж — лучше
 * задержка, чем проекция по неполным данным.
 *
 * <p><b>Идемпотентность не пожелание, а требование.</b> Между обработкой
 * события и коммитом система может упасть, и тогда событие приедет второй
 * раз — не «может быть», а приедет: источник правды это строка очереди, а
 * не факт вызова. Поэтому вставка идёт через {@code MERGE}: строка,
 * которая уже есть, не вставляется второй раз, а удаление повторяемо по
 * определению.
 *
 * <p><b>Про неизвестные события.</b> Очередь несёт и то, что ленте не
 * нужно. Молча пропускать всё подряд нельзя: так проекция однажды
 * перестанет замечать событие, которое ей как раз нужно. Поэтому
 * пропускаемое перечислено явно в {@link #IGNORED}, а
 * {@code FeedProjectionIT} сверяет объединение обработанного и
 * пропускаемого с каталогом событий.
 */
public final class FeedProjection implements OutboxDrainer.Handler {

    private static final Logger log = LoggerFactory.getLogger(FeedProjection.class);

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

    /** Строка ленты по готовым значениям. Идемпотентна по первичному ключу. */
    private static final String MERGE_ONE =
            "MERGE INTO feed_entries fe USING ("
                    + " SELECT CAST(? AS VARCHAR(40)) AS owner_id,"
                    + " CAST(? AS VARCHAR(40)) AS post_id,"
                    + " CAST(? AS VARCHAR(40)) AS author_id,"
                    + " CAST(? AS TIMESTAMP) AS created_at"
                    + " FROM rdb$database) src"
                    + " ON (fe.owner_id = src.owner_id AND fe.post_id = src.post_id)"
                    + " WHEN NOT MATCHED THEN INSERT"
                    + " (owner_id, post_id, author_id, created_at)"
                    + " VALUES (src.owner_id, src.post_id, src.author_id,"
                    + " src.created_at)";

    /** То же самое, но владельцами становятся все подписчики автора. */
    private static final String MERGE_FOLLOWERS =
            "MERGE INTO feed_entries fe USING ("
                    + " SELECT s.follower_id AS owner_id,"
                    + " CAST(? AS VARCHAR(40)) AS post_id,"
                    + " CAST(? AS VARCHAR(40)) AS author_id,"
                    + " CAST(? AS TIMESTAMP) AS created_at"
                    + " FROM feed_subscriptions s WHERE s.target_id = ?) src"
                    + " ON (fe.owner_id = src.owner_id AND fe.post_id = src.post_id)"
                    + " WHEN NOT MATCHED THEN INSERT"
                    + " (owner_id, post_id, author_id, created_at)"
                    + " VALUES (src.owner_id, src.post_id, src.author_id,"
                    + " src.created_at)";

    private final Enrichment enrichment;

    public FeedProjection(Enrichment enrichment) {
        this.enrichment = enrichment;
    }

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
     * <p>Подписчики раскладываются одним запросом, а не выборкой в память
     * и вставкой по одному: их может быть много, и круг к базе на каждого
     * превратил бы один пост в тысячу обменов.
     *
     * <p><b>Пост, которого уже нет, — не отказ.</b> Между созданием и
     * дренажом его могли удалить, и {@code NotFound} тут означает
     * «раскладывать нечего», а не «что-то сломалось». Считать это отказом
     * значило бы держать в очереди событие, которое не пройдёт никогда.
     */
    private void onPostCreated(Connection c, OutboxRecord record)
            throws Exception {
        String postId = record.getAggregateId();
        String authorId = Payloads.field(record.getPayload(), "authorId");
        if (authorId == null) {
            throw new IllegalStateException(
                    "в post.created нет authorId: раскладывать пост некому");
        }

        PostView view;
        try {
            view = enrichment.loadPost(postId);
        } catch (NotFound gone) {
            log.info("пост {} исчез до раскладки: класть нечего", postId);
            return;
        }
        Timestamp createdAt = new Timestamp(view.createdAtMillis);

        // Автор видит свой пост в своей ленте.
        try (PreparedStatement ps = c.prepareStatement(MERGE_ONE)) {
            ps.setString(1, authorId);
            ps.setString(2, postId);
            ps.setString(3, authorId);
            ps.setTimestamp(4, createdAt);
            ps.executeUpdate();
        }

        // И каждый, кто на него подписан.
        try (PreparedStatement ps = c.prepareStatement(MERGE_FOLLOWERS)) {
            ps.setString(1, postId);
            ps.setString(2, authorId);
            ps.setTimestamp(3, createdAt);
            ps.setString(4, authorId);
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
     * Подписка заведена: строка в графе и недавние посты в ленту.
     *
     * <p>Сперва граф — им фанаут будет пользоваться дальше. Дотягивание
     * прежних постов следствие, а не причина: без него лента после
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

        PostView[] recent = enrichment.recentPostsBy(targetId, BACKFILL_LIMIT);
        if (recent.length == 0) {
            return;
        }
        // Пачкой, а не по одному: круг к базе на каждый пост превратил бы
        // одну подписку в двести обменов.
        try (PreparedStatement ps = c.prepareStatement(MERGE_ONE)) {
            for (PostView view : recent) {
                ps.setString(1, followerId);
                ps.setString(2, view.postId);
                ps.setString(3, targetId);
                ps.setTimestamp(4, new Timestamp(view.createdAtMillis));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Подписка снята: уходит и строка графа, и посты этого автора.
     *
     * <p>Снимается по паре (владелец, автор), а не соединением с постами:
     * автор записан в строке ленты именно для этого.
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
