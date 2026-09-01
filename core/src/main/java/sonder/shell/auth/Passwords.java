package sonder.shell.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Objects;

/**
 * Хэширование паролей.
 *
 * <p>BCrypt, а не SHA с солью и не что-нибудь своё. Быстрый хэш — это
 * подарок тому, кто украл базу: перебор идёт со скоростью железа. BCrypt
 * медленный намеренно, и стоимость настраивается.
 *
 * <p>Соль генерируется внутри и хранится в самой строке хэша, поэтому
 * отдельной колонки под неё нет и быть не должно. Два хэша одного пароля
 * различаются — это свойство, а не случайность, и оно проверяется тестом.
 *
 * <p><b>Стоимость.</b> Двенадцать раундов — примерно четверть секунды на
 * современном железе. Это заметно для перебора и незаметно для входа,
 * который случается раз в сессию. Число подлежит уточнению бенчмарком:
 * когда железо станет быстрее, стоимость надо поднимать, и запись об этом
 * должна быть здесь, а не в чьей-то памяти.
 */
public final class Passwords {

    /** Раундов BCrypt. Растёт со временем, а не остаётся навсегда. */
    public static final int COST = 12;

    private static final BCryptPasswordEncoder ENCODER =
            new BCryptPasswordEncoder(COST);

    private Passwords() {
    }

    public static String hash(String raw) {
        Objects.requireNonNull(raw, "пароль");
        if (raw.isEmpty()) {
            // Пустой пароль — не «слабый пароль», а отсутствие пароля.
            // Захэшировать его значило бы завести учётную запись, в
            // которую входит кто угодно, знающий, что она такая.
            throw new IllegalArgumentException("пустой пароль не хэшируется");
        }
        return ENCODER.encode(raw);
    }

    /**
     * Проверить пароль против хэша.
     *
     * <p>Сравнение внутри BCrypt постоянное по времени: побайтное
     * сравнение хэшей утекало бы длиной совпадающего префикса.
     */
    public static boolean matches(String raw, String hash) {
        if (raw == null || hash == null || hash.isEmpty()) {
            return false;
        }
        return ENCODER.matches(raw, hash);
    }
}
