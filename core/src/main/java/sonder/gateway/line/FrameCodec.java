package sonder.gateway.line;

/**
 * Кадр в байты.
 *
 * <p>Порядок в точности как у {@code FrameEncode}: маркер, длина младшим
 * вперёд, канал, флаги, нагрузка, сумма младшим вперёд. Сумма считается
 * по тем же байтам и в том же порядке, в каком их увидит разборщик, —
 * длина, канал, флаги, нагрузка, — и маркер в неё НЕ входит.
 *
 * <p>Маркер вне суммы не по недосмотру: разборщик ищет его в шуме и до
 * начала счёта не знает, кадр перед ним или мусор.
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    public static byte[] encode(Frame frame) {
        byte[] payload = frame.getPayload();
        int len = payload.length;
        byte[] out = new byte[Frame.HEADER_BYTES + len + Frame.TRAILER_BYTES];

        int i = 0;
        out[i++] = (byte) Frame.SYNC_LO;
        out[i++] = (byte) Frame.SYNC_HI;

        int crc = Crc16.INIT;

        out[i] = (byte) (len & 0xFF);
        crc = Crc16.update(crc, out[i++]);
        out[i] = (byte) ((len >>> 8) & 0xFF);
        crc = Crc16.update(crc, out[i++]);
        out[i] = (byte) frame.getChannel();
        crc = Crc16.update(crc, out[i++]);
        out[i] = (byte) frame.getFlags();
        crc = Crc16.update(crc, out[i++]);

        for (int p = 0; p < len; p++) {
            out[i] = payload[p];
            crc = Crc16.update(crc, out[i++]);
        }

        out[i++] = (byte) (crc & 0xFF);
        out[i] = (byte) ((crc >>> 8) & 0xFF);
        return out;
    }
}
