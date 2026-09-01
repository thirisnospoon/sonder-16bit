package sonder.shell.app;

import sonder.contract.decider.ActorContext;
import sonder.contract.decider.BanUserCommand;
import sonder.contract.decider.BanUserRequest;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;
import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.FollowContext;
import sonder.contract.decider.FollowUserCommand;
import sonder.contract.decider.FollowUserRequest;
import sonder.contract.decider.NickContext;
import sonder.contract.decider.RegisterUserCommand;
import sonder.contract.decider.RegisterUserRequest;
import sonder.contract.decider.TargetUserContext;
import sonder.contract.decider.UserStatus;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxEvent;
import sonder.shell.state.StateLoader;
import sonder.shell.store.UserStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Команды о пользователях: регистрация, подписка, блокировка.
 *
 * <p>Три обработчика в одном файле, потому что они устроены одинаково и
 * различаются только тем, какое состояние грузят и что пишут. Разнести их
 * по трём файлам значило бы трижды повторить одну и ту же обвязку и
 * потерять из виду, что различий всего два.
 *
 * <p>Ни одного доменного правила: форму ника, право модератора,
 * самоподписку решает NODE-7.
 */
public final class UserHandlers {

    /** Исход команды. Отказ — нормальный результат. */
    public static final class Outcome {
        private final boolean accepted;
        private final String errorCode;
        private final String subjectId;

        Outcome(boolean accepted, String errorCode, String subjectId) {
            this.accepted = accepted;
            this.errorCode = errorCode;
            this.subjectId = subjectId;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getErrorCode() {
            return errorCode;
        }

        /** Кого команда касалась: созданный пользователь, цель подписки. */
        public String getSubjectId() {
            return subjectId;
        }
    }

    private final CommandFlow flow;
    private final Decider decider;

    public UserHandlers(CommandFlow flow, Decider decider) {
        this.flow = flow;
        this.decider = decider;
    }

    private static CommandMeta meta(String traceId, String commandId, Instant now) {
        CommandMeta m = new CommandMeta();
        m.setTraceId(traceId);
        m.setCommandId(commandId);
        m.setIssuedAtMillis(now.toEpochMilli());
        return m;
    }

    static String newUserId() {
        return "u-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void writeEvents(Connection c, Decision decision, String traceId)
            throws SQLException {
        for (DomainEvent event : decision.getEvent()) {
            Outbox.append(c, new OutboxEvent(event.getAggregateId(),
                    event.getType(), Events.payloadOf(event), traceId));
        }
    }

    /**
     * Регистрация.
     *
     * <p>Пароля в запросе к ядру НЕТ, и это не упущение: ядро о учётных
     * данных не знает вовсе — хэширование и проверка требуют хранилища,
     * которого под DOS нет. Контракт операции пароля и не объявляет.
     */
    public Outcome register(String nick, String displayName, String passwordHash,
                            String traceId, Instant now) throws Exception {
        String userId = newUserId();

        return flow.run(
                c -> StateLoader.loadNick(c, nick),
                nickCtx -> decider.registerUser(
                        registerRequest(nickCtx, userId, nick, displayName,
                                traceId, now)),
                (c, nickCtx, decision) -> {
                    if (!decision.isAccepted()) {
                        return new Outcome(false, decision.getErrorCode(), null);
                    }
                    UserStore.insert(c, userId, nick, displayName, passwordHash, now);
                    writeEvents(c, decision, traceId);
                    return new Outcome(true, null, userId);
                });
    }

    private static RegisterUserRequest registerRequest(NickContext nickCtx,
                                                       String userId, String nick,
                                                       String displayName,
                                                       String traceId, Instant now) {
        RegisterUserCommand command = new RegisterUserCommand();
        command.setUserId(userId);
        command.setNick(nick);
        command.setDisplayName(displayName);

        RegisterUserRequest request = new RegisterUserRequest();
        request.setMeta(meta(traceId, traceId, now));
        request.setCommand(command);
        request.setNick(nickCtx);
        return request;
    }

    /** Состояние подписки: кто, на кого, подписан ли уже. */
    static final class FollowState {
        final ActorContext actor;
        final TargetUserContext target;
        final FollowContext follow;

        FollowState(ActorContext actor, TargetUserContext target, FollowContext follow) {
            this.actor = actor;
            this.target = target;
            this.follow = follow;
        }
    }

    public Outcome follow(String actorId, String targetId,
                          String traceId, Instant now) throws Exception {
        return flow.run(
                c -> new FollowState(
                        StateLoader.loadActor(c, actorId, now),
                        StateLoader.loadTarget(c, targetId),
                        StateLoader.loadFollow(c, actorId, targetId)),
                state -> decider.followUser(
                        followRequest(state, targetId, traceId, now)),
                (c, state, decision) -> {
                    if (!decision.isAccepted()) {
                        return new Outcome(false, decision.getErrorCode(), null);
                    }
                    UserStore.addFollow(c, actorId, targetId, now);
                    writeEvents(c, decision, traceId);
                    return new Outcome(true, null, targetId);
                });
    }

    private static FollowUserRequest followRequest(FollowState state, String targetId,
                                                   String traceId, Instant now) {
        FollowUserCommand command = new FollowUserCommand();
        command.setTargetUserId(targetId);

        FollowUserRequest request = new FollowUserRequest();
        request.setMeta(meta(traceId, traceId, now));
        request.setCommand(command);
        request.setActor(state.actor);
        request.setTarget(state.target);
        request.setFollow(state.follow);
        return request;
    }

    /** Состояние блокировки: кто блокирует и кого. */
    static final class BanState {
        final ActorContext actor;
        final TargetUserContext target;

        BanState(ActorContext actor, TargetUserContext target) {
            this.actor = actor;
            this.target = target;
        }
    }

    public Outcome ban(String actorId, String targetId, String reason,
                       String traceId, Instant now) throws Exception {
        return flow.run(
                c -> new BanState(
                        StateLoader.loadActor(c, actorId, now),
                        StateLoader.loadTarget(c, targetId)),
                state -> decider.banUser(
                        banRequest(state, targetId, reason, traceId, now)),
                (c, state, decision) -> {
                    if (!decision.isAccepted()) {
                        return new Outcome(false, decision.getErrorCode(), null);
                    }
                    // Версия та, что видело ядро. Сдвинулась — команда
                    // переигрывается: решение принято по старому состоянию.
                    UserStore.updateStatus(c, targetId, UserStatus.BANNED.value(),
                            state.target.getVersion());
                    writeEvents(c, decision, traceId);
                    return new Outcome(true, null, targetId);
                });
    }

    private static BanUserRequest banRequest(BanState state, String targetId,
                                             String reason, String traceId,
                                             Instant now) {
        BanUserCommand command = new BanUserCommand();
        command.setTargetUserId(targetId);
        command.setReason(reason);

        BanUserRequest request = new BanUserRequest();
        request.setMeta(meta(traceId, traceId, now));
        request.setCommand(command);
        request.setActor(state.actor);
        request.setTarget(state.target);
        return request;
    }
}
