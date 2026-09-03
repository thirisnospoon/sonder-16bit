package sonder.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameDecoder;
import sonder.gateway.line.LineMux;
import sonder.gateway.line.LineServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обратное давление: медленный читатель не теряет кадров.
 *
 * <p>Третье утверждение гейта Ф8. Проверяется не «сколько успело», а
 * НИЧЕГО ЛИ НЕ ПРОПАЛО: шестнадцать команд по несколько кадров каждая
 * уходят в линию, пока нода читает медленно, и потом сверяется всё —
 * число кадров, их порядок внутри канала и содержимое.
 *
 * <p><b>Давление здесь настоящее, а не изображённое.</b> Приёмный буфер
 * сокета уменьшен, читатель спит между чтениями: буфер заполняется, и
 * запись в линию блокируется по-настоящему. Тест, в котором запись ни
 * разу не заблокировалась, проверял бы не обратное давление, а его
 * отсутствие.
 *
 * <p>Потеря кадра тут страшнее задержки: нода собирает сообщение до
 * первого кадра без признака продолжения, и пропавшая середина означает
 * склеенное из двух команд решение.
 */
class BackpressureTest {

    /** Команд одновременно — столько же, сколько каналов. */
    private static final int COMMANDS = 16;
    /** Кадров в каждой команде: заведомо больше одного. */
    private static final int FRAMES_PER_COMMAND = 12;
    /** Приёмный буфер клиента: маленький, чтобы давление возникло. */
    private static final int RECV_BUFFER = 4096;

    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00Z");

    private LineServer line;
    private final ConcurrentLinkedQueue<Frame> fromNode = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void start() throws IOException {
        line = new LineServer(0, fromNode::add, null);
        line.start();
    }

    @AfterEach
    void stop() {
        if (line != null) {
            line.close();
        }
    }

    @Test
    @DisplayName("медленная нода не теряет ни одного кадра")
    void slowNodeLosesNothing() throws Exception {
        Socket node = new Socket();
        node.setReceiveBufferSize(RECV_BUFFER);
        node.connect(new java.net.InetSocketAddress("127.0.0.1", line.getPort()));
        node.setTcpNoDelay(true);

        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!line.isConnected() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertTrue(line.isConnected(), "линия не заметила подключения");

        LineMux mux = new LineMux(line::write, null);

        // Читатель нарочно медленный: спит между чтениями, чтобы буфер
        // заполнился и запись в линию упёрлась.
        List<Frame> received = new ArrayList<>();
        CountDownLatch readerDone = new CountDownLatch(1);
        int expectedFrames = COMMANDS * FRAMES_PER_COMMAND;

        Thread reader = new Thread(() -> {
            FrameDecoder decoder = new FrameDecoder();
            byte[] buf = new byte[512];
            try (InputStream in = node.getInputStream()) {
                node.setSoTimeout(5000);
                while (received.size() < expectedFrames) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    received.addAll(decoder.feed(buf, 0, n));
                    TimeUnit.MILLISECONDS.sleep(1);
                }
            } catch (Exception e) {
                // Молча: итог проверяется по собранному, а не по
                // исключению в потоке, которого никто не ждёт.
            } finally {
                readerDone.countDown();
            }
        }, "slow-node");
        reader.setDaemon(true);
        reader.start();

        // Полезная нагрузка у каждой команды своя и узнаваемая: так
        // видно не только «сколько», но и «то ли самое».
        Map<Integer, byte[]> sentByOrder = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(COMMANDS);
        List<java.util.concurrent.Future<CompletableFuture<byte[]>>> futures =
                new ArrayList<>();
        for (int i = 0; i < COMMANDS; i++) {
            final int n = i;
            byte[] payload = payload(n);
            sentByOrder.put(n, payload);
            futures.add(pool.submit(() -> mux.send(payload, T0)));
        }
        long sendStarted = System.nanoTime();
        for (java.util.concurrent.Future<CompletableFuture<byte[]>> f : futures) {
            assertTrue(!f.get().isCompletedExceptionally(),
                    "команда отвергнута, хотя каналов хватало");
        }
        long sendMs = (System.nanoTime() - sendStarted) / 1_000_000L;
        pool.shutdown();

        assertTrue(readerDone.await(20, TimeUnit.SECONDS),
                "медленная нода не дочитала за отведённое время");

        assertEquals(expectedFrames, received.size(),
                "кадров пришло не столько, сколько ушло: обратное давление "
                        + "потеряло часть, и нода собрала бы склеенное решение");

        // Кадры каждого канала пришли по порядку и складываются в ту же
        // нагрузку, что уходила.
        Map<Integer, java.io.ByteArrayOutputStream> byChannel = new LinkedHashMap<>();
        Map<Integer, Integer> framesOn = new LinkedHashMap<>();
        for (Frame f : received) {
            byChannel.computeIfAbsent(f.getChannel(),
                    k -> new java.io.ByteArrayOutputStream());
            byte[] part = f.getPayload();
            byChannel.get(f.getChannel()).write(part, 0, part.length);
            framesOn.merge(f.getChannel(), 1, Integer::sum);
        }

        assertEquals(COMMANDS, byChannel.size(),
                "кадры пришли не на все каналы");
        for (Map.Entry<Integer, Integer> e : framesOn.entrySet()) {
            assertEquals(FRAMES_PER_COMMAND, (int) e.getValue(),
                    "на канале " + e.getKey() + " кадров не столько, сколько ушло");
        }

        // Каждая собранная нагрузка обязана совпасть с одной из
        // отправленных — и ровно с одной.
        List<byte[]> expected = new ArrayList<>(sentByOrder.values());
        for (java.io.ByteArrayOutputStream collected : byChannel.values()) {
            byte[] whole = collected.toByteArray();
            int match = -1;
            for (int i = 0; i < expected.size(); i++) {
                if (java.util.Arrays.equals(expected.get(i), whole)) {
                    match = i;
                    break;
                }
            }
            assertTrue(match >= 0,
                    "собранная нагрузка не совпала ни с одной отправленной: "
                            + "кадры перемешались между каналами");
            expected.remove(match);
        }
        assertTrue(expected.isEmpty(), "часть команд не дошла целиком");

        assertEquals(expectedFrames, mux.getSent());

        // ДАВЛЕНИЕ ОБЯЗАНО БЫТЬ НАСТОЯЩИМ. Читатель спит миллисекунду на
        // каждые полкилобайта, и на сто килобайт ему нужно не меньше
        // двух сотен миллисекунд. Если отправка уложилась заметно
        // быстрее, значит буферы проглотили всё и упереться было не во
        // что — проверка тогда показывает не то, что обещает.
        long readerFloorMs = (long) expectedFrames
                * (Frame.MAX_PAYLOAD + Frame.HEADER_BYTES + Frame.TRAILER_BYTES)
                / 512;
        System.out.println("обратное давление: отправка " + sendMs
                + " мс, нижняя оценка чтения " + readerFloorMs + " мс");
        assertTrue(sendMs * 3 >= readerFloorMs,
                "отправка (" + sendMs + " мс) много быстрее, чем читатель "
                        + "физически может принять (" + readerFloorMs + " мс): "
                        + "запись ни разу не упёрлась, и обратное давление "
                        + "этой проверкой не задето");

        node.close();
    }

    /** Нагрузка ровно на FRAMES_PER_COMMAND кадров, узнаваемая по номеру. */
    private static byte[] payload(int n) {
        byte[] b = new byte[Frame.MAX_PAYLOAD * FRAMES_PER_COMMAND];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((n * 7 + i) & 0xFF);
        }
        return b;
    }
}
