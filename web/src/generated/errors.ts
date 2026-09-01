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

// Union строковых литералов, а не enum: коды приходят с сервера
// строками, и union проверяет их ровно на границе.
export type ErrorCode =
  | 'NICK_FORMAT_INVALID'
  | 'POST_BODY_EMPTY'
  | 'POST_BODY_TOO_LONG'
  | 'COMMENT_BODY_EMPTY'
  | 'COMMENT_BODY_TOO_LONG'
  | 'BAN_REASON_EMPTY'
  | 'SESSION_INVALID'
  | 'CREDENTIALS_INVALID'
  | 'ACTOR_BANNED'
  | 'NOT_OWNER'
  | 'ROLE_INSUFFICIENT'
  | 'CANNOT_MODERATE_PEER'
  | 'USER_NOT_FOUND'
  | 'POST_NOT_FOUND'
  | 'NICK_TAKEN'
  | 'SELF_FOLLOW'
  | 'ALREADY_FOLLOWING'
  | 'ALREADY_BANNED'
  | 'STATE_VERSION_CONFLICT'
  | 'POST_RATE_EXCEEDED'
  | 'COMMENT_RATE_EXCEEDED'
  | 'LOGIN_RATE_EXCEEDED'
  | 'DECIDER_UNAVAILABLE'
  | 'INSUFFICIENT_CONTEXT'
  | 'DECIDER_PANIC'

export type ErrorCategory =
  | 'VALIDATION'
  | 'AUTH'
  | 'PERMISSION'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'RATE_LIMIT'
  | 'UPSTREAM'
  | 'INTERNAL'

export interface ErrorMeta {
  readonly category: ErrorCategory
  readonly httpStatus: number
  readonly retryable: boolean
}

export const ERROR_META: Readonly<Record<ErrorCode, ErrorMeta>> = {
  NICK_FORMAT_INVALID: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  POST_BODY_EMPTY: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  POST_BODY_TOO_LONG: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  COMMENT_BODY_EMPTY: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  COMMENT_BODY_TOO_LONG: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  BAN_REASON_EMPTY: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  SESSION_INVALID: { category: 'AUTH', httpStatus: 401, retryable: false },
  CREDENTIALS_INVALID: { category: 'AUTH', httpStatus: 401, retryable: false },
  ACTOR_BANNED: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  NOT_OWNER: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  ROLE_INSUFFICIENT: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  CANNOT_MODERATE_PEER: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  USER_NOT_FOUND: { category: 'NOT_FOUND', httpStatus: 404, retryable: false },
  POST_NOT_FOUND: { category: 'NOT_FOUND', httpStatus: 404, retryable: false },
  NICK_TAKEN: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  SELF_FOLLOW: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  ALREADY_FOLLOWING: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  ALREADY_BANNED: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  STATE_VERSION_CONFLICT: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  POST_RATE_EXCEEDED: { category: 'RATE_LIMIT', httpStatus: 429, retryable: true },
  COMMENT_RATE_EXCEEDED: { category: 'RATE_LIMIT', httpStatus: 429, retryable: true },
  LOGIN_RATE_EXCEEDED: { category: 'RATE_LIMIT', httpStatus: 429, retryable: true },
  DECIDER_UNAVAILABLE: { category: 'UPSTREAM', httpStatus: 502, retryable: true },
  INSUFFICIENT_CONTEXT: { category: 'INTERNAL', httpStatus: 500, retryable: false },
  DECIDER_PANIC: { category: 'INTERNAL', httpStatus: 500, retryable: false },
}

export const ALL_ERROR_CODES = Object.keys(ERROR_META) as ErrorCode[]
