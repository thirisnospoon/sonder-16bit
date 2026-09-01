/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/errors/errors.yaml
 * Генератор: tools/gen-errors/gen_errors.py
 * Перегенерация: ./sonder codegen
 *
 * Правка этого файла будет затёрта, а расхождение с источником
 * поймано проверкой дрейфа в CI.
 */
package sonder.contract;

/**
 * Коды отказа. Enum, а не строки: опечатка обязана ломать компиляцию.
 *
 * <p>{@code decidedByCore} говорит, кто принимает решение. Код с
 * {@code true} оболочка возвращать не имеет права — это ответ ядра,
 * и дублирование правила в Java означало бы два места, где живёт
 * одна и та же логика.
 */
public enum ErrorCode {

    /** Ник не соответствует [a-z0-9_]{3,20} */
    NICK_FORMAT_INVALID(Category.VALIDATION, 400, false, true),
    /** Тело поста пустое или состоит из пробельных символов */
    POST_BODY_EMPTY(Category.VALIDATION, 400, false, true),
    /** Тело поста длиннее допустимого */
    POST_BODY_TOO_LONG(Category.VALIDATION, 400, false, true),
    /** Тело комментария пустое */
    COMMENT_BODY_EMPTY(Category.VALIDATION, 400, false, true),
    /** Тело комментария длиннее допустимого */
    COMMENT_BODY_TOO_LONG(Category.VALIDATION, 400, false, true),
    /** Причина блокировки обязательна: она попадает в аудит */
    BAN_REASON_EMPTY(Category.VALIDATION, 400, false, true),
    /** Причина блокировки длиннее допустимого */
    BAN_REASON_TOO_LONG(Category.VALIDATION, 400, false, true),
    /** Отображаемое имя пустое или длиннее допустимого */
    DISPLAY_NAME_INVALID(Category.VALIDATION, 400, false, true),
    /** Свободный текст не является корректным UTF-8. Ядро считает длину в символах, а испорченную последовательность посчитать нельзя, поэтому отказ выносится раньше проверки длины. В норме недостижим: байты порождает Java, а линия защищена CRC-16. */
    TEXT_ENCODING_INVALID(Category.VALIDATION, 400, false, true),
    /** Сессия не найдена, истекла или отозвана */
    SESSION_INVALID(Category.AUTH, 401, false, false),
    /** Неверная пара логин и пароль */
    CREDENTIALS_INVALID(Category.AUTH, 401, false, false),
    /** Заблокированный пользователь не создаёт содержимое */
    ACTOR_BANNED(Category.PERMISSION, 403, false, true),
    /** Действие доступно только владельцу объекта */
    NOT_OWNER(Category.PERMISSION, 403, false, true),
    /** Роль не даёт права на это действие */
    ROLE_INSUFFICIENT(Category.PERMISSION, 403, false, true),
    /** Модератор не применяет меры к равному или старшему по роли */
    CANNOT_MODERATE_PEER(Category.PERMISSION, 403, false, true),
    /** Пользователь не существует */
    USER_NOT_FOUND(Category.NOT_FOUND, 404, false, true),
    /** Пост не существует */
    POST_NOT_FOUND(Category.NOT_FOUND, 404, false, true),
    /** Ник уже занят */
    NICK_TAKEN(Category.CONFLICT, 409, false, true),
    /** Нельзя подписаться на себя */
    SELF_FOLLOW(Category.CONFLICT, 409, false, true),
    /** Подписка уже существует */
    ALREADY_FOLLOWING(Category.CONFLICT, 409, false, true),
    /** Пользователь уже заблокирован */
    ALREADY_BANNED(Category.CONFLICT, 409, false, true),
    /** Состояние изменилось между загрузкой и сохранением, команду надо переиграть */
    STATE_VERSION_CONFLICT(Category.CONFLICT, 409, false, false),
    /** Превышено число постов за окно */
    POST_RATE_EXCEEDED(Category.RATE_LIMIT, 429, true, true),
    /** Превышено число комментариев за окно */
    COMMENT_RATE_EXCEEDED(Category.RATE_LIMIT, 429, true, true),
    /** Слишком много попыток входа */
    LOGIN_RATE_EXCEEDED(Category.RATE_LIMIT, 429, true, false),
    /** Доменное ядро недоступно или не ответило в срок */
    DECIDER_UNAVAILABLE(Category.UPSTREAM, 502, true, false),
    /** Ядру не хватило данных для решения: оболочка прислала не всё, что объявлено контрактом. Ядро обязано вернуть этот код, а не додумывать значение по умолчанию. Это дефект, а не пользовательская ошибка. */
    INSUFFICIENT_CONTEXT(Category.INTERNAL, 500, false, true),
    /** Файбер обработки команды упал, арена сброшена, нода жива */
    DECIDER_PANIC(Category.INTERNAL, 500, false, true),
    /** Конверт SOAP не разобрался: нарушена структура XML, превышен предел разборщика или встретился DTD, который ядро отвергает намеренно. Снаружи все эти случаи неразличимы — конверт не разобрался, — а разница нужна только в логе, поэтому код один. Это дефект оболочки или порча на линии, а не пользовательская ошибка. */
    MALFORMED_ENVELOPE(Category.INTERNAL, 500, false, true);

    public enum Category {
        /** Команда не проходит проверку формы */
        VALIDATION,
        /** Не удалось установить личность */
        AUTH,
        /** Личность установлена, но действие не разрешено */
        PERMISSION,
        /** Объект не существует */
        NOT_FOUND,
        /** Состояние не допускает этого действия */
        CONFLICT,
        /** Превышено ограничение частоты */
        RATE_LIMIT,
        /** Отказала зависимость */
        UPSTREAM,
        /** Дефект системы */
        INTERNAL;
    }

    private final Category category;
    private final int httpStatus;
    private final boolean retryable;
    private final boolean decidedByCore;

    ErrorCode(Category category, int httpStatus,
              boolean retryable, boolean decidedByCore) {
        this.category = category;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.decidedByCore = decidedByCore;
    }

    public Category category()    { return category; }
    public int httpStatus()       { return httpStatus; }
    public boolean retryable()    { return retryable; }
    public boolean decidedByCore() { return decidedByCore; }
}
