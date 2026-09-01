package sonder.shell.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sonder.contract.ErrorCode;
import sonder.shell.auth.Passwords;
import sonder.shell.auth.SessionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
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

    public AuthController(DataSource dataSource) {
        this.dataSource = dataSource;
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

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request)
            throws SQLException {
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
                return error(HttpStatus.UNAUTHORIZED, ErrorCode.CREDENTIALS_INVALID);
            }

            String token = SessionStore.open(c, userId, Instant.now());
            return ResponseEntity.ok(Collections.singletonMap("token", token));
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String auth)
            throws SQLException {
        String token = bearer(auth);
        try (Connection c = dataSource.getConnection()) {
            SessionStore.revoke(c, token);
        }
        // Выход идемпотентен: отсутствие сессии — не ошибка. Сообщать «её и
        // не было» значило бы подтверждать угаданный токен.
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, String>> me(
            @RequestHeader(value = "Authorization", required = false) String auth)
            throws SQLException {
        String token = bearer(auth);
        try (Connection c = dataSource.getConnection()) {
            String userId = SessionStore.userOf(c, token, Instant.now());
            if (userId == null) {
                return error(HttpStatus.UNAUTHORIZED, ErrorCode.SESSION_INVALID);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT nick, display_name, role FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // Сессия есть, пользователя нет: он удалён, пока
                        // сессия жила. Для клиента это то же самое.
                        return error(HttpStatus.UNAUTHORIZED, ErrorCode.SESSION_INVALID);
                    }
                    Map<String, String> body = new java.util.LinkedHashMap<>();
                    body.put("userId", userId);
                    body.put("nick", rs.getString(1));
                    body.put("displayName", rs.getString(2));
                    body.put("role", rs.getString(3));
                    return ResponseEntity.ok(body);
                }
            }
        }
    }

    /** Токен из заголовка. Схема Bearer, без неё — ничего. */
    static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!header.startsWith(prefix)) {
            return null;
        }
        String token = header.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Ответ с кодом отказа. Статус берётся ИЗ КОНТРАКТА, а не пишется
     * рядом с каждым отказом: два источника разошлись бы, и клиент получил
     * бы 401 там, где ему обещали 409.
     */
    private static <T> ResponseEntity<T> error(HttpStatus expected, ErrorCode code) {
        HttpStatus fromContract = HttpStatus.valueOf(code.httpStatus());
        if (fromContract != expected) {
            throw new IllegalStateException(
                    "статус кода " + code + " в контракте " + fromContract
                            + ", а здесь ожидался " + expected);
        }
        return ResponseEntity.status(fromContract)
                .header("X-Sonder-Error", code.name())
                .build();
    }
}
