package sonder.shell.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import sonder.contract.ErrorCode;
import sonder.shell.auth.SessionStore;
import sonder.shell.stream.FeedStream;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Поток обновлений.
 *
 * <p>Открывает соединение и держит его. Что в него слать, решает
 * {@link FeedStream}, а он получает события после коммита дренажа — так
 * контракт и требует: клиент не увидит события раньше, чем оно попадёт в
 * чтение.
 *
 * <p><b>Срок соединения конечен.</b> Вечное соединение переживает и
 * ушедшего клиента, и промежуточные прокси, которые всё равно оборвут его
 * молча — с той разницей, что мы об этом не узнаем. Конечный срок
 * означает предсказуемое переподключение вместо непредсказуемого обрыва.
 */
@RestController
public class EventsController {

    private final DataSource dataSource;
    private final FeedStream stream;
    private final long timeoutMillis;

    public EventsController(DataSource dataSource, FeedStream stream,
                            @Value("${sonder.events.timeout-ms:300000}")
                            long timeoutMillis) {
        this.dataSource = dataSource;
        this.stream = stream;
        this.timeoutMillis = timeoutMillis;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object subscribe(
            @RequestHeader(value = "Authorization", required = false) String auth)
            throws SQLException {

        String actorId = actor(auth);
        if (actorId == null) {
            // Отказ отдаётся обычным телом ошибки, а не потоком: клиент,
            // получивший поток, стал бы ждать в нём событий, которых
            // никогда не будет.
            String traceId = "t-" + UUID.randomUUID().toString().replace("-", "");
            ResponseEntity<?> error =
                    RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
            return error;
        }
        return stream.open(actorId, timeoutMillis);
    }

    private String actor(String auth) throws SQLException {
        String token = AuthController.bearer(auth);
        if (token == null) {
            return null;
        }
        try (Connection c = dataSource.getConnection()) {
            return SessionStore.userOf(c, token, Instant.now());
        }
    }
}
