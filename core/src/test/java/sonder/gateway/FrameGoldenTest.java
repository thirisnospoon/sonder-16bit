package sonder.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameCodec;
import sonder.gateway.line.FrameDecoder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кадры: то, что на самом деле кладёт в линию паскалевская сторона.
 *
 * <p>Эталоны порождены НАСТОЯЩИМ {@code FrameEncode} ядра
 * ({@code dosnode/tools/mkframes.pas}) и лежат в
 * {@code contracts/generated/frames/frames.bin}. Здесь они разбираются и
 * кодируются обратно; совпадение байт в байт доказывает согласие обоих
 * кодировщиков сразу, а не по очереди.
 *
 * <p><b>Почему не «обе стороны написаны по одному описанию».</b> Потому
 * что это уже подводило: рукописный конверт SOAP назывался
 * {@code <createPost>}, а настоящий — {@code <CreatePostRequest>}, и ядро
 * не поняло бы ни одной команды. Описание одно, прочтения разные.
 *
 * <p>Набор случаев подобран по границам: пустая нагрузка (так выглядит
 * подтверждение), полная в 512 байт, все служебные каналы, все сочетания
 * флагов, нагрузка из байтов маркера и нагрузка со старшим битом — в Java
 * байт знаковый, и это классическая ловушка.
 */
class FrameGoldenTest {

    private static final File GOLDEN =
            new File("../contracts/generated/frames/frames.bin");

    /** Записи «длина, байты» подряд — так их пишет mkframes. */
    private static List<byte[]> goldenFrames() throws IOException {
        assertTrue(GOLDEN.isFile(),
                "нет эталонных кадров: " + GOLDEN.getAbsolutePath()
                        + ". Их порождает dosnode/tools/mkframes.pas");
        byte[] all = Files.readAllBytes(GOLDEN.toPath());
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i + 2 <= all.length) {
            int len = (all[i] & 0xFF) | ((all[i + 1] & 0xFF) << 8);
            i += 2;
            assertTrue(i + len <= all.length,
                    "файл эталонов обрывается посреди кадра");
            byte[] frame = new byte[len];
            System.arraycopy(all, i, frame, 0, len);
            out.add(frame);
            i += len;
        }
        assertEquals(all.length, i, "в файле остались лишние байты");
        return out;
    }

    @Test
    @DisplayName("эталонов достаточно, и они не вырождены")
    void goldenIsUseful() throws IOException {
        List<byte[]> frames = goldenFrames();
        assertTrue(frames.size() >= 10,
                "эталонов слишком мало, проверка была бы поверхностной: "
                        + frames.size());

        int longest = 0;
        for (byte[] f : frames) {
            longest = Math.max(longest, f.length);
        }
        assertEquals(Frame.HEADER_BYTES + Frame.MAX_PAYLOAD + Frame.TRAILER_BYTES,
                longest, "среди эталонов нет кадра с полной нагрузкой, а "
                        + "именно на границе ломается счёт длины");
    }

    /**
     * ГЛАВНАЯ ПРОВЕРКА. Каждый эталон разбирается, и обратное кодирование
     * даёт ТЕ ЖЕ БАЙТЫ. Разойдись порядок полей, порядок байтов в длине
     * или вариант контрольной суммы — совпадения не будет.
     */
    @Test
    @DisplayName("каждый эталон разбирается и кодируется обратно байт в байт")
    void roundTripMatchesGolden() throws IOException {
        List<byte[]> frames = goldenFrames();
        FrameDecoder decoder = new FrameDecoder();

        int decoded = 0;
        for (byte[] wire : frames) {
            List<Frame> got = decoder.feed(wire, 0, wire.length);
            assertEquals(1, got.size(),
                    "эталон " + decoded + " не собрался в один кадр");
            Frame frame = got.get(0);
            assertArrayEquals(wire, FrameCodec.encode(frame),
                    "кадр " + decoded + " закодировался иначе: "
                            + frame + ". Стороны линии разошлись");
            decoded++;
        }

        assertEquals(frames.size(), decoded);
        assertEquals(frames.size(), decoder.getFramesOk());
        assertEquals(0, decoder.getCrcErrors(), "сумма не сошлась ни на одном");
        assertEquals(0, decoder.getJunkBytes(), "в потоке эталонов нашёлся мусор");
    }

    /**
     * Байты приходят по одному. Линия отдаёт их как придётся, и разборщик,
     * работающий только на целых кадрах, в бою не работает вовсе.
     */
    @Test
    @DisplayName("эталоны собираются из потока по одному байту")
    void assemblesByteByByte() throws IOException {
        List<byte[]> frames = goldenFrames();
        FrameDecoder decoder = new FrameDecoder();

        int assembled = 0;
        for (byte[] wire : frames) {
            Frame frame = null;
            for (int i = 0; i < wire.length; i++) {
                if (decoder.feed(wire[i])) {
                    frame = decoder.take();
                    assertEquals(wire.length - 1, i,
                            "кадр собрался раньше последнего байта");
                }
            }
            assertNotNull(frame, "кадр не собрался из потока");
            assembled++;
        }
        assertEquals(frames.size(), assembled);
    }

    /**
     * Мусор между кадрами. Линия шумит, особенно при подключении, и
     * разборщик обязан находить начало кадра, а не захлёбываться.
     */
    @Test
    @DisplayName("мусор перед кадром отбрасывается, кадр находится")
    void junkBeforeFrameIsSkipped() throws IOException {
        byte[] wire = goldenFrames().get(0);
        FrameDecoder decoder = new FrameDecoder();

        byte[] junk = {0x00, 0x7F, (byte) 0xA5, 0x11, (byte) 0xFF, (byte) 0xA5};
        List<Frame> none = decoder.feed(junk, 0, junk.length);
        assertTrue(none.isEmpty(), "из мусора собрался кадр");

        List<Frame> got = decoder.feed(wire, 0, wire.length);
        assertEquals(1, got.size(), "кадр после мусора не найден");
        assertTrue(decoder.getJunkBytes() > 0, "мусор не посчитан");
    }

    /**
     * Порченый кадр не принимается и не ломает следующий. Первое — работа
     * контрольной суммы, второе — того, что разбор возвращается к поиску
     * маркера, а не остаётся в середине.
     */
    @Test
    @DisplayName("порча кадра замечается и не уносит следующий")
    void corruptionIsCaughtAndDoesNotSpread() throws IOException {
        List<byte[]> frames = goldenFrames();
        byte[] bad = frames.get(5).clone();
        // Портим нагрузку, а не заголовок: сумма — единственное, что
        // может это заметить.
        bad[Frame.HEADER_BYTES] ^= 0x01;

        FrameDecoder decoder = new FrameDecoder();
        assertTrue(decoder.feed(bad, 0, bad.length).isEmpty(),
                "порченый кадр принят");
        assertEquals(1, decoder.getCrcErrors(), "порча не посчитана");

        byte[] good = frames.get(6);
        assertEquals(1, decoder.feed(good, 0, good.length).size(),
                "следующий кадр потерян вслед за порченым");
    }

    /**
     * Пауза посреди кадра бросает недособранное. Без этого разборщик
     * доел бы недостающие байты из начала следующего кадра — вместе с его
     * маркером, — и потерял бы уже два.
     */
    @Test
    @DisplayName("пауза посреди кадра бросает недособранное")
    void idleDropsPartial() throws IOException {
        List<byte[]> frames = goldenFrames();
        byte[] wire = frames.get(8);

        FrameDecoder decoder = new FrameDecoder();
        decoder.feed(wire, 0, wire.length / 2);
        assertTrue(decoder.idle(), "пауза посреди кадра осталась незамеченной");
        assertFalse(decoder.idle(), "пауза на чистом месте сочтена обрывом");

        assertEquals(1, decoder.feed(wire, 0, wire.length).size(),
                "после обрыва целый кадр не собрался");
    }

    /** Слишком длинный кадр не берётся: маркер был случайным в шуме. */
    @Test
    @DisplayName("невозможная длина отбрасывается вместе с ложным маркером")
    void oversizeIsRejected() {
        FrameDecoder decoder = new FrameDecoder();
        byte[] impossible = {
                (byte) Frame.SYNC_LO, (byte) Frame.SYNC_HI,
                (byte) 0xFF, (byte) 0xFF,   // длина 65535
                0x00, 0x00
        };
        assertTrue(decoder.feed(impossible, 0, impossible.length).isEmpty());
        assertEquals(1, decoder.getOversize(), "невозможная длина не посчитана");
    }
}
