package sonder.shell.rest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Курсор страницы ленты.
 *
 * <p><b>Ключ, а не смещение.</b> Контракт говорит прямо: «смещений нет —
 * лента растёт». Пока читатель листает, сверху добавляются посты, и
 * {@code OFFSET 20} на второй странице показал бы часть первой заново, а
 * часть — не показал бы вовсе. Курсор же называет место в ленте: «то, что
 * старше вот этого поста».
 *
 * <p><b>Пара, а не одно время.</b> Два поста могут появиться в одну
 * миллисекунду, и тогда время не различает их. Идентификатор поста в
 * ключе — не для порядка ради порядка, а чтобы взаимное положение таких
 * постов было устойчивым: иначе страница либо пропустила бы строку, либо
 * выдала её дважды.
 *
 * <p><b>Непрозрачный для клиента.</b> Base64 не прячет содержимое и не
 * пытается: это не защита, а граница. Клиент, разобравший курсор и
 * построивший свой, привязался бы к внутреннему устройству ленты, и
 * менять его стало бы ломающим изменением.
 *
 * <p>Негодный курсор — это {@code null} из {@link #parse}, а не
 * исключение: подделанный или устаревший курсор приходит снаружи и
 * является пользовательской ошибкой, а не дефектом.
 */
final class FeedCursor {

    private final Instant createdAt;
    private final String postId;

    FeedCursor(Instant createdAt, String postId) {
        this.createdAt = createdAt;
        this.postId = postId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getPostId() {
        return postId;
    }

    String encode() {
        String raw = createdAt.toEpochMilli() + ":" + postId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Разобрать курсор. {@code null}, если он не годится. */
    static FeedCursor parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        int sep = raw.indexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            return null;
        }
        long millis;
        try {
            millis = Long.parseLong(raw.substring(0, sep));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return new FeedCursor(Instant.ofEpochMilli(millis), raw.substring(sep + 1));
    }
}
