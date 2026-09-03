/*
 * Поток обновлений, привязанный к сессии.
 *
 * Здесь стерегут ровно один дефект, и дефект был настоящий: страница
 * открывала поток при загрузке, когда ещё неизвестно, кто пришёл.
 * Гость получал от `/events` ответ 401, а `EventSource` при ответе не
 * 200 закрывается НАВСЕГДА — в отличие от обрыва, после которого
 * возвращается сам. Вход после этого уже ничего не менял: обновления
 * не приходили никому и никогда.
 *
 * Снаружи это неотличимо от исправной системы. Лента и так читается
 * запросом, счётчик «показать новое» просто молчит, а молчащий счётчик
 * выглядит как отсутствие новостей. Поэтому проверка считает ОТКРЫТИЯ:
 * ничего другого этот дефект не сдвигает.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { потокСессии } from '../app/stream.js'
import { signal } from '../core/reactive.js'
import type { Client } from '../core/api.js'
import type { EventSourceLike } from '../core/stream.js'
import type { Session, Who } from '../app/session.js'

interface Fake extends EventSourceLike {
  url: string
  closed: boolean
  emit(type: string, data: string, id?: string): void
  fail(): void
}

/** Кто открывался, в каком порядке и что с ними стало. */
function записнаяКнижка(): {
  readonly открытые: Fake[]
  open(url: string): EventSourceLike
} {
  const открытые: Fake[] = []
  return {
    открытые,
    open(url: string): EventSourceLike {
      const listeners = new Map<string, Array<(event: MessageEvent) => void>>()
      const source: Fake = {
        url,
        closed: false,
        onopen: null,
        onerror: null,
        addEventListener(type, listener) {
          const было = listeners.get(type) ?? []
          было.push(listener)
          listeners.set(type, было)
        },
        close() {
          source.closed = true
        },
        emit(type, data, id = '1') {
          for (const listener of listeners.get(type) ?? []) {
            listener({ data, lastEventId: id } as MessageEvent)
          }
        },
        fail() {
          source.onerror?.(new Event('error'))
        },
      }
      открытые.push(source)
      return source
    },
  }
}

function сессия(): { readonly session: Session; стать(who: Who): void } {
  const who = signal<Who>({ state: 'неизвестно' })
  return {
    session: {
      who,
      refresh: () => Promise.resolve(),
      login: () => Promise.resolve(),
      logout: () => Promise.resolve(),
    },
    стать(next: Who): void {
      who.value = next
    },
  }
}

const client = {
  url: () => '/api/events',
} as unknown as Client

test('пока неизвестно, кто пришёл, поток не открывается', () => {
  const книжка = записнаяКнижка()
  const { session } = сессия()

  потокСессии(client, session, { open: книжка.open })

  assert.equal(книжка.открытые.length, 0)
})

test('гостю поток не открывают: ответ 401 убил бы его навсегда', () => {
  const книжка = записнаяКнижка()
  const { session, стать } = сессия()

  потокСессии(client, session, { open: книжка.open })
  стать({ state: 'гость' })

  assert.equal(книжка.открытые.length, 0)
})

test('поток открывается, когда сессия стала своей', () => {
  const книжка = записнаяКнижка()
  const { session, стать } = сессия()

  потокСессии(client, session, { open: книжка.open })
  стать({
    state: 'свой',
    me: { userId: 'u1', nick: 'кто', displayName: 'Кто', role: 'USER' },
  })

  assert.equal(книжка.открытые.length, 1)
})

test('события доходят до подписавшегося на общие сигналы', () => {
  const книжка = записнаяКнижка()
  const { session, стать } = сессия()

  const поток = потокСессии(client, session, { open: книжка.open })
  стать({
    state: 'свой',
    me: { userId: 'u1', nick: 'кто', displayName: 'Кто', role: 'USER' },
  })

  const первый = книжка.открытые[0]
  assert.notEqual(первый, undefined)
  первый?.emit('post.created', JSON.stringify({ postId: 'p1' }), '7')

  assert.equal(поток.received.value, 1)
  assert.equal(поток.last.value?.type, 'post.created')
})

test('выход закрывает поток', () => {
  const книжка = записнаяКнижка()
  const { session, стать } = сессия()

  const поток = потокСессии(client, session, { open: книжка.open })
  стать({
    state: 'свой',
    me: { userId: 'u1', nick: 'кто', displayName: 'Кто', role: 'USER' },
  })
  стать({ state: 'гость' })

  assert.equal(книжка.открытые[0]?.closed, true)
  assert.equal(поток.open.value, false)
})

test('повторный вход открывает поток заново', () => {
  const книжка = записнаяКнижка()
  const { session, стать } = сессия()
  const свой: Who = {
    state: 'свой',
    me: { userId: 'u1', nick: 'кто', displayName: 'Кто', role: 'USER' },
  }

  const поток = потокСессии(client, session, { open: книжка.open })
  стать(свой)
  стать({ state: 'гость' })
  стать(свой)

  assert.equal(книжка.открытые.length, 2)

  // И — главное — новый поток питает ТЕ ЖЕ сигналы. Отдай мы наружу
  // сигналы конкретного соединения, подписавшийся однажды перестал бы
  // получать события после первого же переоткрытия, и заметить это
  // было бы нечем: ошибок нет, поток открыт, событий нет.
  книжка.открытые[1]?.emit('post.created', JSON.stringify({ postId: 'p2' }), '9')
  assert.equal(поток.last.value?.id, '9')
})

test('обновление своей же сессии не рвёт поток', () => {
  const книжка = записнаяКнижка()
  const { session, стать } = сессия()

  // `refresh` кладёт НОВЫЙ объект при каждом ответе сервера, даже когда
  // пользователь тот же. Переоткрытие потока на каждый такой ответ
  // означало бы разрыв на ровном месте.
  const поток = потокСессии(client, session, { open: книжка.open })
  стать({
    state: 'свой',
    me: { userId: 'u1', nick: 'кто', displayName: 'Кто', role: 'USER' },
  })
  стать({
    state: 'свой',
    me: { userId: 'u1', nick: 'кто', displayName: 'Кто', role: 'USER' },
  })

  assert.equal(книжка.открытые.length, 1)
  assert.equal(книжка.открытые[0]?.closed, false)

  // Подкачка при этом обязана уцелеть: она заводится заново каждый
  // прогон, потому что прогон снимает эффекты предыдущего.
  книжка.открытые[0]?.emit('post.created', JSON.stringify({ postId: 'p3' }), '3')
  assert.equal(поток.last.value?.id, '3')
})
