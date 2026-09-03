package sonder.gateway.line;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Мультиплексор линии со стороны гейтвея.
 *
 * <p><b>Номера каналов назначает гейтвей, нода их принимает</b> — так
 * решено в ADR-0015, и здесь это выражено тем, что таблица каналов живёт
 * тут. Раздавай номера обе стороны, и однажды они выдали бы один и тот же
 * дважды, а команда получила бы чужой ответ.
 *
 * <p><b>Шестнадцать каналов, потому что у ноды шестнадцать файберов.</b>
 * Число не подобрано, а взято с той стороны: команда без файбера всё
 * равно не начнётся. Семнадцатая команда получает отказ немедленно, а не
 * ждёт в очереди: ожидание внутри гейтвея — это очередь, о которой не
 * знает ни отправитель, ни метрика.
 *
 * <p><b>Ответ собирается из кадров до первого без {@code FlagMore}.</b>
 * Так же, как нода собирает команду. Признак конца — отсутствие флага, а
 * не длина: длину сообщения ни одна сторона заранее не знает.
 */
public final class LineMux {

    private static final Logger log = LoggerFactory.getLogger(LineMux.class);

    /** Первый и последний канал данных. Служебные — вне диапазона. */
    public static final int FIRST_DATA_CHAN = 1;
    public static final int LAST_DATA_CHAN = 16;

    /** Куда уходят байты. Отделено, чтобы линию можно было подменить. */
    public interface Sink {
        void write(byte[] bytes);
    }

    /** Кадр управляющего канала: приветствие, метрики, отзыв. */
    public interface ControlHandler {
        void onControl(Frame frame);
    }

    /** Одна команда в работе. */
    private static final class InFlight {
        final CompletableFuture<byte[]> reply = new CompletableFuture<>();
        final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        final Instant startedAt;

        InFlight(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    private final Sink sink;
    private final ControlHandler control;

    /** Занятые каналы. Ключ — номер, значение — команда в работе. */
    private final Map<Integer, InFlight> busy = new ConcurrentHashMap<>();

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong unrouted = new AtomicLong();
    private final AtomicLong controlFrames = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    public LineMux(Sink sink, ControlHandler control) {
        this.sink = sink;
        this.control = control;
    }

    /**
     * Отправить команду и получить обещание ответа.
     *
     * <p>Пустое обещание означает отказ: свободных каналов нет. Это не
     * ошибка, а обратное давление — у ноды кончились файберы, и врать ей
     * про семнадцатую команду незачем.
     */
    public CompletableFuture<byte[]> send(byte[] payload, Instant now) {
        int channel = takeChannel(now);
        if (channel < 0) {
            refused.incrementAndGet();
            CompletableFuture<byte[]> refusal = new CompletableFuture<>();
            refusal.completeExceptionally(new LineBusy(
                    "нет свободных каналов: у ноды " + LAST_DATA_CHAN
                            + " файберов, и все заняты"));
            return refusal;
        }

        InFlight inFlight = busy.get(channel);
        try {
            for (Frame frame : split(channel, payload)) {
                sink.write(FrameCodec.encode(frame));
                sent.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // Линия отказала на середине сообщения. Канал надо
            // освободить: иначе он останется занятым навсегда, и через
            // шестнадцать таких отказов гейтвей встанет.
            busy.remove(channel);
            inFlight.reply.completeExceptionally(e);
        }
        return inFlight.reply;
    }

    /**
     * Разбить нагрузку на кадры.
     *
     * <p>Пустая нагрузка — это один кадр без данных, а не ноль кадров:
     * команда без тела всё равно команда, и нода ждёт хотя бы один кадр,
     * чтобы завести файбер.
     */
    static List<Frame> split(int channel, byte[] payload) {
        List<Frame> out = new ArrayList<>();
        int offset = 0;
        do {
            int take = Math.min(Frame.MAX_PAYLOAD, payload.length - offset);
            byte[] chunk = new byte[take];
            System.arraycopy(payload, offset, chunk, 0, take);
            offset += take;
            boolean last = offset >= payload.length;
            int flags = last ? Frame.FLAG_NEEDS_REPLY : Frame.FLAG_MORE;
            out.add(new Frame(channel, flags, chunk));
        } while (offset < payload.length);
        return out;
    }

    /** Занять канал. Возвращает −1, если свободных нет. */
    private int takeChannel(Instant now) {
        for (int c = FIRST_DATA_CHAN; c <= LAST_DATA_CHAN; c++) {
            if (busy.putIfAbsent(c, new InFlight(now)) == null) {
                return c;
            }
        }
        return -1;
    }

    /** Сколько команд сейчас в работе. */
    public int inFlight() {
        return busy.size();
    }

    /**
     * Кадр из линии.
     *
     * <p>Управляющий канал уходит своему обработчику: его кадры не
     * адресованы командам, и считать их неприкаянными значило бы
     * превратить полезный счётчик в шум.
     */
    public void onFrame(Frame frame) {
        received.incrementAndGet();
        int channel = frame.getChannel();

        if (channel == Frame.CHAN_CONTROL
                || channel == Frame.CHAN_METRICS
                || channel == Frame.CHAN_LOG) {
            controlFrames.incrementAndGet();
            if (control != null) {
                control.onControl(frame);
            }
            return;
        }

        InFlight inFlight = busy.get(channel);
        if (inFlight == null) {
            // Ответ на канал, которого никто не занимал. Так выглядит
            // ответ, опоздавший после срока, и молчать о нём нельзя:
            // это признак того, что срок выбран не тот.
            unrouted.incrementAndGet();
            log.warn("кадр на свободный канал {}: ответ опоздал или линия врёт",
                    channel);
            return;
        }

        byte[] part = frame.getPayload();
        inFlight.collected.write(part, 0, part.length);

        if (!frame.hasFlag(Frame.FLAG_MORE)) {
            busy.remove(channel);
            inFlight.reply.complete(inFlight.collected.toByteArray());
        }
    }

    /**
     * Освободить каналы, чей ответ не пришёл в срок.
     *
     * <p>Без этого одна потерянная в линии команда занимает канал
     * навсегда, и после шестнадцати таких гейтвей перестаёт принимать
     * команды вовсе — молча, потому что каждая отдельная потеря выглядит
     * как редкая неудача.
     *
     * @return сколько каналов освобождено
     */
    public int expire(Instant now, Duration limit) {
        int freed = 0;
        for (Map.Entry<Integer, InFlight> e : busy.entrySet()) {
            InFlight f = e.getValue();
            if (Duration.between(f.startedAt, now).compareTo(limit) < 0) {
                continue;
            }
            if (busy.remove(e.getKey(), f)) {
                timedOut.incrementAndGet();
                freed++;
                f.reply.completeExceptionally(new LineTimeout(
                        "нода не ответила на канал " + e.getKey() + " за " + limit));
            }
        }
        if (freed > 0) {
            log.warn("освобождено каналов по сроку: {}", freed);
        }
        return freed;
    }

    public long getSent() {
        return sent.get();
    }

    public long getReceived() {
        return received.get();
    }

    public long getRefused() {
        return refused.get();
    }

    public long getUnrouted() {
        return unrouted.get();
    }

    public long getControlFrames() {
        return controlFrames.get();
    }

    public long getTimedOut() {
        return timedOut.get();
    }

    /** Свободных каналов нет: у ноды кончились файберы. */
    public static final class LineBusy extends RuntimeException {
        public LineBusy(String message) {
            super(message);
        }
    }

    /** Нода не ответила в срок. */
    public static final class LineTimeout extends RuntimeException {
        public LineTimeout(String message) {
            super(message);
        }
    }
}
