package sonder.shell.irc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Строка протокола IRC: разбор и сборка.
 *
 * <p>Формат из RFC 1459 §2.3.1 короче, чем кажется, и ровно поэтому в нём
 * ошибаются:
 *
 * <pre>
 *   [":" префикс ПРОБЕЛ] команда {ПРОБЕЛ параметр} [ПРОБЕЛ ":" хвост] CRLF
 * </pre>
 *
 * <p>Три места, где разбор ломается молча:
 *
 * <ol>
 *   <li><b>Хвостовой параметр.</b> Параметр, начинающийся с двоеточия,
 *       забирает ВСЮ оставшуюся строку вместе с пробелами. Разбор
 *       простым split по пробелу превращает сообщение «привет как дела»
 *       в три параметра, и до получателя доезжает одно слово.</li>
 *   <li><b>Пустой хвост.</b> Строка {@code PRIVMSG #feed :} законна и
 *       означает пустое сообщение, а не отсутствие параметра. Разница
 *       видна только на пустом вводе.</li>
 *   <li><b>Предел в 512 байт</b> вместе с CRLF (§2.3). Он не совет:
 *       чтение строки без предела — это память, которую отдаёт кто
 *       угодно, прислав мегабайт без перевода строки.</li>
 * </ol>
 *
 * <p>Команда приводится к верхнему регистру: протокол её регистр не
 * различает, а сравнение по одному написанию — различает.
 *
 * <p>Префикс от клиента разбирается и ИГНОРИРУЕТСЯ. Присылать его клиент
 * права не имеет, но присылает; принять его на веру значило бы позволить
 * назваться кем угодно одной строкой.
 */
public final class IrcLine {

    /**
     * Предел длины строки вместе с CRLF (RFC 1459 §2.3).
     *
     * <p>Стоит здесь, а не у сервера: это правило протокола, и знать его
     * обязан тот, кто протокол разбирает.
     */
    public static final int MAX_BYTES = 512;

    private final String prefix;
    private final String command;
    private final List<String> params;

    private IrcLine(String prefix, String command, List<String> params) {
        this.prefix = prefix;
        this.command = command;
        this.params = Collections.unmodifiableList(params);
    }

    /**
     * Разобрать строку без CRLF.
     *
     * @return разобранная строка или {@code null}, если в ней нет команды
     *         (пустая строка или одни пробелы — по протоколу их следует
     *         молча пропускать, а не отвечать отказом)
     */
    public static IrcLine parse(String raw) {
        if (raw == null) {
            return null;
        }
        String rest = raw;

        String prefix = null;
        if (rest.startsWith(":")) {
            int space = rest.indexOf(' ');
            if (space < 0) {
                // Префикс без команды — это не сообщение.
                return null;
            }
            prefix = rest.substring(1, space);
            rest = rest.substring(space + 1);
        }

        rest = stripLeadingSpaces(rest);
        if (rest.isEmpty()) {
            return null;
        }

        String command;
        int space = rest.indexOf(' ');
        if (space < 0) {
            command = rest;
            rest = "";
        } else {
            command = rest.substring(0, space);
            rest = rest.substring(space + 1);
        }

        List<String> params = new ArrayList<>();
        while (true) {
            rest = stripLeadingSpaces(rest);
            if (rest.isEmpty()) {
                break;
            }
            if (rest.charAt(0) == ':') {
                // Хвост: всё остальное одним параметром, пробелы внутри
                // сохраняются. Пустой хвост — законный пустой параметр.
                params.add(rest.substring(1));
                break;
            }
            int next = rest.indexOf(' ');
            if (next < 0) {
                params.add(rest);
                break;
            }
            params.add(rest.substring(0, next));
            rest = rest.substring(next + 1);
        }

        return new IrcLine(prefix, command.toUpperCase(Locale.ROOT), params);
    }

    private static String stripLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    /** Префикс, если клиент его прислал. Доверять ему нельзя. */
    public String getPrefix() {
        return prefix;
    }

    /** Команда в верхнем регистре. */
    public String getCommand() {
        return command;
    }

    public List<String> getParams() {
        return params;
    }

    /**
     * Параметр по номеру или {@code null}, если его нет.
     *
     * <p>Именно {@code null}, а не исключение: недостающий параметр —
     * обычное дело в протоколе, где строку набирают руками, и отвечать на
     * него положено числовым отказом, а не падением потока.
     */
    public String param(int index) {
        return index >= 0 && index < params.size() ? params.get(index) : null;
    }

    @Override
    public String toString() {
        return command + " " + params;
    }
}
