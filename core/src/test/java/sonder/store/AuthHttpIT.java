package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.shell.auth.Passwords;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Вход, выход и «кто я» по настоящему HTTP против настоящей базы.
 *
 * <p>Проверяется то, чего не видно ни в модульном тесте контроллера, ни в
 * тесте хранилища: заголовки, статусы, кодировка ответа и то, что
 * приложение вообще поднимается с этой конфигурацией. Приложение,
 * собирающееся и не стартующее, — обычное дело, и узнавать об этом лучше
 * здесь.
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Адрес приходит от ops/ci/run-it.sh тем же свойством, что и
        // остальным интеграционным тестам.
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

    @BeforeEach
    void seed() throws Exception {
        assumeTrue(!System.getProperty("sonder.it.jdbcUrl", "").isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");
        disableStreaming();
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
    }

    private ResponseEntity<Map> login(String nick, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("nick", nick);
        body.put("password", password);
        return http.postForEntity("/auth/login", body, Map.class);
    }

    private static HttpEntity<Void> withToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.COOKIE, "sonder_session=" + token);
        }
        return new HttpEntity<>(headers);
    }

    /**
     * Вход отвечает ровно так, как объявляет контракт.
     *
     * <p>Эта проверка написана после того, как расхождение нашлось не
     * ею. Оболочка отдавала токен ТЕЛОМ ответа, а контракт говорит:
     * «Сессия выдаётся кукой HttpOnly; Secure; SameSite=Strict. Токенов
     * в localStorage нет: их читает любой скрипт на странице». Сверка
     * маршрутов такого не ловит — она смотрит пути и методы, а не то,
     * чем оканчивается вход, — и держалось расхождение до первого
     * настоящего подъёма всей системы, когда фронт, построенный по
     * контракту, не смог войти.
     *
     * <p>Поэтому сверяются ВСЕ объявленные свойства, а не факт успеха.
     */
    @Test
    @DisplayName("вход отвечает 204 и кукой, а не токеном в теле")
    void loginSucceeds() {
        ResponseEntity<Map> response = login("andrey", "тайна");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "контракт объявляет 204");
        assertNull(response.getBody(), "тело у ответа на вход быть не должно");

        String header = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(header, "вход не выдал куки");
        assertTrue(header.startsWith("sonder_session="), "чужая кука: " + header);
        assertTrue(header.contains("HttpOnly"),
                "кука читается скриптом — ровно то, что контракт запрещает");
        assertTrue(header.contains("SameSite=Strict"),
                "без SameSite кука поедет с чужой страницы");

        assertEquals(43, sessionOf(response).length(), "длина токена не та");
    }

    @Test
    @DisplayName("ник узнаётся без учёта регистра")
    void loginIgnoresNickCase() {
        assertEquals(
                HttpStatus.NO_CONTENT, login("ANDREY", "тайна").getStatusCode());
    }

    /**
     * Неизвестный ник и неверный пароль отвечают ОДИНАКОВО. Разные ответы
     * превратили бы вход в перечислитель учётных записей: злоумышленник
     * узнавал бы существующие ники, не зная ни одного пароля.
     */
    @Test
    @DisplayName("неизвестный ник неотличим от неверного пароля")
    void unknownNickIndistinguishableFromWrongPassword() {
        ResponseEntity<Map> wrongPassword = login("andrey", "не тайна");
        ResponseEntity<Map> unknownNick = login("нет-такого", "тайна");

        assertEquals(HttpStatus.UNAUTHORIZED, wrongPassword.getStatusCode());
        assertEquals(unknownNick.getStatusCode(), wrongPassword.getStatusCode(),
                "статусы различаются — вход стал перечислителем учётных записей");
        assertEquals(unknownNick.getBody().get("code"),
                wrongPassword.getBody().get("code"),
                "коды отказа различаются");
        assertEquals("CREDENTIALS_INVALID", wrongPassword.getBody().get("code"));
        assertNotNull(wrongPassword.getBody().get("traceId"),
                "контракт требует traceId в теле отказа");
    }

    @Test
    @DisplayName("токен опознаёт пользователя, кириллица доезжает")
    void meReturnsUser() {
        String token = sessionOf(login("andrey", "тайна"));

        ResponseEntity<Map> me = http.exchange("/auth/me", HttpMethod.GET,
                withToken(token), Map.class);

        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals("u-1", me.getBody().get("userId"));
        assertEquals("andrey", me.getBody().get("nick"));
        assertEquals("Андрей", me.getBody().get("displayName"),
                "кириллица в ответе испортилась");
        assertEquals("USER", me.getBody().get("role"));
    }

    @Test
    @DisplayName("без токена и с чужим токеном — один и тот же отказ")
    void meRejectsBadToken() {
        ResponseEntity<Map> none = http.exchange("/auth/me", HttpMethod.GET,
                withToken(null), Map.class);
        ResponseEntity<Map> bogus = http.exchange("/auth/me", HttpMethod.GET,
                withToken("совершенно-выдуманный-токен"), Map.class);

        assertEquals(HttpStatus.UNAUTHORIZED, none.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, bogus.getStatusCode());
        assertEquals("SESSION_INVALID", bogus.getBody().get("code"));
    }

    /**
     * Заголовок без схемы Bearer токеном не считается. Иначе строка,
     * случайно попавшая в заголовок, иногда «срабатывала бы».
     */
    @Test
    @DisplayName("заголовок без схемы Bearer не принимается")
    void rawHeaderIsNotAToken() {
        String token = sessionOf(login("andrey", "тайна"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        ResponseEntity<Map> me = http.exchange("/auth/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertEquals(HttpStatus.UNAUTHORIZED, me.getStatusCode());
    }

    @Test
    @DisplayName("после выхода токен не действует, повторный выход не ошибка")
    void logoutIsIdempotent() {
        String token = sessionOf(login("andrey", "тайна"));

        ResponseEntity<Void> first = http.exchange("/auth/logout", HttpMethod.POST,
                withToken(token), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode());

        ResponseEntity<Map> me = http.exchange("/auth/me", HttpMethod.GET,
                withToken(token), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, me.getStatusCode(),
                "токен действует после выхода");

        ResponseEntity<Void> second = http.exchange("/auth/logout", HttpMethod.POST,
                withToken(token), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, second.getStatusCode(),
                "повторный выход сообщил об ошибке — это подтвердило бы, "
                        + "что сессия существовала");
    }

    /**
     * Сессия действительно записана в базу, а не живёт в памяти
     * приложения. Сессия в памяти переживёт ровно до перезапуска, и
     * заметят это пользователи.
     */
    @Test
    @DisplayName("сессия хранится в базе, а не в памяти")
    void sessionIsPersisted() throws SQLException {
        String token = sessionOf(login("andrey", "тайна"));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "сессии нет в базе");
                assertEquals("u-1", rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("пустое тело запроса на вход не роняет приложение")
    void emptyLoginBody() {
        ResponseEntity<Map> response = http.postForEntity("/auth/login",
                Collections.emptyMap(), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("CREDENTIALS_INVALID", response.getBody().get("code"));
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
