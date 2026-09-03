/*
 * Сессия: три состояния вместо двух.
 *
 * «Ещё не спрашивали», «спросили — никого» и «спросить не удалось» —
 * разные вещи, и свести их к «вошёл / не вошёл» значит однажды сказать
 * пользователю, что он вышел, когда всего лишь пропала сеть.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { ApiFailure, ApiUnreachable } from '../core/api.js'
import type { Client } from '../core/api.js'
import { createSession } from '../app/session.js'

const ME = {
  userId: 'u-1',
  nick: 'андрей',
  displayName: 'Андрей',
  role: 'USER',
} as const

function clientThat(
  answer: (name: string) => unknown,
): { client: Client; calls: string[] } {
  const calls: string[] = []
  const client = {
    async call(name: string): Promise<unknown> {
      calls.push(name)
      const result = answer(name)
      if (result instanceof Error) {
        throw result
      }
      return result
    },
    url(): string {
      return '/api'
    },
  } as unknown as Client
  return { client, calls }
}

test('до первого вопроса состояние — «неизвестно», а не «гость»', () => {
  // Показать форму входа сразу значит мигнуть ею тому, кто на самом
  // деле вошёл: кука есть, а спросить о ней ещё не успели.
  const { client } = clientThat(() => ME)
  const session = createSession(client)

  assert.equal(session.who.value.state, 'неизвестно')
})

test('сервер узнал пользователя — состояние «свой»', async () => {
  const { client, calls } = clientThat(() => ME)
  const session = createSession(client)

  await session.refresh()

  assert.deepEqual(calls, ['me'])
  const who = session.who.value
  assert.equal(who.state, 'свой')
  if (who.state === 'свой') {
    assert.equal(who.me.nick, 'андрей')
  }
})

test('401 — это ответ: сервер знает, что нас не знает', async () => {
  const { client } = clientThat(
    () => new ApiFailure(401, 'UNAUTHENTICATED', undefined, undefined),
  )
  const session = createSession(client)

  await session.refresh()

  assert.equal(session.who.value.state, 'гость')
})

test('нет связи — это НЕ «гость»', async () => {
  // Главная проверка. Сведи их вместе, и пропавшая на секунду сеть
  // покажет пользователю форму входа, хотя он никуда не выходил.
  const { client } = clientThat(
    () => new ApiUnreachable('me', new TypeError('failed to fetch')),
  )
  const session = createSession(client)

  await session.refresh()

  const who = session.who.value
  assert.equal(who.state, 'нет связи', 'сетевой сбой выдан за выход из системы')
  if (who.state === 'нет связи') {
    assert.ok(who.why.length > 0, 'причина не названа')
  }
})

test('вход спрашивает, кто мы теперь, а не выводит это сам', async () => {
  // Роль и отображаемое имя знает сервер. Вывести их из того, что вход
  // прошёл, значит завести второй источник правды о пользователе.
  const { client, calls } = clientThat((name) => (name === 'login' ? null : ME))
  const session = createSession(client)

  await session.login('андрей', 'секретище')

  assert.deepEqual(calls, ['login', 'me'])
  assert.equal(session.who.value.state, 'свой')
})

test('неудачный вход не меняет состояния и доносит отказ', async () => {
  const { client } = clientThat(
    () => new ApiFailure(401, 'BAD_CREDENTIALS', undefined, undefined),
  )
  const session = createSession(client)

  await assert.rejects(() => session.login('андрей', 'не то'), ApiFailure)
  assert.equal(
    session.who.value.state,
    'неизвестно',
    'неудачный вход изменил состояние',
  )
})

test('выход состоялся, даже если сервер не ответил', async () => {
  // Держать пользователя в системе после его же нажатия «выйти» — худшее
  // из возможного: он считает, что вышел, и уходит от чужого монитора.
  const { client } = clientThat(
    () => new ApiUnreachable('logout', new TypeError('failed to fetch')),
  )
  const session = createSession(client)

  await assert.rejects(() => session.logout(), ApiUnreachable)
  assert.equal(session.who.value.state, 'гость', 'выход не состоялся')
})
