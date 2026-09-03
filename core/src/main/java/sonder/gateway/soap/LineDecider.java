package sonder.gateway.soap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import sonder.gateway.line.LineMux;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ядро за линией: тот же интерфейс контракта, только вызов уезжает в
 * кадрах.
 *
 * <p>Это и есть работа гейтвея: терминировать SOAP и переупаковать вызов
 * в линию (ADR-0007). Оболочка при этом не знает, что ядро далеко, —
 * интерфейс один и тот же, порождённый CXF из
 * {@code decider-v1.wsdl}.
 *
 * <p><b>Имя элемента берётся у класса запроса, а не из таблицы.</b> Классы
 * порождены из WSDL по именам элементов, поэтому
 * {@code CreatePostRequest.class.getSimpleName()} и есть имя элемента.
 * Таблица «операция → имя» была бы третьим списком тех же имён — после
 * WSDL и порождённых классов, — и разошлась бы молча. Что совпадение не
 * случайно, проверяется сверкой с манифестом операций.
 *
 * <p><b>Отказ линии пробрасывается как есть.</b> Переводить его в
 * решение здесь незачем: оболочка уже умеет это делать, и делает по
 * контракту — {@code DECIDER_UNAVAILABLE}. Гейтвей, придумавший своё
 * решение, соврал бы от имени ядра.
 */
public final class LineDecider implements Decider {

    private static final Logger log = LoggerFactory.getLogger(LineDecider.class);

    private final LineMux mux;
    private final Duration timeout;

    public LineDecider(LineMux mux, Duration timeout) {
        this.mux = mux;
        this.timeout = timeout;
    }

    private <T> Decision decide(Class<T> type, T request) {
        return call(type, request, Decision.class);
    }

    private <T, R> R call(Class<T> type, T request, Class<R> replyType) {
        byte[] envelope = Envelopes.wrap(type, type.getSimpleName(), request);
        CompletableFuture<byte[]> reply = mux.send(envelope, Instant.now());
        try {
            byte[] answer = reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Envelopes.unwrap(replyType, answer);
        } catch (TimeoutException e) {
            // Срок вышел. Канал освободит уборка по сроку — здесь только
            // сообщаем вызывающему, а решать, повторять ли, ему.
            throw new LineCallFailed(
                    "нода не ответила за " + timeout + " на "
                            + type.getSimpleName(), e);
        } catch (ExecutionException e) {
            throw new LineCallFailed(
                    "линия не донесла " + type.getSimpleName() + ": "
                            + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LineCallFailed("ожидание ответа прервано", e);
        }
    }

    @Override
    public Decision registerUser(RegisterUserRequest r) {
        return decide(RegisterUserRequest.class, r);
    }

    @Override
    public Decision createPost(CreatePostRequest r) {
        return decide(CreatePostRequest.class, r);
    }

    @Override
    public Decision createComment(CreateCommentRequest r) {
        return decide(CreateCommentRequest.class, r);
    }

    @Override
    public Decision deletePost(DeletePostRequest r) {
        return decide(DeletePostRequest.class, r);
    }

    @Override
    public Decision followUser(FollowUserRequest r) {
        return decide(FollowUserRequest.class, r);
    }

    @Override
    public Decision unfollowUser(UnfollowUserRequest r) {
        return decide(UnfollowUserRequest.class, r);
    }

    @Override
    public Decision banUser(BanUserRequest r) {
        return decide(BanUserRequest.class, r);
    }

    @Override
    public PingResponse ping(PingRequest r) {
        return call(PingRequest.class, r, PingResponse.class);
    }

    /** Вызов не дошёл или не вернулся. */
    public static final class LineCallFailed extends RuntimeException {
        public LineCallFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
