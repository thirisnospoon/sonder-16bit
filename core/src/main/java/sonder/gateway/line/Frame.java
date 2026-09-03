package sonder.gateway.line;

import java.util.Arrays;

/**
 * Кадр линии: канал, флаги, полезная нагрузка.
 *
 * <p>Форма задана {@code tcframe} на паскалевской стороне, и здесь она не
 * выдумывается заново, а повторяется. Раскладка байтов проверяется
 * эталонами, которые породил НАСТОЯЩИЙ кодировщик ядра
 * ({@code contracts/generated/frames/frames.bin}): «обе стороны написаны
 * по одному описанию» — не доказательство, и на конвертах SOAP это уже
 * подтвердилось.
 */
public final class Frame {

    /** Маркер начала: не похож ни на текст, ни на длинные серии бит. */
    public static final int SYNC_LO = 0xA5;
    public static final int SYNC_HI = 0xC3;

    public static final int MAX_PAYLOAD = 512;
    public static final int HEADER_BYTES = 6;
    public static final int TRAILER_BYTES = 2;
    public static final int MAX_FRAME_BYTES =
            HEADER_BYTES + MAX_PAYLOAD + TRAILER_BYTES;

    /** Каналы: ноль — управление, дальше команды, верхние — служебные. */
    public static final int CHAN_CONTROL = 0;
    public static final int CHAN_METRICS = 254;
    public static final int CHAN_LOG = 255;

    /** Отправитель ждёт ответа на этот кадр. */
    public static final int FLAG_NEEDS_REPLY = 0x01;
    /** Сообщение продолжается следующим кадром. */
    public static final int FLAG_MORE = 0x02;
    /** Объявление готовности; канал управления. */
    public static final int FLAG_HELLO = 0x04;

    private final int channel;
    private final int flags;
    private final byte[] payload;

    public Frame(int channel, int flags, byte[] payload) {
        if (channel < 0 || channel > 255) {
            throw new IllegalArgumentException("канал вне байта: " + channel);
        }
        if (flags < 0 || flags > 255) {
            throw new IllegalArgumentException("флаги вне байта: " + flags);
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                    "нагрузка длиннее кадра: " + payload.length);
        }
        this.channel = channel;
        this.flags = flags;
        this.payload = payload;
    }

    public int getChannel() {
        return channel;
    }

    public int getFlags() {
        return flags;
    }

    /** Нагрузка как есть. Массив не копируется: кадр живёт недолго. */
    public byte[] getPayload() {
        return payload;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame)) {
            return false;
        }
        Frame other = (Frame) o;
        return channel == other.channel
                && flags == other.flags
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return (channel * 31 + flags) * 31 + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Frame{канал=" + channel + ", флаги=0x"
                + Integer.toHexString(flags) + ", байт=" + payload.length + "}";
    }
}
