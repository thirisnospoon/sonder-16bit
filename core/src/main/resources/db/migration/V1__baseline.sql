/*
  Базовая схема.

  ЧТО ЗДЕСЬ ЕСТЬ И ЧЕГО НЕТ. Здесь только write-модель и очередь outbox.
  Проекции read-модели появятся в фазе 7 отдельными миграциями: они
  выводятся из событий и потому переживают пересборку, а таблицы
  агрегатов — нет.

  ОГРАНИЧЕНИЯ CHECK НА ПЕРЕЧИСЛЕНИЯХ — НЕ ДУБЛИРОВАНИЕ ДОМЕННОГО ПРАВИЛА.
  Ядро решает, ЧТО РАЗРЕШЕНО СДЕЛАТЬ; эти ограничения решают, ЧТО ВООБЩЕ
  ПРЕДСТАВИМО. Роль «КОРОЛЬ» — не отказанное действие, а испорченная
  строка, и ловить её надо там же, где ловят испорченный тип.

  ДЛИНЫ И ПЕРЕЧИСЛЕНИЯ СВЕРЯЮТСЯ С КОНТРАКТОМ. Комментарии вида
  `-- limit: имя` и `-- enum: Имя` читает tools/validate-contracts: длина
  VARCHAR обязана совпасть с границей из limits.yaml, список в CHECK — с
  перечислением из WSDL. Без сверки схема разошлась бы с ядром молча, и
  разошлась бы ровно так, как уже расходились байты с символами.

  ДЛИНА VARCHAR В FIREBIRD СЧИТАЕТСЯ В СИМВОЛАХ для колонки с набором
  символов. Поэтому VARCHAR(60) CHARACTER SET UTF8 — это шестьдесят
  символов, а не шестьдесят байт, и совпадает с maxLength контракта
  буквально (ADR-0014).
*/

CREATE TABLE users (
  id             VARCHAR(40)  CHARACTER SET UTF8 NOT NULL,
  -- limit: nick_max_len
  nick           VARCHAR(20)  CHARACTER SET UTF8 NOT NULL,
  -- limit: display_name_max_len
  display_name   VARCHAR(60)  CHARACTER SET UTF8 NOT NULL,
  -- enum: Role
  role           VARCHAR(16)  CHARACTER SET UTF8 NOT NULL,
  -- enum: UserStatus
  status         VARCHAR(16)  CHARACTER SET UTF8 NOT NULL,
  password_hash  VARCHAR(100) CHARACTER SET ASCII NOT NULL,
  /* Оптимистическая блокировка. Ядро видит версию в контексте команды и
     возвращает решение под неё; оболочка сохраняет с проверкой, что
     версия не сдвинулась, иначе команду надо переиграть. */
  version        INTEGER      DEFAULT 0 NOT NULL,
  created_at     TIMESTAMP    NOT NULL,
  CONSTRAINT pk_users PRIMARY KEY (id),
  CONSTRAINT ck_users_role CHECK (role IN ('USER', 'MODERATOR', 'ADMIN')),
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BANNED', 'DELETED'))
);

/* Ник уникален без учёта регистра: «Andrey» и «andrey» — один и тот же
   занятый ник. Ядро при этом регистр НЕ приводит и «Andrey» отвергает по
   форме; приведение — работа оболочки при проверке занятости, и вот она.
   Хранится нормализованный вид, иначе уникальность пришлось бы искать
   выражением, а Firebird по выражению индекс строит только вычисляемый. */
CREATE UNIQUE INDEX ux_users_nick ON users (nick);

CREATE TABLE posts (
  id          VARCHAR(40)   CHARACTER SET UTF8 NOT NULL,
  author_id   VARCHAR(40)   CHARACTER SET UTF8 NOT NULL,
  -- limit: post_body_max_len
  body        VARCHAR(1000) CHARACTER SET UTF8 NOT NULL,
  -- enum: PostStatus
  status      VARCHAR(16)   CHARACTER SET UTF8 NOT NULL,
  version     INTEGER       DEFAULT 0 NOT NULL,
  created_at  TIMESTAMP     NOT NULL,
  CONSTRAINT pk_posts PRIMARY KEY (id),
  CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users (id),
  CONSTRAINT ck_posts_status CHECK (status IN ('VISIBLE', 'DELETED'))
);

/* Лента автора и подсчёт постов за час — по одному индексу. */
CREATE INDEX ix_posts_author_created ON posts (author_id, created_at);

CREATE TABLE comments (
  id          VARCHAR(40)  CHARACTER SET UTF8 NOT NULL,
  post_id     VARCHAR(40)  CHARACTER SET UTF8 NOT NULL,
  author_id   VARCHAR(40)  CHARACTER SET UTF8 NOT NULL,
  -- limit: comment_body_max_len
  body        VARCHAR(500) CHARACTER SET UTF8 NOT NULL,
  version     INTEGER      DEFAULT 0 NOT NULL,
  created_at  TIMESTAMP    NOT NULL,
  CONSTRAINT pk_comments PRIMARY KEY (id),
  CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id)
);

CREATE INDEX ix_comments_post ON comments (post_id, created_at);
CREATE INDEX ix_comments_author_created ON comments (author_id, created_at);

CREATE TABLE follows (
  follower_id  VARCHAR(40) CHARACTER SET UTF8 NOT NULL,
  target_id    VARCHAR(40) CHARACTER SET UTF8 NOT NULL,
  created_at   TIMESTAMP   NOT NULL,
  CONSTRAINT pk_follows PRIMARY KEY (follower_id, target_id),
  CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id),
  CONSTRAINT fk_follows_target FOREIGN KEY (target_id) REFERENCES users (id),
  /* Самоподписку отвергает ядро, но представимой она быть тоже не должна:
     строка follower = target — это порча, а не отказанное действие. */
  CONSTRAINT ck_follows_not_self CHECK (follower_id <> target_id)
);

CREATE INDEX ix_follows_target ON follows (target_id);

CREATE TABLE sessions (
  token       VARCHAR(64) CHARACTER SET ASCII NOT NULL,
  user_id     VARCHAR(40) CHARACTER SET UTF8  NOT NULL,
  created_at  TIMESTAMP   NOT NULL,
  expires_at  TIMESTAMP   NOT NULL,
  /* Сессию никто не правит параллельно, и версия ей не нужна. Она здесь
     ради того, чтобы правило «у каждого агрегата есть версия» осталось
     сплошным: правило со списком исключений однажды пополнится тем
     агрегатом, которому версия была нужна. */
  version     INTEGER     DEFAULT 0 NOT NULL,
  CONSTRAINT pk_sessions PRIMARY KEY (token),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_sessions_user ON sessions (user_id);

/*
  Очередь исходящих событий.

  Источник правды для конвейера — именно эта таблица, а не уведомление
  POST_EVENT: событие Firebird недолговечно и полезной нагрузки не несёт,
  оно только дверной звонок (ADR-0003, ADR-0013). Потерянное уведомление
  означает задержку, потерянная строка означала бы потерянное событие.

  Строка пишется В ТОЙ ЖЕ ТРАНЗАКЦИИ, что и изменение агрегата. Иначе
  между сохранением и публикацией возникает окно, в котором система
  считает действие совершённым, а мир о нём не знает.
*/
CREATE TABLE outbox (
  id            BIGINT GENERATED BY DEFAULT AS IDENTITY,
  aggregate_id  VARCHAR(40) CHARACTER SET UTF8 NOT NULL,
  event_type    VARCHAR(64) CHARACTER SET UTF8 NOT NULL,
  /* Полезная нагрузка — JSON по схеме из contracts/events. BLOB, а не
     VARCHAR: предела на размер события контракт не задаёт, и упереться в
     него посреди ночи хуже, чем платить за BLOB. */
  payload       BLOB SUB_TYPE TEXT CHARACTER SET UTF8 NOT NULL,
  trace_id      VARCHAR(40) CHARACTER SET UTF8,
  created_at    TIMESTAMP   NOT NULL,
  /* Попытки считаются, чтобы ядовитое событие было видно, а не крутилось
     в очереди вечно. */
  attempts      INTEGER     DEFAULT 0 NOT NULL,
  published_at  TIMESTAMP,
  CONSTRAINT pk_outbox PRIMARY KEY (id)
);

/* Неопубликованные в порядке появления. Порядок по id, а не по времени:
   часы могут сдвинуться, а идентичность — нет. */
CREATE INDEX ix_outbox_pending ON outbox (published_at, id);
