package sonder.shell.auth;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Токены сессий.
 *
 * <p><b>SecureRandom, а не Random.</b> Это единственное, что здесь важно.
 * {@code java.util.Random} — линейный конгруэнтный генератор: по двум
 * подряд выданным значениям восстанавливается состояние, а по состоянию —
 * все следующие. Токен сессии, выданный им, угадывается, и выглядеть это
 * будет как «кто-то знает чужой пароль».
 *
 * <p>Тот же проект намеренно использует линейный конгруэнтный генератор в
 * фаззерах и там это правильно: нужна воспроизводимость. Здесь нужна
 * непредсказуемость, и это ровно противоположное требование.
 *
 * <p>256 бит: столько же, сколько у ключа, который никто не подбирает.
 * Кодирование base64url без набивки — токен попадает в заголовок и в
 * cookie, и не должен требовать экранирования.
 */
public final class Tokens {

    /** Байт энтропии в токене. */
    public static final int BYTES = 32;

    /** Длина токена в символах после base64url без набивки. */
    public static final int LENGTH = 43;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    public static String next() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
