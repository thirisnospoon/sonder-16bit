package sonder.shell.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sonder.enrichment.Enrichment;
import sonder.shell.app.ConnectionSource;
import sonder.shell.projection.FeedProjection;
import sonder.shell.stream.FeedStream;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Что запускает дренаж очереди.
 *
 * <p><b>Опрос, а не только уведомление.</b> Firebird умеет
 * {@code POST_EVENT}, и оно короче по задержке, но недолговечно: потерянное
 * уведомление означало бы навсегда застрявшее событие. Опрос — не запасной
 * путь на случай сбоя, а основной: уведомление лишь укорачивает ожидание,
 * и потому появится отдельно, не заменив этот цикл.
 *
 * <p><b>{@code fixedDelay}, а не {@code fixedRate}.</b> Интервал считается
 * от КОНЦА прошлого захода: при {@code fixedRate} длинный заход накладывался
 * бы на следующий, и два потока пошли бы в очередь одновременно. Это не
 * сломало бы данные — {@code SKIP LOCKED} их разведёт, — но означало бы,
 * что нагрузка на базу растёт ровно тогда, когда база и так не справляется.
 *
 * <p><b>Исключение не выпускает наружу.</b> Планировщик Spring на
 * исключении из задачи прекращает её повторять — то есть один отказ базы
 * навсегда останавливает дренаж, и снаружи это выглядит как «события
 * просто перестали доходить». Поэтому здесь ловится всё, включая
 * {@code RuntimeException}.
 */
@Configuration
@ConditionalOnProperty(name = "sonder.outbox.enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxSchedule {

    @Bean
    public OutboxDrainer outboxDrainer(DataSource dataSource,
                                       FeedStream stream,
                                       Enrichment enrichment,
                                       @Value("${sonder.outbox.batch:32}") int batch) {
        ConnectionSource connections = dataSource::getConnection;
        // Проекция пишет В транзакцию, поток рассылает ПОСЛЕ коммита.
        // Порядок задан здесь и только здесь — так требует контракт
        // операции subscribe.
        return new OutboxDrainer(connections, new FeedProjection(enrichment),
                new Backoff(), batch, stream);
    }

    @Bean
    public OutboxPump outboxPump(OutboxDrainer drainer,
                                 @Value("${sonder.outbox.batch:32}") int batch,
                                 @Value("${sonder.outbox.max-rounds:8}") int rounds) {
        return new OutboxPump(drainer, batch, rounds);
    }

    /**
     * Тик дренажа. Отдельным бобом, а не методом конфигурации: расписание
     * Spring вешает на бобы, а не на {@code @Bean}-методы.
     */
    @Component
    @ConditionalOnProperty(name = "sonder.outbox.enabled",
            havingValue = "true", matchIfMissing = true)
    public static class Ticker {

        private static final Logger log = LoggerFactory.getLogger(Ticker.class);

        private final OutboxPump pump;
        private final AtomicLong ticks = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();

        public Ticker(OutboxPump pump) {
            this.pump = pump;
        }

        @Scheduled(fixedDelayString = "${sonder.outbox.poll-ms:1000}",
                   initialDelayString = "${sonder.outbox.initial-delay-ms:2000}")
        public void tick() {
            ticks.incrementAndGet();
            try {
                pump.pumpOnce(Instant.now());
            } catch (Exception e) {
                // Считаем, но не пробрасываем: проброшенное исключение
                // остановило бы расписание НАВСЕГДА, и очередь встала бы
                // молча.
                failures.incrementAndGet();
                log.warn("заход дренажа не удался: {}", e.toString());
            }
        }

        /** Сколько раз тик отработал. Метрика, а не логика. */
        public long getTicks() {
            return ticks.get();
        }

        /** Сколько заходов кончилось отказом. */
        public long getFailures() {
            return failures.get();
        }
    }
}
