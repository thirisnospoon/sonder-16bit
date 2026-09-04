package sonder.shell.irc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import sonder.shell.auth.Login;
import sonder.shell.auth.SessionStore;
import sonder.shell.rest.Trace;

import javax.sql.DataSource;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Шлюз IRC: второй канал того же конвейера.
 *
 * <p><b>Зачем протокол 1988 года.</b> ADR-0002 утверждает, что домен не
 * знает о транспорте, а [ADR-0001](../../../../../../docs/adr/0001-modern-design-everywhere.md)
 * прямо называет IRC среди адаптеров, которые это показывают. Утверждение
 * без второго транспорта проверить нечем: пока клиент один, «домен не
 * знает о транспорте» и «домен знает ровно об одном транспорте» выглядят
 * одинаково.
 *
 * <p><b>Почему IRC, а не Gopher или Finger.</b> У тех нет аутентификации
 * вовсе, а по контракту публичны только вход, выход и регистрация:
 * лента закрыта сессией. Адаптер без аутентификации потребовал бы
 * объявить чтение публичным — то есть сменить приватность продукта ради
 * демонстрации. У IRC своя аутентификация есть (PASS), и вопрос
 * снимается: в шлюз входят тем же именем и паролем, что и в браузер, и
 * получают ту же сессию.
 *
 * <p><b>Обычные блокирующие сокеты, поток на соединение</b> — по той же
 * причине, что и у линии к ноде ([ADR-0017](../../../../../../docs/adr/0017-line-without-netty.md)):
 * соединений здесь единицы, а Netty — это зависимость, объём и ещё одна
 * модель исполнения ради работы, которую делает {@code readLine}.
 *
 * <p><b>Порт пуст — шлюза нет.</b> Тот же приём, что у линии: молчаливое
 * умолчание однажды открыло бы порт там, где его не ждут.
 */
public class IrcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IrcServer.class);

    private final DataSource dataSource;
    private final int port;
    private final int maxConnections;
    private final int handshakeTimeoutMs;

    private final AtomicInteger live = new AtomicInteger();

    private volatile ServerSocket server;
    private volatile Thread acceptor;
    private volatile boolean stopping;

    public IrcServer(DataSource dataSource, int port, int maxConnections,
                     int handshakeTimeoutMs) {
        this.dataSource = dataSource;
        this.port = port;
        this.maxConnections = maxConnections;
        this.handshakeTimeoutMs = handshakeTimeoutMs;
    }

    /**
     * Порт, на котором шлюз слушает на самом деле, или {@code -1}, если
     * он не поднят.
     *
     * <p>Настроенный ноль означает «любой свободный»: так проверка
     * поднимает шлюз, не занимая 6667 на машине, где он может быть занят
     * чем-то ещё. Спрашивать порт у настройки в таком случае бесполезно —
     * там ноль, а слушает шлюз где-то ещё.
     */
    public int boundPort() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    /** Занять порт и начать принимать. Зовётся конфигурацией при подъёме. */
    public void start() throws IOException {
        server = new ServerSocket(port);
        acceptor = new Thread(this::acceptLoop, "irc-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("шлюз IRC слушает порт {}", boundPort());
    }

    private void acceptLoop() {
        while (!stopping) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!stopping) {
                    log.warn("шлюз IRC: приём соединения не удался", e);
                }
                return;
            }
            // Предел на число соединений: поток на соединение без
            // предела — это память, которую отдаёт кто угодно, открыв
            // тысячу сокетов и ничего не прислав.
            if (live.get() >= maxConnections) {
                refuse(socket);
                continue;
            }
            live.incrementAndGet();
            Thread t = new Thread(() -> {
                try {
                    serve(socket);
                } finally {
                    live.decrementAndGet();
                }
            }, "irc-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void refuse(Socket socket) {
        try (Socket s = socket) {
            s.getOutputStream().write(
                    "ERROR :Слишком много соединений\r\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
        } catch (IOException ignored) {
            // Отказавшему в приёме соединению уже всё равно.
        }
    }

    private void serve(Socket socket) {
        String token = null;
        try (Socket s = socket;
             InputStream in = s.getInputStream();
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {

            // Срок на рукопожатие: молчащий клиент не должен держать
            // поток вечно. После входа срок снимается — соединение
            // живёт долго по своей природе.
            s.setSoTimeout(handshakeTimeoutMs);

            IrcSession session = new IrcSession(this::authenticate);
            LineReader reader = new LineReader(in);

            while (true) {
                String raw;
                try {
                    raw = reader.next();
                } catch (SocketTimeoutException e) {
                    send(out, "ERROR :Слишком долго без регистрации");
                    break;
                }
                if (raw == null) {
                    break;
                }
                if (raw == LineReader.TOO_LONG) {
                    // Строка длиннее предела протокола — не повод рвать
                    // соединение: клиент мог отправить длинное сообщение.
                    send(out, ":" + IrcSession.SERVER + " 417 * :Строка длиннее "
                            + IrcLine.MAX_BYTES + " байт");
                    continue;
                }

                MDC.put(Trace.MDC_KEY, Trace.create());
                try {
                    IrcSession.Reply reply = session.feed(IrcLine.parse(raw));
                    for (String line : reply.getLines()) {
                        send(out, line);
                    }
                    if (session.isRegistered() && token == null) {
                        token = session.getToken();
                        s.setSoTimeout(0);
                        log.info("IRC: вошёл {}", session.getNick());
                    }
                    if (reply.isClose()) {
                        break;
                    }
                } finally {
                    MDC.remove(Trace.MDC_KEY);
                }
            }
        } catch (IOException e) {
            log.debug("IRC: соединение оборвалось", e);
        } finally {
            // Сессия, открытая ради этого соединения, закрывается вместе
            // с ним. Иначе каждый обрыв оставлял бы живой токен, и
            // сессии копились бы у тех, кто просто переподключился.
            revoke(token);
        }
    }

    private void revoke(String token) {
        if (token == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            SessionStore.revoke(c, token);
        } catch (SQLException e) {
            log.warn("IRC: не снять сессию при обрыве", e);
        }
    }

    /**
     * Проверка имени и пароля — тем же кодом, что и вход по HTTP.
     *
     * <p>Написать её здесь заново значило бы завести вторую, которая
     * молча разойдётся с первой: в той спрятаны предел попыток, сверка
     * пароля даже для несуществующего ника и запись неудач по любому
     * имени. Ни одно из трёх свойств не видно по сигнатуре.
     */
    private IrcSession.Authenticator.Result authenticate(String nick, String password) {
        try (Connection c = dataSource.getConnection()) {
            Login.Outcome outcome = Login.attempt(c, nick, password, Instant.now());
            switch (outcome.getResult()) {
                case OK:
                    return new IrcSession.Authenticator.Result(
                            IrcSession.Authenticator.Verdict.OK,
                            outcome.getUserId(), outcome.getToken());
                case RATE_EXCEEDED:
                    return new IrcSession.Authenticator.Result(
                            IrcSession.Authenticator.Verdict.RATE_EXCEEDED, null, null);
                default:
                    return new IrcSession.Authenticator.Result(
                            IrcSession.Authenticator.Verdict.INVALID, null, null);
            }
        } catch (SQLException e) {
            log.warn("IRC: вход не проверить, база недоступна", e);
            return new IrcSession.Authenticator.Result(
                    IrcSession.Authenticator.Verdict.UNAVAILABLE, null, null);
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    @Override
    public void close() {
        stopping = true;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Закрываем при остановке; жаловаться некому.
            }
        }
        Thread t = acceptor;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Чтение строк с пределом длины.
     *
     * <p>{@code BufferedReader.readLine} тут не годится: он растёт, пока
     * не встретит перевод строки, и мегабайт без него — это мегабайт в
     * памяти. Предел протокола (512 байт вместе с CRLF) существует ровно
     * затем, чтобы этого не было.
     *
     * <p>Слишком длинная строка ДОЧИТЫВАЕТСЯ до конца и выбрасывается, а
     * не рвёт соединение: иначе её хвост был бы разобран как следующая
     * команда — и это ещё хуже, чем потеря, потому что тихо.
     */
    static final class LineReader {

        /** Признак «строка была длиннее предела». Сравнивается по ссылке. */
        static final String TOO_LONG = new String("");

        private final InputStream in;
        private final byte[] buffer = new byte[IrcLine.MAX_BYTES];

        LineReader(InputStream in) {
            this.in = in;
        }

        String next() throws IOException {
            int len = 0;
            boolean overflow = false;
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return len == 0 && !overflow ? null : finish(len, overflow);
                }
                if (b == '\n') {
                    return finish(len, overflow);
                }
                if (b == '\r') {
                    continue;
                }
                if (len == buffer.length) {
                    overflow = true;
                    continue;
                }
                buffer[len++] = (byte) b;
            }
        }

        private String finish(int len, boolean overflow) {
            return overflow ? TOO_LONG : new String(buffer, 0, len, StandardCharsets.UTF_8);
        }
    }
}
