package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.contract.decider.BanUserRequest;
import sonder.contract.decider.CreateCommentRequest;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.FollowUserRequest;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PingResponse;
import sonder.contract.decider.RegisterUserRequest;
import sonder.contract.decider.UnfollowUserRequest;
import sonder.contract.decider.DeletePostRequest;
import sonder.shell.auth.Passwords;
import sonder.shell.outbox.OutboxRecord;
import sonder.shell.stream.FeedStream;
import sonder.shell.irc.IrcServer;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Шлюз IRC по настоящему сокету против настоящей базы.
 *
 * <p>Модульные тесты проверяют разбор строки и порядок рукопожатия без
 * сокетов — и правильно делают, они за миллисекунды. Здесь проверяется
 * ровно то, чего там не видно: что шлюз поднимается вместе с
 * приложением, занимает порт, разбирает поток байтов и — главное —
 * ВХОДИТ ТЕМ ЖЕ ВХОДОМ, что и браузер.
 *
 * <p>Последнее и есть смысл всей затеи. Второй транспорт, у которого
 * своя проверка пароля и своя сессия, ничего не доказывал бы про
 * независимость домена: он был бы вторым приложением, притворяющимся
 * тем же. Поэтому проверяется не «шлюз ответил 001», а «в таблице
 * сессий появилась строка, и после QUIT её не стало».
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IrcGatewayIT.FakeCore.class)
class IrcGatewayIT {

    /**
     * Подменное ядро.
     *
     * <p>Настоящее живёт под DOSBox за последовательной линией, и в
     * интеграционном прогоне его нет. Подмена отвечает согласием — этого
     * довольно: проверяется не решение ядра, а то, что команда из IRC
     * идёт ТЕМ ЖЕ путём, что и команда из REST, и доезжает до базы.
     *
     * <p>Реализуется полный интерфейс контракта, а не удобное
     * подмножество: подмена, умеющая меньше настоящего, однажды скроет
     * то, чего оболочка не делает.
     */
    @TestConfiguration
    static class FakeCore {
        @Bean
        @Primary
        Decider fakeDecider() {
            return new Decider() {
                private Decision accepted(String type) {
                    Decision d = new Decision();
                    d.setAccepted(true);
                    DomainEvent e = new DomainEvent();
                    e.setType(type);
                    e.setAggregateId("p-irc");
                    d.getEvent().add(e);
                    return d;
                }

                @Override
                public Decision createPost(CreatePostRequest r) {
                    return accepted("post.created");
                }

                @Override public Decision deletePost(DeletePostRequest r) {
                    return accepted("post.deleted");
                }

                @Override public Decision createComment(CreateCommentRequest r) {
                    return accepted("comment.created");
                }

                @Override public Decision registerUser(RegisterUserRequest r) {
                    return accepted("user.registered");
                }

                @Override public Decision followUser(FollowUserRequest r) {
                    return accepted("user.followed");
                }

                @Override public Decision unfollowUser(UnfollowUserRequest r) {
                    return accepted("user.unfollowed");
                }

                @Override public Decision banUser(BanUserRequest r) {
                    return accepted("user.banned");
                }

                @Override public PingResponse ping(PingRequest r) {
                    return new PingResponse();
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty("sonder.it.jdbcUrl", ""));
        registry.add("spring.datasource.username",
                () -> System.getProperty("sonder.it.user", "sysdba"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("sonder.it.password", "masterkey"));
        registry.add("sonder.outbox.enabled", () -> "false");
        // Ноль — любой свободный порт. Занимать 6667 на машине, где
        // может стоять чужой ircd, проверка права не имеет.
        registry.add("sonder.irc.port", () -> "0");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IrcServer irc;

    @Autowired
    private FeedStream stream;

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
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        }
    }

    private int sessions() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sessions")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Разговор с шлюзом: отправить строки и собрать ответ до признака. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final BufferedReader in;

        Client(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            out = socket.getOutputStream();
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) throws Exception {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /**
         * Читать, пока не встретится строка с признаком, или пока сервер
         * не закроет соединение. Ждать «сколько-нибудь миллисекунд» и
         * смотреть, что накопилось, — это гонка, а не проверка.
         */
        List<String> until(String marker) throws Exception {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                if (line.contains(marker)) {
                    break;
                }
            }
            return lines;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    private static boolean has(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("шлюз поднялся вместе с приложением и занял порт")
    void gatewayIsListening() {
        assertTrue(irc.boundPort() > 0, "шлюз не слушает ни одного порта");
    }

    @Test
    @DisplayName("вход по IRC открывает ту же сессию, что и вход по HTTP")
    void loginOpensRealSession() throws Exception {
        assertEquals(0, sessions(), "сессии остались от прошлого теста");

        try (Client client = new Client(irc.boundPort())) {
            client.send("PASS тайна");
            client.send("NICK andrey");
            client.send("USER andrey 0 * :Андрей");

            List<String> welcome = client.until(" 376 ");
            assertTrue(has(welcome, " 001 "), "нет приветствия: " + welcome);
            assertEquals(1, sessions(), "сессия в базе не открылась");

            client.send("QUIT :пока");
            client.until("ERROR");
        }

        // Сессия снимается вместе с соединением. Проверяется ПОСЛЕ
        // закрытия сокета: снятие идёт в обработчике обрыва, и спросить
        // базу раньше значило бы спросить до того, как оно случилось.
        long deadline = System.currentTimeMillis() + 5_000;
        int left = sessions();
        while (left != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            left = sessions();
        }
        assertEquals(0, left, "сессия пережила соединение");
    }

    /**
     * Создать пост так, как это делает путь HTTP, — но без HTTP.
     *
     * <p>Пишутся ровно те три строки, что пишет обработчик команды:
     * пост, строка проекции (чья это лента) и событие. Идти сюда через
     * настоящий {@code POST /api/posts} значило бы проверять заодно
     * контроллер, куку и сериализацию, — всё это проверено своими
     * тестами, а здесь нужен только повод для рассылки.
     */
    private String createPostAsHttpWould(String body) throws Exception {
        String postId = "p-" + System.nanoTime();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO posts (id, author_id, body, status, version,"
                            + " created_at) VALUES (?, 'u-1', ?, 'VISIBLE', 0, ?)")) {
                ps.setString(1, postId);
                ps.setString(2, body);
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO feed_entries (owner_id, post_id, author_id,"
                            + " created_at) VALUES ('u-1', ?, 'u-1', ?)")) {
                ps.setString(1, postId);
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        }
        return postId;
    }

    /**
     * Позвать рассылку так, как её зовёт дренаж очереди.
     *
     * <p>Дренаж в этой проверке выключен намеренно: он разбирает outbox
     * сам по себе, и ждать его значило бы проверять расписание, а не
     * рассылку. Событие подаётся тем же вызовом, которым его подал бы
     * дренаж после коммита.
     */
    private void deliverToListeners(String postId) {
        stream.onPublished(java.util.Collections.singletonList(
                new OutboxRecord(1L, postId, "post.created",
                        "{\"actor\":\"u-1\"}", "t-irc", 0)));
    }

    private int posts() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM posts")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int outbox() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM outbox")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Ради этого второй транспорт и заводился.
     *
     * <p>Написанное в канал проходит ТОТ ЖЕ путь, что и {@code POST
     * /api/posts}: загрузка состояния, решение на ядре, сохранение с
     * оптимистической блокировкой и запись в outbox одной транзакцией.
     * Собери шлюз свой путь записи — он доказывал бы независимость
     * домена ровно так же, как два разных приложения доказывают
     * независимость друг от друга, то есть никак.
     *
     * <p>Проверяется поэтому не ответ шлюза, а <b>следы в базе</b>: пост
     * с тем самым текстом и событие в очереди рядом с ним.
     */
    @Test
    @DisplayName("написанное в канал становится постом и событием в outbox")
    void messageInChannelBecomesPost() throws Exception {
        assertEquals(0, posts(), "посты остались от прошлого теста");

        try (Client client = new Client(irc.boundPort())) {
            client.send("PASS тайна");
            client.send("NICK andrey");
            client.send("USER andrey 0 * :Андрей");
            client.until(" 366 ");

            client.send("PRIVMSG #feed :пост, написанный из ирки");

            // Ответа на успех протокол не предполагает, поэтому ждём не
            // строку, а след в базе. Опрос с пределом, а не сон на
            // фиксированное время: сон проверял бы скорость машины.
            long deadline = System.currentTimeMillis() + 10_000;
            while (posts() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        }

        assertEquals(1, posts(), "пост из IRC не доехал до базы");
        assertEquals(1, outbox(), "событие не легло в очередь вместе с постом");

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT author_id, body FROM posts")) {
            assertTrue(rs.next());
            assertEquals("u-1", rs.getString(1), "пост записан не тому автору");
            assertEquals("пост, написанный из ирки", rs.getString(2),
                    "текст доехал не целиком");
        }
    }

    /**
     * Обратное направление: пост, созданный ЧЕРЕЗ HTTP, приходит в канал
     * IRC. Ради этого рассылка и перестала быть привязанной к SSE.
     *
     * <p>Проверяется именно перекрёстный случай — написано в браузере,
     * прочитано в ирке, — потому что он единственный доказывает, что
     * ответ на вопрос «чья это новость» ОДИН. Напиши мы из IRC и прочти
     * в IRC, тот же результат дала бы вторая, отдельная рассылка.
     */
    @Test
    @DisplayName("пост из HTTP приходит в канал IRC той же рассылкой")
    void postFromHttpReachesIrcChannel() throws Exception {
        try (Client client = new Client(irc.boundPort())) {
            client.send("PASS тайна");
            client.send("NICK andrey");
            client.send("USER andrey 0 * :Андрей");
            client.until(" 366 ");

            // Ждём НАБЛЮДАЕМОГО условия, а не «немножко». Подписка
            // случается после отправки приветствия, и событие, посланное
            // между этими двумя мгновениями, ушло бы в пустоту — тест
            // мигал бы раз в сотню прогонов, и объяснить это было бы
            // нечем.
            long deadline = System.currentTimeMillis() + 5_000;
            while (stream.openCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(stream.openCount() > 0, "соединение не подписалось на ленту");

            // Пост создаётся ПОМИМО IRC — прямо через тот же обработчик,
            // что зовёт REST, — и в свою же ленту автор его получает.
            String postId = createPostAsHttpWould("новость из браузера");
            deliverToListeners(postId);

            List<String> got = client.until("новость из браузера");
            assertTrue(has(got, "PRIVMSG #feed"), "новость пришла не в канал: " + got);
            assertTrue(has(got, ":andrey!andrey@sonder"),
                    "отправителем оказался не автор: " + got);
        }
    }

    /**
     * Личных сообщений у продукта нет, и проглотить их молча значило бы
     * показать отправителю доставку, которой не было.
     */
    @Test
    @DisplayName("личное сообщение не становится постом")
    void privateMessageIsNotAPost() throws Exception {
        try (Client client = new Client(irc.boundPort())) {
            client.send("PASS тайна");
            client.send("NICK andrey");
            client.send("USER andrey 0 * :Андрей");
            client.until(" 366 ");

            client.send("PRIVMSG vasya :привет");
            assertTrue(has(client.until(" 401 "), " 401 "), "не отказано");
        }
        assertEquals(0, posts(), "личное сообщение стало постом");
    }

    @Test
    @DisplayName("неверный пароль не пускает и не оставляет сессии")
    void wrongPasswordIsRefused() throws Exception {
        try (Client client = new Client(irc.boundPort())) {
            client.send("PASS не-та-тайна");
            client.send("NICK andrey");
            client.send("USER andrey 0 * :Андрей");

            List<String> out = client.until("ERROR");
            assertTrue(has(out, " 464 "), "нет отказа 464: " + out);
            assertFalse(has(out, " 001 "), "пустили с неверным паролем");
        }
        assertEquals(0, sessions(), "неудачный вход оставил сессию");
    }

    /**
     * Без PASS клиент не должен ждать вечно. Молчащий сервер выглядит
     * зависшим, и разбираться в этом будут не там.
     */
    @Test
    @DisplayName("без пароля соединение закрывается с объяснением")
    void withoutPasswordConnectionCloses() throws Exception {
        try (Client client = new Client(irc.boundPort())) {
            client.send("NICK andrey");
            client.send("USER andrey 0 * :Андрей");

            List<String> out = client.until("ERROR");
            assertTrue(has(out, " 464 "), "не сказано, что нужен пароль: " + out);
        }
        assertEquals(0, sessions());
    }

    @Test
    @DisplayName("PING отвечается до входа: это проверка связи, а не прав")
    void pingBeforeLogin() throws Exception {
        try (Client client = new Client(irc.boundPort())) {
            client.send("PING :проба");
            List<String> out = client.until("PONG");
            assertTrue(has(out, "проба"), "не вернули то, что прислали: " + out);
        }
    }

    /**
     * Строка длиннее предела протокола не рвёт соединение и не
     * разбирается хвостом как следующая команда. Второе опаснее: оно
     * тихо превращает мусор в команду.
     */
    @Test
    @DisplayName("слишком длинная строка отвергается, а хвост не становится командой")
    void tooLongLineIsRejectedWhole() throws Exception {
        StringBuilder huge = new StringBuilder("PASS ");
        while (huge.length() < 2000) {
            huge.append('x');
        }
        try (Client client = new Client(irc.boundPort())) {
            client.send(huge.toString());
            List<String> out = client.until(" 417 ");
            assertTrue(has(out, " 417 "), "про длину не сказано: " + out);

            // Хвост длинной строки не должен был превратиться в команду:
            // соединение живо и отвечает на PING.
            client.send("PING :жив");
            assertTrue(has(client.until("PONG"), "жив"), "соединение испорчено хвостом");
        }
    }
}
