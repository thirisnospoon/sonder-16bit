package sonder.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.decider.BanUserRequest;
import sonder.contract.decider.CreateCommentRequest;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DeletePostRequest;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.EventField;
import sonder.contract.decider.FollowUserRequest;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PingResponse;
import sonder.contract.decider.RegisterUserRequest;
import sonder.shell.app.CommandFlow;
import sonder.shell.app.DeletePostHandler;
import sonder.shell.app.VersionConflict;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полный ход команды: состояние → ядро → решение → запись.
 *
 * <p>Ядро здесь подменено, и это единственный честный способ проверить
 * оболочку: настоящее живёт за последовательной линией, которой ещё нет
 * (Ф8). Подменяется при этом ИНТЕРФЕЙС ИЗ КОНТРАКТА, порождённый CXF из
 * того же WSDL, — не выдуманный, не упрощённый. Когда появится транспорт,
 * он реализует этот же интерфейс.
 *
 * <p>Главное, что проверяется: ядру приезжает ЗАПОЛНЕННОЕ состояние.
 * Оболочка, приславшая пустой контекст, получила бы формально корректное
 * решение по неполным данным — риск R5, и здесь он проверяется на живом
 * ходу команды, а не только на загрузчике.
 */
class DeletePostFlowIT extends FirebirdSupport {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    /**
     * Подменное ядро: записывает пришедшие запросы и отвечает по сценарию.
     *
     * <p>Реализует полный интерфейс контракта, а не удобное подмножество:
     * подмена, которая умеет меньше настоящего, однажды скроет то, чего
     * оболочка не делает.
     */
    static final class FakeDecider implements Decider {
        final List<DeletePostRequest> seen = new ArrayList<>();
        Consumer<DeletePostRequest> onCall = r -> { };
        Decision answer = accepted("post.deleted", "p-1");

        static Decision accepted(String type, String aggregateId) {
            Decision d = new Decision();
            d.setAccepted(true);
            DomainEvent e = new DomainEvent();
            e.setType(type);
            e.setAggregateId(aggregateId);
            EventField f = new EventField();
            f.setKey("actor");
            f.setValue("u-1");
            e.getField().add(f);
            d.getEvent().add(e);
            return d;
        }

        static Decision rejected(String code) {
            Decision d = new Decision();
            d.setAccepted(false);
            d.setErrorCode(code);
            return d;
        }

        @Override
        public Decision deletePost(DeletePostRequest request) {
            seen.add(request);
            onCall.accept(request);
            return answer;
        }

        @Override public Decision createPost(CreatePostRequest r) { return answer; }
        @Override public Decision createComment(CreateCommentRequest r) { return answer; }
        @Override public Decision registerUser(RegisterUserRequest r) { return answer; }
        @Override public Decision followUser(FollowUserRequest r) { return answer; }
        @Override public Decision banUser(BanUserRequest r) { return answer; }

        @Override
        public PingResponse ping(PingRequest r) {
            return new PingResponse();
        }
    }

    private FakeDecider decider;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void clean() throws Exception {
        decider = new FakeDecider();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM comments");
            st.executeUpdate("DELETE FROM outbox");
            st.executeUpdate("DELETE FROM posts");
            st.executeUpdate("DELETE FROM users");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, nick, display_name, role, status,"
                            + " password_hash, version, created_at)"
                            + " VALUES ('u-1', 'u1', 'Автор', 'USER', 'ACTIVE',"
                            + " 'x', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(NOW));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES ('p-1', 'u-1', 'текст',"
                            + " 'VISIBLE', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(NOW));
                ps.executeUpdate();
            }
        }
    }

    private DeletePostHandler handler() {
        return new DeletePostHandler(
                new CommandFlow(FirebirdSupport::connect), decider);
    }

    private DeletePostHandler handler(int attempts) {
        return new DeletePostHandler(
                new CommandFlow(FirebirdSupport::connect, attempts), decider);
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

    private static long outboxCount() throws SQLException {
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM outbox")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static void bumpPost() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE posts SET version = version + 1 WHERE id = 'p-1'")) {
            ps.executeUpdate();
        }
    }

    /**
     * Ядру приезжает заполненное состояние. Это R5 на живом ходу команды:
     * загрузчик может быть исправен, а обработчик — забыть положить
     * загруженное в запрос.
     */
    @Test
    @DisplayName("ядро получает заполненное состояние, а не пустое")
    void deciderSeesLoadedState() throws Exception {
        handler().handle("u-1", "p-1", "t-1", "c-1", NOW);

        assertEquals(1, decider.seen.size(), "ядро вызвано не один раз");
        DeletePostRequest sent = decider.seen.get(0);

        assertNotNull(sent.getActor(), "состояние лица не приложено");
        assertEquals("u-1", sent.getActor().getUserId());
        assertTrue(sent.getActor().getPostsLastHour() >= 0,
                "счётчик приехал незаполненным: ядро сочтёт состояние неполным");

        assertNotNull(sent.getPost(), "состояние поста не приложено");
        assertTrue(sent.getPost().isExists());
        assertEquals("u-1", sent.getPost().getAuthorId());
        assertTrue(sent.getPost().getVersion() >= 0, "версия не приехала");

        assertEquals("p-1", sent.getCommand().getPostId());
        assertEquals("t-1", sent.getMeta().getTraceId());
        assertEquals(NOW.toEpochMilli(), sent.getMeta().getIssuedAtMillis());
    }

    @Test
    @DisplayName("принятое решение пишет и статус, и событие")
    void acceptedWritesBoth() throws Exception {
        DeletePostHandler.Outcome outcome =
                handler().handle("u-1", "p-1", "t-1", "c-1", NOW);

        assertTrue(outcome.isAccepted());
        assertEquals(1, outcome.getEventsWritten());
        assertEquals("DELETED", statusOf("p-1"));
        assertEquals(1, outboxCount());
    }

    /**
     * Отказ ядра — штатный исход, а не исключение. Не записывается НИЧЕГО:
     * ни статус, ни событие.
     */
    @Test
    @DisplayName("отказ ядра не пишет ничего")
    void rejectedWritesNothing() throws Exception {
        decider.answer = FakeDecider.rejected("NOT_OWNER");

        DeletePostHandler.Outcome outcome =
                handler().handle("u-1", "p-1", "t-1", "c-1", NOW);

        assertFalse(outcome.isAccepted());
        assertEquals("NOT_OWNER", outcome.getErrorCode());
        assertEquals(0, outcome.getEventsWritten());
        assertEquals("VISIBLE", statusOf("p-1"), "статус изменён при отказе");
        assertEquals(0, outboxCount(), "событие записано при отказе");
    }

    /**
     * Между решением и записью состояние сдвинулось. Ход повторяется
     * ЦЕЛИКОМ, включая новый вызов ядра: применить старое решение к новому
     * состоянию значило бы решить по устаревшим данным.
     */
    @Test
    @DisplayName("сдвиг версии между решением и записью переигрывает весь ход")
    void conflictReplaysWholeFlow() throws Exception {
        decider.onCall = r -> {
            if (decider.seen.size() == 1) {
                try {
                    bumpPost();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        DeletePostHandler.Outcome outcome =
                handler().handle("u-1", "p-1", "t-1", "c-1", NOW);

        assertTrue(outcome.isAccepted());
        assertEquals(2, decider.seen.size(),
                "ядро спрошено не заново — решение применено по устаревшему "
                        + "состоянию");
        // Второй вызов увидел версию после чужого изменения.
        assertEquals(1, decider.seen.get(1).getPost().getVersion(),
                "повторный вызов получил старую версию");
        assertEquals("DELETED", statusOf("p-1"));
        assertEquals(1, outboxCount(), "событие первой попытки уцелело");
    }

    @Test
    @DisplayName("при непрерывном конфликте ход сдаётся отказом")
    void givesUp() {
        decider.onCall = r -> {
            try {
                bumpPost();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        };

        assertThrows(VersionConflict.class,
                () -> handler(2).handle("u-1", "p-1", "t-1", "c-1", NOW));
        assertEquals(2, decider.seen.size(), "попыток сделано не столько, сколько задано");
    }

    /**
     * Поля события доезжают в полезной нагрузке. Событие без полей —
     * это уведомление «что-то произошло», по которому обработчик ничего
     * сделать не может.
     */
    @Test
    @DisplayName("поля события попадают в полезную нагрузку")
    void eventFieldsReachPayload() throws Exception {
        handler().handle("u-1", "p-1", "t-1", "c-1", NOW);

        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, payload, trace_id FROM outbox")) {
            assertTrue(rs.next());
            assertEquals("post.deleted", rs.getString(1));
            assertEquals("{\"actor\":\"u-1\"}", rs.getString(2));
            assertEquals("t-1", rs.getString(3), "трассировка не доехала");
        }
    }
}
