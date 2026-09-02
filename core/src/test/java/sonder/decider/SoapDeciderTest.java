package sonder.decider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.decider.ActorContext;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.CreatePostCommand;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.Role;
import sonder.contract.decider.UserStatus;
import sonder.shell.decider.DeciderConfig;
import sonder.shell.decider.DeciderFaults;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Клиент SOAP к ядру: что уходит в линию и что бывает, когда не отвечают.
 *
 * <p>Ядра здесь нет — вместо него обычный HTTP-сервер из JDK, который
 * отдаёт заранее заготовленные байты. Подменяется НЕ клиент, а тот, кому
 * он звонит: сам клиент настоящий, порождённый CXF из
 * {@code decider-v1.wsdl}, со всеми его сроками и разметкой. Иначе
 * проверялась бы выдумка, а не то, что поедет в бой.
 *
 * <p>Так же устроен и настоящий путь: оболочка говорит по SOAP с
 * гейтвеем, а гейтвей переупаковывает вызов в кадры последовательной
 * линии (ADR-0007). Здесь проверена именно эта, ближняя половина.
 *
 * <p>Отказы важнее удачи. Ядро за линией и эмулятором отвалится,
 * задумается и ответит ерундой ещё много раз, и на каждый такой случай
 * оболочка обязана вернуть отказ из контракта, а не исключение.
 */
class SoapDeciderTest {

    private static final String NS = "urn:sonder:decider:v1";

    /** Ответ ноды на удачную команду. Форма — из схемы WSDL. */
    private static final String ACCEPTED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<soap:Envelope xmlns:soap=\""
                    + "http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<DecisionResponse xmlns=\"" + NS + "\">"
                    + "<accepted>true</accepted>"
                    + "<event>"
                    + "<type>post.created</type>"
                    + "<aggregateId>p-1001</aggregateId>"
                    + "<field><key>actor</key><value>u-andrey</value></field>"
                    + "</event>"
                    + "</DecisionResponse></soap:Body></soap:Envelope>";

    /** Ответ ноды на отказ по доменной причине. */
    private static final String REJECTED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<soap:Envelope xmlns:soap=\""
                    + "http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<DecisionResponse xmlns=\"" + NS + "\">"
                    + "<accepted>false</accepted>"
                    + "<errorCode>RATE_POSTS_EXCEEDED</errorCode>"
                    + "</DecisionResponse></soap:Body></soap:Envelope>";

    private HttpServer server;
    private int port;

    /** Что приехало на сервер: тело и заголовок SOAPAction. */
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAction = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Заготовленный ответ на любой запрос, с записью того, что пришло. */
    private void answerWith(final int status, final String body,
                            final long delayMillis) {
        server.createContext("/decider", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastBody.set(read(exchange.getRequestBody()));
                lastAction.set(exchange.getRequestHeaders()
                        .getFirst("SOAPAction"));

                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders()
                        .set("Content-Type", "text/xml; charset=utf-8");
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        });
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int got;
        while ((got = in.read(chunk)) > 0) {
            buf.write(chunk, 0, got);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private String address() {
        return "http://127.0.0.1:" + port + "/decider";
    }

    /** Клиент с обёрткой отказов — такой же, какой собирает конфигурация. */
    private Decider client(long receiveMs) {
        return DeciderFaults.wrap(
                DeciderConfig.soapClient(address(), 1000, receiveMs), address());
    }

    private static CreatePostRequest sampleCommand() {
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
     * Команда доезжает целиком, решение возвращается целиком.
     *
     * <p>Проверяется не «вызов не упал», а что в линию ушли ИМЕННО те
     * поля, по которым ядро выносит решение. Клиент, потерявший контекст
     * лица, получил бы формально корректный ответ по неполным данным (R5).
     */
    @Test
    @DisplayName("команда уходит в линию со всем состоянием, решение возвращается")
    void roundTripCarriesCommandAndDecision() {
        answerWith(200, ACCEPTED, 0);

        Decision d = client(5000).createPost(sampleCommand());

        String sent = lastBody.get();
        assertNotNull(sent, "на сервер ничего не пришло");
        assertTrue(sent.contains("CreatePostRequest"),
                "имя элемента операции не то, что объявляет WSDL: " + sent);
        assertTrue(sent.contains("<postId>p-1001</postId>"), "нет команды");
        assertTrue(sent.contains("<userId>u-andrey</userId>"), "нет лица");
        assertTrue(sent.contains("<role>USER</role>"), "нет роли");
        assertTrue(sent.contains("<postsLastHour>0</postsLastHour>"),
                "нет счётчика, по которому ядро проверяет частоту");
        assertTrue(sent.contains("<issuedAtMillis>1756684800000</issuedAtMillis>"),
                "нет времени команды");
        assertTrue(sent.contains("&amp;"),
                "амперсанд в теле поста не экранирован");

        assertTrue(d.isAccepted(), "решение не разобрано");
        assertEquals(1, d.getEvent().size(), "событие не доехало");
        DomainEvent e = d.getEvent().get(0);
        assertEquals("post.created", e.getType());
        assertEquals("p-1001", e.getAggregateId());
        assertEquals(1, e.getField().size(), "поле события не доехало");
        assertEquals("actor", e.getField().get(0).getKey());
        assertEquals("u-andrey", e.getField().get(0).getValue());
    }

    /**
     * Заголовок маршрутизации совпадает с объявленным в контракте.
     *
     * <p>По SOAPAction гейтвей выбирает, какую команду класть в линию.
     * Разойдись он с контрактом — команда уехала бы не той операцией, и
     * узналось бы это на живой ноде.
     */
    @Test
    @DisplayName("SOAPAction совпадает с объявленным в контракте")
    void soapActionMatchesContract() throws Exception {
        answerWith(200, ACCEPTED, 0);
        client(5000).createPost(sampleCommand());

        JsonNode manifest = new ObjectMapper().readTree(
                new File("../contracts/generated/operations.json"));
        String declared = null;
        for (JsonNode op : manifest.get("operations")) {
            if ("CreatePost".equals(op.get("operation").asText())) {
                declared = op.get("soapAction").asText();
            }
        }
        assertNotNull(declared, "в манифесте нет операции CreatePost");

        // CXF заключает значение в кавычки, как требует SOAP 1.1.
        assertEquals("\"" + declared + "\"", lastAction.get(),
                "клиент шлёт не тот SOAPAction, по которому гейтвей "
                        + "выбирает операцию");
    }

    /** Отказ ядра — обычное решение, а не повод для исключения. */
    @Test
    @DisplayName("доменный отказ ядра доезжает кодом, а не исключением")
    void domainRejectionArrivesAsDecision() {
        answerWith(200, REJECTED, 0);

        Decision d = client(5000).createPost(sampleCommand());

        assertFalse(d.isAccepted());
        assertEquals("RATE_POSTS_EXCEEDED", d.getErrorCode(),
                "код отказа ядра подменён");
    }

    /**
     * Гейтвея нет вовсе.
     *
     * <p>Порт занимается и тут же освобождается: так адрес заведомо
     * никем не слушается, а «свободный» порт мог бы к моменту вызова
     * оказаться занят кем-то ещё.
     */
    @Test
    @DisplayName("недоступный гейтвей даёт отказ из контракта, а не исключение")
    void refusedConnectionBecomesUnavailable() throws Exception {
        int dead;
        try (ServerSocket probe = new ServerSocket(0)) {
            dead = probe.getLocalPort();
        }
        String nowhere = "http://127.0.0.1:" + dead + "/decider";

        Decider client = DeciderFaults.wrap(
                DeciderConfig.soapClient(nowhere, 1000, 1000), nowhere);

        Decision d = client.createPost(sampleCommand());
        assertFalse(d.isAccepted());
        assertEquals("DECIDER_UNAVAILABLE", d.getErrorCode());
    }

    /**
     * Нода задумалась дольше срока.
     *
     * <p>Это главная проверка здешнего клиента: без своего срока CXF
     * ждёт по умолчанию минуту, и одна залипшая команда столько же
     * держит поток сервлета. Проверяется не только код отказа, но и
     * ВРЕМЯ: срок должен обрывать ожидание много раньше, чем ответит
     * сервер.
     *
     * <p>Проверено, что умеет провалиться: без {@code setReceiveTimeout}
     * вызов длится 5,08 с и краснеет — сервер успевает ответить, и
     * решение приходит принятым.
     */
    @Test
    @DisplayName("молчание ноды обрывается сроком, а не ожиданием до конца")
    void slowNodeIsCutOffByTimeout() {
        answerWith(200, ACCEPTED, 5000);

        long started = System.nanoTime();
        Decision d = client(300).createPost(sampleCommand());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertFalse(d.isAccepted());
        assertEquals("DECIDER_UNAVAILABLE", d.getErrorCode());
        assertTrue(elapsedMs < 3000,
                "ожидание длилось " + elapsedMs + " мс при сроке 300: "
                        + "срок ответа не действует");
    }

    /** Гейтвей отвечает, но не тем. */
    @Test
    @DisplayName("ответ не по контракту даёт отказ из контракта")
    void garbageResponseBecomesUnavailable() {
        answerWith(200, "<html><body>502 Bad Gateway</body></html>", 0);

        Decision d = client(5000).createPost(sampleCommand());
        assertFalse(d.isAccepted());
        assertEquals("DECIDER_UNAVAILABLE", d.getErrorCode());
    }

    /** Гейтвей отвечает ошибкой HTTP. */
    @Test
    @DisplayName("ошибка HTTP от гейтвея даёт отказ из контракта")
    void httpErrorBecomesUnavailable() {
        answerWith(503, "<html>unavailable</html>", 0);

        Decision d = client(5000).createPost(sampleCommand());
        assertFalse(d.isAccepted());
        assertEquals("DECIDER_UNAVAILABLE", d.getErrorCode());
    }

    /**
     * Проверка здоровья обязана сломаться, а не соврать.
     *
     * <p>У {@code ping} нет формы «не получилось»: контракт возвращает
     * метрики, и выдуманные нули означали бы «нода жива, просто пустая».
     * Поэтому отказ пробрасывается — единственный метод, где так.
     */
    @Test
    @DisplayName("недоступный пинг проваливается, а не возвращает нули")
    void pingFailsLoudly() throws Exception {
        int dead;
        try (ServerSocket probe = new ServerSocket(0)) {
            dead = probe.getLocalPort();
        }
        String nowhere = "http://127.0.0.1:" + dead + "/decider";

        final Decider client = DeciderFaults.wrap(
                DeciderConfig.soapClient(nowhere, 1000, 1000), nowhere);

        assertThrows(Exception.class,
                () -> client.ping(new PingRequest()),
                "пинг к мёртвому адресу ответил успехом");
    }
}
