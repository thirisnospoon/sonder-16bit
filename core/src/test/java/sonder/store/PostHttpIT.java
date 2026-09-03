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
import sonder.contract.decider.EventField;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Посты по HTTP: создание, чтение, удаление.
 *
 * <p>Ядро подменено бобом, который отвечает по сценарию. Подменяется
 * интерфейс из контракта, порождённый CXF, а не выдуманный: когда мост
 * «SOAP ↔ линия» появится в фазе 8, он реализует ровно этот интерфейс, и
 * контроллеры менять не придётся.
 *
 * <p>В бою на его месте стоит {@code UnavailableDecider}, который честно
 * отвечает 502: моста ещё нет, и делать вид, что есть, хуже.
 */
@SpringBootTest(
        classes = {Application.class, PostHttpIT.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostHttpIT {

    /** Подменное ядро. Сценарий задаётся статически: бобы создаются один
     *  раз, а тесту нужно менять ответ от случая к случаю. */
    static Decision answer = accepted("post.created");

    static Decision accepted(String type) {
        Decision d = new Decision();
        d.setAccepted(true);
        DomainEvent e = new DomainEvent();
        e.setType(type);
        e.setAggregateId("p-x");
        EventField f = new EventField();
        f.setKey("authorId");
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

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        public Decider testDecider() {
            return new Decider() {
                @Override public Decision createPost(CreatePostRequest r) { return answer; }
                @Override public Decision deletePost(DeletePostRequest r) { return answer; }
                @Override public Decision registerUser(RegisterUserRequest r) { return answer; }
                @Override public Decision createComment(CreateCommentRequest r) { return answer; }
                @Override public Decision followUser(FollowUserRequest r) { return answer; }
                @Override public Decision unfollowUser(UnfollowUserRequest r) { return answer; }
                @Override public Decision banUser(BanUserRequest r) { return answer; }
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
        answer = accepted("post.created");

        try (Connection c = dataSource.getConnection()) {
            FirebirdSupport.wipe(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, nick, display_name, role, status,"
                            + " password_hash, version, created_at)"
                            + " VALUES ('u-1', 'andrey', 'Андрей', 'USER',"
                            + " 'ACTIVE', ?, 0, ?)")) {
                ps.setString(1, Passwords.hash("тайна"));
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        }

        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("nick", "andrey");
        creds.put("password", "тайна");
        token = sessionOf(http.postForEntity("/auth/login", creds, Map.class));
    }

    private HttpEntity<Object> authed(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "sonder_session=" + token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<Map> create(String body) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("body", body);
        return http.exchange("/posts", HttpMethod.POST, authed(payload), Map.class);
    }

    @Test
    @DisplayName("принятая команда создаёт пост и событие")
    void createAccepted() throws Exception {
        ResponseEntity<Map> response = create("Первый пост & последний");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        String postId = response.getBody().get("postId").toString();
        assertTrue(postId.startsWith("p-"), "идентификатор не той формы: " + postId);

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT body FROM posts")) {
            assertTrue(rs.next(), "пост не сохранился");
            assertEquals("Первый пост & последний", rs.getString(1),
                    "тело поста испортилось по дороге");
        }
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, payload FROM outbox")) {
            assertTrue(rs.next(), "событие не записано");
            assertEquals("post.created", rs.getString(1));
            assertEquals("{\"authorId\":\"u-1\"}", rs.getString(2));
        }
    }

    /**
     * Статус ответа берётся ИЗ КОНТРАКТА по коду отказа. Проверяется на
     * трёх разных категориях: если бы статус писался рядом с каждым
     * отказом, разойтись он мог бы в любой из них.
     */
    @Test
    @DisplayName("код отказа переводится в статус по контракту")
    void errorCodeMapsToContractStatus() {
        answer = rejected("POST_BODY_TOO_LONG");
        ResponseEntity<Map> tooLong = create("x");
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatusCode());
        assertEquals("POST_BODY_TOO_LONG", tooLong.getBody().get("code"));
        assertNotNull(tooLong.getBody().get("traceId"),
                "контракт требует traceId в теле отказа");

        answer = rejected("ACTOR_BANNED");
        assertEquals(HttpStatus.FORBIDDEN, create("x").getStatusCode());

        answer = rejected("POST_RATE_EXCEEDED");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, create("x").getStatusCode());
    }

    /**
     * Код, которого Java не знает, — расхождение контрактов, а не
     * пользовательская ошибка. Тихо превратить его в 400 значило бы
     * показать «вы неправильно заполнили форму» там, где разъехались две
     * сборки.
     */
    @Test
    @DisplayName("неизвестный код отказа виден как расхождение контрактов")
    void unknownErrorCodeIsLoud() {
        answer = rejected("КОД_ИЗ_БУДУЩЕГО");
        ResponseEntity<Map> response = create("x");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody(),
                "тело отказа пустое. Tomcat подменяет ответ 5xx БЕЗ ТЕЛА "
                        + "своей страницей ошибки, теряя всё, что положила "
                        + "оболочка");
        assertEquals("UNKNOWN_ERROR_CODE", response.getBody().get("code"));
        assertTrue(response.getBody().get("detail").toString()
                        .contains("КОД_ИЗ_БУДУЩЕГО"),
                "в подробностях нет кода, который не опознан");
        assertNotNull(response.getBody().get("traceId"),
                "контракт требует traceId в теле любого отказа");
    }

    @Test
    @DisplayName("отказ ничего не пишет")
    void rejectedWritesNothing() throws Exception {
        answer = rejected("POST_BODY_EMPTY");
        create("   ");

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM posts")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "пост записан при отказе");
        }
    }

    @Test
    @DisplayName("без сессии команда не проходит")
    void noSessionRejected() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("body", "x");
        ResponseEntity<Map> response = http.postForEntity("/posts", payload, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("созданный пост читается обратно")
    void readBack() {
        String postId = create("Текст поста").getBody().get("postId").toString();

        ResponseEntity<Map> got = http.exchange("/posts/" + postId,
                HttpMethod.GET, authed(null), Map.class);

        assertEquals(HttpStatus.OK, got.getStatusCode());
        assertEquals(postId, got.getBody().get("postId"));
        assertEquals("Текст поста", got.getBody().get("body"));
        assertNotNull(got.getBody().get("createdAt"), "нет времени создания");
        Map<?, ?> author = (Map<?, ?>) got.getBody().get("author");
        assertEquals("andrey", author.get("nick"));
        assertEquals("Андрей", author.get("displayName"));
    }

    /**
     * Удалённый пост неотличим от несуществующего. Иначе ответ сообщал бы,
     * что такой пост когда-то был.
     */
    @Test
    @DisplayName("удалённый пост читается так же, как несуществующий")
    void deletedLooksMissing() throws SQLException {
        String postId = create("Текст").getBody().get("postId").toString();

        answer = accepted("post.deleted");
        ResponseEntity<Void> deleted = http.exchange("/posts/" + postId,
                HttpMethod.DELETE, authed(null), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());

        ResponseEntity<Map> got = http.exchange("/posts/" + postId,
                HttpMethod.GET, authed(null), Map.class);
        ResponseEntity<Map> missing = http.exchange("/posts/нет-такого",
                HttpMethod.GET, authed(null), Map.class);

        assertEquals(HttpStatus.NOT_FOUND, got.getStatusCode());
        assertEquals(missing.getStatusCode(), got.getStatusCode());
        assertEquals(missing.getBody().get("code"), got.getBody().get("code"));

        // Строка при этом на месте: событие post.deleted уже в очереди, и
        // обработчику может понадобиться то, что он обрабатывает.
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status FROM posts WHERE id = '" + postId + "'")) {
            assertTrue(rs.next(), "строка удалена вместе со статусом");
            assertEquals("DELETED", rs.getString(1));
        }
    }

    /**
     * В бою ядра ещё нет, и заглушка обязана отвечать 502, а не делать
     * вид. Проверяется на настоящем бобе приложения, а не на подмене.
     */
    @Test
    @DisplayName("без моста к ядру команда отвечает 502")
    void withoutBridgeUnavailable() {
        sonder.shell.decider.UnavailableDecider real = new sonder.shell.decider.UnavailableDecider();
        Decision d = real.createPost(new CreatePostRequest());
        assertEquals("DECIDER_UNAVAILABLE", d.getErrorCode());
        assertEquals(HttpStatus.BAD_GATEWAY,
                HttpStatus.valueOf(sonder.contract.ErrorCode
                        .valueOf(d.getErrorCode()).httpStatus()));
        assertTrue(sonder.contract.ErrorCode.DECIDER_UNAVAILABLE.retryable(),
                "недоступность ядра обязана быть повторяемой");
    }

    /**
     * Клиент JDK на ответе 401 С ТЕЛОМ пытается повторить запрос с
     * аутентификацией и падает: тело запроса уже отдано потоком.
     * {@code HttpRetryException: cannot retry due to server
     * authentication, in streaming mode}.
     *
     * <p>Это особенность тестового клиента, а не продукта: браузер и
     * любой нормальный клиент такой ответ обрабатывают. Отключаем
     * потоковую отправку — запрос буферизуется и повтор становится
     * возможен.
     */
    private void disableStreaming() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        http.getRestTemplate().setRequestFactory(factory);
    }

    /**
     * Значение куки сессии из ответа на вход.
     *
     * <p>Контракт объявляет вход так: 204 и кука. Токена в теле нет и
     * быть не должно — всякое место, куда клиент его положил бы,
     * читается сценарием, попавшим на страницу.
     */
    private static String sessionOf(org.springframework.http.ResponseEntity<?> login) {
        String header = login.getHeaders().getFirst(
                org.springframework.http.HttpHeaders.SET_COOKIE);
        if (header == null) {
            throw new AssertionError("вход не выдал куки");
        }
        int eq = header.indexOf('=');
        int end = header.indexOf(';');
        return header.substring(eq + 1, end < 0 ? header.length() : end);
    }

}
