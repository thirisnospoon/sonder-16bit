/*
 * Общие части страниц.
 *
 * Не «библиотека компонентов»: здесь ровно то, что встречается больше
 * одного раза. Заготовка, использованная однажды, — это лишний слой
 * между разметкой и тем, кто её читает.
 */
import { h, when, type Child } from '../core/dom.js'
import { ApiFailure, ApiUnreachable } from '../core/api.js'
import type { ReadSignal } from '../core/reactive.js'
import { ERROR_META, type ErrorCode } from '../generated/errors.js'

/**
 * Человеческое объяснение отказа.
 *
 * Код из контракта показывается РЯДОМ, а не вместо: пользователю нужна
 * фраза, а тому, кто будет разбираться, — код. Убери код, и обращение в
 * поддержку начнётся со слов «ну там красным написало».
 */
export function отказ(error: unknown): Child {
  if (error instanceof ApiUnreachable) {
    return h(
      'div',
      { class: 'отказ', role: 'alert' },
      h('span', null, 'Сервер не ответил. Проверьте связь и повторите.'),
    )
  }
  if (error instanceof ApiFailure) {
    return h(
      'div',
      { class: 'отказ', role: 'alert' },
      h('span', null, объяснение(error.code)),
      h('span', { class: 'отказ__код' }, error.code),
    )
  }
  return h(
    'div',
    { class: 'отказ', role: 'alert' },
    h('span', null, 'Что-то пошло не так.'),
  )
}

/** Фразы для кодов, которые пользователь видит чаще прочих. */
const ФРАЗЫ: Partial<Record<ErrorCode, string>> = {
  SESSION_INVALID: 'Нужно войти заново.',
  CREDENTIALS_INVALID: 'Ник или пароль не подошли.',
  ACTOR_BANNED: 'Учётная запись заблокирована.',
  NICK_TAKEN: 'Такой ник уже занят.',
  NICK_FORMAT_INVALID: 'Ник может состоять из строчных букв, цифр и подчёркивания.',
  POST_BODY_EMPTY: 'Пост не может быть пустым.',
  POST_BODY_TOO_LONG: 'Пост слишком длинный.',
  SELF_FOLLOW: 'На себя подписаться нельзя.',
  ALREADY_FOLLOWING: 'Вы уже подписаны.',
  NOT_FOLLOWING: 'Вы и так не подписаны.',
  POST_RATE_EXCEEDED: 'Слишком часто. Подождите немного.',
  COMMENT_RATE_EXCEEDED: 'Слишком часто. Подождите немного.',
  LOGIN_RATE_EXCEEDED: 'Слишком много попыток входа.',
  DECIDER_UNAVAILABLE: 'Ядро недоступно. Попробуйте позже.',
  STATE_VERSION_CONFLICT: 'Данные изменились. Обновите страницу и повторите.',
}

/** Запасная фраза по КАТЕГОРИИ кода — она объявлена тем же контрактом. */
const ПО_КАТЕГОРИИ: Record<string, string> = {
  VALIDATION: 'Данные не подошли.',
  AUTH: 'Нужно войти.',
  PERMISSION: 'Недостаточно прав.',
  NOT_FOUND: 'Не найдено.',
  CONFLICT: 'Так сделать нельзя.',
  RATE_LIMIT: 'Слишком часто. Подождите немного.',
  UPSTREAM: 'Ядро недоступно. Попробуйте позже.',
  INTERNAL: 'Внутренняя ошибка. Мы уже знаем.',
}

/**
 * Фраза по коду отказа.
 *
 * Незнакомый код НЕ выдумывается: сначала ищется своя фраза, затем
 * фраза категории — категория объявлена тем же контрактом, поэтому у
 * всякого нового кода объяснение появляется само. Кода нет в контракте
 * вовсе — говорим об этом прямо, а не подставляем правдоподобное.
 */
function объяснение(code: string): string {
  const meta = (ERROR_META as Record<string, { category: string } | undefined>)[
    code
  ]
  if (meta === undefined) {
    return 'Сервер ответил кодом, которого нет в контракте.'
  }
  return (
    ФРАЗЫ[code as ErrorCode] ?? ПО_КАТЕГОРИИ[meta.category] ?? 'Отказано.'
  )
}

/** Пустое место с объяснением, а не молчаливая пустая страница. */
export function пусто(текст: string, подсказка?: string): Child {
  return h(
    'div',
    { class: 'пусто' },
    h('p', null, текст),
    подсказка === undefined ? null : h('p', { class: 'подпись' }, подсказка),
  )
}

/** Признак живого потока обновлений. */
export function метка(поток: ReadSignal<boolean>): Child {
  return h(
    'span',
    {
      class: {
        'метка-потока': true,
        'метка-потока--живая': () => поток.value,
      },
      // Читалке экрана важна не зелёная точка, а что она значит.
      'aria-live': 'polite',
    },
    when(
      () => поток.value,
      () => 'обновления приходят',
      () => 'обновления не приходят',
    ),
  )
}

/**
 * Время поста в виде, который читается.
 *
 * Абсолютное время остаётся в атрибуте: «5 минут назад» нельзя
 * скопировать, сослаться на него и сравнить с чем-то.
 */
export function когда(iso: string): Child {
  const дата = new Date(iso)
  return h(
    'time',
    { class: 'подпись', datetime: iso, title: дата.toLocaleString('ru') },
    относительно(дата),
  )
}

const ШКАЛА: ReadonlyArray<readonly [Intl.RelativeTimeFormatUnit, number]> = [
  ['minute', 60],
  ['hour', 3600],
  ['day', 86400],
  ['week', 604800],
  ['month', 2629800],
  ['year', 31557600],
]

function относительно(дата: Date): string {
  const секунд = Math.round((Date.now() - дата.getTime()) / 1000)
  if (секунд < 60) {
    return 'только что'
  }
  const формат = new Intl.RelativeTimeFormat('ru', { numeric: 'auto' })
  let выбор: readonly [Intl.RelativeTimeFormatUnit, number] = ['minute', 60]
  for (const пара of ШКАЛА) {
    if (секунд >= пара[1]) {
      выбор = пара
    }
  }
  return формат.format(-Math.round(секунд / выбор[1]), выбор[0])
}
