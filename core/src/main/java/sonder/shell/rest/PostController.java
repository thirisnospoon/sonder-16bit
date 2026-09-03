package sonder.shell.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RestController;
import sonder.contract.ErrorCode;
import sonder.contract.decider.Decider;
import sonder.shell.app.CommandFlow;
import sonder.shell.app.CreatePostHandler;
import sonder.shell.app.DeletePostHandler;
import sonder.shell.app.VersionConflict;
import sonder.shell.auth.SessionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Посты.
 *
 * <p>Здесь нет ни одного доменного правила. Может ли автор удалить пост,
 * не слишком ли длинное тело, не превышена ли частота — решает NODE-7.
 * Контроллер устанавливает личность по сессии, зовёт ход команды и
 * переводит решение в ответ HTTP.
 *
 * <p>Чтение поста ядра не касается: читать — не решать. Пока это
 * write-модель напрямую; проекции появятся в фазе 7.
 */
@RestController
public class PostController {

    private final DataSource dataSource;
    private final CommandFlow flow;
    private final Decider decider;

    public PostController(DataSource dataSource, Decider decider) {
        this.dataSource = dataSource;
        this.decider = decider;
        this.flow = new CommandFlow(dataSource::getConnection);
    }

    /** Тело запроса на создание поста. */
    public static final class CreateRequest {
        private String body;

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }

    @PostMapping("/posts")
    public ResponseEntity<Map<String, Object>> createPost(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @RequestBody(required = false) CreateRequest request)
            throws Exception {
        // Идентификатор трассировки заводится ДО первой возможной ошибки:
        // контракт требует его в теле любого отказа, в том числе отказа
        // по сессии.
        String traceId = newTraceId();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        CreatePostHandler handler = new CreatePostHandler(flow, decider);
        CreatePostHandler.Outcome outcome;
        try {
            outcome = handler.handle(actorId,
                    request == null ? null : request.getBody(),
                    traceId, traceId, Instant.now());
        } catch (VersionConflict conflict) {
            return RestErrors.of(ErrorCode.STATE_VERSION_CONFLICT, traceId);
        }

        if (!outcome.isAccepted()) {
            return RestErrors.of(outcome.getErrorCode(), traceId);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postId", outcome.getPostId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Map<String, Object>> deletePost(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @PathVariable String postId) throws Exception {
        String traceId = newTraceId();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        DeletePostHandler handler = new DeletePostHandler(flow, decider);
        DeletePostHandler.Outcome outcome;
        try {
            outcome = handler.handle(actorId, postId, traceId, traceId, Instant.now());
        } catch (VersionConflict conflict) {
            // Попытки исчерпаны. Отказ повторяем клиентом — так помечен
            // код в контракте.
            return RestErrors.of(ErrorCode.STATE_VERSION_CONFLICT, traceId);
        }

        if (!outcome.isAccepted()) {
            return RestErrors.of(outcome.getErrorCode(), traceId);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<Map<String, Object>> getPost(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @PathVariable String postId) throws SQLException {
        String traceId = newTraceId();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT p.author_id, u.nick, u.display_name, p.body,"
                             + " p.created_at FROM posts p"
                             + " JOIN users u ON u.id = p.author_id"
                             + " WHERE p.id = ? AND p.status = 'VISIBLE'")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Удалённый пост неотличим от несуществующего: иначе
                    // ответ сообщал бы, что такой пост когда-то был.
                    //
                    // Код здесь ОБОЛОЧЕЧНЫЙ, а не POST_NOT_FOUND ядра:
                    // читать — не решать, ядро в чтении не участвует, и
                    // одалживать его код значило бы связать поведение
                    // чтения с доменным решением. Гейт ArchUnit это и
                    // поймал.
                    return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, traceId);
                }
                Map<String, Object> author = new LinkedHashMap<>();
                author.put("userId", rs.getString(1));
                author.put("nick", rs.getString(2));
                author.put("displayName", rs.getString(3));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("postId", postId);
                body.put("author", author);
                body.put("body", rs.getString(4));
                body.put("createdAt", rs.getTimestamp(5).toInstant().toString());
                return ResponseEntity.ok(body);
            }
        }
    }

    private String actor(String token) throws SQLException {
        if (token == null) {
            return null;
        }
        try (Connection c = dataSource.getConnection()) {
            return SessionStore.userOf(c, token, Instant.now());
        }
    }

    /**
     * Идентификатор трассировки. Один на команду: он же уходит в
     * {@code meta.traceId} к ядру и в строку outbox, и по нему потом
     * сходятся лог оболочки, лог ядра и событие.
     */
    private static String newTraceId() {
        return "t-" + UUID.randomUUID().toString().replace("-", "");
    }
}
