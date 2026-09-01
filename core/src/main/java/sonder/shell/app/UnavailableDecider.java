package sonder.shell.app;

import org.springframework.stereotype.Component;
import sonder.contract.ErrorCode;
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

/**
 * Заглушка ядра до появления транспорта.
 *
 * <p>NODE-7 живёт за последовательной линией, а моста «SOAP ↔ линия» ещё
 * нет: он строится в фазе 8. До тех пор всякая команда честно отвечает
 * {@link ErrorCode#DECIDER_UNAVAILABLE} — код, который контракт помечает
 * решаемым оболочкой и повторяемым.
 *
 * <p><b>Почему не реализовать правила здесь «пока что».</b> Потому что
 * «пока что» кончается тем, что правила остаются в двух местах и
 * расходятся. ArchUnit это и запрещает, а данный класс проходит проверку
 * ровно потому, что ничего не решает: он сообщает, что решать некому.
 *
 * <p>Отвечает решением, а не исключением: недоступность ядра — штатный
 * исход, известный контракту, а не дефект оболочки.
 *
 * <p>Не помечен {@code @Primary}: в бою он единственный, и объявлять его
 * главным незачем, а в тестах главным становится подменный — два
 * «главных» боба Spring не различает и отказывается поднимать контекст.
 */
@Component
public class UnavailableDecider implements Decider {

    private static Decision unavailable() {
        Decision d = new Decision();
        d.setAccepted(false);
        d.setErrorCode(ErrorCode.DECIDER_UNAVAILABLE.name());
        d.setErrorDetail("мост SOAP↔линия появится в фазе 8");
        return d;
    }

    @Override public Decision registerUser(RegisterUserRequest r) { return unavailable(); }
    @Override public Decision createPost(CreatePostRequest r) { return unavailable(); }
    @Override public Decision createComment(CreateCommentRequest r) { return unavailable(); }
    @Override public Decision deletePost(DeletePostRequest r) { return unavailable(); }
    @Override public Decision followUser(FollowUserRequest r) { return unavailable(); }
    @Override public Decision banUser(BanUserRequest r) { return unavailable(); }

    @Override
    public PingResponse ping(PingRequest r) {
        // Метрики ядра нулевые: ядра нет. Ноль здесь честнее выдуманных
        // значений — по нулевым метрикам сразу видно, что моста ещё нет.
        PingResponse response = new PingResponse();
        response.setNonce(0);
        response.setFibersInUse(0);
        response.setArenaHighMark(0);
        return response;
    }
}
