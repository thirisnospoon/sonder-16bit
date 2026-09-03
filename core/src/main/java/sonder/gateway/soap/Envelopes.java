package sonder.gateway.soap;

import sonder.contract.decider.Decision;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Конверт SOAP для линии.
 *
 * <p><b>Здесь, а не в тесте, и это исправление настоящей дыры.</b>
 * Сборка конверта жила в {@code EnvelopeGoldenTest}, и эталоны, которые он
 * фиксировал, доказывали ровно одно: что ядро понимает байты, собранные
 * тестом. Про байты, которые в линию положит гейтвей, они не говорили
 * ничего — а это разные куски кода, пока они не один кусок.
 *
 * <p>Гейтвей терминирует SOAP и переупаковывает вызов в кадры линии
 * (ADR-0007), поэтому конверт для ноды собирает именно он. Тело при этом
 * маршалит тот же JAXB, что и в бою: именно тело содержит всё, на чём
 * стороны могут разойтись.
 *
 * <p><b>Разбор ответа — потоковый и без DTD.</b> Ответ приходит из линии,
 * то есть из-за пределов доверия: разборщик с включённой внешней сущностью
 * ходил бы по ссылкам оттуда. Здесь читается ровно тело конверта, и
 * ничего больше.
 */
public final class Envelopes {

    public static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String DECIDER_NS = "urn:sonder:decider:v1";

    private static final String PROLOG =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\">"
                    + "<soap:Body>";
    private static final String EPILOG = "</soap:Body></soap:Envelope>";

    private Envelopes() {
    }

    /**
     * Завернуть запрос в конверт.
     *
     * @param elementName имя элемента операции — то, что объявляет WSDL,
     *                    а не имя самой операции. Разница уже стоила
     *                    непонятой команды: рукописный конверт назывался
     *                    {@code <createPost>}, а настоящий —
     *                    {@code <CreatePostRequest>}
     */
    public static <T> byte[] wrap(Class<T> type, String elementName, T value) {
        try {
            JAXBContext ctx = JAXBContext.newInstance(type);
            Marshaller m = ctx.createMarshaller();
            m.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);

            StringWriter body = new StringWriter();
            m.marshal(new JAXBElement<>(
                    new QName(DECIDER_NS, elementName), type, value), body);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write(PROLOG);
            w.write(body.toString());
            w.write(EPILOG);
            w.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "не собрался конверт для " + elementName, e);
        }
    }

    /**
     * Достать решение из конверта ответа.
     *
     * <p>Ищется первый элемент внутри {@code soap:Body} — имя его
     * контракт объявляет как {@code DecisionResponse}, но опираться на имя
     * тут незачем: тело конверта содержит ровно один элемент, и он и есть
     * ответ.
     */
    public static Decision unwrapDecision(byte[] envelope) {
        return unwrap(Decision.class, envelope);
    }

    /** То же, но для любого типа ответа: у ping он свой. */
    public static <T> T unwrap(Class<T> type, byte[] envelope) {
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            // Внешние сущности и DTD выключены: ответ приходит из линии,
            // то есть из-за пределов доверия.
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
            factory.setProperty(
                    XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);

            XMLStreamReader reader = factory.createXMLStreamReader(
                    new ByteArrayInputStream(envelope), "UTF-8");

            int depth = 0;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                depth++;
                // Envelope, Body, и третий — само решение.
                if (depth == 3) {
                    Unmarshaller u = JAXBContext.newInstance(type)
                            .createUnmarshaller();
                    return u.unmarshal(reader, type).getValue();
                }
            }
            throw new IllegalStateException("в конверте нет тела ответа");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "не разобрался конверт ответа: " + e.getMessage(), e);
        }
    }
}
