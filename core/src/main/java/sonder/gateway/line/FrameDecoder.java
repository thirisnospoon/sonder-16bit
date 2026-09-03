package sonder.gateway.line;

/**
 * Разборщик кадров из потока байтов.
 *
 * <p>Повторяет автомат {@code TDecoder}: тот же порядок состояний, те же
 * метрики. Байт за байтом, потому что линия отдаёт их как придётся — по
 * одному, пачками, с разрывом посреди кадра.
 *
 * <p><b>Метрики не для красоты.</b> Отброшенный мусор и битые суммы — это
 * не «ничего не случилось», а признак состояния линии. Разборщик, молча
 * выбрасывающий каждый второй кадр, выглядит как работающий.
 */
public final class FrameDecoder {

    private enum State {
        SYNC1, SYNC2, LEN_LO, LEN_HI, CHAN, FLAGS, PAYLOAD, CRC_LO, CRC_HI
    }

    private State state = State.SYNC1;

    private int len;
    private int channel;
    private int flags;
    private final byte[] payload = new byte[Frame.MAX_PAYLOAD];
    private int got;
    private int crc = Crc16.INIT;
    private int wantCrc;

    private Frame ready;

    private long framesOk;
    private long crcErrors;
    private long oversize;
    private long junkBytes;
    private long resyncs;

    /**
     * Скормить байт. {@code true} означает, что кадр собран и его надо
     * забрать через {@link #take()} до следующего вызова.
     */
    public boolean feed(int b) {
        int v = b & 0xFF;
        switch (state) {
            case SYNC1:
                if (v == Frame.SYNC_LO) {
                    state = State.SYNC2;
                } else {
                    junkBytes++;
                }
                return false;

            case SYNC2:
                if (v == Frame.SYNC_HI) {
                    state = State.LEN_LO;
                    crc = Crc16.INIT;
                    got = 0;
                } else if (v == Frame.SYNC_LO) {
                    // Два младших маркера подряд: второй может оказаться
                    // началом настоящего кадра, и терять его нельзя.
                    junkBytes++;
                } else {
                    junkBytes++;
                    state = State.SYNC1;
                }
                return false;

            case LEN_LO:
                len = v;
                crc = Crc16.update(crc, v);
                state = State.LEN_HI;
                return false;

            case LEN_HI:
                len |= v << 8;
                crc = Crc16.update(crc, v);
                if (len > Frame.MAX_PAYLOAD) {
                    // Длина за пределом кадра означает, что маркер был
                    // случайным совпадением в шуме. Ищем следующий.
                    oversize++;
                    resyncs++;
                    state = State.SYNC1;
                    return false;
                }
                state = State.CHAN;
                return false;

            case CHAN:
                channel = v;
                crc = Crc16.update(crc, v);
                state = State.FLAGS;
                return false;

            case FLAGS:
                flags = v;
                crc = Crc16.update(crc, v);
                state = (len == 0) ? State.CRC_LO : State.PAYLOAD;
                return false;

            case PAYLOAD:
                payload[got++] = (byte) v;
                crc = Crc16.update(crc, v);
                if (got == len) {
                    state = State.CRC_LO;
                }
                return false;

            case CRC_LO:
                wantCrc = v;
                state = State.CRC_HI;
                return false;

            case CRC_HI:
                wantCrc |= v << 8;
                state = State.SYNC1;
                if (wantCrc == crc) {
                    byte[] body = new byte[len];
                    System.arraycopy(payload, 0, body, 0, len);
                    ready = new Frame(channel, flags, body);
                    framesOk++;
                    return true;
                }
                crcErrors++;
                return false;

            default:
                throw new IllegalStateException("состояние " + state);
        }
    }

    /** Скормить кусок. Возвращает собранные кадры в порядке прихода. */
    public java.util.List<Frame> feed(byte[] data, int off, int length) {
        java.util.List<Frame> out = new java.util.ArrayList<>();
        for (int i = 0; i < length; i++) {
            if (feed(data[off + i])) {
                out.add(take());
            }
        }
        return out;
    }

    /** Забрать собранный кадр. */
    public Frame take() {
        Frame f = ready;
        ready = null;
        return f;
    }

    /**
     * Сообщить о паузе в потоке.
     *
     * <p>Кадр передаётся непрерывно, поэтому пауза посреди него означает,
     * что отправитель оборвался. Без этого сигнала разборщик продолжает
     * ждать недостающие байты и доедает их из начала СЛЕДУЮЩЕГО кадра —
     * вместе с его маркером, — так что следующий теряется целиком, а
     * восстановление наступает только через один.
     *
     * @return {@code true}, если пришлось бросить недособранный кадр
     */
    public boolean idle() {
        if (state == State.SYNC1) {
            return false;
        }
        state = State.SYNC1;
        got = 0;
        resyncs++;
        return true;
    }

    public long getFramesOk() {
        return framesOk;
    }

    public long getCrcErrors() {
        return crcErrors;
    }

    public long getOversize() {
        return oversize;
    }

    public long getJunkBytes() {
        return junkBytes;
    }

    public long getResyncs() {
        return resyncs;
    }
}
