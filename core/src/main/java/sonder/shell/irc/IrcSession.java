package sonder.shell.irc;

import java.util.ArrayList;
import java.util.List;

/**
 * Разговор с одним клиентом IRC: что он прислал и что ему ответить.
 *
 * <p><b>Без сокетов и без базы.</b> Здесь только состояние разговора и
 * правила протокола; кто на самом деле проверяет пароль, приходит
 * снаружи через {@link Authenticator}. Так рукопожатие проверяется
 * тестом за миллисекунды и без поднятой системы — а без этого
 * единственным способом проверить порядок команд был бы живой клиент и
 * пара глаз.
 *
 * <p><b>Порядок команд в IRC не задан жёстко.</b> RFC 1459 §4.1 говорит,
 * что регистрация завершается, когда получены NICK и USER; PASS, если
 * он вообще будет, обязан прийти ДО них. Клиенты этим пользуются
 * по-разному: одни шлют PASS, NICK, USER, другие NICK, USER, третьи
 * добавляют CAP LS и ждут ответа. Поэтому здесь не последовательность, а
 * накопление: команды принимаются в любом порядке, а регистрация
 * случается в тот момент, когда собрано всё нужное.
 *
 * <p><b>Пароль обязателен,</b> хотя протокол считает PASS необязательным.
 * Причина не в протоколе: у ленты нет публичного чтения — по контракту
 * закрыто всё, кроме входа, выхода и регистрации. Пускать без пароля
 * значило бы завести публичное чтение молча, одним новым портом.
 *
 * <p><b>CAP отвечается отказом, а не молчанием.</b> Современный клиент
 * шлёт {@code CAP LS} и ЖДЁТ ответа, прежде чем слать NICK; сервер,
 * промолчавший на CAP, выглядит зависшим — соединение открыто, клиент
 * ждёт вечно, в журнале ничего.
 */
public final class IrcSession {

    /** Имя сервера в числовых ответах. */
    public static final String SERVER = "sonder";

    /** Кто проверяет имя и пароль. Реализация ходит в базу, тест — нет. */
    public interface Authenticator {
        /** Исход попытки входа. */
        enum Verdict { OK, INVALID, RATE_EXCEEDED, UNAVAILABLE }

        /** Результат: приговор и, если повезло, кому и с чем. */
        final class Result {
            private final Verdict verdict;
            private final String userId;
            private final String token;

            public Result(Verdict verdict, String userId, String token) {
                this.verdict = verdict;
                this.userId = userId;
                this.token = token;
            }

            public Verdict getVerdict() {
                return verdict;
            }

            public String getUserId() {
                return userId;
            }

            public String getToken() {
                return token;
            }
        }

        Result authenticate(String nick, String password);
    }

    /** Что отправить клиенту и закрывать ли соединение после отправки. */
    public static final class Reply {
        private final List<String> lines;
        private final boolean close;

        Reply(List<String> lines, boolean close) {
            this.lines = lines;
            this.close = close;
        }

        public List<String> getLines() {
            return lines;
        }

        public boolean isClose() {
            return close;
        }
    }

    private final Authenticator authenticator;

    private String nick;
    private String password;
    private boolean userSent;
    private boolean registered;
    private String userId;
    private String token;

    public IrcSession(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** Идентификатор вошедшего или {@code null}, пока не вошёл. */
    public String getUserId() {
        return userId;
    }

    /** Токен сессии; его же снимает выход. */
    public String getToken() {
        return token;
    }

    public String getNick() {
        return nick;
    }

    /** Обработать одну разобранную строку. */
    public Reply feed(IrcLine line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return new Reply(out, false);
        }

        switch (line.getCommand()) {
            case "CAP":
                // Возможностей у нас нет ни одной, и это законный ответ.
                // Важно ответить хоть что-то: клиент ждёт.
                out.add(":" + SERVER + " CAP * LS :");
                return new Reply(out, false);

            case "PASS":
                if (registered) {
                    out.add(numeric(462, ":Регистрация уже завершена"));
                    return new Reply(out, false);
                }
                if (line.param(0) == null) {
                    out.add(numeric(461, "PASS :Не хватает параметров"));
                    return new Reply(out, false);
                }
                password = line.param(0);
                return maybeRegister(out);

            case "NICK":
                if (line.param(0) == null || line.param(0).isEmpty()) {
                    out.add(numeric(431, ":Не указано имя"));
                    return new Reply(out, false);
                }
                if (registered) {
                    // Смена имени после входа означала бы смену
                    // пользователя: имя здесь — учётная запись, а не
                    // подпись под сообщением.
                    out.add(numeric(484, ":Имя менять нельзя: оно и есть учётная запись"));
                    return new Reply(out, false);
                }
                nick = line.param(0);
                return maybeRegister(out);

            case "USER":
                if (registered) {
                    out.add(numeric(462, ":Регистрация уже завершена"));
                    return new Reply(out, false);
                }
                if (line.getParams().size() < 4) {
                    out.add(numeric(461, "USER :Не хватает параметров"));
                    return new Reply(out, false);
                }
                userSent = true;
                return maybeRegister(out);

            case "PING":
                // Отвечать обязательно и до регистрации тоже: клиент
                // проверяет живость соединения, а не права.
                out.add(":" + SERVER + " PONG " + SERVER
                        + (line.param(0) == null ? "" : " :" + line.param(0)));
                return new Reply(out, false);

            case "PONG":
                return new Reply(out, false);

            case "QUIT":
                out.add("ERROR :До свидания");
                return new Reply(out, true);

            default:
                if (!registered) {
                    out.add(numeric(451, ":Сначала регистрация"));
                    return new Reply(out, false);
                }
                out.add(numeric(421, line.getCommand() + " :Неизвестная команда"));
                return new Reply(out, false);
        }
    }

    /**
     * Регистрация случается, когда собрано всё нужное: имя, пароль и
     * USER. Раньше — молчание, а не отказ: клиент ещё досылает.
     */
    private Reply maybeRegister(List<String> out) {
        if (registered || nick == null || !userSent) {
            return new Reply(out, false);
        }
        if (password == null) {
            // Без PASS дальше идти некуда, и молчать тут нельзя: клиент
            // считает, что он представился, и будет ждать приветствия.
            out.add(numeric(464, ":Нужен пароль: команда PASS до NICK"));
            out.add("ERROR :Вход без пароля здесь не предусмотрен");
            return new Reply(out, true);
        }

        Authenticator.Result result = authenticator.authenticate(nick, password);
        switch (result.getVerdict()) {
            case OK:
                registered = true;
                userId = result.getUserId();
                token = result.getToken();
                welcome(out);
                return new Reply(out, false);

            case RATE_EXCEEDED:
                // Предел тот же, что у входа по HTTP: он на нике, а не на
                // транспорте. Иначе новый порт стал бы обходом предела.
                out.add(numeric(464, ":Слишком много попыток, подождите"));
                out.add("ERROR :Слишком много попыток входа");
                return new Reply(out, true);

            case UNAVAILABLE:
                out.add("ERROR :Хранилище недоступно, попробуйте позже");
                return new Reply(out, true);

            default:
                out.add(numeric(464, ":Имя или пароль не подошли"));
                out.add("ERROR :Вход не выполнен");
                return new Reply(out, true);
        }
    }

    private void welcome(List<String> out) {
        out.add(numeric(1, ":Добро пожаловать в Sonder, " + nick));
        out.add(numeric(2, ":Оболочка на Java, решения принимает NODE-7 под DOS"));
        out.add(numeric(3, ":Шлюз IRC — второй канал того же конвейера событий"));
        out.add(numeric(4, SERVER + " sonder-1 - -"));
        out.add(numeric(375, ":- " + SERVER + " -"));
        out.add(numeric(372, ":- Лента приходит сюда же, куда и в браузер."));
        out.add(numeric(376, ":Конец сообщения дня"));
    }

    private String numeric(int code, String rest) {
        return String.format(":%s %03d %s %s", SERVER, code,
                nick == null ? "*" : nick, rest);
    }
}
