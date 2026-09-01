package sonder.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.auth.Passwords;
import sonder.shell.auth.Tokens;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пароли и токены.
 *
 * <p>Проверяются СВОЙСТВА, а не поведение по образцу. «Хэширование
 * работает» — не утверждение: работать оно будет и с MD5 без соли, и с
 * возвратом самого пароля. Утверждения здесь такие, что нарушить их
 * означает завести настоящую дыру.
 */
class CredentialsTest {

    @Test
    @DisplayName("хэш не совпадает с паролем")
    void hashIsNotThePassword() {
        String raw = "правильная-лошадь-батарейка-скрепка";
        String hash = Passwords.hash(raw);
        assertNotEquals(raw, hash, "пароль сохранён как есть");
        assertFalse(hash.contains(raw), "пароль виден внутри хэша");
    }

    /**
     * Два хэша одного пароля различаются. Это и есть соль: без неё
     * одинаковые пароли видны как одинаковые строки, и утёкшая база
     * сразу показывает, у кого пароль такой же, как у соседа.
     */
    @Test
    @DisplayName("два хэша одного пароля различаются")
    void hashesAreSalted() {
        String raw = "один и тот же пароль";
        assertNotEquals(Passwords.hash(raw), Passwords.hash(raw),
                "соли нет: одинаковые пароли дают одинаковые хэши");
    }

    @Test
    @DisplayName("верный пароль принимается, неверный отвергается")
    void verification() {
        String hash = Passwords.hash("тайна");
        assertTrue(Passwords.matches("тайна", hash));
        assertFalse(Passwords.matches("тайна ", hash), "пробел в конце принят");
        assertFalse(Passwords.matches("Тайна", hash), "регистр проигнорирован");
        assertFalse(Passwords.matches("", hash));
        assertFalse(Passwords.matches(null, hash));
        assertFalse(Passwords.matches("тайна", null));
        assertFalse(Passwords.matches("тайна", ""),
                "пустой хэш принял пароль — так выглядит запись без пароля");
    }

    /**
     * Пустой пароль — не «слабый пароль», а его отсутствие. Захэшировать
     * его значило бы завести учётную запись, в которую входит всякий, кто
     * знает, что она такая.
     */
    @Test
    @DisplayName("пустой пароль не хэшируется")
    void emptyPasswordRejected() {
        assertThrows(IllegalArgumentException.class, () -> Passwords.hash(""));
        assertThrows(NullPointerException.class, () -> Passwords.hash(null));
    }

    /**
     * Стоимость BCrypt объявлена и не должна тихо съехать вниз. Четыре
     * раунда работают ровно так же — до утечки базы.
     */
    @Test
    @DisplayName("стоимость хэширования не ниже объявленной")
    void costIsHigh() {
        assertTrue(Passwords.COST >= 12,
                "стоимость BCrypt опустили до " + Passwords.COST
                        + " — перебор пойдёт со скоростью железа");
        // Стоимость записана в самой строке хэша: $2a$12$...
        assertTrue(Passwords.hash("x").startsWith("$2a$" + Passwords.COST + "$"),
                "фактическая стоимость хэша не та, что объявлена");
    }

    @Test
    @DisplayName("токен нужной длины и без символов, требующих экранирования")
    void tokenShape() {
        String token = Tokens.next();
        assertEquals(Tokens.LENGTH, token.length(),
                "длина токена не та: " + token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]+"),
                "в токене символы, требующие экранирования: " + token);
    }

    /**
     * Токены не повторяются. Проверка слабая — совпадение двух из
     * 2^256 невероятно при любом генераторе, — но повтор в такой выборке
     * означал бы что-то настолько сломанное, что молчать нельзя.
     */
    @Test
    @DisplayName("тысяча токенов различны")
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(Tokens.next()), "токен повторился");
        }
        assertEquals(1000, seen.size());
    }

    /**
     * Байты токена не вырождены. Генератор, отдающий нули или
     * повторяющийся узор, прошёл бы все проверки выше: длина та, символы
     * те, значения различны за счёт счётчика.
     *
     * <p>Проверка грубая и намеренно такая: считается доля различных
     * символов в тысяче токенов. У линейного конгруэнтного генератора с
     * плохими младшими битами она заметно ниже.
     */
    @Test
    @DisplayName("байты токенов не вырождены")
    void tokensLookRandom() {
        Set<Character> alphabet = new HashSet<>();
        int total = 0;
        for (int i = 0; i < 1000; i++) {
            for (char ch : Tokens.next().toCharArray()) {
                alphabet.add(ch);
                total++;
            }
        }
        // base64url — 64 символа. На 43 000 знаков должны встретиться
        // почти все; заметно меньше означает вырожденный источник.
        assertTrue(alphabet.size() >= 60,
                "в токенах встретилось всего " + alphabet.size()
                        + " различных символов из 64 — источник вырожден");
        assertEquals(1000 * Tokens.LENGTH, total);
    }
}
