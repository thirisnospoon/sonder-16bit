package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.projection.FeedProjection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лента как проекция из очереди событий.
 *
 * <p>Проверяется весь путь целиком: событие кладётся в outbox, дренажёр
 * его забирает, проекция раскладывает. Проверять проекцию вызовом её
 * метода напрямую значило бы не проверить главного — что она уживается
 * с транзакцией дренажа и переживает повтор.
 *
 * <p>Отдельно проверяется покрытие: лента обязана знать про КАЖДОЕ
 * событие каталога — обрабатывать или сознательно пропускать. Проекция,
 * молча игнорирующая незнакомое, однажды перестанет замечать то, что ей
 * как раз нужно, и заметит это читатель ленты, а не сборка.
 */
class FeedProjectionIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    private OutboxDrainer drainer;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        drainer = new OutboxDrainer(FirebirdSupport::connect, new FeedProjection());
        try (Connection c = connect()) {
            wipe(c);
        }
        // Андрей пишет, Борис и Вера читают.
        addUser("u-andrey", "andrey");
        addUser("u-boris", "boris");
        addUser("u-vera", "vera");
    }

    private static void addUser(String id, String nick) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (id, nick, display_name, role, status,"
                             + " password_hash, version, created_at)"
                             + " VALUES (?, ?, ?, 'USER', 'ACTIVE', 'x', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, nick);
            ps.setTimestamp(4, Timestamp.from(T0));
            ps.executeUpdate();
        }
    }

    private static void addPost(String id, String author, Instant at)
            throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO posts (id, author_id, body, status, version,"
                             + " created_at) VALUES (?, ?, ?, 'VISIBLE', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, author);
            ps.setString(3, "пост " + id);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    private static void addFollow(String follower, String target)
            throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO follows (follower_id, target_id, created_at)"
                             + " VALUES (?, ?, ?)")) {
            ps.setString(1, follower);
            ps.setString(2, target);
            ps.setTimestamp(3, Timestamp.from(T0));
            ps.executeUpdate();
        }
    }

    private static void enqueue(String aggregateId, String type, String payload)
            throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            Outbox.append(c, new OutboxEvent(aggregateId, type, payload, "t-1"));
            c.commit();
        }
    }

    /** Чья лента, в порядке страницы: новое сверху. */
    private static List<String> feedOf(String owner) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT post_id FROM feed_entries WHERE owner_id = ?"
                             + " ORDER BY created_at DESC, post_id DESC")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    private static long feedSize() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM feed_entries");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    @DisplayName("пост попадает в ленту автора и каждого подписчика")
    void fansOutToFollowers() throws Exception {
        addFollow("u-boris", "u-andrey");
        addPost("p-1", "u-andrey", T0);
        enqueue("p-1", "post.created", "{\"authorId\":\"u-andrey\"}");

        OutboxDrainer.Result r = drainer.drainOnce(T0);
        assertEquals(1, r.getPublished(), "событие не обработано");

        assertEquals(java.util.Arrays.asList("p-1"), feedOf("u-andrey"),
                "автор не видит своего поста");
        assertEquals(java.util.Arrays.asList("p-1"), feedOf("u-boris"),
                "подписчик не увидел пост");
        assertTrue(feedOf("u-vera").isEmpty(),
                "пост попал тому, кто не подписан");
    }

    /**
     * Событие приезжает второй раз — так бывает всегда, когда система
     * упала между обработкой и коммитом. Лента обязана выглядеть так же.
     */
    @Test
    @DisplayName("повторное событие не удваивает строки ленты")
    void replayIsIdempotent() throws Exception {
        addFollow("u-boris", "u-andrey");
        addPost("p-1", "u-andrey", T0);

        enqueue("p-1", "post.created", "{\"authorId\":\"u-andrey\"}");
        drainer.drainOnce(T0);
        long afterFirst = feedSize();
        assertEquals(2, afterFirst, "первый проход разложил не то");

        enqueue("p-1", "post.created", "{\"authorId\":\"u-andrey\"}");
        OutboxDrainer.Result replay = drainer.drainOnce(T0.plusSeconds(1));

        // Оба утверждения нужны вместе. Одна лишь неизменность ленты
        // выполнялась бы и тогда, когда повтор ПАДАЕТ по первичному
        // ключу: дренажёр откатил бы его до точки сохранения, и размер
        // остался бы прежним. Идемпотентность — это «прошло и ничего не
        // изменило», а не «ничего не изменило, потому что не прошло».
        assertEquals(1, replay.getPublished(),
                "повтор не обработан: обработчик не идемпотентен, а падает");
        assertEquals(0, replay.getFailed(), "повтор сочтён отказом");
        assertEquals(afterFirst, feedSize(),
                "повтор удвоил ленту: обработчик не идемпотентен");
    }

    @Test
    @DisplayName("удалённый пост уходит из всех лент")
    void deletionRemovesEverywhere() throws Exception {
        addFollow("u-boris", "u-andrey");
        addFollow("u-vera", "u-andrey");
        addPost("p-1", "u-andrey", T0);
        enqueue("p-1", "post.created", "{\"authorId\":\"u-andrey\"}");
        drainer.drainOnce(T0);
        assertEquals(3, feedSize(), "фанаут разложил не всем");

        enqueue("p-1", "post.deleted", "{\"deletedBy\":\"u-andrey\"}");
        drainer.drainOnce(T0.plusSeconds(1));

        assertEquals(0, feedSize(), "удалённый пост остался в лентах");
    }

    /**
     * Новая подписка дотягивает уже написанное. Без этого лента после
     * подписки выглядела бы пустой, пока автор не напишет следующий пост.
     */
    @Test
    @DisplayName("подписка дотягивает прежние посты в ленту подписчика")
    void followBackfills() throws Exception {
        addPost("p-1", "u-andrey", T0);
        addPost("p-2", "u-andrey", T0.plusSeconds(60));
        enqueue("p-1", "post.created", "{\"authorId\":\"u-andrey\"}");
        enqueue("p-2", "post.created", "{\"authorId\":\"u-andrey\"}");
        drainer.drainOnce(T0);
        assertTrue(feedOf("u-boris").isEmpty(), "лента непустая до подписки");

        addFollow("u-boris", "u-andrey");
        enqueue("u-boris", "follow.created", "{\"targetUserId\":\"u-andrey\"}");
        drainer.drainOnce(T0.plusSeconds(120));

        assertEquals(java.util.Arrays.asList("p-2", "p-1"), feedOf("u-boris"),
                "дотянулось не то или не в том порядке: новое сверху");
    }

    @Test
    @DisplayName("отписка убирает из ленты посты этого автора и только их")
    void unfollowRemovesOnlyThatAuthor() throws Exception {
        addFollow("u-boris", "u-andrey");
        addFollow("u-boris", "u-vera");
        addPost("p-1", "u-andrey", T0);
        addPost("p-2", "u-vera", T0.plusSeconds(60));
        enqueue("p-1", "post.created", "{\"authorId\":\"u-andrey\"}");
        enqueue("p-2", "post.created", "{\"authorId\":\"u-vera\"}");
        drainer.drainOnce(T0);
        assertEquals(java.util.Arrays.asList("p-2", "p-1"), feedOf("u-boris"));

        enqueue("u-boris", "follow.removed", "{\"targetUserId\":\"u-andrey\"}");
        drainer.drainOnce(T0.plusSeconds(120));

        assertEquals(java.util.Arrays.asList("p-2"), feedOf("u-boris"),
                "отписка убрала не то");
        assertEquals(java.util.Arrays.asList("p-1"), feedOf("u-andrey"),
                "чужая отписка тронула ленту автора");
    }

    /** Событие, которое ленте не нужно, проходит без следа и без отказа. */
    @Test
    @DisplayName("пропускаемое событие обрабатывается без следа")
    void ignoredEventPassesQuietly() throws Exception {
        enqueue("u-andrey", "user.registered", "{\"nick\":\"andrey\"}");

        OutboxDrainer.Result r = drainer.drainOnce(T0);

        assertEquals(1, r.getPublished(), "пропускаемое событие сочтено отказом");
        assertEquals(0, feedSize(), "пропускаемое событие что-то записало");
    }

    /**
     * Незнакомое событие — отказ, а не тишина. Тишина означала бы, что
     * новое событие можно завести и не заметить, что лента про него не
     * знает.
     */
    @Test
    @DisplayName("незнакомое событие даёт отказ, а не молчаливый пропуск")
    void unknownEventIsRefused() throws Exception {
        enqueue("p-1", "post.embalmed", "{}");

        OutboxDrainer.Result r = drainer.drainOnce(T0);

        assertEquals(0, r.getPublished());
        assertEquals(1, r.getFailed(), "незнакомое событие прошло молча");
    }

    /**
     * Механическая проверка покрытия: лента знает про каждое событие
     * каталога.
     *
     * <p>Перечень берётся из {@code contracts/events/events.yaml} — того
     * самого, который валидатор сверяет с ядром. Цепочка получается
     * замкнутой: ядро порождает ровно то, что объявлено в каталоге, а
     * лента знает ровно то, что в каталоге объявлено. Новое событие
     * красит сборку дважды, и оба раза до того, как оно доедет до
     * читателя.
     */
    @Test
    @DisplayName("лента знает про каждое событие каталога")
    void everyCatalogueEventIsKnown() throws Exception {
        Set<String> declared = catalogueTypes();
        assertFalse(declared.isEmpty(),
                "каталог пуст — проверка была бы пустой");

        Set<String> known = new TreeSet<>(FeedProjection.HANDLED);
        known.addAll(FeedProjection.IGNORED);

        Set<String> unknown = new TreeSet<>(declared);
        unknown.removeAll(known);
        assertTrue(unknown.isEmpty(),
                "лента не знает событий каталога: " + unknown
                        + ". Обработать или явно пропустить с причиной");

        Set<String> phantom = new TreeSet<>(known);
        phantom.removeAll(declared);
        assertTrue(phantom.isEmpty(),
                "лента знает событие, которого в каталоге нет: " + phantom);

        Set<String> both = new TreeSet<>(FeedProjection.HANDLED);
        both.retainAll(FeedProjection.IGNORED);
        assertTrue(both.isEmpty(),
                "событие и обрабатывается, и пропускается: " + both);
    }

    /** Типы событий из каталога. Разбор построчный: yaml тут ни к чему. */
    private static Set<String> catalogueTypes() throws Exception {
        String text = new String(Files.readAllBytes(
                new File("../contracts/events/events.yaml").toPath()),
                StandardCharsets.UTF_8);
        Set<String> types = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\s*-\\s+type:\\s*(\\S+)\\s*$")
                .matcher(text);
        while (m.find()) {
            types.add(m.group(1));
        }
        return types;
    }
}
