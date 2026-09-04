package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sonder.Application;
import sonder.shell.auth.Passwords;
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
class IrcGatewayIT {

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
