{ GENERATED. Do not edit by hand.

  Source:      contracts/errors/errors.yaml
  Generator:   tools/gen-errors/gen_errors.py
  Regenerate:  ./sonder codegen

  An edit to this file will be overwritten, and the divergence from
  the source caught by the drift check in CI.
}

{ Refusal codes travel the line as strings and are compared
  byte by byte, so these are constants, not an enumeration. }

const

  { --- VALIDATION --- }
  ERR_NICK_FORMAT_INVALID        = 'NICK_FORMAT_INVALID';
  ERR_POST_BODY_EMPTY            = 'POST_BODY_EMPTY';
  ERR_POST_BODY_TOO_LONG         = 'POST_BODY_TOO_LONG';
  ERR_COMMENT_BODY_EMPTY         = 'COMMENT_BODY_EMPTY';
  ERR_COMMENT_BODY_TOO_LONG      = 'COMMENT_BODY_TOO_LONG';
  ERR_BAN_REASON_EMPTY           = 'BAN_REASON_EMPTY';
  ERR_BAN_REASON_TOO_LONG        = 'BAN_REASON_TOO_LONG';
  ERR_DISPLAY_NAME_INVALID       = 'DISPLAY_NAME_INVALID';
  ERR_TEXT_ENCODING_INVALID      = 'TEXT_ENCODING_INVALID';
  ERR_MALFORMED_REQUEST          = 'MALFORMED_REQUEST';

  { --- AUTH --- }
  ERR_SESSION_INVALID            = 'SESSION_INVALID';
  ERR_CREDENTIALS_INVALID        = 'CREDENTIALS_INVALID';

  { --- PERMISSION --- }
  ERR_ACTOR_BANNED               = 'ACTOR_BANNED';
  ERR_NOT_OWNER                  = 'NOT_OWNER';
  ERR_ROLE_INSUFFICIENT          = 'ROLE_INSUFFICIENT';
  ERR_CANNOT_MODERATE_PEER       = 'CANNOT_MODERATE_PEER';

  { --- NOT_FOUND --- }
  ERR_USER_NOT_FOUND             = 'USER_NOT_FOUND';
  ERR_POST_NOT_FOUND             = 'POST_NOT_FOUND';
  ERR_RESOURCE_NOT_FOUND         = 'RESOURCE_NOT_FOUND';

  { --- CONFLICT --- }
  ERR_NICK_TAKEN                 = 'NICK_TAKEN';
  ERR_SELF_FOLLOW                = 'SELF_FOLLOW';
  ERR_ALREADY_FOLLOWING          = 'ALREADY_FOLLOWING';
  ERR_NOT_FOLLOWING              = 'NOT_FOLLOWING';
  ERR_ALREADY_BANNED             = 'ALREADY_BANNED';
  ERR_STATE_VERSION_CONFLICT     = 'STATE_VERSION_CONFLICT';

  { --- RATE_LIMIT --- }
  ERR_POST_RATE_EXCEEDED         = 'POST_RATE_EXCEEDED';
  ERR_COMMENT_RATE_EXCEEDED      = 'COMMENT_RATE_EXCEEDED';
  ERR_LOGIN_RATE_EXCEEDED        = 'LOGIN_RATE_EXCEEDED';

  { --- UPSTREAM --- }
  ERR_DECIDER_UNAVAILABLE        = 'DECIDER_UNAVAILABLE';

  { --- INTERNAL --- }
  ERR_INSUFFICIENT_CONTEXT       = 'INSUFFICIENT_CONTEXT';
  ERR_DECIDER_PANIC              = 'DECIDER_PANIC';
  ERR_MALFORMED_ENVELOPE         = 'MALFORMED_ENVELOPE';
  ERR_INTERNAL_ERROR             = 'INTERNAL_ERROR';

  { --- UPSTREAM --- }
  ERR_GATEWAY_UNAVAILABLE        = 'GATEWAY_UNAVAILABLE';

  { How many codes there are: useful for checking a table is complete. }
  ERR_CODE_COUNT = 34;

  { Length of the longest code. A test compares it against
    MaxErrCodeLen in TcResult: adding a long code must not be
    silently truncated on assignment to TErrCode. }
  ERR_MAX_CODE_LEN = 22;

  { Codes returned by the decision function. The golden set
    must hold a case for each of them: a code nothing
    produces exists only on paper. }
  ERR_DECISION_CODE_COUNT = 23;
  ErrDecisionCodes: array[1..23] of PChar = (
    'NICK_FORMAT_INVALID',
    'POST_BODY_EMPTY',
    'POST_BODY_TOO_LONG',
    'COMMENT_BODY_EMPTY',
    'COMMENT_BODY_TOO_LONG',
    'BAN_REASON_EMPTY',
    'BAN_REASON_TOO_LONG',
    'DISPLAY_NAME_INVALID',
    'TEXT_ENCODING_INVALID',
    'ACTOR_BANNED',
    'NOT_OWNER',
    'ROLE_INSUFFICIENT',
    'CANNOT_MODERATE_PEER',
    'USER_NOT_FOUND',
    'POST_NOT_FOUND',
    'NICK_TAKEN',
    'SELF_FOLLOW',
    'ALREADY_FOLLOWING',
    'NOT_FOLLOWING',
    'ALREADY_BANNED',
    'POST_RATE_EXCEEDED',
    'COMMENT_RATE_EXCEEDED',
    'INSUFFICIENT_CONTEXT'
  );

