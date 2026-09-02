{ СГЕНЕРИРОВАНО. Не править руками.

  Источник: contracts/errors/errors.yaml
  Генератор: tools/gen-errors/gen_errors.py
  Перегенерация: ./sonder codegen

  Правка этого файла будет затёрта, а расхождение с источником
  поймано проверкой дрейфа в CI.
}

{ Коды отказа передаются по линии строками и сравниваются
  побайтно, поэтому здесь константы, а не перечислимый тип. }

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

  { Число кодов: полезно для проверки полноты таблиц. }
  ERR_CODE_COUNT = 32;

  { Длина самого длинного кода. Тест сверяет её с MaxErrCodeLen
    из TcResult: добавление длинного кода не должно молча
    обрезаться при присваивании в TErrCode. }
  ERR_MAX_CODE_LEN = 22;

  { Коды, которые возвращает функция решения. По каждому из них
    golden-набор обязан иметь случай: код, который ничто не
    порождает, существует только на бумаге. }
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

