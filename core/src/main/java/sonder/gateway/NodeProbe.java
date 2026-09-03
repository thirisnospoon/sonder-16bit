package sonder.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.contract.decider.Decider;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PingResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Опрос ноды: последний снимок её метрик и когда он снят.
 *
 * <p>Нода отвечает своими счётчиками на {@code ping}, но до сих пор их
 * никто не спрашивал: метрики существовали и никуда не уходили. Здесь их
 * спрашивают по часам и держат последний ответ.
 *
 * <p><b>Возраст снимка — часть снимка.</b> Замолчавшая нода не портит
 * последний ответ: он остаётся правдоподобным и полным, и без времени
 * снятия неотличим от свежего. Проверка здоровья, читающая протухший
 * снимок, говорит «всё хорошо» ровно тогда, когда всё плохо, — это тот
 * же класс лжи, что и подставленная метрика.
 *
 * <p><b>Нонс случайный и сверяется.</b> Ответ с чужим нонсом означает,
 * что ответы перепутались между каналами; принять его значило бы
 * записать в метрики чужую правду.
 */
public final class NodeProbe {

    private static final Logger log = LoggerFactory.getLogger(NodeProbe.class);

    /**
     * Снимок метрик ноды или причина, по которой его нет.
     *
     * <p>Неизменяемый: его читает поток актуатора, а пишет поток опроса,
     * и общая изменяемая запись потребовала бы блокировки там, где
     * довольно подмены ссылки.
     */
    public static final class Snapshot {
        private final Instant at;
        private final PingResponse metrics;
        private final String failure;

        private Snapshot(Instant at, PingResponse metrics, String failure) {
            this.at = at;
            this.metrics = metrics;
            this.failure = failure;
        }

        static Snapshot ok(Instant at, PingResponse metrics) {
            return new Snapshot(at, metrics, null);
        }

        static Snapshot failed(Instant at, String why) {
            return new Snapshot(at, null, why);
        }

        public Instant getAt() {
            return at;
        }

        public PingResponse getMetrics() {
            return metrics;
        }

        public String getFailure() {
            return failure;
        }

        public boolean isOk() {
            return metrics != null;
        }
    }

    private final Decider decider;
    private final Duration staleAfter;
    private final AtomicReference<Snapshot> last = new AtomicReference<>();

    /**
     * @param decider ядро в том виде, в каком его объявляет контракт.
     *                ВАЖНО, чтобы сюда не попал {@code Decider},
     *                обёрнутый переводом отказов в решения: опрос обязан
     *                видеть отказ отказом, а не «решением» с кодом
     *                недоступности — у ping формы «не получилось» нет
     */
    public NodeProbe(Decider decider, Duration staleAfter) {
        this.decider = decider;
        this.staleAfter = staleAfter;
    }

    /** Один опрос. Отказ — тоже результат, и он записывается. */
    public Snapshot probe(Instant now) {
        int nonce = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        PingRequest request = new PingRequest();
        request.setNonce(nonce);

        Snapshot snapshot;
        try {
            PingResponse answer = decider.ping(request);
            if (answer.getNonce() != nonce) {
                // Не «почти правильный ответ», а чужой. Записать его в
                // метрики значило бы поверить чужой правде.
                snapshot = Snapshot.failed(now,
                        "нода вернула чужой нонс " + answer.getNonce()
                                + " вместо " + nonce);
            } else {
                snapshot = Snapshot.ok(now, answer);
            }
        } catch (RuntimeException e) {
            // ping — единственная операция, чей отказ не переводится в
            // решение: у него нет формы «не получилось», и выдуманные
            // нули соврали бы ровно этой проверке.
            snapshot = Snapshot.failed(now, String.valueOf(e.getMessage()));
        }

        Snapshot previous = last.getAndSet(snapshot);
        if (previous == null || previous.isOk() != snapshot.isOk()) {
            if (snapshot.isOk()) {
                log.info("нода отвечает на опрос");
            } else {
                log.warn("нода не ответила на опрос: {}", snapshot.getFailure());
            }
        }
        return snapshot;
    }

    public Snapshot getLast() {
        return last.get();
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    /** Снимок протух: он есть, но верить ему уже нельзя. */
    public boolean isStale(Snapshot snapshot, Instant now) {
        return snapshot == null
                || Duration.between(snapshot.getAt(), now).compareTo(staleAfter) > 0;
    }

    /** Метрики снимка в виде, пригодном для показа. */
    public static Map<String, Object> describe(PingResponse m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fibersInUse", m.getFibersInUse());
        // Пик и ёмкость только вместе: пик без ёмкости не значит ничего.
        out.put("arenaHighMark", m.getArenaHighMark());
        out.put("arenaCapacity", m.getArenaCapacity());
        out.put("commandsServed", m.getCommandsServed());
        out.put("commandsRefused", m.getCommandsRefused());
        out.put("commandsMalformed", m.getCommandsMalformed());
        out.put("lineErrors", m.getLineErrors());
        out.put("rxBytes", m.getRxBytes());
        out.put("txBytes", m.getTxBytes());
        return out;
    }
}
