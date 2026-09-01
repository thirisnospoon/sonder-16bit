package sonder.shell.app;

import sonder.contract.decider.ActorContext;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DeletePostCommand;
import sonder.contract.decider.DeletePostRequest;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.PostContext;
import sonder.contract.decider.PostStatus;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.state.StateLoader;
import sonder.shell.store.PostStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Удаление поста: полный ход команды через ядро.
 *
 * <p>Здесь нет ни одного доменного правила, и это проверяется ArchUnit, а
 * не подразумевается. Может ли автор удалить чужой пост, что делать с уже
 * удалённым, кто из модераторов старше — решает NODE-7. Задача этого
 * класса: собрать объявленное контрактом состояние, донести его до ядра и
 * записать то, что ядро решило.
 *
 * <p>Удаление — смена статуса, а не удаление строки: событие
 * {@code post.deleted} уже уйдёт в outbox, и обработчику может
 * понадобиться то, что он обрабатывает. Строка, исчезнувшая раньше своего
 * события, даёт гонку, которая проявляется только под нагрузкой.
 */
public final class DeletePostHandler {

    /** Что вышло из хода команды. Отказ здесь — нормальный исход. */
    public static final class Outcome {
        private final boolean accepted;
        private final String errorCode;
        private final int eventsWritten;

        Outcome(boolean accepted, String errorCode, int eventsWritten) {
            this.accepted = accepted;
            this.errorCode = errorCode;
            this.eventsWritten = eventsWritten;
        }

        public boolean isAccepted() {
            return accepted;
        }

        /** Код отказа из решения ядра. {@code null}, если принято. */
        public String getErrorCode() {
            return errorCode;
        }

        public int getEventsWritten() {
            return eventsWritten;
        }
    }

    /** Состояние, прочитанное в первой фазе. */
    static final class State {
        final ActorContext actor;
        final PostContext post;

        State(ActorContext actor, PostContext post) {
            this.actor = actor;
            this.post = post;
        }
    }

    private final CommandFlow flow;
    private final Decider decider;

    public DeletePostHandler(CommandFlow flow, Decider decider) {
        this.flow = flow;
        this.decider = decider;
    }

    public Outcome handle(String actorId, String postId,
                          String traceId, String commandId, Instant now)
            throws Exception {
        return flow.run(
                c -> new State(
                        StateLoader.loadActor(c, actorId, now),
                        StateLoader.loadPost(c, postId)),
                state -> decider.deletePost(request(
                        state, postId, traceId, commandId, now)),
                (c, state, decision) -> apply(c, state, decision, traceId));
    }

    private static DeletePostRequest request(State state, String postId,
                                             String traceId, String commandId,
                                             Instant now) {
        CommandMeta meta = new CommandMeta();
        meta.setTraceId(traceId);
        meta.setCommandId(commandId);
        meta.setIssuedAtMillis(now.toEpochMilli());

        DeletePostCommand command = new DeletePostCommand();
        command.setPostId(postId);

        DeletePostRequest request = new DeletePostRequest();
        request.setMeta(meta);
        request.setCommand(command);
        request.setActor(state.actor);
        request.setPost(state.post);
        return request;
    }

    /**
     * Применить решение. Отказ — не исключение: ядро отказывает штатно, и
     * оболочке остаётся не записать ничего и передать код дальше.
     */
    private static Outcome apply(Connection c, State state, Decision decision,
                                 String traceId)
            throws SQLException, VersionConflict {
        if (!decision.isAccepted()) {
            return new Outcome(false, decision.getErrorCode(), 0);
        }

        // Версия та, что прочитана в первой фазе. Если она сдвинулась,
        // решение принято по устаревшему состоянию — ход повторяется.
        PostStore.updateStatus(c, state.post.getPostId(),
                PostStatus.DELETED.value(), state.post.getVersion());

        int written = 0;
        for (DomainEvent event : decision.getEvent()) {
            Outbox.append(c, new OutboxEvent(
                    event.getAggregateId(),
                    event.getType(),
                    Events.payloadOf(event),
                    traceId));
            written++;
        }
        return new Outcome(true, null, written);
    }

}
