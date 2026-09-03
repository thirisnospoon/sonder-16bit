package sonder.shell.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Счёт неудачных попыток входа.
 *
 * <p>ДО ЭТОГО КЛАССА ПОДБОР ПАРОЛЯ НЕ БЫЛ ОГРАНИЧЕН НИЧЕМ. Контракт
 * объявляет код {@code LOGIN_RATE_EXCEEDED} с пометкой
 * {@code decided_by: shell}, ARCHITECTURE.md §14 обещает ограничение —
 * и ни одна строка кода его не выдавала. Измерено: двенадцать неверных
 * паролей подряд проходили без единого отказа.
 *
 * <p>СЧИТАЕТ ОБОЛОЧКА, и это не небрежность по отношению к
 * {@link sonder.contract.decider.Decider ядру}. Счёт требует хранилища и
 * часов, а у ядра нет ни того, ни другого (ADR-0011) — ровно как со
 * счётчиком постов за час. Разница в том, что предел постов объявлен
 * доменом и решается ядром, а предел попыток входа операционный: он
 * стоит рядом со сроком жизни сессии и периодом дренажа, а не рядом с
 * правилами домена.
 *
 * <p>ПОПЫТКА ПИШЕТСЯ ПО ЛЮБОМУ НИКУ, существующему или нет. Считать
 * только существующие значило бы завести оракул перебора: несуществующий
 * ник отвечал бы вечно, существующий упирался бы в предел, и разница
 * выдавала бы, кто в системе есть.
 *
 * <p>НИК ПРИВОДИТСЯ К НИЖНЕМУ РЕГИСТРУ. Вход нечувствителен к регистру,
 * и счёт обязан быть таким же — иначе предел обходится сменой регистра,
 * что даёт подбирающему столько попыток, сколько букв в нике.
 *
 * <p>Успешный вход стирает счёт: предел защищает от подбора, а не
 * наказывает за опечатку. Забывший пароль и вспомнивший его не должен
 * ждать окончания окна.
 */
public final class LoginAttempts {

    private LoginAttempts() {
    }

    /** Сколько неудач подряд допустимо в пределах окна. */
    public static final int LIMIT = 10;

    /**
     * Окно счёта.
     *
     * <p>Пятнадцать минут — не догадка, а расчёт: десять попыток за
     * четверть часа дают сорок в час, и перебор даже тысячи самых
     * частых паролей растянулся бы на сутки. Час был бы неудобен
     * человеку, ошибшемуся раскладкой; минута не мешала бы подбору
     * вовсе.
     */
    public static final Duration WINDOW = Duration.ofMinutes(15);

    /** Ключ счёта: тот же ник, что и при входе, только в нижнем регистре. */
    private static String key(String nick) {
        return nick == null ? "" : nick.toLowerCase(Locale.ROOT);
    }

    /**
     * Слишком много неудач за окно.
     *
     * <p>Спрашивается ДО проверки пароля: иначе предел не мешал бы
     * подбору, а лишь сообщал о нём после.
     */
    public static boolean tooMany(Connection c, String nick, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM login_attempts"
                        + " WHERE nick = ? AND attempted_at >= ?")) {
            ps.setString(1, key(nick));
            ps.setTimestamp(2, Timestamp.from(now.minus(WINDOW)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) >= LIMIT;
            }
        }
    }

    /** Записать неудачу. */
    public static void record(Connection c, String nick, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO login_attempts (nick, attempted_at) VALUES (?, ?)")) {
            ps.setString(1, key(nick));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.executeUpdate();
        }
        // Подрезка ЗДЕСЬ, а не отдельным расписанием: строки появляются
        // только на неудачах, их немного, и заводить ради них
        // планировщик значило бы завести вторую вещь, которая может не
        // работать. Чистится всё старее окна, а не только свой ник:
        // проход по индексу дешевле, чем накопление чужого мусора.
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM login_attempts WHERE attempted_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(now.minus(WINDOW)));
            ps.executeUpdate();
        }
    }

    /**
     * Забыть неудачи после успешного входа.
     *
     * <p>Предел защищает от подбора, а не наказывает за опечатку.
     */
    public static void clear(Connection c, String nick) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM login_attempts WHERE nick = ?")) {
            ps.setString(1, key(nick));
            ps.executeUpdate();
        }
    }
}
