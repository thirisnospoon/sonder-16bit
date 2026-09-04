package sonder.shell.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Вход: проверка имени и пароля, предел попыток, выдача сессии.
 *
 * <p><b>Зачем отдельно от HTTP.</b> Вход нужен не одному транспорту.
 * Кроме REST его спрашивает шлюз IRC — протокол 1988 года со своей
 * аутентификацией, — и написать проверку пароля там заново значило бы
 * завести вторую, которая молча разойдётся с этой.
 *
 * <p>Разошлась бы она не в мелочах. В этих сорока строках спрятаны три
 * свойства, каждое из которых куплено отдельной проверкой и ни одно из
 * которых не видно по сигнатуре:
 *
 * <ol>
 *   <li><b>Предел спрашивается ДО проверки пароля.</b> Спроси его
 *       после — он не мешал бы подбору, а лишь сообщал о нём, когда
 *       очередная попытка уже состоялась.</li>
 *   <li><b>Пароль сверяется ВСЕГДА,</b> даже когда пользователя нет:
 *       иначе время ответа выдаёт существование ника. Разница была
 *       измерена и составляла 272 мс против 40 —
 *       {@code ./sonder timing}.</li>
 *   <li><b>Неудача записывается по ЛЮБОМУ нику,</b> существующему или
 *       нет. Считать только существующие значило бы завести оракул
 *       перебора: несуществующий ник отвечал бы вечно, существующий
 *       упирался бы в предел.</li>
 * </ol>
 *
 * <p>Транспорт получает исход и переводит его на свой язык: REST — в код
 * ответа и куку, IRC — в числовой ответ протокола. Ни тому, ни другому
 * не нужно знать, почему проверка идёт именно в этом порядке.
 *
 * <p><b>Решения тут нет, и ядра тут нет.</b> Вход — не доменное правило,
 * а сверка предъявленного с хранимым; ядро на Паскале решает, можно ли
 * создать пост, а не кто его создаёт (ADR-0011).
 */
public final class Login {

    /**
     * Хэш для несуществующего пользователя.
     *
     * <p>Считается один раз при загрузке класса, а сверяется при каждом
     * входе с неизвестным ником — чтобы работа была одинаковой в обоих
     * случаях. Строка внутри значения не имеет: важно лишь, что это
     * настоящий хэш той же стоимости.
     */
    private static final String DUMMY_HASH = Passwords.hash("нет такого пользователя");

    private static final String BY_NICK =
            "SELECT id, password_hash FROM users"
                    + " WHERE LOWER(nick) = LOWER(?) AND status = 'ACTIVE'";

    /** Чем кончилась попытка входа. */
    public enum Result {
        /** Имя и пароль сошлись, сессия открыта. */
        OK,
        /** Слишком много попыток за окно; пароль даже не сверялся. */
        RATE_EXCEEDED,
        /** Имя или пароль не подошли. Какое именно — не сообщается. */
        INVALID
    }

    /** Исход попытки: что случилось и, если повезло, кому и с чем. */
    public static final class Outcome {
        private final Result result;
        private final String userId;
        private final String token;

        private Outcome(Result result, String userId, String token) {
            this.result = result;
            this.userId = userId;
            this.token = token;
        }

        public Result getResult() {
            return result;
        }

        /** Идентификатор вошедшего; {@code null} при неудаче. */
        public String getUserId() {
            return userId;
        }

        /** Токен новой сессии; {@code null} при неудаче. */
        public String getToken() {
            return token;
        }

        public boolean isOk() {
            return result == Result.OK;
        }
    }

    private Login() {
    }

    /**
     * Попытка входа.
     *
     * <p>Соединение приходит снаружи: у REST оно берётся из пула на время
     * запроса, у шлюза IRC — на время рукопожатия. Открывать своё здесь
     * значило бы решать за вызывающего, сколько их держать.
     *
     * @param c        соединение с базой
     * @param nick     предъявленное имя; {@code null} допустим и ведёт к
     *                 той же работе, что и неизвестное имя
     * @param password предъявленный пароль; {@code null} допустим
     * @param now      момент попытки; передаётся, а не берётся здесь,
     *                 чтобы проверки могли двигать время
     */
    public static Outcome attempt(Connection c, String nick, String password,
                                  Instant now) throws SQLException {
        if (LoginAttempts.tooMany(c, nick, now)) {
            return new Outcome(Result.RATE_EXCEEDED, null, null);
        }

        String userId = null;
        String hash = DUMMY_HASH;

        if (nick != null) {
            try (PreparedStatement ps = c.prepareStatement(BY_NICK)) {
                ps.setString(1, nick);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getString(1);
                        hash = rs.getString(2);
                    }
                }
            }
        }

        boolean ok = Passwords.matches(password, hash) && userId != null;
        if (!ok) {
            LoginAttempts.record(c, nick, now);
            return new Outcome(Result.INVALID, null, null);
        }

        // Предел защищает от подбора, а не наказывает за опечатку:
        // вспомнивший пароль не должен ждать окончания окна.
        LoginAttempts.clear(c, nick);
        String token = SessionStore.open(c, userId, now);
        return new Outcome(Result.OK, userId, token);
    }
}
