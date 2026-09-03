/*
 * Кто сейчас в системе.
 *
 * Сессия живёт в куке, а не в памяти страницы: перезагрузка не должна
 * выкидывать пользователя. Отсюда следует, что состояние здесь —
 * ОТРАЖЕНИЕ серверного, а не источник. Единственный способ узнать, кто
 * мы, — спросить сервер; всё остальное было бы догадкой, которая
 * разойдётся с правдой ровно тогда, когда сессия истечёт.
 */
import type { Client } from '../core/api.js'
import { ApiFailure, ApiUnreachable } from '../core/api.js'
import type { Me } from '../generated/api.js'
import { effect, signal, type ReadSignal } from '../core/reactive.js'

/**
 * Что известно о текущем пользователе.
 *
 * Три состояния, а не два. «Ещё не спрашивали» и «спросили, никого нет»
 * — разные вещи: показать форму входа в первом случае значит мигнуть ею
 * тому, кто на самом деле вошёл.
 */
export type Who =
  | { readonly state: 'неизвестно' }
  | { readonly state: 'гость' }
  | { readonly state: 'свой'; readonly me: Me }
  | { readonly state: 'нет связи'; readonly why: string }

export interface Session {
  readonly who: ReadSignal<Who>
  /** Спросить сервер. Зовётся при запуске и после входа или выхода. */
  refresh(): Promise<void>
  login(nick: string, password: string): Promise<void>
  logout(): Promise<void>
}

export function createSession(client: Client): Session {
  const who = signal<Who>({ state: 'неизвестно' })

  async function refresh(): Promise<void> {
    try {
      const me = await client.call('me', null)
      who.value = { state: 'свой', me }
    } catch (error) {
      if (error instanceof ApiFailure && error.status === 401) {
        // 401 — это ОТВЕТ: сервер знает, что нас не знает.
        who.value = { state: 'гость' }
        return
      }
      if (error instanceof ApiUnreachable) {
        // А это не ответ вовсе. Показать здесь «гость» значило бы
        // сказать пользователю, что он вышел, — хотя он не выходил.
        who.value = { state: 'нет связи', why: 'сервер не ответил' }
        return
      }
      who.value = {
        state: 'нет связи',
        why: error instanceof Error ? error.message : String(error),
      }
    }
  }

  async function login(nick: string, password: string): Promise<void> {
    await client.call('login', { nick, password })
    // Кто мы теперь — спрашиваем, а не выводим из того, что вход прошёл:
    // роль и отображаемое имя знает сервер.
    await refresh()
  }

  async function logout(): Promise<void> {
    try {
      await client.call('logout', null)
    } finally {
      // Выход состоялся, даже если сервер не ответил: держать
      // пользователя в системе после его же нажатия «выйти» —
      // худшее, что тут можно сделать.
      who.value = { state: 'гость' }
    }
  }

  return { who, refresh, login, logout }
}

/**
 * Читать — только когда есть кому, и перечитывать — только когда сменился
 * человек.
 *
 * ЧИТАТЬ, ПОКА НЕИЗВЕСТНО, КТО ПРИШЁЛ, НЕЛЬЗЯ, и это не оптимизация
 * запросов. В этой системе личное ВСЁ: и лента, и профиль, и отдельный
 * пост отвечают гостю 401 — отвечают правильно. Страница, спрашивающая
 * их при показе, получает отказ и честно его рисует: первый встречный
 * видел на первом же экране красную карточку «Нужно войти заново.
 * SESSION_INVALID». Ему сообщали, что истекла сессия, которой не было.
 *
 * Отсюда же и `забыть`: выход обязан убирать чужое с экрана. Лента
 * предыдущего человека, оставшаяся после выхода, — это не косметика.
 *
 * Сверять приходится идентификатор, а не объект: `refresh` кладёт новый
 * объект при каждом ответе сервера, даже когда человек тот же, и
 * перечитывание на каждый такой ответ дёргало бы страницу на ровном
 * месте.
 */
export function толькоСвоим(
  session: Session,
  читать: (me: Me) => void,
  забыть: () => void,
): void {
  let длякого: string | null = null

  effect(() => {
    const кто = session.who.value

    if (кто.state !== 'свой') {
      длякого = null
      забыть()
      return
    }

    if (длякого === кто.me.userId) {
      return
    }
    длякого = кто.me.userId
    читать(кто.me)
  })
}
