package sonder.shell.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Видимость застрявших событий.
 *
 * <p><b>Это и есть «DLQ» в том виде, в каком проект её допускает.</b>
 * Обычная очередь мёртвых писем перекладывает ядовитое событие в сторону
 * по достижении порога — то есть код по числу решает выкинуть данные, и
 * решает молча. Здесь решено иначе, и решено давно, там же, где заведён
 * счётчик попыток: разбирается человек, а код обязан сделать так, чтобы
 * разбираться было по чему.
 *
 * <p>Отсюда сторож: раз в минуту он считает застрявшее и, если оно есть,
 * пишет об этом. Строка в журнале — не украшение: без неё «часть событий
 * не доходит» выглядит как «всё работает, просто медленно», и разница
 * между этими двумя состояниями видна только по счётчику попыток.
 *
 * <p><b>Молчание, когда всё хорошо.</b> Сторож, пишущий каждую минуту
 * «застрявших нет», за сутки даёт полторы тысячи строк, среди которых
 * настоящая тонет. Пишем только когда есть что сказать, а «когда
 * проверяли в последний раз» отдаём метрикой.
 */
@Component
@ConditionalOnProperty(name = {"sonder.outbox.enabled", "sonder.outbox.watch"},
        havingValue = "true", matchIfMissing = true)
public class OutboxWatch {

    private static final Logger log = LoggerFactory.getLogger(OutboxWatch.class);

    private final DataSource dataSource;
    private final int minAttempts;

    private final AtomicLong checks = new AtomicLong();
    private final AtomicLong lastCount = new AtomicLong();
    private final AtomicLong lastOldestId = new AtomicLong(-1);

    public OutboxWatch(DataSource dataSource,
                       @Value("${sonder.outbox.stuck-attempts:5}") int minAttempts) {
        this.dataSource = dataSource;
        this.minAttempts = minAttempts;
    }

    /**
     * Порог в пять попыток выбран не наугад. При удвоении с потолком в
     * пять минут пятая попытка приходится примерно на шестнадцатую
     * секунду после первой неудачи: короткий сбой к этому времени уже
     * прошёл, и всё, что дожило до пятой попытки, ждёт человека, а не
     * времени.
     */
    @Scheduled(fixedDelayString = "${sonder.outbox.watch-ms:60000}",
               initialDelayString = "${sonder.outbox.watch-ms:60000}")
    public void look() {
        checks.incrementAndGet();
        try (Connection c = dataSource.getConnection()) {
            Outbox.Stuck stuck = Outbox.stuck(c, minAttempts);
            lastCount.set(stuck.getCount());
            lastOldestId.set(stuck.getOldestId());
            if (stuck.getCount() > 0) {
                log.warn("в очереди застряло {} событий (попыток {} и больше),"
                                + " самое старое — {}. Дренаж их больше не"
                                + " разберёт сам: нужен разбор",
                        stuck.getCount(), minAttempts, stuck.getOldestId());
            }
        } catch (Exception e) {
            // Сторож, роняющий расписание, хуже отсутствующего сторожа:
            // вместе с ним встанет всё, что на этом расписании висит.
            log.warn("не удалось посчитать застрявшие события: {}", e.toString());
        }
    }

    /** Сколько застряло на последней проверке. Метрика, а не логика. */
    public long getLastCount() {
        return lastCount.get();
    }

    /** Самое старое застрявшее на последней проверке; -1, если таких нет. */
    public long getLastOldestId() {
        return lastOldestId.get();
    }

    /** Сколько раз сторож смотрел. Ноль означает, что он не работает. */
    public long getChecks() {
        return checks.get();
    }
}
