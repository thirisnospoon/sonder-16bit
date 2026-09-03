package sonder.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameCodec;
import sonder.gateway.line.FrameDecoder;
import sonder.gateway.line.LineMux;
import sonder.gateway.line.LineServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Линия через настоящий сокет.
 *
 * <p>Ноды здесь нет — вместо неё обычный TCP-клиент, как её и видит
 * гейтвей: DOSBox подключается нульмодемом ровно так же. Подменена не
 * линия, а то, что на другом её конце, и это единственная возможная
 * подмена: настоящая нода живёт в эмуляторе.
 *
 * <p>Второе утверждение гейта Ф8 — «обрыв линии приводит к корректному
 * переподключению без потери и без дублирования команд» — проверяется
 * здесь. Дублирование возникло бы от автоматического повтора, и его тут
 * нет намеренно: нода могла успеть принять команду и начать решать.
 */
class LineServerTest {

    /** Сколько ждать событий линии. Заведомо больше её задержек. */
    private static final long DEADLINE_MS = 3000;

    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00Z");

    private LineServer line;
    private LineMux mux;
    private final ConcurrentLinkedQueue<Frame> got = new ConcurrentLinkedQueue<>();
    private final AtomicInteger breaks = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        mux = null;
        line = new LineServer(0, got::add, breaks::incrementAndGet);
        line.start();
    }

    @AfterEach
    void stop() {
        if (line != null) {
            line.close();
        }
    }

    /** Подключиться как нода и дождаться, пока линия это заметит. */
    private Socket connectAsNode() throws Exception {
        Socket socket = new Socket("127.0.0.1", line.getPort());
        socket.setTcpNoDelay(true);
        await(() -> line.isConnected(), "линия не заметила подключения");
        return socket;
    }

    private static void await(java.util.function.BooleanSupplier condition,
                              String message) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError(message + " (ждали " + DEADLINE_MS + " мс)");
    }

    @Test
    @DisplayName("нода подключается, и линия это видит")
    void nodeConnects() throws Exception {
        try (Socket node = connectAsNode()) {
            assertEquals(1, line.getConnects());
            assertTrue(line.isConnected());
        }
    }

    /** Кадр из линии доезжает до обработчика собранным. */
    @Test
    @DisplayName("кадр от ноды доезжает собранным")
    void frameFromNodeArrives() throws Exception {
        try (Socket node = connectAsNode()) {
            byte[] wire = FrameCodec.encode(
                    new Frame(3, Frame.FLAG_NEEDS_REPLY, new byte[]{7, 8, 9}));
            OutputStream out = node.getOutputStream();
            out.write(wire);
            out.flush();

            await(() -> !got.isEmpty(), "кадр не доехал");
            Frame frame = got.poll();
            assertNotNull(frame);
            assertEquals(3, frame.getChannel());
            assertArrayEquals(new byte[]{7, 8, 9}, frame.getPayload());
            assertEquals(wire.length, line.getBytesIn());
        }
    }

    /**
     * Кадр, разорванный паузой, не съедает следующий. Без сигнала о
     * паузе разборщик доел бы недостающие байты из начала следующего
     * кадра — вместе с его маркером, — и потерял бы уже два.
     */
    @Test
    @DisplayName("пауза посреди кадра не уносит следующий")
    void pauseDoesNotEatNextFrame() throws Exception {
        try (Socket node = connectAsNode()) {
            byte[] whole = FrameCodec.encode(new Frame(4, 0, new byte[]{1, 2, 3}));
            OutputStream out = node.getOutputStream();

            // Половина кадра и молчание дольше паузы.
            out.write(whole, 0, whole.length / 2);
            out.flush();
            await(() -> line.getIdleResets() > 0, "пауза не замечена");

            // Целый кадр после паузы обязан собраться.
            out.write(whole);
            out.flush();
            await(() -> !got.isEmpty(), "кадр после паузы потерян");
            assertEquals(4, got.poll().getChannel());
        }
    }

    /** Запись уходит в линию байт в байт. */
    @Test
    @DisplayName("кадр гейтвея приходит к ноде байт в байт")
    void frameReachesNode() throws Exception {
        try (Socket node = connectAsNode()) {
            byte[] wire = FrameCodec.encode(
                    new Frame(5, Frame.FLAG_MORE, new byte[]{10, 20, 30}));
            line.write(wire);

            InputStream in = node.getInputStream();
            byte[] read = new byte[wire.length];
            int off = 0;
            while (off < read.length) {
                int n = in.read(read, off, read.length - off);
                assertTrue(n > 0, "линия закрылась на середине кадра");
                off += n;
            }
            assertArrayEquals(wire, read);
            assertEquals(wire.length, line.getBytesOut());
        }
    }

    /** Без подключения писать некуда, и это отказ, а не тишина. */
    @Test
    @DisplayName("запись без ноды отвергается")
    void writeWithoutNodeIsRefused() {
        assertThrows(LineServer.LineDown.class,
                () -> line.write(new byte[]{1, 2, 3}));
    }

    /**
     * ГЕЙТ, ПУНКТ ВТОРОЙ. Обрыв линии роняет команды в работе и не
     * повторяет их сам.
     *
     * <p>Повтор создал бы дублирование: нода могла успеть принять команду
     * и начать решать. Решает, повторять ли, тот, кто знает про
     * идемпотентность, — оболочка.
     */
    @Test
    @DisplayName("обрыв роняет команды в работе и не повторяет их")
    void breakFailsInFlightWithoutRetry() throws Exception {
        mux = new LineMux(line::write, null);
        LineServer withMux = line;

        Socket node = connectAsNode();
        CompletableFuture<byte[]> reply = mux.send(new byte[]{1}, T0);
        assertEquals(1, mux.inFlight(), "команда не занята каналом");

        // Читаем то, что уехало: иначе обрыв случится с непрочитанным
        // буфером, и проверка была бы о другом.
        InputStream in = node.getInputStream();
        assertTrue(in.read() >= 0);

        node.close();
        await(() -> breaks.get() > 0, "обрыв не замечен");
        // Обрыв обязан уронить команды: это делает тот, кто их держит.
        mux.expire(T0, Duration.ZERO);

        assertTrue(reply.isCompletedExceptionally(),
                "команда не узнала об обрыве и будет ждать вечно");
        assertEquals(0, mux.inFlight(), "канал остался занятым после обрыва");
        assertEquals(1, withMux.getBreaks());
    }

    /** После обрыва нода подключается снова, и линия работает. */
    @Test
    @DisplayName("после обрыва нода подключается снова")
    void reconnectWorks() throws Exception {
        Socket first = connectAsNode();
        first.close();
        await(() -> breaks.get() > 0, "первый обрыв не замечен");
        await(() -> !line.isConnected(), "линия считает себя подключённой");

        try (Socket second = connectAsNode()) {
            assertEquals(2, line.getConnects(), "второе подключение не принято");

            byte[] wire = FrameCodec.encode(new Frame(6, 0, new byte[]{42}));
            second.getOutputStream().write(wire);
            second.getOutputStream().flush();

            await(() -> !got.isEmpty(), "кадр после переподключения не дошёл");
            assertEquals(6, got.poll().getChannel());
        }
    }

    /**
     * Вторая нода не принимается. Приняв её молча, мы получили бы два
     * источника кадров на одни и те же каналы, и ответы перепутались бы
     * между собой.
     */
    @Test
    @DisplayName("вторая нода на линию не пускается")
    void secondNodeIsRefused() throws Exception {
        try (Socket first = connectAsNode();
             Socket second = new Socket("127.0.0.1", line.getPort())) {

            // Вторую закрывают с той стороны; читаем до конца потока.
            second.setSoTimeout((int) DEADLINE_MS);
            assertEquals(-1, second.getInputStream().read(),
                    "вторая нода осталась подключённой");
            assertEquals(1, line.getConnects(),
                    "второе подключение сочтено полноценным");
        }
    }

    /** Кадры одной команды уходят подряд: между ними ничего не влезает. */
    @Test
    @DisplayName("кадры одной команды уходят подряд")
    void framesOfOneCommandAreContiguous() throws Exception {
        try (Socket node = connectAsNode()) {
            mux = new LineMux(line::write, null);
            byte[] big = new byte[Frame.MAX_PAYLOAD + 10];
            mux.send(big, T0);

            FrameDecoder decoder = new FrameDecoder();
            InputStream in = node.getInputStream();
            node.setSoTimeout((int) DEADLINE_MS);

            java.util.List<Frame> frames = new java.util.ArrayList<>();
            byte[] buf = new byte[1024];
            while (frames.size() < 2) {
                int n = in.read(buf);
                assertTrue(n > 0, "линия закрылась раньше двух кадров");
                frames.addAll(decoder.feed(buf, 0, n));
            }

            assertEquals(2, frames.size());
            assertTrue(frames.get(0).hasFlag(Frame.FLAG_MORE));
            assertEquals(frames.get(0).getChannel(), frames.get(1).getChannel(),
                    "кадры одной команды уехали на разные каналы");
        }
    }
}
