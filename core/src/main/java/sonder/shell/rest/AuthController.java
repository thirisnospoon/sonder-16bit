package sonder.shell.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sonder.contract.ErrorCode;
import sonder.shell.auth.Passwords;
import sonder.shell.auth.SessionStore;

import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

/**
 * Вход, выход и «кто я».
 *
 * <p>Аутентификация целиком здесь: она требует хранилища и часов, а ядро
 * под DOS не может ни того ни другого. Коды {@code CREDENTIALS_INVALID} и
 * {@code SESSION_INVALID} помечены в контракте как решаемые оболочкой
 * именно поэтому, и ссылаться на них отсюда законно — ArchUnit проверяет,
 * что оболочка не трогает коды ЯДРА, а не что она молчит вообще.
 *
 * <p><b>Неизвестный ник и неверный пароль отвечают одинаково.</b> Разные
 * ответы превратили бы вход в перечислитель учётных записей: злоумышленник
 * узнавал бы, какие ники существуют, не зная ни одного пароля.
 *
 * <p>При неизвестном нике пароль всё равно проверяется — против
 * фиктивного хэша. Иначе ответ приходил бы заметно быстрее, и время
 * ответа сообщало бы то же самое, что и разные коды.
 */
@RestController
public class AuthController {

    /**
     * Хэш, против которого проверяется пароль несуществующего
     * пользователя. Нужен ровно затем, чтобы обе ветки стоили одинаково.
     */
    private static final String DUMMY_HASH = Passwords.hash("нет такого пользователя");

    private final DataSource dataSource;

    /**
     * Отдавать ли куку только по HTTPS.
     *
     * <p>По умолчанию да. Выключается на локальном подъёме по обычному
     * HTTP: браузер иначе просто выбросит куку, и вход не заработает
     * вовсе — причём молча, потому что запрос уйдёт без неё и вернётся
     * законным «сессия недействительна».
     *
     * <p>Настройкой, а не догадкой по схеме запроса: за прокси схема
     * приходит из заголовка, которому верить нельзя, и незаметно
     * выключенный {@code Secure} однажды уехал бы в бой.
     */
    private final boolean cookieSecure;

    /**
     * Сколько живёт кука.
     *
     * <p>Умолчание берётся у самой сессии, а не пишется числом рядом:
     * кука, живущая дольше сессии, шлётся браузером ещё сутки после
     * того, как сессия умерла, и пользователь получает «войдите заново»
     * там, где мог бы просто увидеть форму входа. Кука короче сессии —
     * тоже расхождение, только в другую сторону.
     */
    private final long cookieMaxAge;

    public AuthController(
            DataSource dataSource,
            @Value("${sonder.session.cookie-secure:true}") boolean cookieSecure,
            @Value("${sonder.session.max-age-seconds:#{null}}") Long cookieMaxAge) {
        this.dataSource = dataSource;
        this.cookieSecure = cookieSecure;
        this.cookieMaxAge =
                cookieMaxAge == null ? SessionStore.TTL.getSeconds() : cookieMaxAge;
    }

    /** Тело запроса на вход. */
    public static final class LoginRequest {
        private String nick;
        private String password;

        public String getNick() {
            return nick;
        }

        public void setNick(String nick) {
            this.nick = nick;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * Вход.
     *
     * <p>Отвечает ПУСТЫМ телом и кукой, как объявляет контракт. Токен в
     * теле клиенту пришлось бы где-то держать, а всякое такое место
     * читается сценарием, попавшим на страницу; кука с {@code HttpOnly}
     * не читается ничем. Оболочка отдавала токен телом до первого
     * настоящего подъёма системы — проверка маршрутов сверяет пути и
     * методы, а не то, чем оканчивается вход.
     */
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody(required = false) LoginRequest request,
            HttpServletResponse response)
            throws SQLException {
        String traceId = Trace.current();
        String nick = request == null ? null : request.getNick();
        String password = request == null ? null : request.getPassword();

        try (Connection c = dataSource.getConnection()) {
            String userId = null;
            String hash = DUMMY_HASH;

            if (nick != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, password_hash FROM users"
                                + " WHERE LOWER(nick) = LOWER(?) AND status = 'ACTIVE'")) {
                    ps.setString(1, nick);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getString(1);
                            hash = rs.getString(2);
                        }
                    }
                }
            }

            // Проверка идёт ВСЕГДА, даже когда пользователя нет: иначе
            // время ответа выдало бы существование ника.
            boolean ok = Passwords.matches(password, hash) && userId != null;
            if (!ok) {
                return RestErrors.of(ErrorCode.CREDENTIALS_INVALID, traceId);
            }

            String token = SessionStore.open(c, userId, Instant.now());
            SessionCookie.issue(response, token, cookieSecure, cookieMaxAge);
            return ResponseEntity.noContent().build();
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            HttpServletResponse response)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            SessionStore.revoke(c, token);
        }
        // Куку снимаем в любом случае: пользователь нажал «выйти», и
        // остаться с живой кукой он не должен, даже если сессии в базе
        // уже не было.
        SessionCookie.clear(response, cookieSecure);
        // Выход идемпотентен: отсутствие сессии — не ошибка. Сообщать «её и
        // не было» значило бы подтверждать угаданный токен.
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(
            @CookieValue(value = SessionCookie.NAME, required = false) String token)
            throws SQLException {
        String traceId = Trace.current();
        try (Connection c = dataSource.getConnection()) {
            String userId = SessionStore.userOf(c, token, Instant.now());
            if (userId == null) {
                return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT nick, display_name, role FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // Сессия есть, пользователя нет: он удалён, пока
                        // сессия жила. Для клиента это то же самое.
                        return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
                    }
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("userId", userId);
                    body.put("nick", rs.getString(1));
                    body.put("displayName", rs.getString(2));
                    body.put("role", rs.getString(3));
                    return ResponseEntity.ok(body);
                }
            }
        }
    }

}
