package sonder.gateway.line;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Линия к ноде: TCP-сервер, к которому подключается нульмодем DOSBox.
 *
 * <p>Направление именно такое, и это не выбор: DOSBox умеет быть
 * нульмодем-клиентом, и спайк S2 проверил связку в этом виде. Гейтвей
 * ждёт подключения, нода приходит.
 *
 * <p><b>Обычные блокирующие сокеты, без Netty</b>
 * ([ADR-0017](../../../../../../docs/adr/0017-line-without-netty.md)):
 * линия одна, и цена «потока на соединение» — один поток.
 *
 * <p><b>Разрыв роняет команды, а не переигрывает их.</b> Гейт фазы 8
 * требует переподключения «без потери и без дублирования». Дублирование
 * возникло бы ровно от автоматического повтора: нода могла успеть принять
 * команду и начать решать, и второй экземпляр создал бы второй пост. Поэтому
 * при обрыве все команды в работе получают отказ, а решает, повторять ли,
 * тот, кто знает про идемпотентность, — оболочка.
 *
 * <p><b>Пауза в чтении — не бездействие.</b> Кадр передаётся непрерывно, и
 * молчание посреди него означает оборвавшегося отправителя. Об этом
 * сообщается разборщику: без сигнала он доел бы недостающие байты из
 * начала следующего кадра и потерял бы уже два.
 */
public final class LineServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LineServer.class);

    /**
     * Сколько молчания считать паузой.
     *
     * <p>Спайк S2 намерял 11 503 Б/с в направление — байт идёт примерно
     * 87 микросекунд. Двести миллисекунд это больше двух тысяч байт, то
     * есть заведомо больше любого кадра: пауза такой длины посреди кадра
     * означает обрыв, а не медленного отправителя.
     */
    public static final int IDLE_MILLIS = 200;

    private final int port;
    private final FrameDecoder decoder = new FrameDecoder();
    private final AtomicReference<Socket> live = new AtomicReference<>();

    /** Кому отдавать собранные кадры. */
    private final java.util.function.Consumer<Frame> onFrame;
    /** Что делать при обрыве: обычно уронить команды в работе. */
    private final Runnable onBreak;

    private final Object writeLock = new Object();

    private volatile ServerSocket server;
    private volatile Thread acceptor;
    private volatile Thread reader;
    private volatile boolean running;

    private final AtomicLong connects = new AtomicLong();
    private final AtomicLong breaks = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final AtomicLong idleResets = new AtomicLong();

    public LineServer(int port, java.util.function.Consumer<Frame> onFrame,
                      Runnable onBreak) {
        this.port = port;
        this.onFrame = onFrame;
        this.onBreak = onBreak;
    }

    /** Порт, на котором ждём ноду. Ноль при запуске означает «любой». */
    public int getPort() {
        ServerSocket s = server;
        return s == null ? port : s.getLocalPort();
    }

    public boolean isConnected() {
        Socket s = live.get();
        return s != null && s.isConnected() && !s.isClosed();
    }

    public void start() throws IOException {
        server = new ServerSocket(port);
        running = true;
        acceptor = new Thread(this::acceptLoop, "line-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("линия ждёт ноду на порту {}", getPort());
    }

    /**
     * Приём подключений.
     *
     * <p><b>Чтение вынесено в отдельный поток, и это не украшение.</b>
     * Пока цикл приёма стоял внутри чтения, второе подключение не
     * отвергалось, а молча ждало в очереди ядра до самого обрыва первого:
     * ветка отказа была недостижима, а нода, подключившаяся по ошибке,
     * висела без ответа. Отказать честно лучше, чем заставить ждать.
     *
     * <p>Вторая нода не предусмотрена конструкцией: у линии один конец.
     * Приняв её молча, мы получили бы два источника кадров на одни и те
     * же каналы, и ответы перепутались бы между собой.
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                if (!live.compareAndSet(null, socket)) {
                    log.warn("вторая нода на линии — отказано");
                    closeQuietly(socket);
                    continue;
                }
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(IDLE_MILLIS);
                connects.incrementAndGet();
                log.info("нода подключилась: {}", socket.getRemoteSocketAddress());

                Thread reader = new Thread(() -> readLoop(socket), "line-reader");
                reader.setDaemon(true);
                this.reader = reader;
                reader.start();
            } catch (IOException e) {
                if (running) {
                    log.warn("приём подключения не удался: {}", e.toString());
                }
            }
        }
    }

    private void readLoop(Socket socket) {
        byte[] buf = new byte[4096];
        try (InputStream in = socket.getInputStream()) {
            while (running && !socket.isClosed()) {
                int n;
                try {
                    n = in.read(buf);
                } catch (SocketTimeoutException quiet) {
                    // Не ошибка: линия молчит. Но недособранный кадр надо
                    // бросить, иначе он съест начало следующего.
                    if (decoder.idle()) {
                        idleResets.incrementAndGet();
                    }
                    continue;
                }
                if (n < 0) {
                    break;
                }
                bytesIn.addAndGet(n);
                for (Frame frame : decoder.feed(buf, 0, n)) {
                    onFrame.accept(frame);
                }
            }
        } catch (IOException e) {
            log.warn("чтение линии оборвалось: {}", e.toString());
        } finally {
            lineBroke(socket);
        }
    }

    private void lineBroke(Socket socket) {
        if (live.compareAndSet(socket, null)) {
            breaks.incrementAndGet();
            closeQuietly(socket);
            decoder.idle();
            log.warn("линия оборвана, команды в работе будут отменены");
            if (onBreak != null) {
                onBreak.run();
            }
        }
    }

    /**
     * Отправить байты в линию.
     *
     * <p>Замок на запись, потому что писать могут разные потоки: кадры
     * одной команды обязаны уйти подряд, иначе нода получит вперемешку
     * куски двух сообщений и не соберёт ни одного.
     */
    public void write(byte[] bytes) {
        Socket socket = live.get();
        if (socket == null) {
            throw new LineDown("нода не подключена");
        }
        try {
            synchronized (writeLock) {
                OutputStream out = socket.getOutputStream();
                out.write(bytes);
                out.flush();
            }
            bytesOut.addAndGet(bytes.length);
        } catch (IOException e) {
            lineBroke(socket);
            throw new LineDown("запись в линию не удалась: " + e.getMessage());
        }
    }

    public long getConnects() {
        return connects.get();
    }

    public long getBreaks() {
        return breaks.get();
    }

    public long getBytesIn() {
        return bytesIn.get();
    }

    public long getBytesOut() {
        return bytesOut.get();
    }

    public long getIdleResets() {
        return idleResets.get();
    }

    public FrameDecoder getDecoder() {
        return decoder;
    }

    @Override
    public void close() {
        running = false;
        Socket s = live.getAndSet(null);
        if (s != null) {
            closeQuietly(s);
        }
        ServerSocket srv = server;
        if (srv != null) {
            try {
                srv.close();
            } catch (IOException ignored) {
                // Закрываемся: жаловаться некому и незачем.
            }
        }
        Thread t = acceptor;
        if (t != null) {
            t.interrupt();
        }
        Thread r = reader;
        if (r != null) {
            r.interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Уже закрыт или сломан — цель достигнута.
        }
    }

    /** Линии нет: нода не подключена или связь оборвалась. */
    public static final class LineDown extends RuntimeException {
        public LineDown(String message) {
            super(message);
        }
    }
}
