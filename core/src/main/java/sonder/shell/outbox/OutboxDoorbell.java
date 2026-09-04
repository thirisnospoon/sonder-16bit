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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
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

    /**
     * Сколько ждать возврата пробного события.
     *
     * <p>Живой канал приносит его за единицы миллисекунд — измеренный
     * лаг доставки события порядка сорока. Две секунды — запас в
     * полсотни раз; больше значило бы держать поток сторожа впустую,
     * меньше — объявлять мёртвым канал, задержавшийся под нагрузкой.
     */
    private static final long PROBE_WAIT_MS = 2000;

    private final DataSource dataSource;
    private final OutboxPump pump;

    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicLong rings = new AtomicLong();
    private final AtomicLong pumps = new AtomicLong();
    private final AtomicLong subscriptions = new AtomicLong();

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
        subscribe();
    }

    /** Подписаться заново. Зовётся при старте и сторожем. */
    private synchronized void subscribe() {
        try (Connection c = dataSource.getConnection()) {
            EventManager fresh = FBEventManager.createFor(c);
            fresh.connect();
            fresh.addEventListener(EVENT_NAME, this);
            events = fresh;
            subscriptions.incrementAndGet();
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

    /**
     * Сторож подписки: ЗВОНИТ В ЗВОНОК, а не спрашивает его.
     *
     * <p>ЗВОНОК НЕ ПЕРЕЖИВАЛ ПЕРЕЗАПУСК БАЗЫ, и это не было видно ничем.
     * Канал событий Firebird умирает вместе с соединением, подписка
     * делалась ОДИН РАЗ при старте, а {@code isSubscribed()} возвращал
     * «да» и после смерти канала — смотрел на ссылку, а не на состояние.
     * Заметить можно было только по времени: измерено 93 мс до
     * перезапуска базы и 794 после, без единой записи в журнале.
     *
     * <p>ПЕРВАЯ ПОПЫТКА ПОЧИНКИ СПРАШИВАЛА {@code isConnected()} И НЕ
     * СРАБОТАЛА: Jaybird отвечает «подключён» и на мёртвом канале —
     * метод говорит о том, звали ли {@code connect()}, а не о том, жив
     * ли сокет. Сторож честно ходил каждые тридцать секунд и каждый раз
     * получал «всё в порядке» о неработающем звонке.
     *
     * <p>Поэтому проверка не спрашивает, а ТРЕБУЕТ ДОКАЗАТЬ: шлёт
     * событие сама и смотрит, пришло ли оно. Не пришло за отведённый
     * срок — канал мёртв, независимо от того, что о себе думает
     * библиотека. Стоит это одной крошечной транзакции в полминуты;
     * заход дренажа, который она вызовет, найдёт пустую очередь и
     * закончится немедленно.
     *
     * <p>Ложного «живой» тут быть не может: если событие пришло, канал
     * работает — неважно, наше это событие или чужое.
     */
    @Scheduled(fixedDelayString = "${sonder.outbox.doorbell-watch-ms:30000}",
               initialDelayString = "${sonder.outbox.doorbell-watch-ms:30000}")
    public void watch() {
        if (proven()) {
            return;
        }
        log.warn("звонок очереди не отозвался на пробное событие — переподписываемся");
        dropQuietly();
        subscribe();
    }

    /**
     * Доказана ли работа звонка: шлём событие и ждём его же.
     *
     * <p>Отсутствие подписки доказывать нечем — сразу «нет».
     */
    private boolean proven() {
        if (!hasChannel()) {
            return false;
        }
        long before = rings.get();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            // POST_EVENT доставляется при коммите, поэтому блок должен
            // завершиться транзакцией. Автокоммит соединения из пула это
            // и делает.
            st.execute("EXECUTE BLOCK AS BEGIN POST_EVENT '" + EVENT_NAME + "'; END");
        } catch (Exception e) {
            // Не смогли даже послать — база недоступна. Переподписка
            // сейчас всё равно не удастся, и шуметь незачем: следующий
            // виток разберётся.
            log.debug("пробное событие не послалось: {}", e.toString());
            return true;
        }

        long deadline = System.currentTimeMillis() + PROBE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (rings.get() > before) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    /** Есть ли вообще ссылка на канал. */
    private synchronized boolean hasChannel() {
        return events != null;
    }

    /** Отпустить мёртвое, не поднимая шума о мёртвом. */
    private synchronized void dropQuietly() {
        if (events == null) {
            return;
        }
        try {
            events.removeEventListener(EVENT_NAME, this);
            events.close();
        } catch (Exception e) {
            log.debug("прежний звонок не закрылся: {}", e.toString());
        }
        events = null;
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

    /**
     * Подписан ли звонок НА САМОМ ДЕЛЕ.
     *
     * <p>Прежняя редакция возвращала {@code events != null} — то есть
     * «подписку когда-то создавали». После перезапуска базы канал мёртв,
     * а ссылка цела, и метод отвечал «да» о неработающем звонке.
     */
    public boolean isSubscribed() {
        return hasChannel();
    }

    /** Сколько раз подписывались. Больше одного — были потери. */
    public long getSubscriptions() {
        return subscriptions.get();
    }

    @Override
    public void destroy() {
        dropQuietly();
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
