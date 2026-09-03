package sonder.shell.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RestController;
import sonder.contract.ErrorCode;
import sonder.contract.decider.Decider;
import sonder.shell.app.CommandFlow;
import sonder.shell.app.UserHandlers;
import sonder.shell.app.VersionConflict;
import sonder.shell.auth.Passwords;
import sonder.shell.auth.SessionStore;
import sonder.shell.store.UserStore;

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
 * Пользователи: регистрация, чтение, подписка, блокировка.
 *
 * <p>Ни одного доменного правила. Форму ника, право модератора,
 * самоподписку решает NODE-7; контроллер устанавливает личность, переводит
 * ник в идентификатор и превращает решение в ответ HTTP.
 *
 * <p><b>Пароль дальше этого класса в открытом виде не идёт.</b> Он
 * хэшируется здесь же и уходит в хранилище хэшем; ядро о нём не знает
 * вовсе — контракт операции регистрации пароля не объявляет.
 */
@RestController
public class UserController {

    private final DataSource dataSource;
    private final CommandFlow flow;
    private final Decider decider;

    public UserController(DataSource dataSource, Decider decider) {
        this.dataSource = dataSource;
        this.decider = decider;
        this.flow = new CommandFlow(dataSource::getConnection);
    }

    /** Тело запроса на регистрацию. */
    public static final class RegisterRequest {
        private String nick;
        private String displayName;
        private String password;

        public String getNick() {
            return nick;
        }

        public void setNick(String nick) {
            this.nick = nick;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /** Тело запроса на блокировку. */
    public static final class BanRequest {
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody(required = false) RegisterRequest request) throws Exception {
        String traceId = newTraceId();
        if (request == null) {
            // Код ОБОЛОЧЕЧНЫЙ: команду не удалось собрать, а не она
            // неверна по смыслу. Доменным кодом здесь оболочка сказала бы
            // пользователю неправду о том, что он сделал не так.
            return RestErrors.of(ErrorCode.MALFORMED_REQUEST,
                    "нет тела запроса", traceId);
        }

        // Пустой пароль — не «слабый пароль», а его отсутствие: хэшировать
        // его нельзя. Это НЕ доменное правило, а свойство хранения
        // учётных данных, о которых ядро не знает.
        String password = request.getPassword();
        if (password == null || password.isEmpty()) {
            return RestErrors.of(ErrorCode.MALFORMED_REQUEST,
                    "пароль обязателен", traceId);
        }

        UserHandlers.Outcome outcome;
        try {
            outcome = new UserHandlers(flow, decider).register(
                    request.getNick(), request.getDisplayName(),
                    Passwords.hash(password), traceId, Instant.now());
        } catch (VersionConflict conflict) {
            return RestErrors.of(ErrorCode.STATE_VERSION_CONFLICT, traceId);
        }

        if (!outcome.isAccepted()) {
            return RestErrors.of(outcome.getErrorCode(), traceId);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", outcome.getSubjectId());
        body.put("nick", request.getNick());
        body.put("displayName", request.getDisplayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/users/{nick}")
    public ResponseEntity<Map<String, Object>> getUser(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @PathVariable String nick) throws SQLException {
        String traceId = newTraceId();
        if (actor(token) == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, nick, display_name FROM users"
                             + " WHERE LOWER(nick) = LOWER(?) AND status <> 'DELETED'")) {
            ps.setString(1, nick);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Код оболочечный: читать — не решать, ядро в чтении
                    // не участвует.
                    return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, traceId);
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("userId", rs.getString(1));
                body.put("nick", rs.getString(2));
                body.put("displayName", rs.getString(3));
                return ResponseEntity.ok(body);
            }
        }
    }

    @PutMapping("/users/{nick}/follow")
    public ResponseEntity<Map<String, Object>> follow(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @PathVariable String nick) throws Exception {
        String traceId = newTraceId();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        String targetId = idByNick(nick);
        if (targetId == null) {
            return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, traceId);
        }

        UserHandlers.Outcome outcome;
        try {
            outcome = new UserHandlers(flow, decider)
                    .follow(actorId, targetId, traceId, Instant.now());
        } catch (VersionConflict conflict) {
            return RestErrors.of(ErrorCode.STATE_VERSION_CONFLICT, traceId);
        }

        if (!outcome.isAccepted()) {
            return RestErrors.of(outcome.getErrorCode(), traceId);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{nick}/follow")
    public ResponseEntity<Map<String, Object>> unfollow(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @PathVariable String nick) throws Exception {
        String traceId = newTraceId();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        String targetId = idByNick(nick);
        if (targetId == null) {
            return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, traceId);
        }

        UserHandlers.Outcome outcome;
        try {
            outcome = new UserHandlers(flow, decider)
                    .unfollow(actorId, targetId, traceId, Instant.now());
        } catch (VersionConflict conflict) {
            return RestErrors.of(ErrorCode.STATE_VERSION_CONFLICT, traceId);
        }

        if (!outcome.isAccepted()) {
            return RestErrors.of(outcome.getErrorCode(), traceId);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/users/{nick}/ban")
    public ResponseEntity<Map<String, Object>> banUser(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @PathVariable String nick,
            @RequestBody(required = false) BanRequest request) throws Exception {
        String traceId = newTraceId();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        String targetId = idByNick(nick);
        if (targetId == null) {
            return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, traceId);
        }

        UserHandlers.Outcome outcome;
        try {
            outcome = new UserHandlers(flow, decider).ban(actorId, targetId,
                    request == null ? null : request.getReason(),
                    traceId, Instant.now());
        } catch (VersionConflict conflict) {
            return RestErrors.of(ErrorCode.STATE_VERSION_CONFLICT, traceId);
        }

        if (!outcome.isAccepted()) {
            return RestErrors.of(outcome.getErrorCode(), traceId);
        }
        return ResponseEntity.noContent().build();
    }

    private String idByNick(String nick) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return UserStore.idByNick(c, nick);
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

    private static String newTraceId() {
        return "t-" + UUID.randomUUID().toString().replace("-", "");
    }
}
