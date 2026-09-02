package sonder.shell.outbox;

import org.firebirdsql.event.DatabaseEvent;
import org.firebirdsql.event.EventListener;
import org.firebirdsql.event.EventManager;
import org.firebirdsql.event.FBEventManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Дверной звонок очереди: просыпаться на уведомление, а не по часам.
 *
 * <p>Триггер шлёт {@code POST_EVENT} на вставку в outbox, и подписчик
 * будит дренаж сразу. Задержка доставки падает с интервала опроса —
 * секунды — до миллисекунд.
 *
 * <p><b>Опрос при этом остаётся.</b> Уведомление Firebird недолговечно и
 * ничего не несёт: потерянное означало бы навсегда застрявшую строку,
 * если бы других способов её найти не было. Источник правды — таблица, и
 * звонок только укорачивает ожидание. Выключенный звонок замедляет
 * систему, выключенный опрос её ломает.
 *
 * <p><b>Работа не делается в потоке уведомлений.</b> Jaybird зовёт
 * слушателя из своего диспетчера, и дренаж в нём занял бы поток, через
 * который приходят все остальные уведомления. Отсюда отдельный
 * исполнитель на один поток.
 *
 * <p><b>Уведомления схлопываются.</b> Сто вставок дадут сто событий, а
 * дренаж на них нужен один: он и так разбирает пачками. Признак
 * «заход уже заказан» снимается ПЕРЕД заходом, а не после — так событие,
 * пришедшее во время работы, закажет ещё один. Лишний заход стоит одного
 * пустого запроса, пропущенный — задержки до следующего опроса.
 */
@Component
// ОБА условия, и это не перестраховка. Звонок будит насос, а насоса нет,
// когда дренаж выключен: боб, требующий несуществующую зависимость, не
// даёт подняться всему приложению. Выключенный дренаж при включённом
// звонке — состояние, которого не бывает, и выражать его надо в
// условии, а не в осторожности вызывающего.
@ConditionalOnProperty(name = {"sonder.outbox.enabled", "sonder.outbox.doorbell"},
        havingValue = "true", matchIfMissing = true)
public class OutboxDoorbell implements EventListener, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxDoorbell.class);

    /**
     * Имя события. Оно же зашито в триггер миграции V6.
     *
     * <p>Разойтись они могут молча: слушатель подпишется на то, чего никто
     * не шлёт, и будет просто спать — а выглядеть это будет как «звонок
     * не работает». Проверка на совпадение есть в тестах.
     */
    public static final String EVENT_NAME = "sonder_outbox";

    private final DataSource dataSource;
    private final OutboxPump pump;

    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicLong rings = new AtomicLong();
    private final AtomicLong pumps = new AtomicLong();

    private ExecutorService worker;
    private EventManager events;

    public OutboxDoorbell(DataSource dataSource, OutboxPump pump) {
        this.dataSource = dataSource;
        this.pump = pump;
    }

    @Override
    public void afterPropertiesSet() {
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "outbox-doorbell");
            t.setDaemon(true);
            return t;
        });
        try (Connection c = dataSource.getConnection()) {
            events = FBEventManager.createFor(c);
            events.connect();
            events.addEventListener(EVENT_NAME, this);
            log.info("звонок очереди подписан на событие {}", EVENT_NAME);
        } catch (Exception e) {
            // Не поднявшийся звонок — не повод не подняться приложению:
            // опрос разберёт очередь и без него, просто медленнее.
            // Промолчать при этом нельзя, иначе «стало медленно» будет
            // необъяснимым.
            log.warn("звонок очереди не подписался, останется только опрос: {}",
                    e.toString());
            events = null;
        }
    }

    @Override
    public void eventOccurred(DatabaseEvent event) {
        rings.incrementAndGet();
        if (worker == null || worker.isShutdown()) {
            return;
        }
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            // Снимаем признак ДО работы: событие, пришедшее во время
            // захода, закажет следующий.
            queued.set(false);
            pumps.incrementAndGet();
            try {
                pump.pumpOnce(Instant.now());
            } catch (Exception e) {
                log.warn("заход по звонку не удался: {}", e.toString());
            }
        });
    }

    /** Сколько раз звонили. Метрика, а не логика. */
    public long getRings() {
        return rings.get();
    }

    /** Сколько заходов сделано по звонку: меньше звонков — схлопывание. */
    public long getPumps() {
        return pumps.get();
    }

    /** Подписался ли звонок на самом деле. */
    public boolean isSubscribed() {
        return events != null;
    }

    @Override
    public void destroy() {
        if (events != null) {
            try {
                events.removeEventListener(EVENT_NAME, this);
                events.close();
            } catch (Exception e) {
                log.warn("звонок не отписался: {}", e.toString());
            }
            events = null;
        }
        if (worker != null) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }
}
