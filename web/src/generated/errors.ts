/*
 * GENERATED. Do not edit by hand.
 *
 * Source:      contracts/errors/errors.yaml
 * Generator:   tools/gen-errors/gen_errors.py
 * Regenerate:  ./sonder codegen
 *
 * An edit to this file will be overwritten, and the divergence from
 * the source caught by the drift check in CI.
 */

// A union of string literals rather than an enum: codes arrive
// from the server as strings, and a union checks them at the edge.
export type ErrorCode =
  | 'NICK_FORMAT_INVALID'
  | 'POST_BODY_EMPTY'
  | 'POST_BODY_TOO_LONG'
  | 'COMMENT_BODY_EMPTY'
  | 'COMMENT_BODY_TOO_LONG'
  | 'BAN_REASON_EMPTY'
  | 'BAN_REASON_TOO_LONG'
  | 'DISPLAY_NAME_INVALID'
  | 'TEXT_ENCODING_INVALID'
  | 'MALFORMED_REQUEST'
  | 'SESSION_INVALID'
  | 'CREDENTIALS_INVALID'
  | 'ACTOR_BANNED'
  | 'NOT_OWNER'
  | 'ROLE_INSUFFICIENT'
  | 'CANNOT_MODERATE_PEER'
  | 'USER_NOT_FOUND'
  | 'POST_NOT_FOUND'
  | 'RESOURCE_NOT_FOUND'
  | 'NICK_TAKEN'
  | 'SELF_FOLLOW'
  | 'ALREADY_FOLLOWING'
  | 'NOT_FOLLOWING'
  | 'ALREADY_BANNED'
  | 'STATE_VERSION_CONFLICT'
  | 'POST_RATE_EXCEEDED'
  | 'COMMENT_RATE_EXCEEDED'
  | 'LOGIN_RATE_EXCEEDED'
  | 'DECIDER_UNAVAILABLE'
  | 'INSUFFICIENT_CONTEXT'
  | 'DECIDER_PANIC'
  | 'MALFORMED_ENVELOPE'
  | 'INTERNAL_ERROR'
  | 'GATEWAY_UNAVAILABLE'

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
  BAN_REASON_TOO_LONG: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  DISPLAY_NAME_INVALID: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  TEXT_ENCODING_INVALID: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  MALFORMED_REQUEST: { category: 'VALIDATION', httpStatus: 400, retryable: false },
  SESSION_INVALID: { category: 'AUTH', httpStatus: 401, retryable: false },
  CREDENTIALS_INVALID: { category: 'AUTH', httpStatus: 401, retryable: false },
  ACTOR_BANNED: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  NOT_OWNER: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  ROLE_INSUFFICIENT: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  CANNOT_MODERATE_PEER: { category: 'PERMISSION', httpStatus: 403, retryable: false },
  USER_NOT_FOUND: { category: 'NOT_FOUND', httpStatus: 404, retryable: false },
  POST_NOT_FOUND: { category: 'NOT_FOUND', httpStatus: 404, retryable: false },
  RESOURCE_NOT_FOUND: { category: 'NOT_FOUND', httpStatus: 404, retryable: false },
  NICK_TAKEN: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  SELF_FOLLOW: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  ALREADY_FOLLOWING: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  NOT_FOLLOWING: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  ALREADY_BANNED: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  STATE_VERSION_CONFLICT: { category: 'CONFLICT', httpStatus: 409, retryable: false },
  POST_RATE_EXCEEDED: { category: 'RATE_LIMIT', httpStatus: 429, retryable: true },
  COMMENT_RATE_EXCEEDED: { category: 'RATE_LIMIT', httpStatus: 429, retryable: true },
  LOGIN_RATE_EXCEEDED: { category: 'RATE_LIMIT', httpStatus: 429, retryable: true },
  DECIDER_UNAVAILABLE: { category: 'UPSTREAM', httpStatus: 502, retryable: true },
  INSUFFICIENT_CONTEXT: { category: 'INTERNAL', httpStatus: 500, retryable: false },
  DECIDER_PANIC: { category: 'INTERNAL', httpStatus: 500, retryable: false },
  MALFORMED_ENVELOPE: { category: 'INTERNAL', httpStatus: 500, retryable: false },
  INTERNAL_ERROR: { category: 'INTERNAL', httpStatus: 500, retryable: false },
  GATEWAY_UNAVAILABLE: { category: 'UPSTREAM', httpStatus: 502, retryable: true },
}

export const ALL_ERROR_CODES = Object.keys(ERROR_META) as ErrorCode[]
