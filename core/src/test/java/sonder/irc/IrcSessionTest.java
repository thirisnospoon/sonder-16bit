package sonder.irc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.irc.IrcLine;
import sonder.shell.irc.IrcSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рукопожатие IRC.
 *
 * <p>Проверяется без сокетов и без базы — потому и проверяется вообще.
 * Единственным другим способом убедиться, что порядок команд разобран
 * верно, был бы живой клиент и пара глаз, а глазами уже проверяли
 * длину записей свода: вышло 6971 негодная запись из 6972.
 */
class IrcSessionTest {

    /** Заглушка входа: отвечает заранее решённым приговором и считает вызовы. */
    private static final class Stub implements IrcSession.Authenticator {
        private final Verdict verdict;
        private final List<String> asked = new ArrayList<>();

        Stub(Verdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public Result authenticate(String nick, String password) {
            asked.add(nick + "/" + password);
            return verdict == Verdict.OK
                    ? new Result(Verdict.OK, "u-1", "t-1")
                    : new Result(verdict, null, null);
        }
    }

    /** Заглушка ядра: запоминает написанное и отвечает заранее решённым. */
    private static final class Pen implements IrcSession.Poster {
        private final String errorCode;
        private final List<String> posted = new ArrayList<>();

        Pen() {
            this(null);
        }

        Pen(String errorCode) {
            this.errorCode = errorCode;
        }

        @Override
        public Result post(String userId, String text) {
            posted.add(userId + ": " + text);
            return new Result(errorCode == null, errorCode);
        }
    }

    private static IrcSession session(IrcSession.Authenticator.Verdict verdict) {
        return new IrcSession(new Stub(verdict), new Pen());
    }

    private static IrcSession entered(IrcSession.Poster poster) {
        IrcSession s = new IrcSession(
                new Stub(IrcSession.Authenticator.Verdict.OK), poster);
        s.feed(IrcLine.parse("PASS secret"));
        s.feed(IrcLine.parse("NICK petya"));
        s.feed(IrcLine.parse("USER petya 0 * :Пётр"));
        return s;
    }

    private static List<String> feed(IrcSession s, String raw) {
        return s.feed(IrcLine.parse(raw)).getLines();
    }

    private static boolean has(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("обычный порядок PASS, NICK, USER доводит до приветствия")
    void classicOrderRegisters() {
        Stub stub = new Stub(IrcSession.Authenticator.Verdict.OK);
        IrcSession s = new IrcSession(stub, new Pen());

        assertTrue(feed(s, "PASS secret").isEmpty(), "ответил раньше времени");
        assertTrue(feed(s, "NICK petya").isEmpty(), "ответил раньше времени");
        List<String> out = feed(s, "USER petya 0 * :Пётр");

        assertTrue(s.isRegistered(), "регистрация не завершилась");
        assertTrue(has(out, " 001 "), "нет приветствия 001");
        assertTrue(has(out, " 376 "), "нет конца сообщения дня");
        assertEquals("u-1", s.getUserId());
        assertEquals("t-1", s.getToken());
        assertEquals(1, stub.asked.size(), "вход спрошен не один раз");
        assertEquals("petya/secret", stub.asked.get(0));
    }

    /**
     * Порядок команд протоколом не задан, и клиенты им пользуются
     * по-разному. Накопление вместо последовательности — ровно затем.
     */
    @Test
    @DisplayName("порядок NICK, USER, PASS тоже доводит до приветствия")
    void anyOrderRegisters() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        feed(s, "NICK petya");
        feed(s, "USER petya 0 * :Пётр");
        assertFalse(s.isRegistered(), "вошёл без пароля");
    }

    /**
     * Без PASS вход невозможен: публичного чтения у ленты нет, и новый
     * порт не должен заводить его молча.
     */
    @Test
    @DisplayName("без пароля соединение закрывается, а не ждёт молча")
    void withoutPasswordConnectionCloses() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        feed(s, "NICK petya");
        IrcSession.Reply reply = s.feed(IrcLine.parse("USER petya 0 * :Пётр"));

        assertFalse(s.isRegistered());
        assertTrue(reply.isClose(), "соединение оставлено висеть");
        assertTrue(has(reply.getLines(), " 464 "), "не сказано, что нужен пароль");
    }

    @Test
    @DisplayName("неверный пароль закрывает соединение и не пускает")
    void wrongPasswordCloses() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.INVALID);
        feed(s, "PASS wrong");
        feed(s, "NICK petya");
        IrcSession.Reply reply = s.feed(IrcLine.parse("USER petya 0 * :Пётр"));

        assertFalse(s.isRegistered());
        assertTrue(reply.isClose());
        assertTrue(has(reply.getLines(), " 464 "));
    }

    /**
     * Предел попыток общий с входом по HTTP: он на нике, а не на
     * транспорте. Новый порт, не знающий о пределе, был бы обходом.
     */
    @Test
    @DisplayName("предел попыток доходит до клиента отдельным ответом")
    void rateLimitIsReported() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.RATE_EXCEEDED);
        feed(s, "PASS secret");
        feed(s, "NICK petya");
        IrcSession.Reply reply = s.feed(IrcLine.parse("USER petya 0 * :Пётр"));

        assertFalse(s.isRegistered());
        assertTrue(reply.isClose());
        assertTrue(has(reply.getLines(), "попыток"), "про предел не сказано");
    }

    /**
     * Клиент, приславший CAP, ЖДЁТ ответа и не шлёт NICK, пока его нет.
     * Молчание тут выглядит как зависший сервер: соединение открыто,
     * клиент ждёт вечно, в журнале ничего.
     */
    @Test
    @DisplayName("на CAP отвечаем, а не молчим")
    void capIsAnswered() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        List<String> out = feed(s, "CAP LS 302");
        assertFalse(out.isEmpty(), "на CAP промолчали — клиент будет ждать вечно");
        assertTrue(has(out, "CAP"));
    }

    @Test
    @DisplayName("PING отвечается до регистрации: это проверка связи, а не прав")
    void pingWorksBeforeRegistration() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        List<String> out = feed(s, "PING :12345");
        assertTrue(has(out, "PONG"), "на PING не ответили");
        assertTrue(has(out, "12345"), "не вернули то, что прислали");
    }

    @Test
    @DisplayName("до регистрации прочие команды получают 451")
    void otherCommandsNeedRegistration() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        assertTrue(has(feed(s, "JOIN #feed"), " 451 "));
    }

    @Test
    @DisplayName("QUIT закрывает соединение")
    void quitCloses() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        assertTrue(s.feed(IrcLine.parse("QUIT :пока")).isClose());
    }

    /**
     * Имя здесь — учётная запись, а не подпись под сообщением. Смена
     * имени после входа означала бы смену пользователя.
     */
    @Test
    @DisplayName("после входа имя сменить нельзя")
    void nickIsNotChangeableAfterLogin() {
        IrcSession s = session(IrcSession.Authenticator.Verdict.OK);
        feed(s, "PASS secret");
        feed(s, "NICK petya");
        feed(s, "USER petya 0 * :Пётр");
        assertTrue(s.isRegistered());

        List<String> out = feed(s, "NICK vasya");
        assertTrue(has(out, " 484 "), "смена имени прошла молча");
        assertEquals("petya", s.getNick(), "имя всё-таки сменилось");
    }

    /* ==================================================================
       Запись: написанное в канал становится постом
       ================================================================== */

    @Test
    @DisplayName("после входа клиент уже в канале, набирать JOIN не нужно")
    void channelIsJoinedOnWelcome() {
        Stub stub = new Stub(IrcSession.Authenticator.Verdict.OK);
        IrcSession s = new IrcSession(stub, new Pen());
        feed(s, "PASS secret");
        feed(s, "NICK petya");
        List<String> out = feed(s, "USER petya 0 * :Пётр");

        assertTrue(has(out, "JOIN #feed"), "в канал не вошли");
        assertTrue(has(out, " 366 "), "нет конца списка имён");
    }

    @Test
    @DisplayName("сообщение в канал уходит в ядро целиком, с пробелами")
    void messageBecomesPost() {
        Pen pen = new Pen();
        IrcSession s = entered(pen);

        List<String> out = feed(s, "PRIVMSG #feed :первый пост из ирки");

        assertEquals(1, pen.posted.size(), "пост не создан");
        assertEquals("u-1: первый пост из ирки", pen.posted.get(0));
        assertTrue(out.isEmpty(), "успех подтверждён лишним сообщением");
    }

    /**
     * Отказ ядра приходит NOTICE и называет код: тот же код человек
     * видит в ответах REST, и сопоставить одно с другим он сможет
     * только если код назван.
     */
    @Test
    @DisplayName("отказ ядра доходит до клиента и называет код")
    void refusalIsReported() {
        IrcSession s = entered(new Pen("POST_BODY_TOO_LONG"));
        List<String> out = feed(s, "PRIVMSG #feed :очень длинный текст");

        assertTrue(has(out, "NOTICE"), "отказ не доехал");
        assertTrue(has(out, "POST_BODY_TOO_LONG"), "код не назван");
        assertTrue(has(out, "длиннее"), "отказ не объяснён по-русски");
    }

    @Test
    @DisplayName("недоступное ядро объясняется, а не молчит")
    void unavailableCoreIsExplained() {
        IrcSession s = entered(new Pen("DECIDER_UNAVAILABLE"));
        assertTrue(has(feed(s, "PRIVMSG #feed :текст"), "ядро не отвечает"));
    }

    /**
     * Личных сообщений у продукта нет. Проглотить их молча значило бы
     * показать отправителю доставку, которой не было.
     */
    @Test
    @DisplayName("личное сообщение отвергается, а не проглатывается")
    void privateMessageIsRefused() {
        Pen pen = new Pen();
        IrcSession s = entered(pen);

        List<String> out = feed(s, "PRIVMSG vasya :привет");

        assertTrue(has(out, " 401 "), "не отказано");
        assertTrue(pen.posted.isEmpty(), "личное сообщение стало постом");
    }

    @Test
    @DisplayName("пустое сообщение постом не становится")
    void emptyMessageIsNotAPost() {
        Pen pen = new Pen();
        IrcSession s = entered(pen);

        assertTrue(has(feed(s, "PRIVMSG #feed :"), " 412 "));
        assertTrue(pen.posted.isEmpty(), "пустой пост ушёл в ядро");
    }

    @Test
    @DisplayName("чужой канал отвергается: здесь он один")
    void otherChannelsAreRefused() {
        IrcSession s = entered(new Pen());
        assertTrue(has(feed(s, "JOIN #курилка"), " 403 "));
    }

    @Test
    @DisplayName("писать в канал до входа нельзя")
    void cannotWriteBeforeLogin() {
        Pen pen = new Pen();
        IrcSession s = new IrcSession(
                new Stub(IrcSession.Authenticator.Verdict.OK), pen);

        assertTrue(has(feed(s, "PRIVMSG #feed :я тут"), " 451 "));
        assertTrue(pen.posted.isEmpty(), "пост создан без входа");
    }

    @Test
    @DisplayName("вход спрашивается один раз, а не на каждую команду")
    void authenticatorIsAskedOnce() {
        Stub stub = new Stub(IrcSession.Authenticator.Verdict.OK);
        IrcSession s = new IrcSession(stub, new Pen());
        feed(s, "PASS secret");
        feed(s, "NICK petya");
        feed(s, "USER petya 0 * :Пётр");
        feed(s, "PING :1");
        feed(s, "JOIN #feed");
        assertEquals(1, stub.asked.size());
    }
}
