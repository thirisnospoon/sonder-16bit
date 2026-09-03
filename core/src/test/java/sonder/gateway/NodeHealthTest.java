package sonder.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import sonder.contract.decider.BanUserRequest;
import sonder.contract.decider.CreateCommentRequest;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DeletePostRequest;
import sonder.contract.decider.FollowUserRequest;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PingResponse;
import sonder.contract.decider.RegisterUserRequest;
import sonder.contract.decider.UnfollowUserRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Здоровье ноды: что показывается наружу и когда этому можно верить.
 *
 * <p>Метрики NODE-7 существовали и никуда не уходили: их отдавала только
 * команда {@code ping}, которую никто не звал. Здесь проверяется, что
 * теперь зовут — и, важнее, что показанному можно верить.
 *
 * <p><b>Главная проверка тут про ПРОТУХАНИЕ.</b> Замолчавшая нода
 * оставляет последний ответ нетронутым: он полон, правдоподобен и без
 * времени снятия неотличим от свежего. Показанный как «UP», он врёт
 * ровно тогда, когда правда нужнее всего, — и это тот же класс лжи, что
 * и метрика, отвечавшая счётчиком команд вместо пика арены.
 */
class NodeHealthTest {

    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(45);

    /**
     * Ядро, отвечающее на пинг тем, что велено.
     *
     * <p>Реализует интерфейс контракта целиком: подменять надо ровно ту
     * операцию, ради которой тест написан, а остальные обязаны
     * существовать — иначе подмена перестала бы быть тем же ядром.
     */
    private static final class StubDecider implements Decider {

        private final AtomicReference<PingResponse> answer = new AtomicReference<>();
        private final AtomicReference<RuntimeException> failure =
                new AtomicReference<>();
        private final AtomicInteger asked = new AtomicInteger();
        /** Отвечать своим нонсом вместо спрошенного. */
        private volatile boolean echoNonce = true;

        @Override
        public PingResponse ping(PingRequest r) {
            asked.incrementAndGet();
            RuntimeException boom = failure.get();
            if (boom != null) {
                throw boom;
            }
            PingResponse out = answer.get();
            if (echoNonce) {
                out.setNonce(r.getNonce());
            }
            return out;
        }

        @Override
        public Decision registerUser(RegisterUserRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Decision createPost(CreatePostRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Decision createComment(CreateCommentRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Decision deletePost(DeletePostRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Decision followUser(FollowUserRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Decision unfollowUser(UnfollowUserRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Decision banUser(BanUserRequest r) {
            throw new UnsupportedOperationException();
        }
    }

    /** Метрики живой ноды: все разные, чтобы перепутанные было видно. */
    private static PingResponse liveMetrics() {
        PingResponse p = new PingResponse();
        p.setFibersInUse(3);
        p.setArenaHighMark(162);
        p.setArenaCapacity(2048);
        p.setCommandsServed(17);
        p.setCommandsRefused(2);
        p.setCommandsMalformed(1);
        p.setLineErrors(0);
        p.setRxBytes(90123);
        p.setTxBytes(45061);
        return p;
    }

    private static StubDecider liveNode() {
        StubDecider node = new StubDecider();
        node.answer.set(liveMetrics());
        return node;
    }

    @Test
    @DisplayName("живая нода: здоровье UP и её настоящие числа")
    void liveNodeIsUp() {
        StubDecider node = liveNode();
        NodeProbe probe = new NodeProbe(node, STALE_AFTER);
        NodeHealth health = new NodeHealth(probe, () -> T0);

        probe.probe(T0);
        Health h = health.health();

        assertEquals(Status.UP, h.getStatus());
        assertEquals(1, node.asked.get(), "ноду не спросили");

        // Числа показываются те, что она прислала, а не какие-нибудь.
        assertEquals(162, h.getDetails().get("arenaHighMark"));
        assertEquals(2048, h.getDetails().get("arenaCapacity"),
                "пик показан без ёмкости: сам по себе он не значит ничего");
        assertEquals(17, h.getDetails().get("commandsServed"));
        assertEquals(90123, h.getDetails().get("rxBytes"));
        assertEquals(45061, h.getDetails().get("txBytes"));
    }

    @Test
    @DisplayName("протухший снимок — это НЕ здоровье")
    void staleSnapshotIsDown() {
        // Ровно та ложь, ради которой всё это писалось. Нода ответила и
        // замолчала; ответ остался полным и правдоподобным. Отличает его
        // от свежего только время снятия.
        StubDecider node = liveNode();
        NodeProbe probe = new NodeProbe(node, STALE_AFTER);

        probe.probe(T0);

        AtomicReference<Instant> now = new AtomicReference<>(T0);
        NodeHealth health = new NodeHealth(probe, now::get);

        now.set(T0.plus(STALE_AFTER));
        assertEquals(Status.UP, health.health().getStatus(),
                "снимок объявлен протухшим ровно на границе срока");

        now.set(T0.plus(STALE_AFTER).plusMillis(1));
        Health h = health.health();
        assertEquals(Status.DOWN, h.getStatus(),
                "протухший снимок сошёл за здоровье: система сказала бы "
                        + "«всё хорошо» о замолчавшей ноде");
        assertEquals("снимок протух", h.getDetails().get("нода"));
        // Числа протухшего снимка наружу не идут: показанные рядом с
        // DOWN, они всё равно были бы прочитаны как текущие.
        assertFalse(h.getDetails().containsKey("arenaHighMark"),
                "наружу ушли числа, которым нельзя верить");
    }

    @Test
    @DisplayName("нода не ответила: здоровье DOWN с причиной, а не с нулями")
    void silentNodeIsDown() {
        StubDecider node = liveNode();
        node.failure.set(new IllegalStateException("линия оборвана"));

        NodeProbe probe = new NodeProbe(node, STALE_AFTER);
        NodeHealth health = new NodeHealth(probe, () -> T0);

        probe.probe(T0);
        Health h = health.health();

        assertEquals(Status.DOWN, h.getStatus());
        assertEquals("не ответила", h.getDetails().get("нода"));
        assertTrue(String.valueOf(h.getDetails().get("причина"))
                        .contains("линия оборвана"),
                "причина потерялась: " + h.getDetails());
        // Выдуманные нули соврали бы этой проверке ровно так, как если бы
        // их написали руками.
        assertFalse(h.getDetails().containsKey("arenaHighMark"),
                "вместо метрик показаны нули: отказ выдан за ответ");
    }

    @Test
    @DisplayName("чужой нонс — это не ответ")
    void foreignNonceIsNotAnAnswer() {
        // Ответ с чужим нонсом означает, что ответы перепутались между
        // каналами. Он выглядит как настоящий, и в этом вся беда: принять
        // его значило бы записать в метрики чужую правду.
        StubDecider node = liveNode();
        node.echoNonce = false;
        node.answer.get().setNonce(999);

        NodeProbe probe = new NodeProbe(node, STALE_AFTER);
        NodeHealth health = new NodeHealth(probe, () -> T0);

        probe.probe(T0);
        Health h = health.health();

        assertEquals(Status.DOWN, h.getStatus(),
                "чужой ответ принят за свой");
        assertTrue(String.valueOf(h.getDetails().get("причина")).contains("нонс"),
                "причина не про нонс: " + h.getDetails());
    }

    @Test
    @DisplayName("до первого опроса здоровье неизвестно, а не плохо")
    void beforeFirstProbeUnknown() {
        // Красная лампа, горящая по устройству первые секунды после
        // запуска, гаснет в глазах людей за неделю.
        NodeProbe probe = new NodeProbe(liveNode(), STALE_AFTER);
        Health h = new NodeHealth(probe, () -> T0).health();

        assertEquals(Status.UNKNOWN, h.getStatus());
        assertNotNull(h.getDetails().get("нода"));
    }

    @Test
    @DisplayName("нода ожила: здоровье возвращается само")
    void recoveryIsSeen() {
        // Показатель, который однажды упал и больше не встаёт, ничем не
        // лучше отсутствующего: его перестают смотреть.
        StubDecider node = liveNode();
        node.failure.set(new IllegalStateException("линия оборвана"));

        NodeProbe probe = new NodeProbe(node, STALE_AFTER);
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        NodeHealth health = new NodeHealth(probe, now::get);

        probe.probe(now.get());
        assertEquals(Status.DOWN, health.health().getStatus());

        node.failure.set(null);
        now.set(T0.plusSeconds(15));
        probe.probe(now.get());

        assertEquals(Status.UP, health.health().getStatus(),
                "нода отвечает, а здоровье осталось лежать");
        assertEquals(162, health.health().getDetails().get("arenaHighMark"));
    }
}
