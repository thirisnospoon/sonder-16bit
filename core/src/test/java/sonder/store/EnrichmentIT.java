package sonder.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sonder.enrichment.NotFound;
import sonder.enrichment.PostView;
import sonder.shell.enrichment.EnrichmentClient;
import sonder.shell.enrichment.EnrichmentServant;
import sonder.shell.enrichment.EnrichmentServer;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обогащение по IIOP: настоящий вызов через настоящий ORB.
 *
 * <p>Оба конца в одном процессе, но ORB у каждого свой и вызов идёт по
 * сети через петлю: сервант не вызывается напрямую, а разворачивается из
 * IOR. Всё, что могло бы сломаться на границе — маршалинг структуры,
 * кодировка, проброс исключения, — здесь ломается так же, как ломалось бы
 * между машинами.
 *
 * <p><b>Кириллица тут не украшение.</b> Спайк S5 выяснил прогоном, что тип
 * {@code string} в CORBA байтовый и падает на не-ASCII с
 * {@code DATA_CONVERSION} — в рантайме, на настоящих данных. В IDL
 * человеческий текст поэтому объявлен {@code wstring}, и эта проверка
 * сторожит, чтобы объявление не переписали обратно «для простоты».
 */
class EnrichmentIT extends FirebirdSupport {

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00Z");

    /** Тело поста: кириллица, амперсанд и эмодзи вне базовой плоскости. */
    private static final String BODY = "Первый пост & последний 🎈";

    @TempDir
    Path tmp;

    private EnrichmentServer server;
    private EnrichmentClient client;

    @BeforeAll
    static void migrate() throws Exception {
        prepareDatabase();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = connect()) {
            wipe(c);
            addUser(c, "u-1", "andrey", "Андрей");
            addPost(c, "p-1", "u-1", BODY, "VISIBLE");
            addPost(c, "p-del", "u-1", "стёртый", "DELETED");
        }

        File ior = tmp.resolve("enrichment.ior").toFile();
        // 127.0.0.1 и порт «любой свободный»: в тесте важна не топология,
        // а то, что вызов идёт через IOR, а не мимо него.
        server = EnrichmentServer.start(
                new EnrichmentServant(FirebirdSupport::connect), "127.0.0.1", 0, ior);
        client = EnrichmentClient.connect(ior);
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static void addUser(Connection c, String id, String nick, String name)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, nick, display_name, role, status,"
                        + " password_hash, version, created_at)"
                        + " VALUES (?, ?, ?, 'USER', 'ACTIVE', 'x', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, name);
            ps.setTimestamp(4, Timestamp.from(T0));
            ps.executeUpdate();
        }
    }

    private static void addPost(Connection c, String id, String author,
                                String body, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO posts (id, author_id, body, status, version,"
                        + " created_at) VALUES (?, ?, ?, ?, 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, author);
            ps.setString(3, body);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(T0));
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("ссылка публикуется и разворачивается в вызываемый объект")
    void iorPublished() {
        assertNotNull(server.getIor(), "ссылка не получена");
        assertTrue(server.getIor().startsWith("IOR:"),
                "опубликовано не то: " + server.getIor());
        assertNotNull(client.service(), "ссылка не развернулась");
    }

    @Test
    @DisplayName("дешёвый вызов проходит через ORB")
    void pingRoundTrip() {
        assertEquals(42, client.service().ping(42));
    }

    /**
     * ГЛАВНАЯ ПРОВЕРКА КОДИРОВОК. Текст возвращается тем же, чем послан.
     * Объяви поле {@code string} вместо {@code wstring} — и вызов не
     * вернёт неправильную строку, а упадёт с DATA_CONVERSION.
     */
    @Test
    @DisplayName("текст вне ASCII проходит через границу неизменным")
    void wideTextSurvives() {
        String text = "Кириллица, ﬁ-лигатура и 🎈";
        assertEquals(text, client.service().echoText(text),
                "текст изменился на границе ORB");
    }

    @Test
    @DisplayName("пост приезжает целиком, с ником и временем")
    void loadsPost() throws Exception {
        PostView view = client.service().loadPost("p-1");

        assertEquals("p-1", view.postId);
        assertEquals("andrey", view.authorNick);
        assertEquals(BODY, view.body, "тело изменилось на границе ORB");
        assertEquals(T0.toEpochMilli(), view.createdAtMillis,
                "время не то, а лента упорядочена по нему");
    }

    @Test
    @DisplayName("несуществующий пост даёт объявленное исключение")
    void unknownPostRaisesNotFound() {
        NotFound e = assertThrows(NotFound.class,
                () -> client.service().loadPost("p-missing"));
        assertEquals("p-missing", e.id, "в исключении не тот идентификатор");
    }

    /**
     * Обратная сторона того же решения о типах. Идентификатор объявлен
     * {@code string}, то есть байтовым, и не-ASCII в него не проходит —
     * вызов падает, а не портит данные молча.
     *
     * <p>Проверка написана после того, как этот отказ случился ЖИВЬЁМ: в
     * первой редакции теста несуществующий пост назывался «p-нет», и
     * вместо {@code NotFound} пришло {@code DATA_CONVERSION}. Ошибка была
     * в тесте, а не в контракте, — но раз граница проходит именно здесь,
     * пусть она будет проверена, а не подразумеваема.
     *
     * <p>Практическое следствие: идентификаторы обязаны оставаться ASCII
     * на всём пути. Ядро их такими и порождает, но проверка сторожит
     * случай, когда кто-нибудь решит пускать в них пользовательский ввод.
     */
    @Test
    @DisplayName("не-ASCII в идентификаторе отвергается границей, а не портится")
    void nonAsciiIdIsRefused() {
        assertThrows(org.omg.CORBA.DATA_CONVERSION.class,
                () -> client.service().loadPost("p-нет"),
                "байтовое поле приняло не-ASCII: значит, где-то по пути "
                        + "текст молча испортится");
    }

    /**
     * Удалённый пост неотличим от несуществующего. Иначе ответ сообщал бы,
     * что такой пост когда-то был.
     */
    @Test
    @DisplayName("удалённый пост неотличим от несуществующего")
    void deletedPostRaisesNotFound() {
        assertThrows(NotFound.class, () -> client.service().loadPost("p-del"));
    }
}
