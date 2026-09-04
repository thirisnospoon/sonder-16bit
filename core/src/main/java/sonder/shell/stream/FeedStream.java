package sonder.shell.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxRecord;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Поток обновлений: кому и что рассылать.
 *
 * <p>Питается тем же конвейером outbox, что и проекции, и зовётся ПОСЛЕ
 * коммита. Так требует контракт: «клиент не увидит события раньше, чем
 * оно попадёт в чтение». Пошли уведомление из обработчика — и клиент,
 * получив его, пошёл бы за лентой и не нашёл там того, о чём его только
 * что известили.
 *
 * <p><b>Кому слать, решает та же проекция, что и лента.</b> Строка
 * {@code feed_entries} и есть ответ на вопрос «чья это новость»: спрашивать
 * подписки заново значило бы завести второй способ считать одно и то же,
 * и однажды эти два способа разойдутся.
 *
 * <p><b>Запрос идёт только если есть кому слать.</b> Открытых соединений
 * обычно нет вовсе, и платить круг к базе за каждое событие в пустоту
 * незачем.
 *
 * <p>Соединение — вещь ненадёжная: клиент уходит, не прощаясь, сеть
 * рвётся. Поэтому отправка в мёртвое соединение не считается бедой:
 * оно просто снимается со списка. Событие при этом уже записано, и
 * несостоявшаяся доставка означает, что клиент увидит новость, когда
 * перечитает ленту.
 */
@Component
public class FeedStream implements OutboxDrainer.Published {

    private static final Logger log = LoggerFactory.getLogger(FeedStream.class);

    /** Что рассылается. Больше типов добавится вместе с потребителями. */
    static final String POST_CREATED = "post.created";

    private final DataSource dataSource;

    /**
     * Открытые соединения по пользователям. Один человек может смотреть
     * с двух вкладок, поэтому список, а не одно соединение.
     */
    private final Map<String, List<FeedListener>> listeners = new ConcurrentHashMap<>();

    public FeedStream(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Открыть поток для пользователя.
     *
     * <p>Первым делом в соединение уходит комментарий, и это не
     * любезность. Пока в поток ничего не записано, ответ не отправлен
     * вовсе: у клиента нет ни кода, ни заголовков, и он висит до первой
     * новости — а её может не быть часами. Промежуточные прокси такое
     * соединение обрывают молча.
     */
    public SseEmitter open(String userId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        SseFeedListener listener = new SseFeedListener(emitter);
        subscribe(userId, listener);

        try {
            emitter.send(SseEmitter.event().comment("поток открыт"));
        } catch (IOException | IllegalStateException gone) {
            // Клиент ушёл, не дождавшись даже приветствия.
            unsubscribe(userId, listener);
        }

        // Все три исхода снимают соединение со списка. Не снять хотя бы
        // один — значит копить мёртвые соединения, пока их не станет
        // больше, чем живых.
        emitter.onCompletion(() -> unsubscribe(userId, listener));
        emitter.onTimeout(() -> unsubscribe(userId, listener));
        emitter.onError(e -> unsubscribe(userId, listener));
        return emitter;
    }

    /**
     * Подписать слушателя, доставляющего своим способом.
     *
     * <p>Открыто для носителей, у которых нет {@code SseEmitter}, — то
     * есть для всех, кроме браузера. Отписывать обязан тот же, кто
     * подписал: рассылка узнаёт о смерти соединения только попыткой
     * записи, а сокет, закрытый своей стороной, скажет об этом раньше и
     * точнее.
     */
    public void subscribe(String userId, FeedListener listener) {
        listeners.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    public void unsubscribe(String userId, FeedListener listener) {
        List<FeedListener> forUser = listeners.get(userId);
        if (forUser == null) {
            return;
        }
        forUser.remove(listener);
        // Пустой список тоже мусор: пользователей много, а смотрят
        // единицы.
        if (forUser.isEmpty()) {
            listeners.remove(userId, forUser);
        }
    }

    /** Сколько сейчас открытых соединений. Метрика, а не логика. */
    public int openCount() {
        int n = 0;
        for (List<FeedListener> forUser : listeners.values()) {
            n += forUser.size();
        }
        return n;
    }

    /**
     * Доставка через SSE.
     *
     * <p>Особенность носителя остаётся здесь: закрытый {@code SseEmitter}
     * бросает {@code IllegalStateException}, а не {@code IOException}, и
     * рассылка не должна об этом знать.
     */
    private static final class SseFeedListener implements FeedListener {
        private final SseEmitter emitter;

        SseFeedListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void deliver(OutboxRecord record) throws IOException {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(record.getId()))
                        .name(record.getType())
                        .data(record.getPayload()));
            } catch (IllegalStateException closed) {
                throw new IOException("поток закрыт", closed);
            }
        }

        @Override
        public void beat() throws IOException {
            try {
                emitter.send(SseEmitter.event().comment("тук"));
            } catch (IllegalStateException closed) {
                throw new IOException("поток закрыт", closed);
            }
        }
    }

    @Override
    public void onPublished(List<OutboxRecord> records) {
        if (listeners.isEmpty()) {
            return;
        }
        for (OutboxRecord record : records) {
            if (!POST_CREATED.equals(record.getType())) {
                continue;
            }
            try {
                for (String owner : ownersOf(record.getAggregateId())) {
                    send(owner, record);
                }
            } catch (SQLException e) {
                // Рассылка не удалась — событие всё равно записано, и
                // клиент увидит его, перечитав ленту. Молчать нельзя,
                // прерывать дренаж — тем более.
                log.warn("не удалось узнать получателей события {}: {}",
                        record.getId(), e.toString());
            }
        }
    }

    /** Чьи ленты получили этот пост. Ответ даёт та же проекция. */
    private Set<String> ownersOf(String postId) throws SQLException {
        Set<String> owners = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT owner_id FROM feed_entries WHERE post_id = ?")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(rs.getString(1));
                }
            }
        }
        return owners;
    }

    private void send(String userId, OutboxRecord record) {
        List<FeedListener> forUser = listeners.get(userId);
        if (forUser == null || forUser.isEmpty()) {
            return;
        }
        List<FeedListener> dead = new ArrayList<>();
        for (FeedListener listener : forUser) {
            try {
                listener.deliver(record);
            } catch (IOException gone) {
                // Клиент ушёл. Это не отказ системы, а обычная жизнь
                // открытого соединения.
                dead.add(listener);
            }
        }
        for (FeedListener listener : dead) {
            unsubscribe(userId, listener);
        }
    }

    /**
     * Удар сердца во все открытые соединения.
     *
     * <p>Простаивающее соединение обрывают промежуточные прокси, и обрыв
     * этот молчаливый: клиент считает, что подписан, а событий больше не
     * будет никогда. Комментарий раз в двадцать секунд стоит нескольких
     * байт и делает разрыв заметным обеим сторонам.
     *
     * <p>Заодно вычищает соединения, которые уже мертвы: узнать об этом
     * можно только попыткой записи.
     */
    @Scheduled(fixedDelayString = "${sonder.events.heartbeat-ms:20000}")
    public void heartbeat() {
        for (Map.Entry<String, List<FeedListener>> entry : listeners.entrySet()) {
            for (FeedListener listener : entry.getValue()) {
                try {
                    listener.beat();
                } catch (IOException gone) {
                    unsubscribe(entry.getKey(), listener);
                }
            }
        }
    }
}
