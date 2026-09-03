package sonder.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.ErrorCode;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.EventField;
import sonder.contract.decider.PingResponse;
import sonder.gateway.soap.Envelopes;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ответы: то, что на самом деле кладёт в линию ядро.
 *
 * <p><b>Зачем этот файл существует.</b> Эталонные конверты были только на
 * запросы: Java порождает, Pascal разбирает. Обратное направление не
 * проверялось ничем — и это стоило четырёх дефектов подряд, найденных не
 * тестом, а сквозным прогоном: ответ без пространства имён, событие без
 * полей, пинг без пространства имён, и круговой тест, читавший ответ не
 * тем разборщиком. Каждый жил на стыке и был невидим поодиночке.
 *
 * <p>Эталоны порождены НАСТОЯЩИМ писателем ядра
 * ({@code dosnode/tools/mkreplies.pas} поверх {@code WriteDecision}) и
 * лежат в {@code contracts/generated/replies/replies.bin}. Разбирает их
 * здесь настоящий связыватель гейтвея — тот же {@link Envelopes}, что
 * работает в бою.
 *
 * <p><b>Почему без обратного кодирования</b>, в отличие от эталонных
 * кадров. Кадры кодируют обе стороны, поэтому там совпадение байт в байт
 * — доказательство согласия. Ответы пишет только ядро и читает только
 * гейтвей: сверять тут нечего, и единственная осмысленная проверка — что
 * прочитанное совпало с тем, что ядру велели написать.
 *
 * <p>Набор случаев подобран по границам, на которых стороны уже
 * расходились: событие с полями, два события в одном решении, отказ с
 * кодом, согласие без событий (так выглядит идемпотентный повтор) и
 * ответ на пинг — у него другой корень и другие поля.
 */
class ReplyGoldenTest {

    private static final File GOLDEN =
            new File("../contracts/generated/replies/replies.bin");

    /** Записи «длина, байты» подряд — так их пишет mkreplies. */
    private static List<byte[]> goldenReplies() throws IOException {
        assertTrue(GOLDEN.isFile(),
                "нет эталонных ответов: " + GOLDEN.getAbsolutePath()
                        + ". Их порождает dosnode/tools/mkreplies.pas");
        byte[] all = Files.readAllBytes(GOLDEN.toPath());
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i + 2 <= all.length) {
            int len = (all[i] & 0xFF) | ((all[i + 1] & 0xFF) << 8);
            i += 2;
            assertTrue(i + len <= all.length,
                    "файл эталонов обрывается посреди конверта");
            byte[] one = new byte[len];
            System.arraycopy(all, i, one, 0, len);
            out.add(one);
            i += len;
        }
        assertEquals(all.length, i, "в файле остались лишние байты");
        return out;
    }

    /** Имя и пространство имён корневого элемента тела конверта. */
    private static String[] bodyRoot(byte[] envelope) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        XMLStreamReader reader = factory.createXMLStreamReader(
                new ByteArrayInputStream(envelope), "UTF-8");
        int depth = 0;
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            depth++;
            if (depth == 3) {
                return new String[]{
                        reader.getName().getNamespaceURI(),
                        reader.getLocalName()};
            }
        }
        throw new IllegalStateException("в конверте нет тела");
    }

    private static final int ACCEPTED_WITH_EVENT = 0;
    private static final int ACCEPTED_TWO_EVENTS = 1;
    private static final int REJECTED = 2;
    private static final int ACCEPTED_NO_EVENTS = 3;
    private static final int PONG = 4;

    @Test
    @DisplayName("эталонов достаточно, и они не вырождены")
    void goldenIsUseful() throws IOException {
        List<byte[]> replies = goldenReplies();
        assertEquals(5, replies.size(),
                "число эталонов изменилось: тест ниже опирается на порядок");

        // Файл, состоящий из одинаковых ответов, зелен всегда. Проверяем,
        // что случаи и правда разные: и по длине, и по содержимому.
        boolean sawAccepted = false;
        boolean sawRejected = false;
        boolean sawNonAscii = false;
        for (byte[] r : replies) {
            String text = new String(r, StandardCharsets.UTF_8);
            sawAccepted |= text.contains("<accepted>true</accepted>");
            sawRejected |= text.contains("<accepted>false</accepted>");
            for (byte b : r) {
                if ((b & 0x80) != 0) {
                    sawNonAscii = true;
                }
            }
        }
        assertTrue(sawAccepted, "среди эталонов нет согласия");
        assertTrue(sawRejected, "среди эталонов нет отказа");
        assertTrue(sawNonAscii,
                "среди эталонов нет ни одного не-ASCII байта, а кодировку "
                        + "линии проверять больше нечем");
    }

    @Test
    @DisplayName("корень тела несёт пространство имён контракта")
    void rootCarriesNamespace() throws Exception {
        // Ровно тот дефект, который сквозной прогон нашёл первым: корень
        // без пространства имён, JAXB связывает пустоту, решение приходит
        // отказом без кода. Проверка отдельная, потому что через
        // связыватель это видно как «ядро отказало», а не как «конверт
        // назван не так».
        List<byte[]> replies = goldenReplies();
        for (int i = 0; i < replies.size(); i++) {
            String[] root = bodyRoot(replies.get(i));
            assertEquals(Envelopes.DECIDER_NS, root[0],
                    "ответ #" + (i + 1) + " (" + root[1]
                            + ") без пространства имён контракта: "
                            + "связыватель прочтёт из него пустоту");
        }
        assertEquals("DecisionResponse", bodyRoot(replies.get(REJECTED))[1]);
        assertEquals("PingResponse", bodyRoot(replies.get(PONG))[1]);
    }

    @Test
    @DisplayName("событие доезжает со своими полями")
    void eventCarriesItsFields() throws IOException {
        // Второй дефект: генератор не писал вложенный повторяемый список,
        // и события уезжали пустыми. Проекция ленты не нашла бы автора.
        Decision d = Envelopes.unwrapDecision(
                goldenReplies().get(ACCEPTED_WITH_EVENT));

        assertTrue(d.isAccepted(), "решение прочиталось отказом");
        assertEquals(1, d.getEvent().size(), "событие не прочиталось");

        DomainEvent e = d.getEvent().get(0);
        assertEquals("post.created", e.getType());
        assertEquals("p-1001", e.getAggregateId());
        assertEquals(2, e.getField().size(),
                "поля события не прочитались: событие уехало бы пустым");

        EventField author = e.getField().get(0);
        assertEquals("authorId", author.getKey());
        assertEquals("u-andrey", author.getValue());

        // Кириллица и амперсанд: и то и другое пишет пользователь, и на
        // границе они ломаются первыми — одно кодировкой, другое разметкой.
        EventField note = e.getField().get(1);
        assertEquals("note", note.getKey());
        assertEquals("Первый & последний", note.getValue());
    }

    @Test
    @DisplayName("два события в одном решении читаются оба и по порядку")
    void twoEventsKeepOrder() throws IOException {
        // Список из одного элемента не отличить от одиночки: связыватель,
        // берущий только первое событие, прошёл бы предыдущую проверку.
        Decision d = Envelopes.unwrapDecision(
                goldenReplies().get(ACCEPTED_TWO_EVENTS));

        assertTrue(d.isAccepted());
        assertEquals(2, d.getEvent().size(),
                "прочиталось не два события: список свёлся к одиночке");

        assertEquals("follow.removed", d.getEvent().get(0).getType());
        assertEquals("u-andrey", d.getEvent().get(0).getAggregateId());
        assertEquals("targetUserId",
                d.getEvent().get(0).getField().get(0).getKey());

        assertEquals("post.deleted", d.getEvent().get(1).getType());
        assertEquals("p-1001", d.getEvent().get(1).getAggregateId());
        assertEquals("deletedBy",
                d.getEvent().get(1).getField().get(0).getKey());
    }

    @Test
    @DisplayName("отказ несёт код из общего словаря и подробность")
    void rejectionCarriesKnownCode() throws IOException {
        Decision d = Envelopes.unwrapDecision(goldenReplies().get(REJECTED));

        assertFalse(d.isAccepted(), "отказ прочитался согласием");
        assertEquals(0, d.getEvent().size(),
                "отказ породил событие: мир узнал бы о том, чего не было");

        // Код обязан быть из контракта, а не выдуманным ядром: valueOf
        // упадёт на чужом. Это сверка двух словарей — паскалевского
        // errcodes.inc и явского перечисления — через настоящий ответ.
        ErrorCode known = ErrorCode.valueOf(d.getErrorCode());
        assertEquals(ErrorCode.POST_RATE_EXCEEDED, known);

        assertEquals("не больше десяти в час", d.getErrorDetail(),
                "подробность отказа потерялась или испортилась");
    }

    @Test
    @DisplayName("согласие без событий — это согласие, а не пустота")
    void acceptedWithoutEventsIsStillAccepted() throws IOException {
        // Так выглядит идемпотентный повтор. Если связыватель прочтёт из
        // конверта пустоту, получится ровно то же самое: accepted=false и
        // ноль событий. Разделяет их только флаг, и потому он тут главный.
        Decision d = Envelopes.unwrapDecision(
                goldenReplies().get(ACCEPTED_NO_EVENTS));

        assertTrue(d.isAccepted(),
                "согласие без событий прочиталось отказом: связыватель "
                        + "не нашёл в конверте ничего");
        assertEquals(0, d.getEvent().size());
        assertTrue(d.getErrorCode() == null || d.getErrorCode().isEmpty(),
                "согласие с кодом ошибки: " + d.getErrorCode());
    }

    @Test
    @DisplayName("ответ на пинг несёт все метрики ноды, а не нули")
    void pongCarriesMetrics() throws IOException {
        // Третий дефект: пинг писался без пространства имён, и метрики
        // приходили нулями. Ноль — законное значение счётчика, поэтому в
        // эталоне все значения ненулевые И РАЗНЫЕ: одинаковые прошли бы
        // и при писателе, который кладёт всюду одно и то же поле.
        PingResponse p = Envelopes.unwrap(
                PingResponse.class, goldenReplies().get(PONG));

        assertNotNull(p);
        assertEquals(4242, p.getNonce(),
                "нонс не прочитался: ответ не связать с вызовом");
        assertEquals(3, p.getFibersInUse());
        // Пик и ёмкость: пик без ёмкости не говорит ничего, и раньше
        // на месте пика уезжало число обслуженных команд.
        assertEquals(1024, p.getArenaHighMark());
        assertEquals(2048, p.getArenaCapacity());
        assertEquals(17, p.getCommandsServed());
        assertEquals(2, p.getCommandsRefused());
        assertEquals(1, p.getCommandsMalformed());
        assertEquals(5, p.getLineErrors());
        assertEquals(90123, p.getRxBytes());
        assertEquals(45061, p.getTxBytes());
    }

    @Test
    @DisplayName("у пинга сверены все поля, объявленные контрактом")
    void pongCheckedFieldByField() throws Exception {
        // Проверка выше перечисляет поля руками, и поле, добавленное в
        // контракт, в неё не попадёт: она останется зелёной, сверяя
        // прежние девять из десяти. Здесь число полей берётся у самого
        // ответа — и расходится с числом сверенных.
        int declared = 0;
        for (java.lang.reflect.Method m : PingResponse.class.getMethods()) {
            if (m.getName().startsWith("get") && m.getParameterCount() == 0
                    && !"getClass".equals(m.getName())) {
                declared++;
            }
        }
        assertEquals(CHECKED_PONG_FIELDS, declared,
                "у PingResponse изменился состав полей: сверьте новое поле "
                        + "в pongCarriesMetrics и поправьте это число. "
                        + "Молча оставленное поле уехало бы непроверенным");
    }

    /** Сколько полей пинга сверяет {@link #pongCarriesMetrics()}. */
    private static final int CHECKED_PONG_FIELDS = 10;
}
