package sonder.shell.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sonder.contract.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Отказ решения → ответ HTTP по контракту.
 *
 * <p><b>Статус берётся из контракта, а не пишется рядом с каждым
 * отказом.</b> Соответствие «код отказа → статус» объявлено в
 * {@code errors.yaml} и порождено в {@link ErrorCode}; повторить его в
 * контроллерах значило бы завести второй источник, который однажды
 * разойдётся с первым — и клиент получит 401 там, где ему обещали 409.
 *
 * <p><b>Тело обязательно, и не только потому, что его требует контракт.</b>
 * OpenAPI объявляет схему {@code ApiError} с обязательными {@code code} и
 * {@code traceId}, и это главная причина. Но есть и вторая, найденная
 * измерением: Tomcat на ответе 5xx БЕЗ ТЕЛА подменяет ответ целиком своей
 * страницей ошибки, теряя все заголовки. Ответ с одним заголовком работал
 * на 401 и молча ломался на 500 — то есть ровно там, где диагностика
 * нужнее всего.
 *
 * <p>Код, которого Java не знает, — не «неизвестная ошибка», а
 * РАСХОЖДЕНИЕ КОНТРАКТОВ: ядро вернуло код, порождённый из другой версии
 * errors.yaml. Такое обязано быть громким, поэтому 500 и отдельная
 * пометка. Тихо превратить его в 400 значило бы показать пользователю
 * «вы неправильно заполнили форму» там, где разъехались две сборки.
 */
public final class RestErrors {

    /** Заголовок с кодом. Дублирует тело: по нему удобно фильтровать логи
     *  и метрики, не разбирая JSON. Источник правды — всё же тело. */
    public static final String HEADER = "X-Sonder-Error";

    /** Пометка расхождения контрактов. */
    public static final String UNKNOWN = "UNKNOWN_ERROR_CODE";

    private RestErrors() {
    }

    private static Map<String, Object> body(String code, String detail, String traceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        if (detail != null && !detail.isEmpty()) {
            out.put("detail", detail);
        }
        out.put("traceId", traceId);
        return out;
    }

    /** Отказ по коду, пришедшему от ядра строкой. */
    public static ResponseEntity<Map<String, Object>> of(String errorCode,
                                                         String traceId) {
        return of(errorCode, null, traceId);
    }

    public static ResponseEntity<Map<String, Object>> of(String errorCode,
                                                         String detail,
                                                         String traceId) {
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(errorCode);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HEADER, UNKNOWN)
                    .body(body(UNKNOWN,
                            "ядро вернуло код, которого нет в контракте: "
                                    + errorCode,
                            traceId));
        }
        return of(code, detail, traceId);
    }

    public static ResponseEntity<Map<String, Object>> of(ErrorCode code,
                                                         String traceId) {
        return of(code, null, traceId);
    }

    public static ResponseEntity<Map<String, Object>> of(ErrorCode code,
                                                         String detail,
                                                         String traceId) {
        return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus()))
                .header(HEADER, code.name())
                .body(body(code.name(), detail, traceId));
    }
}
