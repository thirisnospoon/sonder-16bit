package sonder.irc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.irc.IrcLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор строки IRC.
 *
 * <p>Проверяется ровно то, на чём разбор этого протокола ломается молча.
 * «Разбор работает» — не утверждение: работать будет и split по пробелу,
 * пока в сообщении не окажется пробела.
 */
class IrcLineTest {

    @Test
    @DisplayName("хвостовой параметр забирает строку целиком, вместе с пробелами")
    void trailingKeepsSpaces() {
        IrcLine line = IrcLine.parse("PRIVMSG #feed :привет как дела");
        assertNotNull(line);
        assertEquals("PRIVMSG", line.getCommand());
        assertEquals(2, line.getParams().size(), "хвост разорван по пробелам");
        assertEquals("#feed", line.param(0));
        assertEquals("привет как дела", line.param(1));
    }

    /**
     * Двоеточие внутри хвоста — обычный символ, а не второй разделитель.
     * Время «12:30» в сообщении не должно ничего резать.
     */
    @Test
    @DisplayName("двоеточие внутри хвоста ничего не разделяет")
    void colonInsideTrailingIsData() {
        IrcLine line = IrcLine.parse("PRIVMSG #feed :в 12:30 у меня встреча");
        assertNotNull(line);
        assertEquals("в 12:30 у меня встреча", line.param(1));
    }

    @Test
    @DisplayName("пустой хвост — это пустой параметр, а не его отсутствие")
    void emptyTrailingIsAParameter() {
        IrcLine line = IrcLine.parse("PRIVMSG #feed :");
        assertNotNull(line);
        assertEquals(2, line.getParams().size());
        assertEquals("", line.param(1));
    }

    @Test
    @DisplayName("команда приводится к верхнему регистру")
    void commandIsUpperCased() {
        assertEquals("NICK", IrcLine.parse("nick petya").getCommand());
        assertEquals("NICK", IrcLine.parse("NiCk petya").getCommand());
    }

    /**
     * Присланный клиентом префикс разбирается и не влияет ни на что.
     * Принять его на веру значило бы позволить назваться кем угодно.
     */
    @Test
    @DisplayName("префикс от клиента разбирается отдельно от команды")
    void prefixIsSeparateFromCommand() {
        IrcLine line = IrcLine.parse(":admin!root@localhost PRIVMSG #feed :я тут главный");
        assertNotNull(line);
        assertEquals("admin!root@localhost", line.getPrefix());
        assertEquals("PRIVMSG", line.getCommand());
    }

    @Test
    @DisplayName("пустая строка и одни пробелы не дают команды")
    void blankLinesGiveNothing() {
        assertNull(IrcLine.parse(""));
        assertNull(IrcLine.parse("   "));
        assertNull(IrcLine.parse(null));
        assertNull(IrcLine.parse(":prefix-without-command"));
    }

    @Test
    @DisplayName("недостающий параметр отдаётся как null, а не исключением")
    void missingParamIsNull() {
        IrcLine line = IrcLine.parse("PASS");
        assertNotNull(line);
        assertNull(line.param(0), "параметра нет, а он нашёлся");
        assertNull(line.param(7));
        assertNull(line.param(-1));
    }

    @Test
    @DisplayName("лишние пробелы между параметрами не создают пустых")
    void extraSpacesDoNotCreateEmptyParams() {
        IrcLine line = IrcLine.parse("USER  petya   0  *  :Пётр");
        assertNotNull(line);
        assertEquals(4, line.getParams().size());
        assertEquals("petya", line.param(0));
        assertEquals("Пётр", line.param(3));
    }

    /**
     * Предел объявлен здесь, а не у сервера: это правило протокола.
     * Проверка стережёт само число — 512 вместе с CRLF (RFC 1459 §2.3).
     */
    @Test
    @DisplayName("предел длины строки объявлен и равен 512 байтам")
    void limitIsDeclared() {
        assertEquals(512, IrcLine.MAX_BYTES);
    }

    @Test
    @DisplayName("список параметров не даёт себя менять")
    void paramsAreImmutable() {
        IrcLine line = IrcLine.parse("JOIN #feed");
        assertTrue(line.getParams().getClass().getName().contains("Unmodifiable"),
                "список параметров можно испортить снаружи");
    }
}
