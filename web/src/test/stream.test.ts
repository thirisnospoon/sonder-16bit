/*
 * Поток обновлений.
 *
 * Главное здесь — что подписка стоит на КАЖДОМ типе из каталога.
 * Событие, на которое никто не подписан, приходит и пропадает молча: ни
 * ошибки, ни следа, ни записи в консоли. Такую потерю не найти иначе,
 * чем пересчитав подписки.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { openStream, type EventSourceLike } from '../core/stream.js'
import { EVENT_TYPES } from '../generated/events.js'

interface Fake extends EventSourceLike {
  listeners: Map<string, Array<(event: MessageEvent) => void>>
  closed: boolean
  // Перекрываем `readonly` из EventSourceLike: настоящий EventSource
  // меняет состояние сам, подделке надо это уметь руками.
  readyState: number
  emit(type: string, data: string, id?: string): void
}

function fakeSource(): Fake {
  const listeners = new Map<string, Array<(event: MessageEvent) => void>>()
  const source: Fake = {
    listeners,
    closed: false,
    onopen: null,
    onerror: null,
    // 1 — открыто. Подделка живёт, пока её не закроют.
    readyState: 1,
    addEventListener(type, listener) {
      const existing = listeners.get(type) ?? []
      existing.push(listener)
      listeners.set(type, existing)
    },
    close() {
      source.closed = true
      source.readyState = 2
    },
    emit(type, data, id = '') {
      for (const listener of listeners.get(type) ?? []) {
        listener({ data, lastEventId: id } as MessageEvent)
      }
    },
  }
  return source
}

test('подписка стоит на каждом типе из каталога', () => {
  // Не «на нескольких» и не «на основных». Тип, забытый в списке,
  // означает событие, которое приезжает и не делает ничего.
  const source = fakeSource()
  openStream('/api/events', { open: () => source })

  for (const type of EVENT_TYPES) {
    assert.equal(
      source.listeners.get(type)?.length,
      1,
      `нет подписки на ${type}`,
    )
  }
  assert.equal(
    source.listeners.size,
    EVENT_TYPES.length,
    'подписок больше, чем типов в каталоге',
  )
})

test('пришедшее событие разбирается и попадает в сигнал', () => {
  const source = fakeSource()
  const stream = openStream('/api/events', { open: () => source })

  // Сравнение вынесено в выражение намеренно. `assert.equal(x, null)`
  // из node:assert/strict — УТВЕРЖДАЮЩАЯ подпись: она сужает свойство к
  // null до конца области, и всякое следующее чтение того же сигнала
  // становится `never`. Проверка типов не знает, что в него пишут из
  // обработчика.
  assert.equal(stream.last.value === null, true, 'сигнал не пуст до события')

  source.emit('post.created', JSON.stringify({ postId: 'p-1', authorId: 'u-1' }), '42')

  const arrived = stream.last.value
  if (arrived === null) {
    throw new Error('событие не дошло до сигнала')
  }
  assert.equal(arrived.type, 'post.created')
  assert.equal(arrived.id, '42')
  assert.deepEqual(arrived.payload, { postId: 'p-1', authorId: 'u-1' })
  assert.equal(stream.received.value, 1)
})

test('неразобранная нагрузка не молчит и не считается событием', () => {
  // Расхождение сторон. Промолчать — значит потерять событие и развести
  // ленту с базой без единого следа.
  const source = fakeSource()
  const bad: Array<[string, string]> = []
  const stream = openStream('/api/events', {
    open: () => source,
    onBadPayload: (type, raw) => bad.push([type, raw]),
  })

  source.emit('post.created', 'это не json')

  assert.deepEqual(bad, [['post.created', 'это не json']])
  assert.equal(stream.last.value, null, 'испорченное событие попало в сигнал')
  assert.equal(stream.received.value, 0, 'испорченное событие посчитано')
})

test('состояние потока следует за открытием и разрывом', () => {
  const source = fakeSource()
  const stream = openStream('/api/events', { open: () => source })

  assert.equal(stream.open.value, false, 'поток считается открытым до открытия')

  source.onopen?.(new Event('open'))
  assert.equal(stream.open.value, true)

  source.onerror?.(new Event('error'))
  assert.equal(stream.open.value, false, 'разрыв не замечен')

  // Разрыв — не поломка: EventSource переподключается сам.
  source.onopen?.(new Event('open'))
  assert.equal(stream.open.value, true, 'после разрыва поток не восстановился')
})

test('закрытие закрывает источник и снимает признак открытости', () => {
  const source = fakeSource()
  const stream = openStream('/api/events', { open: () => source })
  source.onopen?.(new Event('open'))

  stream.close()

  assert.equal(source.closed, true, 'источник остался открытым')
  assert.equal(stream.open.value, false)
})

test('счётчик растёт на каждое событие, а сигнал держит последнее', () => {
  const source = fakeSource()
  const stream = openStream('/api/events', { open: () => source })

  source.emit('post.created', JSON.stringify({ postId: 'p-1', authorId: 'u-1' }))
  source.emit('post.deleted', JSON.stringify({ postId: 'p-1', deletedBy: 'u-1' }))

  assert.equal(stream.received.value, 2)
  assert.equal(stream.last.value?.type, 'post.deleted')
})
