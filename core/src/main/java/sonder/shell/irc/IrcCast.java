package sonder.shell.irc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Пост — в строки протокола.
 *
 * <p>Пост и строка IRC меряются РАЗНЫМ. Доменный предел поста — тысяча
 * ЗНАКОВ; предел строки протокола — 512 БАЙТ вместе с CRLF (RFC 1459
 * §2.3). Кириллица занимает два байта на знак, и пост, законный по
 * домену, в одну строку не влезает почти никогда.
 *
 * <p>Это ровно та пара единиц, на которой проект ошибался четырежды
 * (ADR-0019). Поэтому здесь считаются байты, и только байты.
 *
 * <p>Два запрета, каждый из которых иначе ломается молча:
 *
 * <ol>
 *   <li><b>Резать посреди знака нельзя.</b> Половина двухбайтовой буквы
 *       в конце одной строки и вторая половина в начале следующей — это
 *       не «перенос», а два испорченных символа: клиент покажет ромбы с
 *       вопросами. Резать можно только по границам знаков.</li>
 *   <li><b>Перевод строки внутри поста — конец строки протокола.</b>
 *       Пост из двух абзацев, отправленный одной строкой, для IRC
 *       превратился бы в команду с приклеенным хвостом: всё после
 *       первого перевода строки разобралось бы как следующая команда.
 *       Абзацы поэтому уходят отдельными сообщениями.</li>
 * </ol>
 */
public final class IrcCast {

    private IrcCast() {
    }

    /**
     * Строки {@code PRIVMSG}, которыми пост доедет до канала целиком.
     *
     * <p>Отправитель — сам автор: {@code :ник!ник@сервер PRIVMSG #feed
     * :текст}. Так пост выглядит в клиенте сообщением автора, а не
     * пересказом от сервера, и это не украшение: сообщение от сервера
     * клиент показывает иначе и не даёт на него ответить.
     */
    public static List<String> privmsgs(String nick, String channel, String body) {
        String prefix = ":" + nick + "!" + nick + "@" + IrcSession.SERVER
                + " PRIVMSG " + channel + " :";
        int room = IrcLine.MAX_BYTES - 2 - utf8Length(prefix);

        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        // Пустая ёмкость означала бы бесконечный цикл ниже. Случай
        // невозможный при разумном имени, но проверка дешевле разбора
        // зависшего потока.
        if (room <= 0) {
            return out;
        }

        // \\R не годится: он есть только с восьмой Java в регулярках, а
        // тут нужен простой и предсказуемый разбор на абзацы.
        for (String paragraph : body.split("\r\n|\n|\r", -1)) {
            if (paragraph.isEmpty()) {
                continue;
            }
            for (String chunk : chunks(paragraph, room)) {
                out.add(prefix + chunk);
            }
        }
        return out;
    }

    /**
     * Разрезать по границам знаков так, чтобы каждый кусок влезал в
     * заданное число байтов.
     *
     * <p>Идём по КОДОВЫМ ТОЧКАМ, а не по символам {@code char}: буква вне
     * основной плоскости — например эмодзи — занимает в Java два
     * {@code char}, и разрез между ними испортил бы её так же, как
     * разрез посреди байтов.
     */
    private static List<String> chunks(String text, int maxBytes) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;

        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            int size = utf8Length(new String(Character.toChars(cp)));

            if (bytes + size > maxBytes && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            current.appendCodePoint(cp);
            bytes += size;
            i += width;
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
