/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/events/events.yaml
 * Генератор: tools/gen-api-types/gen_events.py
 * Перегенерация: ./sonder codegen
 */

/**
 * Типы доменных событий.
 *
 * Список нужен в РАНТАЙМЕ: слушатель SSE ставится на каждое имя
 * отдельно, и событие, на которое никто не подписан, приходит и
 * пропадает молча.
 */
export const EVENT_TYPES = [
  'user.registered',
  'user.banned',
  'post.created',
  'post.deleted',
  'comment.created',
  'follow.created',
  'follow.removed',
] as const

export type EventType =
  | 'user.registered'
  | 'user.banned'
  | 'post.created'
  | 'post.deleted'
  | 'comment.created'
  | 'follow.created'
  | 'follow.removed'

/**
 * Полезная нагрузка каждого события.
 *
 * Событие несёт ИДЕНТИЧНОСТЬ, а не копию агрегата: тела поста
 * и отображаемого имени здесь нет, они читаются из проекций.
 */
export interface EventPayloads {
  /** Пользователь заведён. Первое событие всякой учётной записи. */
  'user.registered': {
    readonly userId: string
    /** Ник, по которому пользователя ищут и упоминают. */
    readonly nick: string
  }
  /**
   * Пользователь заблокирован модератором. Содержимое остаётся, но
   * создавать новое он больше не может — это решает ядро, а не проекция.
   */
  'user.banned': {
    readonly targetUserId: string
    /** Причина, как её ввёл модератор. */
    readonly reason: string
    /** Кто заблокировал. Нужен для разбора спорных случаев. */
    readonly bannedBy: string
  }
  /**
   * Пост создан. Разворачивается фанаутом в ленты подписчиков автора; тело
   * и время берутся из write-модели, а не из полезной нагрузки —
   * дублировать их здесь значило бы завести второй источник правды.
   */
  'post.created': {
    readonly postId: string
    /** Автор поста. По нему ищутся подписчики при фанауте. */
    readonly authorId: string
  }
  /**
   * Пост удалён. Из лент убирается; сама запись в write-модели остаётся со
   * статусом DELETED, потому что удаление идемпотентно и должно отличаться
   * от «поста никогда не было».
   */
  'post.deleted': {
    readonly postId: string
    /**
     * Кто удалил. Автор и модератор — разные случаи, и по событию их надо
     * различать.
     */
    readonly deletedBy: string
  }
  /** Комментарий написан к посту. */
  'comment.created': {
    readonly commentId: string
    /** Пост, к которому написан комментарий. */
    readonly postId: string
    /** Автор комментария. */
    readonly authorId: string
  }
  /**
   * Подписка заведена. Лента подписчика дополняется постами того, на кого
   * подписались.
   */
  'follow.created': {
    readonly followerId: string
    /** На кого подписались. */
    readonly targetUserId: string
  }
  /**
   * Подписка снята. Зеркало follow.created: из ленты убирается то, что она
   * добавила.
   */
  'follow.removed': {
    readonly followerId: string
    /** От кого отписались. */
    readonly targetUserId: string
  }
}
