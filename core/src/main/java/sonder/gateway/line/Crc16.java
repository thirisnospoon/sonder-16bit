package sonder.gateway.line;

/**
 * CRC-16/CCITT-FALSE: полином {@code 0x1021}, начальное значение
 * {@code 0xFFFF}, без отражения и без финального XOR.
 *
 * <p>Тот же, что считает {@code tcframe} на другом конце линии. Вариантов
 * «CRC-16» существует десяток, и различаются они ровно теми мелочами,
 * которые здесь перечислены: перепутай отражение — и суммы разойдутся на
 * всех кадрах, кроме случайно совпавших.
 *
 * <p><b>Байт разворачивается в {@code & 0xFF} везде.</b> В Java байт
 * знаковый, и {@code 0xA5} приезжает как −91. Без маскирования сумма
 * посчиталась бы по другим числам, и разошлась бы она только на кадрах со
 * старшим битом — то есть на кириллице и на двоичных данных, а на
 * латинице работала бы прекрасно.
 */
public final class Crc16 {

    /** Таблица на 256 записей: побитовый счёт вдвое медленнее и не нужен. */
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc & 0x8000) != 0)
                        ? ((crc << 1) ^ 0x1021)
                        : (crc << 1);
            }
            TABLE[i] = crc & 0xFFFF;
        }
    }

    /** Начальное значение. */
    public static final int INIT = 0xFFFF;

    private Crc16() {
    }

    public static int update(int crc, int b) {
        int index = ((crc >>> 8) ^ (b & 0xFF)) & 0xFF;
        return ((crc << 8) ^ TABLE[index]) & 0xFFFF;
    }

    public static int of(byte[] data, int off, int len) {
        int crc = INIT;
        for (int i = 0; i < len; i++) {
            crc = update(crc, data[off + i]);
        }
        return crc;
    }
}
