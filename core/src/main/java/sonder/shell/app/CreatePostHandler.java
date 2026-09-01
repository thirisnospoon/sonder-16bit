package sonder.shell.app;

import sonder.contract.decider.ActorContext;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.CreatePostCommand;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.PostStatus;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.state.StateLoader;
import sonder.shell.store.PostStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Создание поста через ядро.
 *
 * <p>Ни одного доменного правила: длину тела, право писать, частоту постов
 * решает NODE-7. Здесь — загрузить объявленное состояние, донести и
 * записать.
 *
 * <p><b>Идемпотентности пока нет, и это записано, а не замаскировано.</b>
 * Повторённый клиентом запрос создаст второй пост: идентификатор
 * порождается здесь, а не приходит от клиента. Правильное решение —
 * журнал команд по {@code meta.commandId}, который контракт уже
 * предусматривает, — относится к тому же конвейеру, что и outbox, и будет
 * сделано вместе с ним. До тех пор повтор создаёт дубликат, и притворяться
 * иначе хуже, чем сказать.
 */
public final class CreatePostHandler {

    /** Что вышло из хода команды. */
    public static final class Outcome {
        private final boolean accepted;
        private final String errorCode;
        private final String postId;

        Outcome(boolean accepted, String errorCode, String postId) {
            this.accepted = accepted;
            this.errorCode = errorCode;
            this.postId = postId;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getErrorCode() {
            return errorCode;
        }

        /** Идентификатор созданного поста. {@code null} при отказе. */
        public String getPostId() {
            return postId;
        }
    }

    private final CommandFlow flow;
    private final Decider decider;

    public CreatePostHandler(CommandFlow flow, Decider decider) {
        this.flow = flow;
        this.decider = decider;
    }

    /**
     * Идентификатор поста.
     *
     * <p>Без дефисов: контракт разрешает {@code [a-zA-Z0-9_-]}, дефис
     * прошёл бы, но короткая форма читается в логах лучше, а длина имеет
     * значение — ядро отвергает идентификаторы длиннее сорока символов.
     */
    static String newPostId() {
        return "p-" + UUID.randomUUID().toString().replace("-", "");
    }

    public Outcome handle(String actorId, String body,
                          String traceId, String commandId, Instant now)
            throws Exception {
        String postId = newPostId();

        return flow.run(
                c -> StateLoader.loadActor(c, actorId, now),
                actor -> decider.createPost(
                        request(actor, postId, body, traceId, commandId, now)),
                (c, actor, decision) ->
                        apply(c, actor, decision, postId, body, traceId));
    }

    private static CreatePostRequest request(ActorContext actor, String postId,
                                             String body, String traceId,
                                             String commandId, Instant now) {
        CommandMeta meta = new CommandMeta();
        meta.setTraceId(traceId);
        meta.setCommandId(commandId);
        meta.setIssuedAtMillis(now.toEpochMilli());

        CreatePostCommand command = new CreatePostCommand();
        command.setPostId(postId);
        command.setBody(body);

        CreatePostRequest request = new CreatePostRequest();
        request.setMeta(meta);
        request.setCommand(command);
        request.setActor(actor);
        return request;
    }

    private static Outcome apply(Connection c, ActorContext actor,
                                 Decision decision, String postId,
                                 String body, String traceId)
            throws SQLException, VersionConflict {
        if (!decision.isAccepted()) {
            return new Outcome(false, decision.getErrorCode(), null);
        }

        PostStore.insert(c, postId, actor.getUserId(), body,
                PostStatus.VISIBLE.value());

        for (DomainEvent event : decision.getEvent()) {
            Outbox.append(c, new OutboxEvent(
                    event.getAggregateId(),
                    event.getType(),
                    Events.payloadOf(event),
                    traceId));
        }
        return new Outcome(true, null, postId);
    }
}
