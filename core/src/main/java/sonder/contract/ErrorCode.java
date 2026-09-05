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

    /** The nick does not match [a-z0-9_]{3,20} */
    NICK_FORMAT_INVALID(Category.VALIDATION, 400, false, true),
    /** The post body is empty or nothing but whitespace */
    POST_BODY_EMPTY(Category.VALIDATION, 400, false, true),
    /** The post body is longer than allowed */
    POST_BODY_TOO_LONG(Category.VALIDATION, 400, false, true),
    /** The comment body is empty */
    COMMENT_BODY_EMPTY(Category.VALIDATION, 400, false, true),
    /** The comment body is longer than allowed */
    COMMENT_BODY_TOO_LONG(Category.VALIDATION, 400, false, true),
    /** A ban reason is required: it goes into the audit trail */
    BAN_REASON_EMPTY(Category.VALIDATION, 400, false, true),
    /** The ban reason is longer than allowed */
    BAN_REASON_TOO_LONG(Category.VALIDATION, 400, false, true),
    /** The display name is empty or longer than allowed */
    DISPLAY_NAME_INVALID(Category.VALIDATION, 400, false, true),
    /** Free text is not valid UTF-8. The core counts length in characters, and a broken sequence cannot be counted, so the refusal comes before the length check. Unreachable in normal operation: the bytes are produced by Java and the line is guarded by CRC-16. */
    TEXT_ENCODING_INVALID(Category.VALIDATION, 400, false, true),
    /** The request does not match the shape declared in OpenAPI: no body, a missing required field, a field of the wrong type. Decided by the shell, and this is no overlap with the domain: the core checks the MEANING of a command -- text length, permissions, rate -- while this is about a command that could not even be assembled. A missing request body is not "an invalid display name", and answering it with a domain code would tell the user something untrue about what they got wrong. */
    MALFORMED_REQUEST(Category.VALIDATION, 400, false, false),
    /** The session is unknown, expired or revoked */
    SESSION_INVALID(Category.AUTH, 401, false, false),
    /** Wrong name and password */
    CREDENTIALS_INVALID(Category.AUTH, 401, false, false),
    /** A banned user creates no content */
    ACTOR_BANNED(Category.PERMISSION, 403, false, true),
    /** The action belongs to the owner of the object */
    NOT_OWNER(Category.PERMISSION, 403, false, true),
    /** The role does not carry the right to this action */
    ROLE_INSUFFICIENT(Category.PERMISSION, 403, false, true),
    /** A moderator does not act against an equal or a senior */
    CANNOT_MODERATE_PEER(Category.PERMISSION, 403, false, true),
    /** No such user */
    USER_NOT_FOUND(Category.NOT_FOUND, 404, false, true),
    /** No such post */
    POST_NOT_FOUND(Category.NOT_FOUND, 404, false, true),
    /** The requested object is absent or not visible. A separate code from POST_NOT_FOUND, because these are DIFFERENT decisions taken by different sides. POST_NOT_FOUND is the core's, handling a COMMAND: the command refers to a post that does not exist or was deleted, and that is a domain refusal. RESOURCE_NOT_FOUND is the shell's, handling a READ: the core takes no part in reading at all -- reading is not deciding. The two codes already mean different things (the core answers POST_NOT_FOUND for a deleted post as well), and one code for two decisions would mean a change in the core quietly altering the behaviour of reads. The distinction was found by the ArchUnit gate on the shell's very first attempt to borrow a code that was not its own. */
    RESOURCE_NOT_FOUND(Category.NOT_FOUND, 404, false, false),
    /** The nick is already taken */
    NICK_TAKEN(Category.CONFLICT, 409, false, true),
    /** One cannot follow oneself */
    SELF_FOLLOW(Category.CONFLICT, 409, false, true),
    /** The subscription already exists */
    ALREADY_FOLLOWING(Category.CONFLICT, 409, false, true),
    /** Nothing to unfollow: there is no subscription. The mirror of ALREADY_FOLLOWING, and it appeared together with the UnfollowUser operation. Unfollowing oneself lands here rather than in SELF_FOLLOW: one cannot follow oneself, so a subscription to oneself never exists, and "you are not following" is an exact description rather than a substitute. */
    NOT_FOLLOWING(Category.CONFLICT, 409, false, true),
    /** The user is already banned */
    ALREADY_BANNED(Category.CONFLICT, 409, false, true),
    /** The state changed between load and save; the command must be replayed */
    STATE_VERSION_CONFLICT(Category.CONFLICT, 409, true, false),
    /** Too many posts within the window */
    POST_RATE_EXCEEDED(Category.RATE_LIMIT, 429, true, true),
    /** Too many comments within the window */
    COMMENT_RATE_EXCEEDED(Category.RATE_LIMIT, 429, true, true),
    /** Too many login attempts */
    LOGIN_RATE_EXCEEDED(Category.RATE_LIMIT, 429, true, false),
    /** The domain core is unreachable or did not answer in time */
    DECIDER_UNAVAILABLE(Category.UPSTREAM, 502, true, false),
    /** The core lacked the data to decide: the shell sent less than the contract declares. The core must return this code rather than invent a default. It is a defect, not a user error. */
    INSUFFICIENT_CONTEXT(Category.INTERNAL, 500, false, true),
    /** The command fiber crashed, the arena was reset, the node lives */
    DECIDER_PANIC(Category.INTERNAL, 500, false, true),
    /** The SOAP envelope did not parse: broken XML structure, a parser limit exceeded, or a DTD the core refuses on purpose. From outside these cases are indistinguishable -- the envelope did not parse -- and the difference matters only in the log, so there is one code. It is a defect of the shell or damage on the line, not a user error. */
    MALFORMED_ENVELOPE(Category.INTERNAL, 500, false, true),
    /** The shell failed on its own: an unhandled exception, a database failure, an unknown route under /api. The code exists because BEFORE IT such answers were not in the contract at all -- Spring served its own error page with timestamp/status/error/path, and a client parsing the declared ApiError stumbled exactly where the cause matters most. Separate from INSUFFICIENT_CONTEXT and DECIDER_PANIC: those declare a defect of the CORE, this one a defect of the shell. One code for both would mean the answer cannot tell you which half of the system to search. */
    INTERNAL_ERROR(Category.INTERNAL, 500, false, false),
    /** The request never reached the shell: it is restarting, crashed, or unreachable over the network. The answer comes from the gateway -- the shell itself is silent, and no `trace_id` exists, because that is born there. SEPARATE FROM DECIDER_UNAVAILABLE, and the difference is not formal: that one means the CORE behind the serial line is not answering, this one that the shell is not. One code for both would send you looking for the fault in a sixteen-bit program under an emulator when in fact Java failed to come up. Worth retrying: restarting the shell takes seconds. */
    GATEWAY_UNAVAILABLE(Category.UPSTREAM, 502, true, false);

    public enum Category {
        /** The command fails a check of form */
        VALIDATION,
        /** Identity could not be established */
        AUTH,
        /** Identity established, action not allowed */
        PERMISSION,
        /** The object does not exist */
        NOT_FOUND,
        /** The state does not admit this action */
        CONFLICT,
        /** A rate limit was exceeded */
        RATE_LIMIT,
        /** A dependency failed */
        UPSTREAM,
        /** A defect of the system */
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
