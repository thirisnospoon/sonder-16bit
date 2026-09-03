package sonder.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import sonder.gateway.line.LineMux;
import sonder.gateway.line.LineServer;
import sonder.gateway.soap.LineDecider;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Вся цепочка целиком: команда доезжает до НАСТОЯЩЕЙ ноды под DOSBox и
 * возвращается решением.
 *
 * <p>До сих пор каждое звено проверялось по отдельности, а стыки — по
 * эталонам. Здесь ничего не подменено вовсе: 16-битная программа
 * исполняется эмулятором, кадры идут через нульмодем, конверт разбирает
 * {@code tcsoap}, решение принимает {@code dmdecide}. Если между звеньями
 * есть щель, она видна только здесь.
 *
 * <p><b>Запускается отдельно</b> — {@code ./sonder e2e} — потому что
 * требует и эмулятора, и того, чтобы обе стороны поднялись в правильном
 * порядке. Обычный прогон обязан оставаться герметичным.
 *
 * <p>Порт приходит снаружи: его выбирает скрипт, он же говорит DOSBox,
 * куда подключаться нульмодемом.
 */
@Tag("e2e")
class NodeE2EIT {

    /** Сколько ждать ноду: DOSBox поднимается неспешно. */
    private static final long CONNECT_DEADLINE_MS = 30000;

    /** Сколько ждать решения. Линия медленная, ядро быстрое. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

    private static int port() {
        return Integer.getInteger("sonder.e2e.port", 0);
    }

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

    @Test
    @DisplayName("настоящая нода под DOSBox принимает команду и решает")
    void realNodeDecides() throws Exception {
        assumeTrue(port() > 0,
                "нет sonder.e2e.port — запускать через ./sonder e2e");

        AtomicReference<LineMux> muxRef = new AtomicReference<>();
        ConcurrentLinkedQueue<Frame> control = new ConcurrentLinkedQueue<>();

        try (LineServer line = new LineServer(port(),
                frame -> {
                    LineMux m = muxRef.get();
                    if (m != null) {
                        m.onFrame(frame);
                    }
                },
                () -> { })) {

            LineMux mux = new LineMux(line::write, control::add);
            muxRef.set(mux);
            line.start();

            // Нода подключается сама: DOSBox поднят скриптом и идёт
            // нульмодемом на этот порт.
            long deadline = System.nanoTime()
                    + CONNECT_DEADLINE_MS * 1_000_000L;
            while (!line.isConnected() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertTrue(line.isConnected(),
                    "нода не подключилась за " + CONNECT_DEADLINE_MS
                            + " мс: DOSBox не поднялся или идёт не на тот порт");

            // Приветствие приходит по управляющему каналу: драйвер порта
            // повторяет его, пока другая сторона молчит.
            deadline = System.nanoTime() + CONNECT_DEADLINE_MS * 1_000_000L;
            while (control.isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertFalse(control.isEmpty(),
                    "нода не поздоровалась: линия открыта, но кадров нет");
            Frame hello = control.peek();
            assertEquals(Frame.CHAN_CONTROL, hello.getChannel(),
                    "первый кадр пришёл не по управляющему каналу");
            assertTrue(hello.hasFlag(Frame.FLAG_HELLO),
                    "первый кадр не приветствие: " + hello);

            LineDecider decider = new LineDecider(mux, CALL_TIMEOUT);

            // ГЛАВНОЕ. Команда уезжает эталонными байтами, ядро их
            // разбирает своим tcsoap и решает своим dmdecide.
            Decision decision = decider.createPost(goldenRequest());

            assertTrue(decision.isAccepted(),
                    "ядро отказало там, где должно было принять: "
                            + decision.getErrorCode());
            assertEquals(1, decision.getEvent().size(),
                    "решение без события: пост создан, а мир об этом не узнает");
            DomainEvent event = decision.getEvent().get(0);
            assertEquals("post.created", event.getType());
            assertEquals("p-1001", event.getAggregateId());
            assertEquals("authorId", event.getField().get(0).getKey());
            assertEquals("u-andrey", event.getField().get(0).getValue());

            // Пинг: ядро отвечает СВОИМИ метриками, а не выдуманными и
            // не подставленными одна вместо другой.
            PingRequest ping = new PingRequest();
            ping.setNonce(4242);
            PingResponse pong = decider.ping(ping);
            assertEquals(4242, pong.getNonce(),
                    "нода вернула чужой нонс: ответ пришёл не на наш вызов");

            // Эталонный конверт доказывает форму ответа, но не то, что
            // числа в нём живые: эталон пишет тест, а не нода. Живость
            // проверяется здесь и только здесь.
            assertEquals(1, pong.getCommandsServed(),
                    "нода насчитала не одну обслуженную команду, хотя до "
                            + "пинга была ровно одна");
            assertEquals(0, pong.getCommandsRefused(), "нода кому-то отказала");
            assertEquals(0, pong.getCommandsMalformed(),
                    "нода сочла наш конверт неразборным");

            assertTrue(pong.getArenaCapacity() > 0,
                    "ёмкость арены нулевая: пик без неё не значит ничего");
            assertTrue(pong.getArenaHighMark() <= pong.getArenaCapacity(),
                    "пик арены больше её ёмкости: "
                            + pong.getArenaHighMark() + " из "
                            + pong.getArenaCapacity());
            // ГЛАВНОЕ ЧИСЛО. На месте пика раньше уезжало количество
            // обслуженных команд — величина правдоподобная и растущая, а
            // потому снаружи неотличимая. Один разобранный запрос с
            // событием занимает в арене сотни байт, и никак не единицу:
            // на подмене эта проверка краснеет.
            assertTrue(pong.getArenaHighMark() >= 64,
                    "пик арены " + pong.getArenaHighMark() + " — столько не "
                            + "занимает даже разобранный запрос. Похоже, "
                            + "вместо пика уехал какой-то счётчик");

            assertTrue(pong.getTxBytes() > 0 && pong.getRxBytes() > 0,
                    "нода насчитала ноль байт на линии, по которой к ней "
                            + "приехали две команды");
            assertEquals(0, pong.getLineErrors(),
                    "ошибки линии: кадры рвутся так же, как рвались до "
                            + "перестановки проверки готовности передатчика");

            // ОТКАЗ доезжает так же, как согласие. Заблокированный автор
            // — правило домена, и решает его ядро, а не гейтвей: тот про
            // блокировки не знает ничего.
            CreatePostRequest banned = goldenRequest();
            banned.getActor().setStatus(UserStatus.BANNED);
            banned.getCommand().setPostId("p-1002");
            Decision refusal = decider.createPost(banned);

            assertFalse(refusal.isAccepted(),
                    "ядро приняло команду от заблокированного");
            assertNotNull(refusal.getErrorCode(), "отказ без кода");
            assertEquals(0, refusal.getEvent().size(),
                    "отказ породил событие: мир узнал бы о том, чего не было");
            // Код обязан быть из контракта, а не выдуманным: valueOf
            // упадёт на чужом.
            sonder.contract.ErrorCode known =
                    sonder.contract.ErrorCode.valueOf(refusal.getErrorCode());
            assertNotNull(known);

            assertTrue(line.getBytesIn() > 0 && line.getBytesOut() > 0,
                    "байты не ходили в обе стороны");
            assertEquals(0, mux.getUnrouted(),
                    "нода прислала кадр на канал, которого никто не занимал");
            assertEquals(0, mux.getRefused(),
                    "каналов не хватило на три команды подряд");
        }
    }
}
