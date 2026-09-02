package sonder.shell.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;

/**
 * Политика дренажа: сколько кругов делать за один заход.
 *
 * <p>Отдельно от расписания, и это главное здесь. Расписание — забота
 * фреймворка, а «сколько раз подряд разгребать очередь» — решение,
 * которое надо проверять, и проверять без ожидания. Смешай их — и
 * единственным способом проверки останется «подожди секунду и посмотри».
 *
 * <p><b>Полная пачка означает, что очередь не разобрана.</b> Ждать после
 * неё следующего тика значило бы разгребать завал со скоростью одной
 * пачки в интервал: тысяча событий при пачке в тридцать две и интервале
 * в секунду разбиралась бы полминуты, причём в основном простаивая.
 * Поэтому пока пачки приходят полными, круги идут подряд.
 *
 * <p><b>Но не бесконечно.</b> Заход обязан кончаться: без предела один
 * поток намертво занят очередью, которую пополняют быстрее, чем он
 * разгребает, и ни метрики, ни остановка приложения до него не
 * достучатся. Предел делает длительность захода ограниченной сверху и
 * предсказуемой.
 */
public final class OutboxPump {

    private static final Logger log = LoggerFactory.getLogger(OutboxPump.class);

    /**
     * Сколько полных пачек разгребать за один заход.
     *
     * <p>Восемь при пачке в тридцать две — это двести пятьдесят шесть
     * событий за тик. Спайк S5 намерял потолок дренажа около пятисот
     * событий в секунду, так что заход укладывается примерно в полсекунды
     * и не запирает поток надолго.
     */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    private final OutboxDrainer drainer;
    private final int batch;
    private final int maxRounds;

    public OutboxPump(OutboxDrainer drainer, int batch) {
        this(drainer, batch, DEFAULT_MAX_ROUNDS);
    }

    public OutboxPump(OutboxDrainer drainer, int batch, int maxRounds) {
        this.drainer = drainer;
        this.batch = batch;
        this.maxRounds = maxRounds;
    }

    /** Что сделал заход. */
    public static final class Result {
        private final int rounds;
        private final int published;
        private final int failed;
        private final boolean moreLikely;

        Result(int rounds, int published, int failed, boolean moreLikely) {
            this.rounds = rounds;
            this.published = published;
            this.failed = failed;
            this.moreLikely = moreLikely;
        }

        public int getRounds() {
            return rounds;
        }

        public int getPublished() {
            return published;
        }

        public int getFailed() {
            return failed;
        }

        /**
         * Заход упёрся в предел кругов, а пачка всё ещё была полной:
         * очередь, скорее всего, не разобрана. Не «точно»: между
         * последним кругом и этим выводом её могли разобрать другие.
         */
        public boolean isMoreLikely() {
            return moreLikely;
        }
    }

    /**
     * Один заход: круги подряд, пока пачки приходят полными.
     *
     * <p>Время параметром по той же причине, что и у дренажёра: очередь
     * с отсрочками, берущая часы у себя, проверяется только ожиданием.
     */
    public Result pumpOnce(Instant now) throws SQLException {
        int rounds = 0;
        int published = 0;
        int failed = 0;
        boolean full = false;

        while (rounds < maxRounds) {
            OutboxDrainer.Result r = drainer.drainOnce(now);
            rounds++;
            published += r.getPublished();
            failed += r.getFailed();
            full = r.isFull(batch);
            if (!full) {
                // Неполная пачка — очередь разобрана до дна. Следующий
                // круг вернул бы пустоту и стоил бы круга к базе.
                break;
            }
        }

        if (full) {
            log.info("очередь не разобрана за {} кругов: опубликовано {}, "
                    + "отказов {}", rounds, published, failed);
        }
        return new Result(rounds, published, failed, full);
    }
}
