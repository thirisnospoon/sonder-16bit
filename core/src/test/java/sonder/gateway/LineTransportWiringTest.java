package sonder.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import sonder.contract.ErrorCode;
import sonder.contract.decider.ActorContext;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.CreatePostCommand;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.Role;
import sonder.contract.decider.UserStatus;
import sonder.gateway.line.Frame;
import sonder.gateway.line.FrameCodec;
import sonder.gateway.line.FrameDecoder;
import sonder.shell.decider.DeciderConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Линия в приложении: путь от бина {@code Decider} до сокета.
 *
 * <p><b>Зачем.</b> Транспорт был написан и проверен по частям — сервер,
 * мультиплексор, связыватель конвертов, — но в приложении к нему не вёл
 * ни один путь: ни одна строка производственного кода не создавала
 * {@link sonder.gateway.line.LineServer}. В бою на месте ядра стоял
 * {@code UnavailableDecider} и честно отвечал 502, хотя мост был готов.
 * Проверенный код, до которого не доходит исполнение, работой не
 * считается.
 *
 * <p>Здесь поднимается настоящий контекст с настоящими конфигурациями, и
 * команда идёт бином {@code Decider} → {@code LineDecider} → кадры →
 * сокет. На другом конце сокета — подставная нода, но отвечает она
 * ЭТАЛОННЫМ конвертом, порождённым настоящим писателем ядра
 * ({@code dosnode/tools/mkreplies.pas}). Выдуманного в цепочке нет
 * ничего, кроме самого факта, что байты пишет тест, а не DOSBox.
 *
 * <p>Полная цепочка с эмулятором — {@code ./sonder e2e}; она требует
 * DOSBox и потому живёт отдельно. Эта проверка герметична и идёт в
 * обычном прогоне.
 */
class LineTransportWiringTest {

    private static final File REPLIES =
            new File("../contracts/generated/replies/replies.bin");

    /** Первый эталон: принято, одно событие, два поля. */
    private static byte[] goldenAccepted() throws IOException {
        assertTrue(REPLIES.isFile(),
                "нет эталонных ответов: " + REPLIES.getAbsolutePath());
        byte[] all = Files.readAllBytes(REPLIES.toPath());
        int len = (all[0] & 0xFF) | ((all[1] & 0xFF) << 8);
        byte[] one = new byte[len];
        System.arraycopy(all, 2, one, 0, len);
        return one;
    }

    /** Пятый эталон: ответ на пинг со всеми метриками. */
    private static byte[] goldenPong() throws IOException {
        byte[] all = Files.readAllBytes(REPLIES.toPath());
        int i = 0;
        byte[] one = new byte[0];
        for (int n = 0; n < 5 && i + 2 <= all.length; n++) {
            int len = (all[i] & 0xFF) | ((all[i + 1] & 0xFF) << 8);
            i += 2;
            one = new byte[len];
            System.arraycopy(all, i, one, 0, len);
            i += len;
        }
        assertTrue(new String(one, StandardCharsets.UTF_8).contains("PingResponse"),
                "пятый эталон оказался не ответом на пинг");
        return one;
    }

    /** Расписание включается отдельно: оно свойство приложения, не линии. */
    @Configuration
    @EnableScheduling
    static class Clockwork {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(LineTransportConfig.class, DeciderConfig.class);
    }

    private static CreatePostRequest request() {
        CommandMeta meta = new CommandMeta();
        meta.setTraceId("t-1");
        meta.setCommandId("c-1");
        meta.setIssuedAtMillis(1756684800000L);

        CreatePostCommand command = new CreatePostCommand();
        command.setPostId("p-1001");
        command.setBody("Первый пост");

        ActorContext actor = new ActorContext();
        actor.setUserId("u-andrey");
        actor.setRole(Role.USER);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setPostsLastHour(0);
        actor.setCommentsLastHour(0);

        CreatePostRequest r = new CreatePostRequest();
        r.setMeta(meta);
        r.setCommand(command);
        r.setActor(actor);
        return r;
    }

    @Test
    @DisplayName("без настроек линии её в контексте нет, а команды отвечают отказом")
    void withoutPortThereIsNoLine() {
        runner().run(context -> {
            assertNull(context.getStartupFailure(),
                    "контекст без линии обязан подниматься");
            assertEquals(0, context.getBeanNamesForType(LineTransport.class).length,
                    "линия поднялась без настройки: порт занят молча");

            Decision d = context.getBean(Decider.class).createPost(request());
            assertFalse(d.isAccepted());
            assertEquals(ErrorCode.DECIDER_UNAVAILABLE.name(), d.getErrorCode());
        });
    }

    @Test
    @DisplayName("порт объявлен пустым — линии нет, и запуск не падает")
    void emptyPortMeansNoLine() {
        // Пустая строка — обычный способ выключить настройку в этом
        // проекте: ${SONDER_DECIDER_LINE_PORT:}. Готовый
        // @ConditionalOnProperty счёл бы её заданной, бин полез бы
        // разбирать пустое число, и ВЫКЛЮЧЕННАЯ линия уронила бы запуск.
        runner()
                .withPropertyValues("sonder.decider.line.port=")
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "пустой порт уронил запуск: выключенная "
                                    + "настройка обязана значить отсутствие");
                    assertEquals(0,
                            context.getBeanNamesForType(LineTransport.class).length,
                            "линия поднялась на пустом порту");
                });
    }

    @Test
    @DisplayName("линия и адрес вместе роняют запуск, а не выбираются наугад")
    void bothTransportsRefuseToStart() {
        runner()
                .withPropertyValues(
                        "sonder.decider.line.port=0",
                        "sonder.decider.endpoint=http://127.0.0.1:1/decider")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure,
                            "приложение поднялось с двумя ядрами сразу: "
                                    + "команды уехали бы неизвестно куда");
                    // Причина лежит в глубине цепочки: Spring
                    // заворачивает отказ бина в свои исключения, и сверять
                    // только верхнее значило бы принимать за успех любое
                    // падение запуска.
                    String text = causes(failure);
                    assertTrue(text.contains("какое из двух ядер настоящее"),
                            "запуск упал не по той причине: " + text);
                });
    }

    @Test
    @DisplayName("линия поднята: команда доезжает до ноды и возвращается решением")
    void commandTravelsThroughTheLine() {
        runner()
                .withPropertyValues(
                        "sonder.decider.line.port=0",
                        "sonder.decider.line.timeout-ms=5000")
                .run(context -> {
                    assertEquals(1,
                            context.getBeanNamesForType(LineTransport.class).length,
                            "линия не поднялась, хотя порт задан");

                    LineTransport line = context.getBean(LineTransport.class);
                    assertTrue(line.getPort() > 0, "порт не выдан");

                    try (FakeNode node = new FakeNode(line.getPort(), goldenAccepted())) {
                        await(line::isConnected, "нода не подключилась");

                        Decision d = context.getBean(Decider.class)
                                .createPost(request());

                        assertTrue(d.isAccepted(),
                                "решение не доехало: " + d.getErrorCode()
                                        + " / " + d.getErrorDetail());
                        assertEquals(1, d.getEvent().size(),
                                "событие потерялось по дороге");
                        assertEquals("post.created", d.getEvent().get(0).getType());
                        assertEquals("authorId",
                                d.getEvent().get(0).getField().get(0).getKey());
                        assertEquals("u-andrey",
                                d.getEvent().get(0).getField().get(0).getValue());

                        // Команда действительно уехала кадрами, а не была
                        // подсунута в обход линии.
                        assertTrue(node.requestBytes() > 0,
                                "нода не получила ни байта запроса");
                        assertTrue(line.getBytesOut() > 0 && line.getBytesIn() > 0,
                                "байты не ходили в обе стороны");
                        assertEquals(0, line.inFlight(),
                                "канал остался занятым после ответа");
                    }
                });
    }

    @Test
    @DisplayName("нода молчит: команда отваливается отказом, а канал освобождает уборщик")
    void silentNodeFreesItsChannel() {
        runner()
                .withPropertyValues(
                        "sonder.decider.line.port=0",
                        // Короче периода уборки: сперва сдаётся ждущий,
                        // потом подметается канал — именно этот порядок и
                        // проверяется.
                        "sonder.decider.line.timeout-ms=200")
                .run(context -> {
                    LineTransport line = context.getBean(LineTransport.class);

                    // Нода подключилась и молчит. Это хуже, чем её
                    // отсутствие: сокет жив, и записи проходят.
                    try (FakeNode mute = new FakeNode(line.getPort(), null)) {
                        await(line::isConnected, "нода не подключилась");

                        Decision d = context.getBean(Decider.class)
                                .createPost(request());
                        assertFalse(d.isAccepted(), "молчание сошло за согласие");
                        assertEquals(ErrorCode.DECIDER_UNAVAILABLE.name(),
                                d.getErrorCode());

                        // Без уборщика канал остался бы занятым навсегда, и
                        // после шестнадцати таких команд гейтвей встал бы
                        // молча. Ждущий уже сдался — а канал занят.
                        await(() -> line.inFlight() == 0,
                                "канал не освобождён: уборщик не работает");
                    }
                });
    }

    @Test
    @DisplayName("без линии здоровья ноды нет вовсе, а не «плохо»")
    void withoutLineThereIsNoHealth() {
        // Красная лампа, горящая по устройству на стенде без эмулятора,
        // погасла бы в глазах людей за неделю.
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeanNamesForType(NodeHealth.class).length,
                    "показатель здоровья ноды есть, а ноды нет");
            assertEquals(0, context.getBeanNamesForType(NodeProbe.class).length,
                    "опрос ноды заведён, а спрашивать некого");
        });
    }

    @Test
    @DisplayName("часы сами доводят опрос до ноды, и здоровье становится UP")
    void probeReachesTheNodeOnItsOwn() {
        // Опрос, который никто не запускает, — это те же метрики, до
        // которых не доходит исполнение. Здесь его не зовёт тест: ждём,
        // пока сработают часы приложения.
        new ApplicationContextRunner()
                .withUserConfiguration(Clockwork.class,
                        LineTransportConfig.class, DeciderConfig.class)
                .withPropertyValues(
                        "sonder.decider.line.port=0",
                        "sonder.decider.line.timeout-ms=2000",
                        "sonder.decider.line.probe-initial-ms=50",
                        "sonder.decider.line.probe-ms=100",
                        "sonder.decider.line.stale-ms=30000")
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            String.valueOf(context.getStartupFailure()));
                    LineTransport line = context.getBean(LineTransport.class);
                    NodeHealth health = context.getBean(NodeHealth.class);

                    try (FakeNode node = new FakeNode(line.getPort(),
                            goldenAccepted(), goldenPong())) {
                        await(line::isConnected, "нода не подключилась");
                        await(() -> health.health().getStatus() == Status.UP,
                                "здоровье не поднялось: опрос до ноды не дошёл");

                        // Числа те, что нода прислала эталонным конвертом.
                        assertEquals(1024,
                                health.health().getDetails().get("arenaHighMark"));
                        assertEquals(2048,
                                health.health().getDetails().get("arenaCapacity"));
                        assertEquals(17,
                                health.health().getDetails().get("commandsServed"));
                        assertTrue(node.pings() > 0, "нода не получила ни пинга");
                    }
                });
    }

    /** Ждать условия не дольше пяти секунд: линия локальная и быстрая. */
    private static void await(java.util.function.BooleanSupplier condition,
                              String message) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError(message);
    }

    /** Вся цепочка причин одной строкой. */
    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append(" <- ");
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Подставная нода: читает кадры и отвечает эталонным конвертом.
     *
     * <p>Отвечает на тот же канал, на который пришла команда, — номера
     * каналов раздаёт гейтвей (ADR-0015), и нода их только повторяет.
     * Ответ без флага {@code FLAG_MORE} на последнем кадре означает
     * «сообщение кончилось».
     */
    private static final class FakeNode implements AutoCloseable {

        private final Socket socket;
        private final Thread thread;
        private final byte[] reply;
        private final byte[] pong;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private volatile long got;
        private volatile int pings;

        FakeNode(int port, byte[] reply) throws IOException {
            this(port, reply, null);
        }

        FakeNode(int port, byte[] reply, byte[] pong) throws IOException {
            this.reply = reply;
            this.pong = pong;
            this.socket = new Socket("127.0.0.1", port);
            this.thread = new Thread(this::loop, "fake-node");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        long requestBytes() {
            return got;
        }

        int pings() {
            return pings;
        }

        /**
         * Ответ на пинг несёт СПРОШЕННЫЙ нонс.
         *
         * <p>Эталон записан с одним нонсом навсегда, а опрос
         * спрашивает случайным и сверяет — иначе перепутанные между
         * каналами ответы прошли бы за свои. Настоящая нода отвечает тем
         * же нонсом; здесь он подставляется в эталон, и это ровно то же
         * самое действие.
         */
        private byte[] pongFor(String request) {
            int a = request.indexOf("<nonce>");
            int b = request.indexOf("</nonce>", a);
            String nonce = request.substring(a + "<nonce>".length(), b);
            String text = new String(pong, StandardCharsets.UTF_8)
                    .replaceFirst("<nonce>[0-9-]+</nonce>",
                            "<nonce>" + nonce + "</nonce>");
            return text.getBytes(StandardCharsets.UTF_8);
        }

        private void loop() {
            FrameDecoder decoder = new FrameDecoder();
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream request =
                    new java.io.ByteArrayOutputStream();
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int n;
                while ((n = in.read(buffer)) > 0) {
                    got += n;
                    for (Frame frame : decoder.feed(buffer, 0, n)) {
                        request.write(frame.getPayload(), 0,
                                frame.getPayload().length);
                        // Отвечаем на последний кадр сообщения: пока идёт
                        // FLAG_MORE, команда ещё не приехала целиком.
                        if (frame.hasFlag(Frame.FLAG_MORE)) {
                            continue;
                        }
                        String text = new String(request.toByteArray(),
                                StandardCharsets.UTF_8);
                        request.reset();

                        byte[] answer = reply;
                        if (text.contains("PingRequest")) {
                            pings++;
                            answer = pong == null ? null : pongFor(text);
                        }
                        if (answer == null) {
                            continue;
                        }
                        for (Frame back : replyFrames(frame.getChannel(), answer)) {
                            out.write(FrameCodec.encode(back));
                        }
                        out.flush();
                    }
                }
            } catch (IOException expected) {
                // Закрытие сокета — обычный конец жизни подставной ноды.
            } finally {
                stopped.countDown();
            }
        }

        private List<Frame> replyFrames(int channel, byte[] payload) {
            List<Frame> out = new ArrayList<>();
            int offset = 0;
            do {
                int take = Math.min(Frame.MAX_PAYLOAD, payload.length - offset);
                byte[] chunk = new byte[take];
                System.arraycopy(payload, offset, chunk, 0, take);
                offset += take;
                boolean last = offset >= payload.length;
                out.add(new Frame(channel, last ? 0 : Frame.FLAG_MORE, chunk));
            } while (offset < payload.length);
            return out;
        }

        @Override
        public void close() throws Exception {
            socket.close();
            stopped.await(2, TimeUnit.SECONDS);
        }
    }
}
