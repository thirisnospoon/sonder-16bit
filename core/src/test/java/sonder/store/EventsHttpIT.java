package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.auth.Passwords;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Поток обновлений по HTTP.
 *
 * <p>{@link FeedStreamIT} проверяет, КОМУ и КОГДА рассылать; здесь —
 * что соединение вообще открывается, отдаёт нужный тип содержимого и что
 * событие доезжает по проводу. Ни одно из этих свойств из логики рассылки
 * не следует: контроллер мог бы не отдать поток, отдать не тот тип или
 * не пустить по сессии, и рассылка об этом не узнала бы.
 *
 * <p>Соединение открывается напрямую, {@link HttpURLConnection}, а не
 * клиентом Spring: тот дочитывает тело до конца, а тело потока не
 * кончается никогда.
 *
 * <p>Расписание дренажа здесь заведомо не сработает — интервал в десять
 * минут, — а дренаж зовётся вручную. Так момент рассылки известен точно,
 * и чтение из потока не превращается в ожидание неизвестно чего.
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Контекст с включённой очередью не должен переживать свой класс: Spring
// кэширует контексты, и живой фоновый дренаж уже дважды ломал соседние
// классы тестов, к очереди отношения не имеющие.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext
                .ClassMode.AFTER_CLASS)
class EventsHttpIT {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    /** Сколько ждать строки из потока. Заведомо больше, чем нужно. */
    private static final int READ_TIMEOUT_MS = 5000;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty("sonder.it.jdbcUrl", ""));
        registry.add("spring.datasource.username",
                () -> System.getProperty("sonder.it.user", "sysdba"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("sonder.it.password", "masterkey"));
        // Дренажёр нужен бобом, а вот будить его сам никто не должен:
        // момент рассылки здесь известен точно, потому что дренаж
        // зовётся вручную. Расписание отодвинуто на десять минут, звонок
        // выключен совсем — иначе он разобрал бы очередь на вставке, и
        // читать из потока было бы нечего.
        registry.add("sonder.outbox.enabled", () -> "true");
        registry.add("sonder.outbox.doorbell", () -> "false");
        registry.add("sonder.outbox.poll-ms", () -> "600000");
        registry.add("sonder.outbox.initial-delay-ms", () -> "600000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxDrainer drainer;

    private String token;

    @BeforeEach
    void seed() throws Exception {
        assumeTrue(!System.getProperty("sonder.it.jdbcUrl", "").isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");

        try (Connection c = dataSource.getConnection()) {
            FirebirdSupport.wipe(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, nick, display_name, role, status,"
                            + " password_hash, version, created_at)"
                            + " VALUES ('u-1', 'andrey', 'Андрей', 'USER',"
                            + " 'ACTIVE', ?, 0, ?)")) {
                ps.setString(1, Passwords.hash("тайна"));
                ps.setTimestamp(2, Timestamp.from(T0));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES ('p-1', 'u-1', 'текст',"
                            + " 'VISIBLE', 0, ?)")) {
                ps.setTimestamp(1, Timestamp.from(T0));
                ps.executeUpdate();
            }
        }

        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("nick", "andrey");
        creds.put("password", "тайна");
        token = http.postForEntity("/auth/login", creds, Map.class)
                .getBody().get("token").toString();
    }

    private HttpURLConnection openStream(String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://localhost:" + port + "/events").openConnection();
        if (bearer != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setConnectTimeout(READ_TIMEOUT_MS);
        conn.connect();
        return conn;
    }

    @Test
    @DisplayName("без сессии поток не открывается")
    void requiresSession() {
        ResponseEntity<Map> response = http.getForEntity("/events", Map.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("SESSION_INVALID", response.getBody().get("code"),
                "отказ отдан не телом ошибки: клиент, получивший поток, "
                        + "ждал бы в нём событий, которых никогда не будет");
    }

    @Test
    @DisplayName("поток открывается как text/event-stream")
    void opensAsEventStream() throws Exception {
        HttpURLConnection conn = openStream(token);
        try {
            assertEquals(200, conn.getResponseCode());
            assertNotNull(conn.getContentType(), "тип содержимого не объявлен");
            assertTrue(conn.getContentType().startsWith("text/event-stream"),
                    "тип содержимого не тот, что объявляет контракт: "
                            + conn.getContentType());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * СКВОЗНАЯ ПРОВЕРКА. Соединение открыто, событие проходит весь путь —
     * очередь, дренаж, проекция, рассылка — и доезжает по проводу.
     */
    @Test
    @DisplayName("событие доезжает до открытого соединения")
    void eventReachesOpenStream() throws Exception {
        HttpURLConnection conn = openStream(token);
        try {
            // Заголовки получены — значит, соединение уже в списке
            // рассылки. Спать ради этого не нужно.
            assertEquals(200, conn.getResponseCode());

            enqueue("p-1", "post.created", "{\"authorId\":\"u-1\"}");
            OutboxDrainer.Result r = drainer.drainOnce(Instant.now());
            assertEquals(1, r.getPublished(), "событие не опубликовано");

            String data = readDataLine(conn);
            assertNotNull(data,
                    "из потока не пришло ни одной строки данных: событие "
                            + "опубликовано, но до соединения не доехало");
            assertTrue(data.contains("u-1"),
                    "в строке нет полезной нагрузки события: " + data);
        } finally {
            conn.disconnect();
        }
    }

    /** Первая строка данных из потока или {@code null}, если её нет. */
    private static String readDataLine(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8));
        for (int i = 0; i < 20; i++) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (line.startsWith("data:")) {
                return line;
            }
        }
        return null;
    }

    private void enqueue(String aggregateId, String type, String payload)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Outbox.append(c, new OutboxEvent(aggregateId, type, payload, "t-1"));
            c.commit();
        }
    }
}
