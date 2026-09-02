package sonder.shell.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.shell.app.ConnectionSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.List;

/**
 * Дренаж очереди исходящих: взять пачку, отдать обработчику, отметить.
 *
 * <p>Круг делается в ОДНОЙ транзакции, и это следует из
 * {@code SKIP LOCKED}: строка держится за нами блокировкой, а блокировка
 * живёт транзакцией. Закрой её между «взял» и «отметил» — и соседний
 * дренажёр возьмёт ту же строку, а событие уедет дважды.
 *
 * <p><b>У каждой строки своя точка сохранения.</b> Обработчик пишет
 * проекции, то есть меняет ту же транзакцию. Упади он на середине — без
 * точки сохранения его половинчатая запись уехала бы в базу вместе с
 * отметкой о неудаче: строка числится неопубликованной, а следы её
 * обработки уже лежат. При повторе они лягут второй раз. Откат до точки
 * убирает написанное обработчиком и оставляет транзакцию живой, чтобы
 * остальные строки пачки дошли до конца.
 *
 * <p>Отсчёт попытки и отсрочка пишутся ПОСЛЕ отката: иначе откат унёс бы
 * и их, и ядовитое событие вернулось бы в следующую же пачку с прежним
 * счётчиком.
 *
 * <p>Одна упавшая строка не уносит пачку: остальные обрабатываются. Иначе
 * одно ядовитое событие останавливало бы очередь целиком, и это
 * выглядело бы как «система встала», а не как «одно событие плохое».
 */
public final class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);

    /**
     * Что делать с событием. Пишет в ТУ ЖЕ транзакцию, соединение
     * приходит параметром по той же причине, что и в {@link Outbox}:
     * своё соединение означало бы свою транзакцию и разъехавшийся коммит.
     *
     * <p>Обязан быть идемпотентным. Между обработкой и коммитом система
     * может упасть, и тогда событие приедет второй раз — не «может быть
     * приедет», а приедет, потому что источник правды это строка, а не
     * факт вызова.
     */
    public interface Handler {
        void handle(Connection c, OutboxRecord record) throws Exception;
    }

    /** Что сделал один круг. Числа, а не журнал: по ним строится метрика. */
    public static final class Result {
        private final int claimed;
        private final int published;
        private final int failed;

        Result(int claimed, int published, int failed) {
            this.claimed = claimed;
            this.published = published;
            this.failed = failed;
        }

        public int getClaimed() {
            return claimed;
        }

        public int getPublished() {
            return published;
        }

        public int getFailed() {
            return failed;
        }

        /** Пачка полна — значит очередь, скорее всего, не разобрана. */
        public boolean isFull(int batch) {
            return claimed >= batch;
        }
    }

    private final ConnectionSource connections;
    private final Handler handler;
    private final Backoff backoff;
    private final int batch;

    public OutboxDrainer(ConnectionSource connections, Handler handler) {
        this(connections, handler, new Backoff(), Outbox.DEFAULT_BATCH);
    }

    public OutboxDrainer(ConnectionSource connections, Handler handler,
                         Backoff backoff, int batch) {
        this.connections = connections;
        this.handler = handler;
        this.backoff = backoff;
        this.batch = batch;
    }

    /**
     * Один круг дренажа.
     *
     * <p>Время параметром: очередь с отсрочками, берущая часы у себя,
     * проверяется только ожиданием — а ожидание в тесте не проверка.
     */
    public Result drainOnce(Instant now) throws SQLException {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            int published = 0;
            int failed = 0;

            List<OutboxRecord> claimed = Outbox.claim(c, batch, now);
            for (OutboxRecord record : claimed) {
                if (handleOne(c, record, now)) {
                    published++;
                } else {
                    failed++;
                }
            }

            c.commit();
            return new Result(claimed.size(), published, failed);
        }
    }

    /** Одна строка. Возвращает, удалось ли опубликовать. */
    private boolean handleOne(Connection c, OutboxRecord record, Instant now)
            throws SQLException {
        Savepoint before = c.setSavepoint();
        try {
            handler.handle(c, record);
            Outbox.markPublished(c, record.getId(), now);
            c.releaseSavepoint(before);
            return true;
        } catch (Exception e) {
            // Сначала убрать написанное обработчиком, и только потом
            // писать отсчёт попытки: в обратном порядке откат унёс бы и
            // его.
            c.rollback(before);
            int attempts = record.getAttempts() + 1;
            Instant notBefore = now.plus(backoff.after(attempts));
            Outbox.recordFailure(c, record.getId(), notBefore);
            log.warn("событие {} ({}) не опубликовано, попытка {}, повтор после {}: {}",
                    record.getId(), record.getType(), attempts, notBefore,
                    e.toString());
            return false;
        }
    }
}
