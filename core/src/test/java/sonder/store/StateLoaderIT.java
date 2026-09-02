package sonder.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.decider.ActorContext;
import sonder.contract.decider.PostContext;
import sonder.contract.decider.PostStatus;
import sonder.contract.decider.Role;
import sonder.contract.decider.TargetUserContext;
import sonder.contract.decider.UserStatus;
import sonder.shell.state.StateLoader;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Загрузка состояния и механическая проверка риска R5.
 *
 * <p>R5 звучит так: объявление состояния в контракте окажется неполным,
 * ядро примет формально корректное и фактически неверное решение, и
 * обнаружится это на данных, а не в тестах. Половина риска снимается
 * контрактом — операция обязана объявить нужное состояние. Вторая
 * половина здесь: оболочка обязана это объявленное ЗАПОЛНИТЬ.
 *
 * <p>Перечень берётся из {@code contracts/generated/operations.json},
 * порождённого из того же WSDL. Список в двух местах разошёлся бы, и
 * разошёлся бы молча — как уже расходились границы, коды и переводы строк.
 *
 * <p><b>Как отличается «поле не заполнено» от «поле равно нулю».</b>
 * Никак, если не завести различие заранее. Счётчик постов за час равен
 * нулю и когда постов не было, и когда их не считали, — и второе для ядра
 * означает неполное состояние. Поэтому загрузчик начинает с −1, а ядро
 * требует неотрицательного. Тест пользуется тем же различием.
 *
 * <p>У булевых полей такого различия нет: у {@code boolean} нет значения
 * «не установлено». Для контекстов из одного булева поля проверка слабее и
 * сводится к тому, что загрузчик для них вообще существует. Притворяться,
 * что она сильнее, было бы хуже, чем сказать это прямо.
 */
class StateLoaderIT extends FirebirdSupport {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    /** Типы контекста, которые оболочка умеет загружать. */
    private static final Set<String> LOADABLE = new HashSet<>(java.util.Arrays.asList(
            "TActorContext",
            "TPostContext",
            "TTargetUserContext",
            "TNickContext",
            "TFollowContext"));

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = connect()) {
            wipe(c);
            addUser(c, "u-1", "andrey", "USER", "ACTIVE");
            addUser(c, "u-2", "maria", "MODERATOR", "ACTIVE");
        }
    }

    private static void addUser(Connection c, String id, String nick,
                                String role, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, 'Имя', ?, ?, 'x', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, role);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(NOW));
            ps.executeUpdate();
        }
    }

    private static void addPost(Connection c, String id, String author,
                                Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO posts (id, author_id, body, status, version,"
                        + " created_at) VALUES (?, ?, 'текст', 'VISIBLE', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, author);
            ps.setTimestamp(3, Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    /**
     * Механическая проверка R5: на каждый тип контекста, объявленный хотя
     * бы одной операцией, у оболочки есть загрузчик.
     *
     * <p>Добавление контекста в WSDL сломает этот тест до тех пор, пока
     * оболочка не научится его заполнять. Именно этого от проверки и
     * требуется: без неё новое состояние тихо приезжало бы в ядро пустым.
     */
    @Test
    @DisplayName("оболочка умеет загрузить каждый объявленный контракт контекст")
    void everyDeclaredContextIsLoadable() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(
                new File("../contracts/generated/operations.json"));

        Set<String> declared = new TreeSet<>();
        for (JsonNode op : manifest.get("operations")) {
            for (JsonNode ctx : op.get("requiredContext")) {
                declared.add(ctx.get("type").asText());
            }
        }

        assertFalse(declared.isEmpty(),
                "манифест не объявил ни одного контекста — проверка была бы пустой");

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(LOADABLE);
        assertTrue(missing.isEmpty(),
                "контракт объявил состояние, которое оболочка не заполняет: "
                        + missing + ". Ядро получит его пустым и решит по "
                        + "неполным данным (R5)");

        // Обратная сторона: загрузчик, которого никто не объявляет, —
        // мёртвый код, и о нём тоже лучше знать.
        Set<String> unused = new TreeSet<>(LOADABLE);
        unused.removeAll(declared);
        assertTrue(unused.isEmpty(),
                "оболочка умеет грузить состояние, которого не объявляет ни "
                        + "одна операция: " + unused);
    }

    /**
     * Счётчики заполняются, а не остаются нулём по умолчанию. Проверяется
     * тем же различием, каким пользуется ядро: до загрузки −1, после —
     * неотрицательное.
     */
    @Test
    @DisplayName("состояние действующего лица заполняется целиком")
    void actorFullyLoaded() throws Exception {
        try (Connection c = connect()) {
            ActorContext actor = StateLoader.loadActor(c, "u-1", NOW);

            assertEquals("u-1", actor.getUserId());
            assertEquals(Role.USER, actor.getRole());
            assertEquals(UserStatus.ACTIVE, actor.getStatus());
            assertTrue(actor.getPostsLastHour() >= 0,
                    "счётчик постов не заполнен: " + actor.getPostsLastHour());
            assertTrue(actor.getCommentsLastHour() >= 0,
                    "счётчик комментариев не заполнен: "
                            + actor.getCommentsLastHour());
        }
    }

    /**
     * Несуществующий пользователь — состояние, а не исключение. Ядро
     * различает это по пустому идентификатору и отвечает
     * INSUFFICIENT_CONTEXT само.
     */
    @Test
    @DisplayName("несуществующее лицо приходит как неполное состояние")
    void missingActorIsIncompleteState() throws Exception {
        try (Connection c = connect()) {
            ActorContext actor = StateLoader.loadActor(c, "нет-такого", NOW);
            assertEquals("", actor.getUserId());
            assertTrue(actor.getPostsLastHour() < 0,
                    "счётчик заполнен для несуществующего лица — ядро решит, "
                            + "что состояние полное");
        }
    }

    /**
     * Окно частоты — ровно час. Проверяется с обеих сторон границы: пост
     * часовой давности не считается, пост получасовой — считается.
     * Односторонняя проверка прошла бы и на окне в сутки.
     */
    @Test
    @DisplayName("в счётчик попадает только последний час")
    void rateWindowIsExactlyAnHour() throws Exception {
        try (Connection c = connect()) {
            addPost(c, "p-old", "u-1", NOW.minus(Duration.ofHours(2)));
            addPost(c, "p-edge", "u-1", NOW.minus(StateLoader.RATE_WINDOW)
                    .minusSeconds(1));
            addPost(c, "p-fresh", "u-1", NOW.minus(Duration.ofMinutes(30)));
            addPost(c, "p-now", "u-1", NOW.minusSeconds(1));

            ActorContext actor = StateLoader.loadActor(c, "u-1", NOW);
            assertEquals(2, actor.getPostsLastHour(),
                    "в окно попало не то количество постов");
        }
    }

    @Test
    @DisplayName("состояние поста заполняется целиком, включая версию")
    void postFullyLoaded() throws Exception {
        try (Connection c = connect()) {
            addPost(c, "p-1", "u-1", NOW);
            PostContext post = StateLoader.loadPost(c, "p-1");

            assertTrue(post.isExists());
            assertEquals("p-1", post.getPostId());
            assertEquals("u-1", post.getAuthorId());
            assertEquals(PostStatus.VISIBLE, post.getStatus());
            assertTrue(post.getVersion() >= 0,
                    "версия не заполнена — оптимистическая блокировка "
                            + "работать не будет");
        }
    }

    @Test
    @DisplayName("несуществующий пост — состояние, а не отказ")
    void missingPostIsState() throws Exception {
        try (Connection c = connect()) {
            PostContext post = StateLoader.loadPost(c, "нет-такого");
            assertFalse(post.isExists());
            assertTrue(post.getVersion() >= 0,
                    "версия отрицательна — ядро сочтёт состояние неполным "
                            + "и ответит INSUFFICIENT_CONTEXT вместо POST_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("состояние цели заполняется целиком")
    void targetFullyLoaded() throws Exception {
        try (Connection c = connect()) {
            TargetUserContext target = StateLoader.loadTarget(c, "u-2");
            assertTrue(target.isExists());
            assertEquals("u-2", target.getUserId());
            assertEquals(Role.MODERATOR, target.getRole());
            assertTrue(target.getVersion() >= 0, "версия цели не заполнена");
        }
    }

    /**
     * Занятость ника — без учёта регистра. Ядро регистр не приводит и
     * «Andrey» отвергает по форме; приведение — работа оболочки, и
     * проверять её надо здесь.
     */
    @Test
    @DisplayName("занятость ника определяется без учёта регистра")
    void nickTakenIgnoresCase() throws Exception {
        try (Connection c = connect()) {
            assertTrue(StateLoader.loadNick(c, "andrey").isTaken());
            assertTrue(StateLoader.loadNick(c, "ANDREY").isTaken(),
                    "«ANDREY» сочтён свободным, хотя «andrey» занят");
            assertTrue(StateLoader.loadNick(c, "Andrey").isTaken());
            assertFalse(StateLoader.loadNick(c, "свободный").isTaken());
            assertFalse(StateLoader.loadNick(c, null).isTaken());
        }
    }

    @Test
    @DisplayName("подписка находится только между теми, кто подписан")
    void followFound() throws Exception {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO follows (follower_id, target_id, created_at)"
                            + " VALUES ('u-1', 'u-2', ?)")) {
                ps.setTimestamp(1, Timestamp.from(NOW));
                ps.executeUpdate();
            }
            assertTrue(StateLoader.loadFollow(c, "u-1", "u-2").isAlreadyFollowing());
            assertFalse(StateLoader.loadFollow(c, "u-2", "u-1").isAlreadyFollowing(),
                    "подписка сочтена взаимной, хотя она односторонняя");
        }
    }
}
