package sonder.irc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.irc.IrcCast;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пост в строки протокола: байты против знаков.
 *
 * <p>Ровно та пара единиц, на которой проект ошибался четырежды. Пост
 * меряется ЗНАКАМИ (тысяча по домену), строка IRC — БАЙТАМИ (512 вместе
 * с CRLF). Кириллица занимает два байта на знак, и всякий разрез по
 * знакам вместо байтов даёт строку, которую протокол обрежет сам — уже
 * посреди буквы.
 */
class IrcCastTest {

    private static final int MAX = 512;

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("короткий пост уходит одной строкой от имени автора")
    void shortPostIsOneLine() {
        List<String> lines = IrcCast.privmsgs("andrey", "#feed", "привет");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith(":andrey!andrey@sonder PRIVMSG #feed :"),
                "отправитель не автор: " + lines.get(0));
        assertTrue(lines.get(0).endsWith("привет"));
    }

    /**
     * Главный случай: длинный кириллический пост. Разрез по знакам дал бы
     * строки в 999 байт при пределе 512, и обрезал бы их уже сам
     * протокол — посреди буквы.
     */
    @Test
    @DisplayName("длинный пост режется по БАЙТАМ, и каждая строка влезает в предел")
    void longPostFitsEveryLine() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            body.append('я');
        }
        List<String> lines = IrcCast.privmsgs("andrey", "#feed", body.toString());

        assertTrue(lines.size() > 1, "длинный пост не разрезан");
        for (String line : lines) {
            assertTrue(bytes(line) + 2 <= MAX,
                    "строка " + bytes(line) + " байт при пределе " + MAX);
        }
    }

    /**
     * Собранное обратно обязано совпасть с исходным текстом. Проверка не
     * на «примерно то же»: потерянный или удвоенный знак на границе
     * кусков — обычная ошибка такого разреза, и видна она только так.
     */
    @Test
    @DisplayName("склеенные обратно куски дают исходный текст без потерь")
    void chunksJoinBackToOriginal() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            body.append("абвгд");
        }
        String original = body.toString();

        StringBuilder back = new StringBuilder();
        String prefix = ":andrey!andrey@sonder PRIVMSG #feed :";
        for (String line : IrcCast.privmsgs("andrey", "#feed", original)) {
            assertTrue(line.startsWith(prefix));
            back.append(line.substring(prefix.length()));
        }
        assertEquals(original, back.toString(), "текст потерян на границе кусков");
    }

    /**
     * Знак вне основной плоскости занимает в Java два char. Разрез между
     * ними испортил бы его так же, как разрез посреди байтов, — а
     * заметно это только на эмодзи.
     */
    @Test
    @DisplayName("суррогатная пара не разрезается посередине")
    void surrogatePairSurvives() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("🙂");
        }
        String original = body.toString();

        StringBuilder back = new StringBuilder();
        String prefix = ":a!a@sonder PRIVMSG #feed :";
        for (String line : IrcCast.privmsgs("a", "#feed", original)) {
            back.append(line.substring(prefix.length()));
        }
        assertEquals(original, back.toString(), "суррогатная пара разорвана");
        assertFalse(back.toString().contains("�"), "в тексте появился знак замены");
    }

    /**
     * Перевод строки внутри поста — конец строки протокола. Уйди он как
     * есть, всё после него разобралось бы как СЛЕДУЮЩАЯ КОМАНДА.
     */
    @Test
    @DisplayName("абзацы уходят отдельными сообщениями, а не одной строкой")
    void paragraphsBecomeSeparateMessages() {
        List<String> lines =
                IrcCast.privmsgs("andrey", "#feed", "первый\nвторой\r\nтретий");
        assertEquals(3, lines.size(), "абзацы склеены: " + lines);
        for (String line : lines) {
            assertFalse(line.contains("\n"), "перевод строки уехал в протокол");
            assertFalse(line.contains("\r"), "возврат каретки уехал в протокол");
        }
    }

    @Test
    @DisplayName("пустой пост не рождает пустых строк")
    void emptyBodyGivesNothing() {
        assertTrue(IrcCast.privmsgs("andrey", "#feed", "").isEmpty());
        assertTrue(IrcCast.privmsgs("andrey", "#feed", null).isEmpty());
        assertTrue(IrcCast.privmsgs("andrey", "#feed", "\n\n").isEmpty());
    }
}
