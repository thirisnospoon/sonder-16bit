package sonder.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.decider.ActorContext;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.CreatePostCommand;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.ObjectFactory;
import sonder.contract.decider.Role;
import sonder.contract.decider.UserStatus;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Эталонный конверт: то, что Java на самом деле кладёт в линию.
 *
 * <p>Смысл файла не в самом тесте, а в том, что он ФИКСИРУЕТ БАЙТЫ. Обе
 * стороны contract-first порождаются из одного WSDL, но «порождаются из
 * одного» и «понимают друг друга» — разные утверждения, и первое не влечёт
 * второго. Разойтись они могут на порядке элементов, на префиксах, на том,
 * как JAXB решит записать перечисление.
 *
 * <p>Эталон коммитится и читается тестом паскалевского ядра: он разбирает
 * ровно эти байты своим tcsoap. Так вопрос «а поймут ли друг друга?»
 * перестаёт быть вопросом веры.
 *
 * <p>Если конверт изменился — тест падает, эталон надо перезаписать
 * осознанно, а не молча. Это ломающее изменение протокола, и выглядеть оно
 * должно как ломающее.
 */
class EnvelopeGoldenTest {

    private static final String SOAP_NS =
            "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String DECIDER_NS = "urn:sonder:decider:v1";

    /** Эталон лежит в contracts/generated: он такой же артефакт контракта,
     *  как сгенерированные типы, и живёт рядом с ними. */
    private static final Path GOLDEN =
            Paths.get("..", "contracts", "generated", "envelopes",
                    "create-post.xml");

    private static CreatePostRequest sampleRequest() {
        CommandMeta meta = new CommandMeta();
        meta.setTraceId("t-1");
        meta.setCommandId("c-1");
        meta.setIssuedAtMillis(1756684800000L);

        CreatePostCommand command = new CreatePostCommand();
        command.setPostId("p-1001");
        // Кириллица и амперсанд намеренно: первое проверяет кодировку,
        // второе — экранирование. И то и другое пишет пользователь.
        command.setBody("Первый пост & последний");

        ActorContext actor = new ActorContext();
        actor.setUserId("u-andrey");
        actor.setRole(Role.USER);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setPostsLastHour(0);
        actor.setCommentsLastHour(0);

        CreatePostRequest request = new CreatePostRequest();
        request.setMeta(meta);
        request.setCommand(command);
        request.setActor(actor);
        return request;
    }

    /**
     * Конверт собирается вручную вокруг тела, как это делает гейтвей.
     *
     * <p>Гейтвей терминирует SOAP и переупаковывает вызов в кадры линии
     * (ADR-0007), поэтому конверт для ноды собирает именно он, а не
     * транспорт CXF. Тело при этом маршалит тот же JAXB, что и в бою, — а
     * именно тело и содержит всё, на чём стороны могут разойтись.
     */
    private static String marshalEnvelope(CreatePostRequest request) throws Exception {
        JAXBContext ctx = JAXBContext.newInstance(CreatePostRequest.class);
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);

        StringWriter body = new StringWriter();
        m.marshal(new JAXBElement<>(
                new QName(DECIDER_NS, "CreatePostRequest"),
                CreatePostRequest.class, request), body);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\">"
                + "<soap:Body>"
                + body
                + "</soap:Body>"
                + "</soap:Envelope>";
    }

    @Test
    @DisplayName("конверт совпадает с зафиксированным эталоном")
    void envelopeMatchesGolden() throws Exception {
        String actual = marshalEnvelope(sampleRequest());

        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, actual.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError(
                    "эталона не было, он записан: " + GOLDEN.toAbsolutePath()
                            + " — проверь содержимое и закоммить");
        }

        String expected = new String(Files.readAllBytes(GOLDEN), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").trim();

        assertEquals(expected, actual.trim(),
                "конверт разошёлся с эталоном. Это ломающее изменение протокола: "
                        + "ядро на Pascal разбирает ИМЕННО эти байты. Перезаписать "
                        + "эталон можно, но осознанно");
    }

    /**
     * Свойства, за которые отвечает не эталон, а разбор на той стороне.
     * Проверяются отдельно, чтобы падение говорило, ЧТО именно сломалось.
     */
    @Test
    @DisplayName("конверт по силам разборщику ядра")
    void envelopeFitsCoreParser() throws Exception {
        String xml = marshalEnvelope(sampleRequest());

        assertTrue(xml.startsWith("<?xml"), "нет объявления");
        assertTrue(xml.contains("<soap:Envelope"), "нет конверта");
        assertTrue(xml.contains("<soap:Body>"), "нет тела");

        // DTD ядро отвергает намеренно, и его тут быть не должно.
        assertTrue(!xml.contains("<!DOCTYPE"), "в конверте DTD, ядро его отвергнет");

        // Амперсанд обязан быть экранирован: неэкранированный сделал бы
        // конверт неразбираемым, а тело поста пишет пользователь.
        assertTrue(xml.contains("&amp;"), "амперсанд не экранирован");

        // Глубина: Envelope, Body, операция, группа, поле — ровно пять.
        // Разборщик ядра глубже не заходит, и это его контракт, а не предел.
        assertEquals(5, maxDepth(xml),
                "конверт глубже пяти уровней — разборщик ядра его отвергнет");
    }

    /** Наибольшая глубина вложенности элементов. */
    private static int maxDepth(String xml) {
        int depth = 0;
        int max = 0;
        int i = 0;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int gt = xml.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            String tag = xml.substring(lt + 1, gt);
            if (!tag.startsWith("?") && !tag.startsWith("!")) {
                if (tag.startsWith("/")) {
                    depth--;
                } else if (tag.endsWith("/")) {
                    max = Math.max(max, depth + 1);
                } else {
                    depth++;
                    max = Math.max(max, depth);
                }
            }
            i = gt + 1;
        }
        return max;
    }
}
