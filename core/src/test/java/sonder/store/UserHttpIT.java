package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.contract.decider.BanUserRequest;
import sonder.contract.decider.CreateCommentRequest;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DeletePostRequest;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.FollowUserRequest;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PingResponse;
import sonder.contract.decider.RegisterUserRequest;
import sonder.contract.decider.UnfollowUserRequest;
import sonder.shell.auth.Passwords;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Пользователи по HTTP: регистрация, чтение, подписка, блокировка.
 *
 * <p>Ядро подменено; проверяется, что оболочка доносит до него состояние,
 * а его решение — до клиента и до базы. Что именно решать, ядро знает
 * само, и подменять его правила тест не пытается: он подменяет ОТВЕТ.
 */
@SpringBootTest(
        classes = {Application.class, UserHttpIT.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserHttpIT {

    static Decision answer = accepted("user.registered", "u-x");
    static RegisterUserRequest lastRegister;
    static FollowUserRequest lastFollow;
    static BanUserRequest lastBan;
    static UnfollowUserRequest lastUnfollow;

    static Decision accepted(String type, String aggregateId) {
        Decision d = new Decision();
        d.setAccepted(true);
        DomainEvent e = new DomainEvent();
        e.setType(type);
        e.setAggregateId(aggregateId);
        d.getEvent().add(e);
        return d;
    }

    static Decision rejected(String code) {
        Decision d = new Decision();
        d.setAccepted(false);
        d.setErrorCode(code);
        return d;
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        public Decider testDecider() {
            return new Decider() {
                @Override
                public Decision registerUser(RegisterUserRequest r) {
                    lastRegister = r;
                    return answer;
                }

                @Override
                public Decision followUser(FollowUserRequest r) {
                    lastFollow = r;
                    return answer;
                }

                @Override
                public Decision banUser(BanUserRequest r) {
                    lastBan = r;
                    return answer;
                }

                @Override
                public Decision unfollowUser(UnfollowUserRequest r) {
                    lastUnfollow = r;
                    return answer;
                }

                @Override public Decision createPost(CreatePostRequest r) { return answer; }
                @Override public Decision deletePost(DeletePostRequest r) { return answer; }
                @Override public Decision createComment(CreateCommentRequest r) { return answer; }
                @Override public PingResponse ping(PingRequest r) { return new PingResponse(); }
            };
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty("sonder.it.jdbcUrl", ""));
        registry.add("spring.datasource.username",
                () -> System.getProperty("sonder.it.user", "sysdba"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("sonder.it.password", "masterkey"));
        // Фоновый дренаж очереди в этих тестах ВЫКЛЮЧЕН намеренно. Он
        // разбирает outbox сам по себе, а здесь проверяется в том числе
        // то, что в очереди лежит: тест, проверяющий содержимое очереди
        // при работающем потребителе, проверял бы гонку. Дренаж как
        // таковой проверяется отдельно, там, где его зовут явно.
        registry.add("sonder.outbox.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private DataSource dataSource;

    private String token;

    @BeforeEach
    void seed() throws Exception {
        assumeTrue(!System.getProperty("sonder.it.jdbcUrl", "").isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");
        disableStreaming();
        answer = accepted("user.registered", "u-x");
        lastRegister = null;
        lastFollow = null;
        lastBan = null;
        lastUnfollow = null;

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            FirebirdSupport.wipe(c);
            addUser(c, "u-1", "andrey", "USER");
            addUser(c, "u-2", "maria", "USER");
            addUser(c, "u-mod", "moder", "MODERATOR");
        }
        token = loginAs("andrey");
    }

    private void addUser(Connection c, String id, String nick, String role)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, 'Имя', ?, 'ACTIVE', ?, 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, role);
            ps.setString(4, Passwords.hash("тайна"));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private String loginAs(String nick) {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("nick", nick);
        creds.put("password", "тайна");
        return http.postForEntity("/auth/login", creds, Map.class)
                .getBody().get("token").toString();
    }

    private HttpEntity<Object> authed(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<Map> register(String nick, String name, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("nick", nick);
        body.put("displayName", name);
        body.put("password", password);
        return http.postForEntity("/users", body, Map.class);
    }

    @Test
    @DisplayName("регистрация создаёт пользователя и событие")
    void registerCreatesUser() throws Exception {
        ResponseEntity<Map> response = register("новичок", "Новичок", "пароль123");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        String userId = response.getBody().get("userId").toString();
        assertTrue(userId.startsWith("u-"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT nick, display_name, password_hash FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "пользователь не сохранён");
                assertEquals("новичок", rs.getString(1));
                assertEquals("Новичок", rs.getString(2));
                assertTrue(Passwords.matches("пароль123", rs.getString(3)),
                        "пароль сохранён не хэшем или не тот");
            }
        }
    }

    /**
     * Пароль до ядра не доходит. Контракт операции его и не объявляет:
     * ядро об учётных данных не знает вовсе — проверка требует хранилища,
     * которого под DOS нет.
     */
    @Test
    @DisplayName("пароль не уходит к ядру")
    void passwordNeverReachesCore() {
        register("новичок", "Новичок", "очень-секретный-пароль");

        assertNotNull(lastRegister, "ядро не было вызвано");
        String sent = lastRegister.toString() + lastRegister.getCommand().getNick()
                + lastRegister.getCommand().getDisplayName()
                + lastRegister.getCommand().getUserId();
        assertFalse(sent.contains("очень-секретный-пароль"),
                "пароль просочился в запрос к ядру");
    }

    /**
     * Нет тела или нет пароля — это НЕ доменный отказ. Ответить доменным
     * кодом значило бы сказать пользователю неправду о том, что он сделал
     * не так.
     */
    @Test
    @DisplayName("запрос без пароля отвергается оболочкой, а не ядром")
    void missingPasswordIsShellError() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("nick", "новичок");
        body.put("displayName", "Новичок");
        ResponseEntity<Map> response = http.postForEntity("/users", body, Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MALFORMED_REQUEST", response.getBody().get("code"));
        assertNotNull(response.getBody().get("traceId"));
        assertNull2(lastRegister);
    }

    private static void assertNull2(Object o) {
        assertTrue(o == null, "ядро вызвано на запросе, который до него не должен дойти");
    }

    @Test
    @DisplayName("занятый ник — отказ ядра, а не оболочки")
    void nickTakenComesFromCore() {
        answer = rejected("NICK_TAKEN");
        ResponseEntity<Map> response = register("andrey", "Другой", "пароль123");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("NICK_TAKEN", response.getBody().get("code"));
        assertNotNull(lastRegister, "ядро не было спрошено");
        assertTrue(lastRegister.getNick().isTaken(),
                "ядру пришло состояние «ник свободен», хотя он занят");
    }

    @Test
    @DisplayName("пользователь читается по нику без учёта регистра")
    void getUserIgnoresCase() {
        ResponseEntity<Map> got = http.exchange("/users/ANDREY", HttpMethod.GET,
                authed(null), Map.class);
        assertEquals(HttpStatus.OK, got.getStatusCode());
        assertEquals("u-1", got.getBody().get("userId"));
        assertEquals("andrey", got.getBody().get("nick"));
    }

    @Test
    @DisplayName("несуществующий ник — отказ оболочки")
    void getUserMissing() {
        ResponseEntity<Map> got = http.exchange("/users/нет-такого", HttpMethod.GET,
                authed(null), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, got.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", got.getBody().get("code"));
    }

    @Test
    @DisplayName("подписка создаётся и ядро видит состояние")
    void followCreatesEdge() throws Exception {
        answer = accepted("user.followed", "u-2");
        ResponseEntity<Map> response = http.exchange("/users/maria/follow",
                HttpMethod.PUT, authed(null), Map.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNotNull(lastFollow, "ядро не было спрошено");
        assertEquals("u-1", lastFollow.getActor().getUserId());
        assertTrue(lastFollow.getTarget().isExists(), "цель пришла несуществующей");
        assertFalse(lastFollow.getFollow().isAlreadyFollowing(),
                "ядру сказали, что подписка уже есть");

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM follows WHERE follower_id = 'u-1'"
                             + " AND target_id = 'u-2'")) {
            rs.next();
            assertEquals(1, rs.getLong(1), "подписка не записана");
        }
    }

    /**
     * Повторная подписка: ядру приходит состояние «уже подписан», и решает
     * оно. Оболочка не проверяет этого сама — иначе правило жило бы в двух
     * местах.
     */
    @Test
    @DisplayName("при повторной подписке ядро видит, что она уже есть")
    void followTwiceTellsCore() throws Exception {
        answer = accepted("user.followed", "u-2");
        http.exchange("/users/maria/follow", HttpMethod.PUT, authed(null), Map.class);

        answer = rejected("ALREADY_FOLLOWING");
        ResponseEntity<Map> second = http.exchange("/users/maria/follow",
                HttpMethod.PUT, authed(null), Map.class);

        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("ALREADY_FOLLOWING", second.getBody().get("code"));
        assertTrue(lastFollow.getFollow().isAlreadyFollowing(),
                "ядру не сказали, что подписка уже есть — оно решило бы вслепую");
    }

    @Test
    @DisplayName("подписка на несуществующий ник — отказ оболочки до ядра")
    void followUnknownNick() {
        ResponseEntity<Map> response = http.exchange("/users/нет-такого/follow",
                HttpMethod.PUT, authed(null), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().get("code"));
    }

    @Test
    @DisplayName("отписка удаляет связь и ядро видит, что подписка есть")
    void unfollowRemovesEdge() throws Exception {
        answer = accepted("user.followed", "u-2");
        http.exchange("/users/maria/follow", HttpMethod.PUT, authed(null), Map.class);

        answer = accepted("follow.removed", "u-1");
        ResponseEntity<Map> response = http.exchange("/users/maria/follow",
                HttpMethod.DELETE, authed(null), Map.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNotNull(lastUnfollow, "ядро не было спрошено об отписке");
        assertTrue(lastUnfollow.getFollow().isAlreadyFollowing(),
                "ядру сказали, что подписки нет, хотя она была");
        assertEquals("u-2", lastUnfollow.getCommand().getTargetUserId());

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM follows WHERE follower_id = 'u-1'")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "подписка не удалена");
        }
    }

    /**
     * Отписка без подписки — отказ ЯДРА, а не оболочки. Оболочка не
     * проверяет этого сама: правило жило бы в двух местах.
     */
    @Test
    @DisplayName("отписка без подписки отвергается ядром")
    void unfollowWithoutFollow() throws Exception {
        answer = rejected("NOT_FOLLOWING");
        ResponseEntity<Map> response = http.exchange("/users/maria/follow",
                HttpMethod.DELETE, authed(null), Map.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("NOT_FOLLOWING", response.getBody().get("code"));
        assertNotNull(lastUnfollow, "ядро не было спрошено");
        assertFalse(lastUnfollow.getFollow().isAlreadyFollowing(),
                "ядру приехало неверное состояние подписки");
    }

    @Test
    @DisplayName("блокировка меняет статус и ядро видит версию цели")
    void banChangesStatus() throws Exception {
        token = loginAs("moder");
        answer = accepted("user.banned", "u-2");

        Map<String, String> body = new LinkedHashMap<>();
        body.put("reason", "нарушение правил");
        ResponseEntity<Map> response = http.exchange("/admin/users/maria/ban",
                HttpMethod.POST, authed(body), Map.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNotNull(lastBan, "ядро не было спрошено");
        assertTrue(lastBan.getTarget().getVersion() >= 0,
                "версия цели не приехала — оптимистическая блокировка "
                        + "работать не будет");
        assertEquals("нарушение правил", lastBan.getCommand().getReason());

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status, version FROM users WHERE id = 'u-2'")) {
            rs.next();
            assertEquals("BANNED", rs.getString(1));
            assertEquals(1, rs.getInt(2), "версия не выросла при записи");
        }
    }

    @Test
    @DisplayName("отказ ядра при блокировке ничего не меняет")
    void banRejectedChangesNothing() throws Exception {
        answer = rejected("ROLE_INSUFFICIENT");

        Map<String, String> body = new LinkedHashMap<>();
        body.put("reason", "просто так");
        ResponseEntity<Map> response = http.exchange("/admin/users/maria/ban",
                HttpMethod.POST, authed(body), Map.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ROLE_INSUFFICIENT", response.getBody().get("code"));

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status FROM users WHERE id = 'u-2'")) {
            rs.next();
            assertEquals("ACTIVE", rs.getString(1), "статус изменён при отказе");
        }
    }

    /**
     * Клиент JDK на ответе 401 с телом пытается повторить запрос с
     * аутентификацией и падает на потоковой отправке. Особенность
     * тестового клиента, не продукта.
     */
    private void disableStreaming() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        http.getRestTemplate().setRequestFactory(factory);
    }
}
