package sonder.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.decider.ActorContext;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.CreatePostCommand;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PingResponse;
import sonder.contract.decider.Role;
import sonder.contract.decider.UserStatus;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameDecoder;
import sonder.gateway.line.FrameCodec;
import sonder.gateway.line.LineMux;
import sonder.gateway.soap.Envelopes;
import sonder.gateway.soap.LineDecider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ядро за линией: вызов уезжает кадрами и возвращается решением.
 *
 * <p>Ноды нет — вместо неё «нода» из нескольких строк, которая собирает
 * кадры, разбирает конверт и отвечает. Подменена сама нода, а не путь до
 * неё: конверт настоящий, кадры настоящие, мультиплексор настоящий.
 *
 * <p><b>Главное здесь — что в линию уходят ТЕ ЖЕ БАЙТЫ, что зафиксированы
 * эталоном.</b> Эталон читает паскалевское ядро своим tcsoap; совпадение
 * замыкает цепочку: оболочка → гейтвей → линия → ядро проверена целиком,
 * а не по кускам.
 */
class LineDeciderTest {

    /** Эталон, который разбирает паскалевская сторона. */
    private static final File GOLDEN_CREATE_POST = Paths.get("..", "contracts",
            "generated", "envelopes", "create-post.xml").toFile();

    /** Подменная нода: собирает кадры и отвечает заготовленным. */
    private static final class FakeNode implements LineMux.Sink {
        final FrameDecoder decoder = new FrameDecoder();
        final List<byte[]> received = new ArrayList<>();
        final ByteArrayOutputStream current = new ByteArrayOutputStream();

        LineMux mux;
        byte[] replyWith;
        /** Отвечать ли вообще: молчание проверяет срок. */
        boolean silent;
        /** Резать ли ответ на несколько кадров. */
        boolean replyInPieces;

        @Override
        public void write(byte[] bytes) {
            for (Frame frame : decoder.feed(bytes, 0, bytes.length)) {
                byte[] part = frame.getPayload();
                current.write(part, 0, part.length);
                if (frame.hasFlag(Frame.FLAG_MORE)) {
                    continue;
                }
                received.add(current.toByteArray());
                current.reset();
                if (!silent) {
                    answer(frame.getChannel());
                }
            }
        }

        private void answer(int channel) {
            if (!replyInPieces) {
                mux.onFrame(new Frame(channel, 0, replyWith));
                return;
            }
            int half = replyWith.length / 2;
            byte[] a = new byte[half];
            byte[] b = new byte[replyWith.length - half];
            System.arraycopy(replyWith, 0, a, 0, half);
            System.arraycopy(replyWith, half, b, 0, b.length);
            mux.onFrame(new Frame(channel, Frame.FLAG_MORE, a));
            mux.onFrame(new Frame(channel, 0, b));
        }
    }

    private FakeNode node;
    private LineMux mux;
    private LineDecider decider;

    @BeforeEach
    void setUp() {
        node = new FakeNode();
        mux = new LineMux(node, null);
        node.mux = mux;
        decider = new LineDecider(mux, Duration.ofSeconds(2));
        node.replyWith = decisionEnvelope("<accepted>true</accepted>"
                + "<event><type>post.created</type>"
                + "<aggregateId>p-1001</aggregateId>"
                + "<field><key>authorId</key><value>u-andrey</value></field>"
                + "</event>");
    }

    private static byte[] decisionEnvelope(String body) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + Envelopes.SOAP_NS + "\">"
                + "<soap:Body><DecisionResponse xmlns=\""
                + Envelopes.DECIDER_NS + "\">" + body
                + "</DecisionResponse></soap:Body></soap:Envelope>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /** Тот же запрос, что зафиксирован эталоном create-post.xml. */
    private static CreatePostRequest goldenRequest() {
        CommandMeta meta = new CommandMeta();
        meta.setTraceId("t-1");
        meta.setCommandId("c-1");
        meta.setIssuedAtMillis(1756684800000L);

        CreatePostCommand command = new CreatePostCommand();
        command.setPostId("p-1001");
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
     * ГЛАВНАЯ ПРОВЕРКА. В линию уходят ровно те байты, которые разбирает
     * паскалевское ядро. Совпадение с эталоном замыкает цепочку целиком.
     */
    @Test
    @DisplayName("в линию уходит ровно эталонный конверт")
    void sendsExactlyTheGoldenEnvelope() throws Exception {
        decider.createPost(goldenRequest());

        assertEquals(1, node.received.size(), "нода получила не одно сообщение");
        String golden = new String(Files.readAllBytes(GOLDEN_CREATE_POST.toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
        String actual = new String(node.received.get(0), StandardCharsets.UTF_8);

        assertEquals(golden, actual,
                "гейтвей кладёт в линию не то, что зафиксировано эталоном: "
                        + "ядро разбирает эталон, а поедет это");
    }

    @Test
    @DisplayName("решение возвращается разобранным")
    void decisionComesBack() {
        Decision d = decider.createPost(goldenRequest());

        assertTrue(d.isAccepted(), "решение не разобрано");
        assertEquals(1, d.getEvent().size(), "событие не доехало");
        DomainEvent e = d.getEvent().get(0);
        assertEquals("post.created", e.getType());
        assertEquals("p-1001", e.getAggregateId());
        assertEquals("authorId", e.getField().get(0).getKey());
        assertEquals("u-andrey", e.getField().get(0).getValue());
    }

    @Test
    @DisplayName("отказ ядра доезжает кодом, а не исключением")
    void rejectionComesBack() {
        node.replyWith = decisionEnvelope("<accepted>false</accepted>"
                + "<errorCode>RATE_POSTS_EXCEEDED</errorCode>");

        Decision d = decider.createPost(goldenRequest());

        assertFalse(d.isAccepted());
        assertEquals("RATE_POSTS_EXCEEDED", d.getErrorCode());
    }

    /** Ответ, приехавший кусками, склеивается до разбора. */
    @Test
    @DisplayName("ответ из нескольких кадров разбирается целиком")
    void multiFrameReplyIsParsed() {
        node.replyInPieces = true;

        Decision d = decider.createPost(goldenRequest());

        assertTrue(d.isAccepted(), "склеенный ответ не разобрался");
        assertEquals(1, d.getEvent().size());
    }

    /**
     * Молчание ноды кончается отказом, а не вечным ожиданием. Оболочка
     * переведёт его в DECIDER_UNAVAILABLE — гейтвею придумывать своё
     * решение незачем.
     */
    @Test
    @DisplayName("молчание ноды кончается отказом по сроку")
    void silenceEndsInFailure() {
        node.silent = true;
        LineDecider quick = new LineDecider(mux, Duration.ofMillis(150));

        LineDecider.LineCallFailed e = assertThrows(
                LineDecider.LineCallFailed.class,
                () -> quick.createPost(goldenRequest()));
        assertTrue(e.getMessage().contains("CreatePostRequest"),
                "в отказе не сказано, какая операция не дошла: " + e.getMessage());
    }

    /** Пинг возвращает свой тип ответа, а не решение. */
    @Test
    @DisplayName("пинг возвращает метрики ноды")
    void pingReturnsMetrics() {
        node.replyWith = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + Envelopes.SOAP_NS + "\">"
                + "<soap:Body><PingResponse xmlns=\"" + Envelopes.DECIDER_NS
                + "\"><nonce>7</nonce><fibersInUse>3</fibersInUse>"
                + "<arenaHighMark>1024</arenaHighMark>"
                + "</PingResponse></soap:Body></soap:Envelope>")
                .getBytes(StandardCharsets.UTF_8);

        PingRequest r = new PingRequest();
        r.setNonce(7);
        PingResponse got = decider.ping(r);

        assertEquals(7, got.getNonce());
        assertEquals(3, got.getFibersInUse());
        assertEquals(1024, got.getArenaHighMark());
    }

    /**
     * Имя элемента берётся у класса запроса. Совпадение не случайно —
     * классы порождены из WSDL по именам элементов, — но полагаться на
     * совпадение без проверки значит ждать, пока оно однажды исчезнет.
     */
    @Test
    @DisplayName("имя класса запроса совпадает с именем элемента в контракте")
    void classNamesMatchContractElements() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(
                new File("../contracts/generated/operations.json"));

        TreeSet<String> declared = new TreeSet<>();
        for (JsonNode op : manifest.get("operations")) {
            declared.add(op.get("request").asText());
        }
        assertFalse(declared.isEmpty(), "манифест пуст — проверка была бы пустой");

        TreeSet<String> classes = new TreeSet<>();
        for (Class<?> c : new Class<?>[]{
                sonder.contract.decider.RegisterUserRequest.class,
                sonder.contract.decider.CreatePostRequest.class,
                sonder.contract.decider.CreateCommentRequest.class,
                sonder.contract.decider.DeletePostRequest.class,
                sonder.contract.decider.FollowUserRequest.class,
                sonder.contract.decider.UnfollowUserRequest.class,
                sonder.contract.decider.BanUserRequest.class,
                sonder.contract.decider.PingRequest.class}) {
            classes.add(c.getSimpleName());
        }

        assertEquals(declared, classes,
                "имя класса разошлось с именем элемента: гейтвей положит в "
                        + "конверт не тот элемент, и ядро не поймёт команду");
    }

    /** Кадры не теряются: сообщение уехало целиком одним куском. */
    @Test
    @DisplayName("длинная команда доезжает до ноды целиком")
    void longCommandArrivesWhole() {
        CreatePostRequest r = goldenRequest();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            body.append("тело ");
        }
        r.getCommand().setBody(body.toString());

        decider.createPost(r);

        assertEquals(1, node.received.size());
        byte[] sent = Envelopes.wrap(CreatePostRequest.class,
                "CreatePostRequest", r);
        assertArrayEquals(sent, node.received.get(0),
                "длинная команда приехала не той, что уезжала");
        assertTrue(sent.length > Frame.MAX_PAYLOAD,
                "команда уместилась в один кадр — проверка не о том");
    }
}
