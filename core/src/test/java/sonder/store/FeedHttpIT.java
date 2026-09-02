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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Лента по HTTP.
 *
 * <p>Ядро тут не участвует и потому не подменяется: читать — не решать.
 * Проекция набивается напрямую, как её набил бы дренажёр, — предметом
 * проверки здесь является страница и курсор, а раскладку проверяет
 * {@link FeedProjectionIT}.
 *
 * <p>Главное здесь — ЛИСТАНИЕ. Страница по смещению на растущей ленте
 * показала бы часть постов дважды, а часть не показала бы вовсе, и
 * заметить это можно только на границе страниц: внутри одной страницы всё
 * выглядит правильно при любом способе.
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedHttpIT {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

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

        try (Connection c = dataSource.getConnection()) {
            FirebirdSupport.wipe(c);
            addUser(c, "u-1", "andrey", "Андрей", Passwords.hash("тайна"));
            addUser(c, "u-2", "boris", "Борис", "x");
        }

        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("nick", "andrey");
        creds.put("password", "тайна");
        token = http.postForEntity("/auth/login", creds, Map.class)
                .getBody().get("token").toString();
    }

    private static void addUser(Connection c, String id, String nick,
                                String displayName, String hash)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, ?, 'USER', 'ACTIVE', ?, 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, displayName);
            ps.setString(4, hash);
            ps.setTimestamp(5, Timestamp.from(T0));
            ps.executeUpdate();
        }
    }

    /**
     * Пост и строка ленты. Время задаётся, а не берётся у часов: лента
     * упорядочена по нему, и проверка порядка на «сейчас» проверяла бы
     * скорость машины.
     */
    private void addToFeed(String postId, String author, Instant at,
                           String status) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES (?, ?, ?, ?, 0, ?)")) {
                ps.setString(1, postId);
                ps.setString(2, author);
                ps.setString(3, "тело " + postId);
                ps.setString(4, status);
                ps.setTimestamp(5, Timestamp.from(at));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO feed_entries (owner_id, post_id, author_id,"
                            + " created_at) VALUES ('u-1', ?, ?, ?)")) {
                ps.setString(1, postId);
                ps.setString(2, author);
                ps.setTimestamp(3, Timestamp.from(at));
                ps.executeUpdate();
            }
        }
    }

    private void addToFeed(String postId, String author, Instant at)
            throws SQLException {
        addToFeed(postId, author, at, "VISIBLE");
    }

    private HttpEntity<Object> authed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<Map> feed(String query) {
        return http.exchange("/feed" + query, HttpMethod.GET, authed(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> idsOf(Map body) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) body.get("items")) {
            ids.add(item.get("postId").toString());
        }
        return ids;
    }

    @Test
    @DisplayName("без сессии лента не отдаётся")
    void requiresSession() {
        ResponseEntity<Map> response =
                http.getForEntity("/feed", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("SESSION_INVALID", response.getBody().get("code"));
    }

    @Test
    @DisplayName("пустая лента — пустой список, а не отказ")
    void emptyFeed() {
        ResponseEntity<Map> response = feed("");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(idsOf(response.getBody()).isEmpty());
        assertEquals(Boolean.FALSE, response.getBody().get("hasMore"));
        assertNull(response.getBody().get("nextCursor"),
                "курсор у пустой ленты зовёт за пустой страницей");
    }

    @Test
    @DisplayName("лента отдаётся новым сверху, с автором и телом")
    void newestFirst() throws Exception {
        addToFeed("p-1", "u-2", T0);
        addToFeed("p-2", "u-2", T0.plusSeconds(60));

        ResponseEntity<Map> response = feed("");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(java.util.Arrays.asList("p-2", "p-1"),
                idsOf(response.getBody()), "порядок не по времени убыванием");

        @SuppressWarnings("unchecked")
        Map<String, Object> first =
                ((List<Map<String, Object>>) response.getBody().get("items")).get(0);
        assertEquals("тело p-2", first.get("body"), "тело не подтянулось");
        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) first.get("author");
        assertEquals("u-2", author.get("userId"));
        assertEquals("boris", author.get("nick"));
        assertEquals("Борис", author.get("displayName"));
        assertNotNull(first.get("createdAt"));
    }

    /**
     * ГЛАВНАЯ ПРОВЕРКА. Между страницами лента растёт, и курсор обязан
     * это пережить: со смещением второй страницы съехали бы на один пост,
     * и один из них клиент увидел бы дважды, а другого не увидел бы вовсе.
     */
    @Test
    @DisplayName("вставка между страницами не сдвигает листание")
    void cursorSurvivesInsertion() throws Exception {
        for (int i = 1; i <= 4; i++) {
            addToFeed("p-" + i, "u-2", T0.plusSeconds(i * 60));
        }

        ResponseEntity<Map> first = feed("?limit=2");
        assertEquals(java.util.Arrays.asList("p-4", "p-3"), idsOf(first.getBody()));
        assertEquals(Boolean.TRUE, first.getBody().get("hasMore"));
        String cursor = first.getBody().get("nextCursor").toString();
        assertNotNull(cursor, "курсор не выдан, а листать есть что");

        // Пока клиент читал первую страницу, сверху добавился пост.
        addToFeed("p-9", "u-2", T0.plusSeconds(600));

        ResponseEntity<Map> second = feed("?limit=2&cursor=" + cursor);
        assertEquals(java.util.Arrays.asList("p-2", "p-1"),
                idsOf(second.getBody()),
                "листание съехало: страница по смещению повторила бы пост");
        assertEquals(Boolean.FALSE, second.getBody().get("hasMore"));
        assertNull(second.getBody().get("nextCursor"),
                "курсор выдан там, где листать нечего");
    }

    /**
     * Посты одной секунды. Время их не различает, и без идентификатора в
     * ключе страница либо потеряла бы один, либо выдала дважды.
     */
    @Test
    @DisplayName("посты одного времени листаются без потерь и повторов")
    void tiesAreStable() throws Exception {
        addToFeed("p-a", "u-2", T0);
        addToFeed("p-b", "u-2", T0);
        addToFeed("p-c", "u-2", T0);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            ResponseEntity<Map> r = feed(
                    "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(idsOf(r.getBody()));
            if (!Boolean.TRUE.equals(r.getBody().get("hasMore"))) {
                break;
            }
            cursor = r.getBody().get("nextCursor").toString();
        }

        assertEquals(java.util.Arrays.asList("p-c", "p-b", "p-a"), seen,
                "листание по одинаковому времени сбилось");
    }

    /** Невидимый пост не показывается, даже если строка ленты уцелела. */
    @Test
    @DisplayName("удалённый пост не показывается, даже пока строка ленты жива")
    void deletedPostHidden() throws Exception {
        addToFeed("p-1", "u-2", T0);
        addToFeed("p-2", "u-2", T0.plusSeconds(60), "DELETED");

        ResponseEntity<Map> response = feed("");

        assertEquals(java.util.Arrays.asList("p-1"), idsOf(response.getBody()),
                "лента показала удалённый пост: очередь могла ещё не дойти");
    }

    @Test
    @DisplayName("предел за границей контракта отвергается, а не подгоняется")
    void limitOutOfContractRejected() {
        for (String bad : new String[]{"0", "51", "-1"}) {
            ResponseEntity<Map> response = feed("?limit=" + bad);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "предел " + bad + " принят молча");
            assertEquals("MALFORMED_REQUEST", response.getBody().get("code"));
        }
    }

    @Test
    @DisplayName("негодный курсор — отказ клиенту, а не отказ сервера")
    void brokenCursorRejected() {
        for (String bad : new String[]{"не-base64!!", "Zm9v", "OjEyMw"}) {
            ResponseEntity<Map> response = feed("?cursor=" + bad);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "курсор «" + bad + "» не отвергнут");
            assertEquals("MALFORMED_REQUEST", response.getBody().get("code"));
        }
    }

    /** Чужая лента не показывается: владелец берётся из сессии. */
    @Test
    @DisplayName("лента показывает только свои строки")
    void showsOnlyOwnFeed() throws Exception {
        addToFeed("p-1", "u-2", T0);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES ('p-чужой', 'u-2', 'чужое',"
                            + " 'VISIBLE', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(T0.plusSeconds(600)));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO feed_entries (owner_id, post_id, author_id,"
                            + " created_at) VALUES ('u-2', 'p-чужой', 'u-2', ?)")) {
                ps.setTimestamp(1, Timestamp.from(T0.plusSeconds(600)));
                ps.executeUpdate();
            }
        }

        ResponseEntity<Map> response = feed("");

        assertEquals(java.util.Arrays.asList("p-1"), idsOf(response.getBody()),
                "в ленту попала чужая строка");
        assertFalse(idsOf(response.getBody()).contains("p-чужой"));
    }
}
