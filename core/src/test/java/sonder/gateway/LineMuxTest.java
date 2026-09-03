package sonder.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameDecoder;
import sonder.gateway.line.LineMux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мультиплексор линии: шестнадцать команд в работе и ни одной лишней.
 *
 * <p>Линии здесь нет — вместо неё разборщик кадров, читающий то, что
 * мультиплексор в неё положил. Это не подмена: кадры настоящие, кодек
 * настоящий, и сверен он с ядром эталонами. Подменён только провод.
 *
 * <p>Первое утверждение гейта Ф8 — «16 команд в работе одновременно» —
 * проверяется здесь буквально: шестнадцать занимают каналы, семнадцатая
 * получает отказ, освобождение канала возвращает возможность.
 */
class LineMuxTest {

    /** Провод: складывает байты и умеет разобрать их обратно в кадры. */
    private static final class Wire implements LineMux.Sink {
        final List<Frame> frames = new ArrayList<>();
        final FrameDecoder decoder = new FrameDecoder();
        RuntimeException failWith;

        @Override
        public void write(byte[] bytes) {
            if (failWith != null) {
                throw failWith;
            }
            frames.addAll(decoder.feed(bytes, 0, bytes.length));
        }

        List<Frame> on(int channel) {
            List<Frame> out = new ArrayList<>();
            for (Frame f : frames) {
                if (f.getChannel() == channel) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00Z");

    private Wire wire;
    private List<Frame> control;
    private LineMux mux;

    @BeforeEach
    void setUp() {
        wire = new Wire();
        control = new ArrayList<>();
        mux = new LineMux(wire, control::add);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i & 0xFF);
        }
        return b;
    }

    /** Ответ одним кадром на указанный канал. */
    private void replyWhole(int channel, byte[] payload) {
        mux.onFrame(new Frame(channel, 0, payload));
    }

    @Test
    @DisplayName("команда уходит кадром и возвращается ответом")
    void roundTrip() throws Exception {
        byte[] command = "решай".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<byte[]> reply = mux.send(command, T0);

        assertEquals(1, wire.frames.size(), "команда ушла не одним кадром");
        Frame sentFrame = wire.frames.get(0);
        assertEquals(LineMux.FIRST_DATA_CHAN, sentFrame.getChannel(),
                "занят не первый свободный канал");
        assertTrue(sentFrame.hasFlag(Frame.FLAG_NEEDS_REPLY),
                "нода не поймёт, что от неё ждут ответа");
        assertFalse(sentFrame.hasFlag(Frame.FLAG_MORE),
                "у последнего кадра стоит признак продолжения");
        assertArrayEquals(command, sentFrame.getPayload());

        assertFalse(reply.isDone(), "ответ готов до того, как пришёл");
        replyWhole(sentFrame.getChannel(),
                "решено".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("решено".getBytes(StandardCharsets.UTF_8), reply.get());
        assertEquals(0, mux.inFlight(), "канал не освобождён после ответа");
    }

    /**
     * Длинная команда режется на кадры: признак продолжения на всех,
     * кроме последнего. Перепутай — и нода начнёт решать по половине
     * сообщения.
     */
    @Test
    @DisplayName("длинная команда режется, продолжение помечено на всех, кроме конца")
    void longCommandIsSplit() {
        byte[] command = bytes(Frame.MAX_PAYLOAD * 2 + 100);
        mux.send(command, T0);

        List<Frame> frames = wire.on(LineMux.FIRST_DATA_CHAN);
        assertEquals(3, frames.size(), "нарезано не столько кадров");

        assertTrue(frames.get(0).hasFlag(Frame.FLAG_MORE));
        assertTrue(frames.get(1).hasFlag(Frame.FLAG_MORE));
        assertFalse(frames.get(2).hasFlag(Frame.FLAG_MORE),
                "у последнего кадра признак продолжения");
        assertTrue(frames.get(2).hasFlag(Frame.FLAG_NEEDS_REPLY),
                "ответа ждут не от последнего кадра");

        assertEquals(Frame.MAX_PAYLOAD, frames.get(0).getPayload().length);
        assertEquals(Frame.MAX_PAYLOAD, frames.get(1).getPayload().length);
        assertEquals(100, frames.get(2).getPayload().length);
    }

    /** Пустая команда — один кадр, а не ноль: нода ждёт хотя бы один. */
    @Test
    @DisplayName("пустая команда всё равно уходит одним кадром")
    void emptyCommandStillSendsAFrame() {
        mux.send(new byte[0], T0);

        assertEquals(1, wire.frames.size(),
                "пустая команда не уехала: нода не заведёт файбер");
        assertEquals(0, wire.frames.get(0).getPayload().length);
        assertTrue(wire.frames.get(0).hasFlag(Frame.FLAG_NEEDS_REPLY));
    }

    /** Ответ из нескольких кадров склеивается по порядку. */
    @Test
    @DisplayName("многокадровый ответ склеивается целиком")
    void multiFrameReplyIsAssembled() throws Exception {
        CompletableFuture<byte[]> reply = mux.send(new byte[]{1}, T0);
        int channel = wire.frames.get(0).getChannel();

        mux.onFrame(new Frame(channel, Frame.FLAG_MORE, new byte[]{10, 11}));
        assertFalse(reply.isDone(), "ответ собран до последнего кадра");
        mux.onFrame(new Frame(channel, Frame.FLAG_MORE, new byte[]{12}));
        assertFalse(reply.isDone());
        mux.onFrame(new Frame(channel, 0, new byte[]{13, 14}));

        assertArrayEquals(new byte[]{10, 11, 12, 13, 14}, reply.get());
    }

    /**
     * ГЕЙТ, ПУНКТ ПЕРВЫЙ. Шестнадцать команд в работе одновременно,
     * семнадцатая отвергается сразу.
     *
     * <p>Отказ, а не очередь: ожидание внутри гейтвея — это очередь, о
     * которой не знает ни отправитель, ни метрика. У ноды шестнадцать
     * файберов, и семнадцатая команда всё равно не начнётся.
     */
    @Test
    @DisplayName("шестнадцать команд в работе, семнадцатая отвергается")
    void sixteenInFlightSeventeenthRefused() throws Exception {
        List<CompletableFuture<byte[]>> pending = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            pending.add(mux.send(new byte[]{(byte) i}, T0));
        }
        assertEquals(16, mux.inFlight(), "заняты не все каналы");
        assertEquals(16, wire.frames.size());

        // Все каналы разные: иначе ответ уехал бы не той команде.
        java.util.Set<Integer> channels = new java.util.TreeSet<>();
        for (Frame f : wire.frames) {
            channels.add(f.getChannel());
        }
        assertEquals(16, channels.size(), "канал выдан дважды");
        assertEquals(LineMux.FIRST_DATA_CHAN, (int) ((java.util.TreeSet<Integer>) channels).first());
        assertEquals(LineMux.LAST_DATA_CHAN, (int) ((java.util.TreeSet<Integer>) channels).last());

        CompletableFuture<byte[]> extra = mux.send(new byte[]{99}, T0);
        assertTrue(extra.isCompletedExceptionally(), "семнадцатая принята");
        assertEquals(1, mux.getRefused());
        assertEquals(16, wire.frames.size(), "семнадцатая всё же уехала в линию");

        ExecutionException e = assertThrows(ExecutionException.class, extra::get);
        assertTrue(e.getCause() instanceof LineMux.LineBusy,
                "отказ не того рода: " + e.getCause());

        // Освободили один — можно снова.
        replyWhole(LineMux.FIRST_DATA_CHAN, new byte[]{0});
        assertEquals(15, mux.inFlight());
        CompletableFuture<byte[]> again = mux.send(new byte[]{100}, T0);
        assertFalse(again.isCompletedExceptionally(),
                "освобождённый канал не переиспользован");
    }

    /**
     * ГЕЙТ, ПУНКТ ПЕРВЫЙ, ЧЕСТНО. Предыдущая проверка занимает каналы по
     * одному, и атомарность выдачи ею не задета вовсе: слово
     * «одновременно» в гейте про другое.
     *
     * <p>Здесь тридцать два потока стартуют по общему барьеру и просят
     * канал разом. Ровно шестнадцать должны получить РАЗНЫЕ каналы, ровно
     * шестнадцать — отказ. Выданный дважды канал означал бы, что ответ
     * уедет чужой команде, и заметить это можно было бы только по
     * неправильному решению у пользователя.
     */
    @Test
    @DisplayName("каналы выдаются атомарно при одновременном запросе")
    void channelsAreHandedOutAtomically() throws Exception {
        int threads = 32;
        java.util.concurrent.CyclicBarrier start =
                new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<CompletableFuture<byte[]>>> futures =
                new ArrayList<>();

        // Провод синхронизируется: подменный список не потокобезопасен, а
        // проверяется здесь мультиплексор, а не список.
        final List<Frame> seen =
                java.util.Collections.synchronizedList(new ArrayList<Frame>());
        LineMux concurrent = new LineMux(bytes -> {
            FrameDecoder d = new FrameDecoder();
            seen.addAll(d.feed(bytes, 0, bytes.length));
        }, control::add);

        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                return concurrent.send(new byte[]{(byte) n}, T0);
            }));
        }

        int accepted = 0;
        int rejected = 0;
        for (java.util.concurrent.Future<CompletableFuture<byte[]>> f : futures) {
            if (f.get().isCompletedExceptionally()) {
                rejected++;
            } else {
                accepted++;
            }
        }
        pool.shutdown();

        assertEquals(16, accepted, "принято не шестнадцать команд");
        assertEquals(16, rejected, "отвергнуто не шестнадцать команд");
        assertEquals(16, concurrent.inFlight());

        java.util.Set<Integer> used = new java.util.TreeSet<>();
        for (Frame f : seen) {
            used.add(f.getChannel());
        }
        assertEquals(16, seen.size(), "в линию ушло не шестнадцать кадров");
        assertEquals(16, used.size(),
                "канал выдан дважды: ответ уедет чужой команде, и узнается "
                        + "это по неправильному решению у пользователя");
    }

    /**
     * Потерянная команда не занимает канал вечно. Без срока шестнадцать
     * потерь останавливают гейтвей молча: каждая отдельная выглядит как
     * редкая неудача.
     */
    @Test
    @DisplayName("канал освобождается по сроку, а команда узнаёт об этом")
    void expiryFreesChannel() {
        CompletableFuture<byte[]> reply = mux.send(new byte[]{1}, T0);
        assertEquals(1, mux.inFlight());

        assertEquals(0, mux.expire(T0.plusSeconds(1), Duration.ofSeconds(5)),
                "канал освобождён раньше срока");
        assertFalse(reply.isDone());

        assertEquals(1, mux.expire(T0.plusSeconds(5), Duration.ofSeconds(5)),
                "канал не освобождён по сроку");
        assertEquals(0, mux.inFlight());
        assertTrue(reply.isCompletedExceptionally(),
                "команда не узнала, что её бросили");

        ExecutionException e = assertThrows(ExecutionException.class, reply::get);
        assertTrue(e.getCause() instanceof LineMux.LineTimeout);
        assertEquals(1, mux.getTimedOut());
    }

    /** Опоздавший ответ считается, а не роняет гейтвей. */
    @Test
    @DisplayName("ответ на свободный канал считается неприкаянным")
    void lateReplyIsCounted() {
        mux.send(new byte[]{1}, T0);
        mux.expire(T0.plusSeconds(5), Duration.ofSeconds(5));

        replyWhole(LineMux.FIRST_DATA_CHAN, new byte[]{7});

        assertEquals(1, mux.getUnrouted(),
                "опоздавший ответ не посчитан: срок подобран не тот, и "
                        + "узнать об этом было бы неоткуда");
    }

    /** Служебные каналы уходят своему обработчику, а не в неприкаянные. */
    @Test
    @DisplayName("служебные кадры не считаются неприкаянными")
    void controlFramesAreRouted() {
        mux.onFrame(new Frame(Frame.CHAN_CONTROL, Frame.FLAG_HELLO, new byte[0]));
        mux.onFrame(new Frame(Frame.CHAN_METRICS, 0, new byte[]{1, 2}));
        mux.onFrame(new Frame(Frame.CHAN_LOG, 0, new byte[]{3}));

        assertEquals(3, control.size(), "служебные кадры не дошли до обработчика");
        assertEquals(3, mux.getControlFrames());
        assertEquals(0, mux.getUnrouted(),
                "служебный кадр сочтён неприкаянным: полезный счётчик "
                        + "превратился бы в шум");
    }

    /**
     * Отказ линии посреди сообщения освобождает канал. Иначе через
     * шестнадцать таких отказов гейтвей перестанет принимать команды.
     */
    @Test
    @DisplayName("отказ линии не оставляет канал занятым")
    void lineFailureFreesChannel() {
        wire.failWith = new IllegalStateException("линия оборвалась");

        CompletableFuture<byte[]> reply = mux.send(new byte[]{1}, T0);

        assertTrue(reply.isCompletedExceptionally(), "отказ линии не дошёл");
        assertEquals(0, mux.inFlight(),
                "канал остался занятым после отказа линии");
    }
}
