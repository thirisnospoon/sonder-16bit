package sonder.shell.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import sonder.contract.ErrorCode;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Всякий отказ уезжает в конверте, объявленном контрактом.
 *
 * <p>ДО ЭТОГО КЛАССА ЧАСТЬ ОТВЕТОВ БЫЛА НЕ ПО КОНТРАКТУ. Обработчики
 * отвечают через {@link RestErrors} и оттого выглядят правильно; но всё,
 * что до них не дошло — опечатка в адресе, тело не того вида,
 * неожиданное исключение, — падало в стандартную страницу Spring с
 * полями {@code timestamp/status/error/path}. Клиент, порождённый по
 * OpenAPI, разбирает {@code ApiError} с полями {@code code} и
 * {@code traceId} и спотыкается ровно там, где причину понять нужнее
 * всего.
 *
 * <p>Замечено это было проверкой доступа: неизвестный маршрут под
 * {@code /api} отвечал 404 в чужой форме.
 *
 * <p>ДВА МЕХАНИЗМА, И ОБА НУЖНЫ. {@link RestControllerAdvice} ловит
 * исключения, брошенные ИЗ обработчика; {@link ErrorController}
 * перехватывает то, что до обработчика не дошло вовсе, — контейнер
 * отправляет такие ответы на {@code /error} собственной пересылкой, и
 * никакой совет их не увидит. Оставить один — значит оставить половину
 * отказов в чужой форме.
 *
 * <p>ПОДРОБНОСТИ ИСКЛЮЧЕНИЯ НАРУЖУ НЕ УХОДЯТ. В сообщении бывает и имя
 * таблицы, и кусок запроса; ищущему хватит {@code traceId}, а по нему в
 * логе лежит всё. Ровно поэтому в логе оно и должно лежать — иначе
 * молчание наружу превращается в молчание вообще.
 */
@RestControllerAdvice
public class ErrorEnvelope implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ErrorEnvelope.class);

    /**
     * Ответ на всё, что не дошло до обработчика.
     *
     * <p>Код берётся из статуса, проставленного контейнером: 404 — это
     * адрес, которого нет; 405 — метод, которого нет у этого адреса;
     * остальное — дефект оболочки. Придумывать вместо этого один код на
     * все случаи значило бы отвечать «внутренняя ошибка» на опечатку в
     * адресе.
     */
    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> контейнерОтказал(
            HttpServletRequest запрос) {
        Object статус = запрос.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int код = статус instanceof Integer ? (Integer) статус : 500;

        if (код == 404) {
            return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, Trace.current());
        }
        if (код == 405 || код == 415 || код == 400) {
            return RestErrors.of(ErrorCode.MALFORMED_REQUEST, Trace.current());
        }

        Object причина = запрос.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        log.error("контейнер ответил {} на {}: {}", код,
                запрос.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                причина == null ? "без исключения" : причина.toString());
        return RestErrors.of(ErrorCode.INTERNAL_ERROR, Trace.current());
    }

    /** Адреса, которого нет. */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> маршрутаНет(
            NoHandlerFoundException e) {
        log.debug("нет маршрута: {} {}", e.getHttpMethod(), e.getRequestURL());
        return RestErrors.of(ErrorCode.RESOURCE_NOT_FOUND, Trace.current());
    }

    /**
     * Запрос не той формы, какую объявляет контракт.
     *
     * <p>Все эти случаи контракт называет одним кодом: нет тела, поле не
     * того типа, метод не тот, обязательный параметр отсутствует. Разница
     * между ними нужна разработчику и лежит в логе; клиенту она не
     * поможет — форму запроса он берёт из того же OpenAPI.
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            HttpRequestMethodNotSupportedException.class,
    })
    public ResponseEntity<Map<String, Object>> формаНеТа(Exception e) {
        log.debug("запрос не по контракту: {}", e.toString());
        return RestErrors.of(ErrorCode.MALFORMED_REQUEST, Trace.current());
    }

    /**
     * Всё остальное — дефект оболочки, и он обязан быть громким.
     *
     * <p>Уровень {@code error} со стеком: это не пользовательская ошибка,
     * а поломка, и увидеть её надо тому, кто чинит. Наружу при этом
     * уходит только код и след — сообщение исключения нередко содержит
     * имя таблицы, кусок запроса или значение поля.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> сломалось(Throwable e) {
        log.error("необработанный отказ оболочки", e);
        return RestErrors.of(ErrorCode.INTERNAL_ERROR, Trace.current());
    }
}
