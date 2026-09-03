package sonder.shell.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Сессии: выдача, проверка, отзыв.
 *
 * <p>Аутентификация целиком в оболочке, и это не разделение обязанностей
 * по вкусу: она требует обращения к хранилищу и к часам, а ядро под DOS не
 * может ни того ни другого (ADR-0011). Контракт помечает
 * {@code SESSION_INVALID} и {@code CREDENTIALS_INVALID} как решаемые
 * оболочкой именно поэтому.
 *
 * <p><b>Срок жизни сессии не в контракте, и это намеренно.</b> Границы в
 * {@code limits.yaml} — то, что применяет ядро при решении. Срок сессии
 * ядро не видит и видеть не может; это операционная политика оболочки, и
 * положить её в доменный контракт значило бы сделать вид, что домен о ней
 * что-то знает.
 *
 * <p>Истёкшая сессия НЕ УДАЛЯЕТСЯ при проверке. Удаление на чтении
 * превращает безобидный запрос в запись, а под нагрузкой — в запись на
 * каждый запрос. Уборка — отдельная операция.
 *
 * <p><b>В БАЗЕ ЛЕЖИТ ОТПЕЧАТОК, А НЕ ТОКЕН.</b> Раньше лежал сам токен и
 * служил первичным ключом: утечка дампа означала угон всех живых сессий
 * — без подбора, без следов и без возможности заметить. Теперь хранится
 * SHA-256; предъявленный токен хэшируется и ищется по отпечатку.
 *
 * <p>SHA-256, а не bcrypt, и разница принципиальная. Пароль человек
 * выбирает сам, он угадываем, и потому хэшируется нарочито медленно.
 * Токен выдаёт {@link Tokens SecureRandom}, в нём 256 бит энтропии, и
 * перебирать его некому: быстрый хэш достаточен. Медленный стоил бы
 * сотни миллисекунд на КАЖДЫЙ запрос — сессия проверяется всегда.
 */
public final class SessionStore {

    /** Сколько живёт сессия. Операционная политика, не доменное правило. */
    public static final Duration TTL = Duration.ofHours(12);

    private SessionStore() {
    }

    /** Выдать сессию. Токен возвращается вызывающему и больше нигде не
     *  появляется: ни в базе, ни в логе. */
    public static String open(Connection c, String userId, Instant now)
            throws SQLException {
        String token = Tokens.next();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sessions (token_hash, user_id, created_at,"
                        + " expires_at, version) VALUES (?, ?, ?, ?, 0)")) {
            ps.setString(1, fingerprint(token));
            ps.setString(2, userId);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setTimestamp(4, Timestamp.from(now.plus(TTL)));
            ps.executeUpdate();
        }
        return token;
    }

    /**
     * Чей это токен, если он ещё действует.
     *
     * <p>Возвращает {@code null}, когда сессии нет ИЛИ она истекла: снаружи
     * эти случаи неразличимы намеренно. Отвечать по-разному значило бы
     * сообщать, существовал ли токен, — то есть подтверждать угаданный.
     */
    public static String userOf(Connection c, String token, Instant now)
            throws SQLException {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM sessions"
                        + " WHERE token_hash = ? AND expires_at > ?")) {
            ps.setString(1, fingerprint(token));
            ps.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Отозвать сессию: выход из системы. */
    public static boolean revoke(Connection c, String token) throws SQLException {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sessions WHERE token_hash = ?")) {
            ps.setString(1, fingerprint(token));
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Отпечаток токена: то, что попадает в базу.
     *
     * <p>Шестнадцатеричная запись, а не base64: колонка объявлена
     * {@code CHARACTER SET ASCII}, а шестьдесят четыре знака hex ложатся
     * в её ширину ровно и без вопросов о регистре и о символах,
     * требующих экранирования.
     *
     * <p>Соли нет намеренно. Соль защищает от радужных таблиц, то есть
     * от заранее посчитанного перебора; перебирать 256 бит случайности
     * нечем и незачем, а соль потребовала бы читать строку прежде, чем
     * искать её, — то есть искать по чему-то другому.
     */
    static String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен для всякой реализации Java. Отсутствие
            // означает сломанную среду, а не случай, который стоит
            // обрабатывать.
            throw new IllegalStateException("в этой Java нет SHA-256", e);
        }
    }

    /** Убрать истёкшее. Отдельная операция, а не побочный эффект чтения. */
    public static int purgeExpired(Connection c, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sessions WHERE expires_at <= ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            return ps.executeUpdate();
        }
    }
}
