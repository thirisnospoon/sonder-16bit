package sonder.report;

import java.nio.charset.StandardCharsets;

/**
 * ПОРОЖДЁННЫЙ ФАЙЛ. Правится contracts/reports/digest-v1.yaml.
 *
 * <p>Одна строка выгрузки свода, ровно 380 байт.
 *
 * <p>ОБРЕЗАНИЕ ЗАПРЕЩЕНО. Поле, не влезшее в свою ширину, — это
 * отказ выгрузки, а не молча усечённая строка: усечение по байтам
 * рвёт UTF-8 посередине символа, и COBOL прочитает это как мусор,
 * не заметив.
 */
public final class DigestRecord {

    /** Длина записи в байтах. */
    public static final int BYTES = 380;

    private DigestRecord() {
    }

    /** Ширина поля postId в байтах. */
    public static final int POSTID_BYTES = 40;

    /** Ширина поля authorNick в байтах. */
    public static final int AUTHORNICK_BYTES = 80;

    /** Ширина поля authorDisplayName в байтах. */
    public static final int AUTHORDISPLAYNAME_BYTES = 240;

    /** Ширина поля createdDate в байтах. */
    public static final int CREATEDDATE_BYTES = 8;

    /** Ширина поля bodyBytes в байтах. */
    public static final int BODYBYTES_BYTES = 6;

    /** Ширина поля bodyChars в байтах. */
    public static final int BODYCHARS_BYTES = 6;

    /** Собрать строку записи. Возвращает ровно 380 байт.
     *
     * @throws IllegalArgumentException если поле не влезло
     */
    public static byte[] of(String postId,
                            String authorNick,
                            String authorDisplayName,
                            String createdDate,
                            long bodyBytes,
                            long bodyChars) {
        byte[] out = new byte[BYTES];
        java.util.Arrays.fill(out, (byte) ' ');
        int at = 0;
        at = text(out, at, postId, POSTID_BYTES, "postId");
        at = text(out, at, authorNick, AUTHORNICK_BYTES, "authorNick");
        at = text(out, at, authorDisplayName, AUTHORDISPLAYNAME_BYTES, "authorDisplayName");
        at = text(out, at, createdDate, CREATEDDATE_BYTES, "createdDate");
        at = number(out, at, bodyBytes, BODYBYTES_BYTES, "bodyBytes");
        at = number(out, at, bodyChars, BODYCHARS_BYTES, "bodyChars");
        if (at != BYTES) {
            throw new IllegalStateException(
                    "собрано " + at + " байт вместо " + BYTES);
        }
        return out;
    }

    /** Текст влево, добивка пробелами. */
    private static int text(byte[] out, int at, String value,
                            int width, String field) {
        byte[] raw = (value == null ? "" : value)
                .getBytes(StandardCharsets.UTF_8);
        if (raw.length > width) {
            throw new IllegalArgumentException(
                    "поле " + field + ": " + raw.length
                            + " байт при ширине " + width
                            + ". Обрезать нельзя — разорвётся UTF-8");
        }
        System.arraycopy(raw, 0, out, at, raw.length);
        return at + width;
    }

    /** Число вправо, добивка нулями: так его читает PIC 9. */
    private static int number(byte[] out, int at, long value,
                              int width, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "поле " + field + ": отрицательное " + value
                            + ", а разметка без знака");
        }
        String s = Long.toString(value);
        if (s.length() > width) {
            throw new IllegalArgumentException(
                    "поле " + field + ": " + s
                            + " не влезает в " + width + " знаков");
        }
        int pad = width - s.length();
        for (int i = 0; i < pad; i++) {
            out[at + i] = '0';
        }
        byte[] raw = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, out, at + pad, raw.length);
        return at + width;
    }
}
