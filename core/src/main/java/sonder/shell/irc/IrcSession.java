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

    /**
     * Единственный канал: лента.
     *
     * <p>Канал здесь не комната для разговора, а имя потока: написанное
     * в него становится ПОСТОМ, а не сообщением соседям. Заводить второй
     * значило бы обещать то, чего у продукта нет.
     */
    public static final String CHANNEL = "#feed";

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

    /**
     * Кто на самом деле создаёт пост.
     *
     * <p>Реализация идёт тем же путём, что и REST: загрузка состояния,
     * решение НА ЯДРЕ, сохранение с оптимистической блокировкой и запись
     * в outbox одной транзакцией. Здесь — только интерфейс, чтобы
     * разговор проверялся без базы и без линии к ноде.
     */
    public interface Poster {
        /** Исход команды: принято или отказ с кодом из контракта. */
        final class Result {
            private final boolean accepted;
            private final String errorCode;

            public Result(boolean accepted, String errorCode) {
                this.accepted = accepted;
                this.errorCode = errorCode;
            }

            public boolean isAccepted() {
                return accepted;
            }

            public String getErrorCode() {
                return errorCode;
            }
        }

        Result post(String userId, String text);
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
    private final Poster poster;

    private String nick;
    private String password;
    private boolean userSent;
    private boolean registered;
    private String userId;
    private String token;

    public IrcSession(Authenticator authenticator, Poster poster) {
        this.authenticator = authenticator;
        this.poster = poster;
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

            case "JOIN":
                if (!registered) {
                    out.add(numeric(451, ":Сначала регистрация"));
                    return new Reply(out, false);
                }
                if (!CHANNEL.equalsIgnoreCase(line.param(0))) {
                    out.add(numeric(403, line.param(0) + " :Здесь один канал: " + CHANNEL));
                    return new Reply(out, false);
                }
                join(out);
                return new Reply(out, false);

            case "PRIVMSG":
                return privmsg(line, out);

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

    /**
     * Написанное в канал становится ПОСТОМ.
     *
     * <p><b>Успех не подтверждается ничем,</b> и это не небрежность:
     * клиент IRC показывает отправленное сам, а сервер своё же сообщение
     * обратно не шлёт. Подтверждение выглядело бы вторым сообщением
     * рядом с первым.
     *
     * <p><b>Отказ приходит NOTICE, а не числовым ответом.</b> Числовые
     * коды протокола описывают беды протокола — нет такого канала, нет
     * прав; отказ ядра к ним не сводится, и притворяться, что сводится,
     * значило бы врать клиенту точным кодом.
     */
    private Reply privmsg(IrcLine line, List<String> out) {
        if (!registered) {
            out.add(numeric(451, ":Сначала регистрация"));
            return new Reply(out, false);
        }
        String target = line.param(0);
        String text = line.param(1);

        if (target == null) {
            out.add(numeric(411, ":Не указано, кому"));
            return new Reply(out, false);
        }
        if (text == null || text.isEmpty()) {
            out.add(numeric(412, ":Пустое сообщение постом не станет"));
            return new Reply(out, false);
        }
        if (!CHANNEL.equalsIgnoreCase(target)) {
            // Личных сообщений у продукта нет вовсе. Молчаливое
            // проглатывание выглядело бы как доставка.
            out.add(numeric(401, target + " :Личных сообщений нет; пишите в " + CHANNEL));
            return new Reply(out, false);
        }

        Poster.Result result = poster.post(userId, text);
        if (!result.isAccepted()) {
            out.add(notice(refusal(result.getErrorCode())));
        }
        return new Reply(out, false);
    }

    /**
     * Отказ ядра по-русски.
     *
     * <p>Код называется рядом с объяснением: он же приходит в ответах
     * REST, и человек, видевший его там, узнает здесь то же самое.
     * Молчаливая замена кода на «что-то пошло не так» лишила бы
     * возможности сопоставить.
     */
    private static String refusal(String code) {
        String reason;
        if (code == null) {
            reason = "команда не принята";
        } else {
            switch (code) {
                case "POST_BODY_EMPTY":
                    reason = "пустой пост";
                    break;
                case "POST_BODY_TOO_LONG":
                    reason = "пост длиннее предела";
                    break;
                case "POST_RATE_EXCEEDED":
                    reason = "слишком часто, подождите";
                    break;
                case "DECIDER_UNAVAILABLE":
                    reason = "ядро не отвечает, пост не создан";
                    break;
                case "SESSION_INVALID":
                    reason = "сессия истекла, войдите заново";
                    break;
                default:
                    reason = "команда не принята";
                    break;
            }
        }
        return code == null ? reason : reason + " (" + code + ")";
    }

    private String notice(String text) {
        return ":" + SERVER + " NOTICE " + CHANNEL + " :" + text;
    }

    /**
     * Вход в канал.
     *
     * <p>Делается сразу после приветствия, без просьбы клиента: канал
     * здесь один, и заставлять набирать JOIN значило бы прятать
     * единственное, ради чего сюда приходят.
     */
    private void join(List<String> out) {
        out.add(":" + nick + "!" + nick + "@" + SERVER + " JOIN " + CHANNEL);
        out.add(numeric(332, CHANNEL + " :Лента. Написанное здесь становится постом"));
        out.add(numeric(353, "= " + CHANNEL + " :" + nick));
        out.add(numeric(366, CHANNEL + " :Конец списка имён"));
    }

    private void welcome(List<String> out) {
        out.add(numeric(1, ":Добро пожаловать в Sonder, " + nick));
        out.add(numeric(2, ":Оболочка на Java, решения принимает NODE-7 под DOS"));
        out.add(numeric(3, ":Шлюз IRC — второй канал того же конвейера событий"));
        out.add(numeric(4, SERVER + " sonder-1 - -"));
        out.add(numeric(375, ":- " + SERVER + " -"));
        out.add(numeric(372, ":- Написанное в " + CHANNEL + " становится постом."));
        out.add(numeric(372, ":- Решение принимает NODE-7, а не этот шлюз."));
        out.add(numeric(376, ":Конец сообщения дня"));
        join(out);
    }

    private String numeric(int code, String rest) {
        return String.format(":%s %03d %s %s", SERVER, code,
                nick == null ? "*" : nick, rest);
    }
}
