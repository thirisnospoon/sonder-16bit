package sonder.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameCodec;
import sonder.gateway.line.LineMux;
import sonder.gateway.line.LineServer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Линия к ноде, собранная в одно живое целое.
 *
 * <p>Транспорт был написан и проверен по частям — сервер, мультиплексор,
 * связыватель конвертов, — но в приложении к нему не вёл ни один путь:
 * ни одна строка производственного кода не создавала {@link LineServer}.
 * Проверенный код, до которого не доходит исполнение, работой не
 * считается: в бою на месте ядра стоял {@code UnavailableDecider} и
 * честно отвечал 502, хотя мост был готов.
 *
 * <p>Здесь три части связаны и получают время жизни приложения: сервер
 * ждёт ноду, мультиплексор раздаёт каналы, уборщик освобождает те, чьи
 * команды не дождались ответа.
 *
 * <p><b>Уборщик обязателен, а не полезен.</b> Потерянная в линии команда
 * держит канал навсегда; шестнадцать таких — и гейтвей перестаёт
 * принимать команды вовсе, причём молча, потому что каждая отдельная
 * потеря выглядит редкой неудачей. Срок уборки тот же, что у вызова:
 * канал нужен ровно до тех пор, пока кто-то ждёт ответа.
 *
 * <p><b>Обрыв роняет команды в работе и не повторяет их.</b> Нода могла
 * успеть принять команду и начать решать, и второй экземпляр создал бы
 * второй пост. Решает, повторять ли, тот, кто знает про идемпотентность,
 * — оболочка, а не транспорт.
 */
public final class LineTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LineTransport.class);

    /**
     * Как часто подметать занятые каналы.
     *
     * <p>Не по сроку вызова, а чаще: уборка происходит после того, как
     * вызывающий уже сдался, и разница между «сдался» и «канал свободен»
     * — это время, которое канал занят зря. Секунда против пяти секунд
     * срока — двадцать процентов сверху в худшем случае, и это дешевле,
     * чем будить поток десять раз в секунду ради шестнадцати записей.
     */
    static final Duration SWEEP_PERIOD = Duration.ofSeconds(1);

    private final LineServer line;
    private final LineMux mux;
    private final Duration timeout;

    private final AtomicLong hellos = new AtomicLong();
    private final AtomicLong droppedOnBreak = new AtomicLong();

    private volatile ScheduledExecutorService sweeper;

    public LineTransport(int port, Duration timeout) {
        this.timeout = timeout;
        // Порядок здесь связан узлом: серверу нужен получатель кадров,
        // мультиплексору — куда писать. Ссылка на мультиплексор берётся
        // методом, а не полем в лямбде: поле к этому моменту ещё не
        // присвоено, и захват его значения дал бы null навсегда.
        this.line = new LineServer(port, this::route, this::onBreak);
        this.mux = new LineMux(this.line::write, this::onControl);
    }

    /** Поднять линию. Порт занимается здесь, а не в конструкторе. */
    public void start() throws IOException {
        line.start();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "line-sweeper");
                    t.setDaemon(true);
                    return t;
                });
        s.scheduleWithFixedDelay(this::sweep,
                SWEEP_PERIOD.toMillis(), SWEEP_PERIOD.toMillis(),
                TimeUnit.MILLISECONDS);
        sweeper = s;
        log.info("линия поднята на порту {} (срок команды {})", getPort(), timeout);
    }

    private void sweep() {
        try {
            mux.expire(Instant.now(), timeout);
        } catch (RuntimeException e) {
            // Уборщик обязан пережить неудачную уборку: остановившись, он
            // не оставит следа, а каналы начнут кончаться.
            log.warn("уборка каналов не удалась", e);
        }
    }

    private void route(Frame frame) {
        mux.onFrame(frame);
    }

    /**
     * Управляющий канал.
     *
     * <p><b>На приветствие ОТВЕЧАЕМ, и это не вежливость.</b> Нода не
     * умеет переподключаться: нульмодем эмулятора идёт на порт один раз
     * при старте. Значит, обрыв она обязана заметить сама — а заметить
     * его можно только по отсутствию ответа: молчание на исправной линии
     * и молчание на мёртвой выглядят с той стороны одинаково, потому что
     * своей волей гейтвей не шлёт ничего.
     *
     * <p>Ответ и есть тот признак, который их различает. Без него
     * перезапуск оболочки оставлял ноду живой и бесполезной: эмулятор
     * работает, цикл крутится, команд больше не будет никогда, а система
     * выглядит поднятой и отвечает «ядро недоступно» на всё подряд.
     */
    private void onControl(Frame frame) {
        if (frame.hasFlag(Frame.FLAG_HELLO)) {
            long n = hellos.incrementAndGet();
            if (n == 1) {
                log.info("нода поздоровалась");
            }
            answerHello();
            return;
        }
        log.debug("управляющий кадр без приветствия: {}", frame);
    }

    private void answerHello() {
        try {
            line.write(FrameCodec.encode(
                    new Frame(Frame.CHAN_CONTROL, Frame.FLAG_HELLO, new byte[0])));
        } catch (RuntimeException e) {
            // Линия отказала прямо сейчас — отвечать некому, и это не
            // повод ронять чтение кадров: обрыв заметит onBreak.
            log.debug("ответ на приветствие не ушёл: {}", e.toString());
        }
    }

    private void onBreak() {
        // Срок нулевой: ждать больше нечего, другой конец линии исчез.
        int freed = mux.expire(Instant.now(), Duration.ZERO);
        droppedOnBreak.addAndGet(freed);
        log.warn("обрыв линии, команд уронено: {}", freed);
    }

    public LineMux getMux() {
        return mux;
    }

    /**
     * Срок команды — один и тот же для ждущего и для уборщика.
     *
     * <p>Отдаётся наружу именно затем, чтобы вызывающий не заводил свой:
     * разойдись эти два числа, канал освобождался бы, пока команда ещё
     * ждёт ответа, — и ответ пришёл бы уже на чужой канал.
     */
    public Duration getTimeout() {
        return timeout;
    }

    /** Порт, на котором линия ждёт ноду; при нуле в настройке — выданный. */
    public int getPort() {
        return line.getPort();
    }

    public boolean isConnected() {
        return line.isConnected();
    }

    public long getConnects() {
        return line.getConnects();
    }

    public long getBreaks() {
        return line.getBreaks();
    }

    public long getBytesIn() {
        return line.getBytesIn();
    }

    public long getBytesOut() {
        return line.getBytesOut();
    }

    public long getHellos() {
        return hellos.get();
    }

    public long getDroppedOnBreak() {
        return droppedOnBreak.get();
    }

    public int inFlight() {
        return mux.inFlight();
    }

    @Override
    public void close() {
        ScheduledExecutorService s = sweeper;
        if (s != null) {
            s.shutdownNow();
            sweeper = null;
        }
        line.close();
        log.info("линия закрыта");
    }
}
