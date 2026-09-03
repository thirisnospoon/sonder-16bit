/*
 * Клиент REST: что уходит на сервер и что приходит обратно.
 *
 * Сервер здесь подставной, и это правильный уровень: настоящий ответ
 * проверяется интеграционными тестами оболочки и сценариями Playwright,
 * а тут проверяется СБОРКА запроса — то место, где клиент может тихо
 * разойтись с контрактом.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { ApiFailure, ApiUnreachable, createClient } from '../core/api.js'
import { API_METHODS } from '../generated/paths.js'

interface Sent {
  url: string
  init: RequestInit
}

function stub(
  reply: () => Response | Promise<Response>,
): { fetcher: typeof fetch; sent: Sent[] } {
  const sent: Sent[] = []
  const fetcher = (async (url: string, init: RequestInit) => {
    sent.push({ url, init })
    return reply()
  }) as unknown as typeof fetch
  return { fetcher, sent }
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

test('метод берётся из контракта, а не из таблицы в коде', () => {
  // Не умозрительная проверка. Таблица, набранная руками при написании
  // клиента, объявляла follow методом POST — в контракте стоит PUT.
  // Запрос уходил бы в 405, и заметить это можно было бы только нажав
  // кнопку «подписаться».
  assert.equal(API_METHODS.follow, 'PUT')
  assert.equal(API_METHODS.unfollow, 'DELETE')
  assert.equal(API_METHODS.me, 'GET')
})

test('переменные пути подставляются и кодируются', async () => {
  const { fetcher, sent } = stub(() => new Response(null, { status: 204 }))
  const client = createClient('/api', fetcher)

  await client.call('follow', null, { params: { nick: 'андрей' } })

  assert.equal(sent.length, 1)
  assert.equal(
    sent[0]?.url,
    '/api/users/%D0%B0%D0%BD%D0%B4%D1%80%D0%B5%D0%B9/follow',
  )
  assert.equal(sent[0]?.init.method, 'PUT')
})

test('пропущенная переменная пути — отказ до запроса', async () => {
  const { fetcher, sent } = stub(() => new Response(null, { status: 204 }))
  const client = createClient('/api', fetcher)

  await assert.rejects(
    () => client.call('getPost', null, {}),
    /не задана переменная postId/,
  )
  assert.equal(sent.length, 0, 'запрос всё-таки ушёл')
})

test('пустые параметры запроса не отправляются', async () => {
  // `?cursor=` — это не «первая страница», а «страница с пустым
  // курсором», и сервер вправе счесть это ошибкой.
  const { fetcher, sent } = stub(() => json(200, { items: [], hasMore: false }))
  const client = createClient('/api', fetcher)

  await client.call('getFeed', null, {
    query: { cursor: undefined, limit: 20 },
  })

  assert.equal(sent[0]?.url, '/api/feed?limit=20')
})

test('ответ без тела приходит как null, а не как ошибка разбора', async () => {
  const { fetcher } = stub(() => new Response(null, { status: 204 }))
  const client = createClient('/api', fetcher)

  const result = await client.call('login', { nick: 'андрей', password: 'секретище' })
  assert.equal(result, null)
})

test('тело запроса уходит как JSON с нужным заголовком', async () => {
  const { fetcher, sent } = stub(() => new Response(null, { status: 204 }))
  const client = createClient('/api', fetcher)

  await client.call('login', { nick: 'андрей', password: 'секретище' })

  const headers = sent[0]?.init.headers as Record<string, string>
  assert.equal(headers['Content-Type'], 'application/json')
  assert.deepEqual(JSON.parse(String(sent[0]?.init.body)), {
    nick: 'андрей',
    password: 'секретище',
  })
})

test('запрос без тела не объявляет Content-Type', async () => {
  // Заголовок при пустом теле — мелкая неправда, но она заставляет
  // браузер слать предварительный запрос там, где он не нужен.
  const { fetcher, sent } = stub(() => json(200, { userId: 'u-1' }))
  const client = createClient('/api', fetcher)

  await client.call('me', null)

  const headers = sent[0]?.init.headers as Record<string, string>
  assert.equal(headers['Content-Type'], undefined)
  assert.equal(sent[0]?.init.body, undefined)
})

test('отказ сервера приходит кодом из контракта', async () => {
  const { fetcher } = stub(() =>
    json(429, {
      code: 'POST_RATE_EXCEEDED',
      detail: 'не больше десяти в час',
      traceId: 't-1',
    }),
  )
  const client = createClient('/api', fetcher)

  await assert.rejects(
    () => client.call('createPost', { body: 'привет' }),
    (error: unknown) => {
      assert.ok(error instanceof ApiFailure)
      assert.equal(error.status, 429)
      assert.equal(error.code, 'POST_RATE_EXCEEDED')
      assert.equal(error.detail, 'не больше десяти в час')
      assert.equal(error.traceId, 't-1')
      return true
    },
  )
})

test('неразобранное тело отказа не превращается в доменный код', async () => {
  // Выдуманный код соврал бы о причине: обработчик наверху принял бы
  // сбой шлюза за решение домена.
  const { fetcher } = stub(
    () => new Response('<html>502</html>', { status: 502 }),
  )
  const client = createClient('/api', fetcher)

  await assert.rejects(
    () => client.call('me', null),
    (error: unknown) => {
      assert.ok(error instanceof ApiFailure)
      assert.equal(error.code, 'HTTP_502')
      return true
    },
  )
})

test('отсутствие ответа — не отказ сервера', async () => {
  // Сведи их к одному исключению, и «сеть отвалилась» станет
  // неотличимо от «система отказала»: первое повторяют, второе нет.
  const { fetcher } = stub(() => {
    throw new TypeError('failed to fetch')
  })
  const client = createClient('/api', fetcher)

  await assert.rejects(
    () => client.call('me', null),
    (error: unknown) => {
      assert.ok(error instanceof ApiUnreachable, 'сетевой сбой выдан за отказ')
      assert.equal(error.operation, 'me')
      return true
    },
  )
})

test('адрес операции можно получить отдельно', async () => {
  // Поток событий открывается не через fetch, а EventSource, и адрес ему
  // нужен тот же самый — собранный из контракта, а не набранный рядом.
  const { fetcher } = stub(() => new Response(null, { status: 204 }))
  const client = createClient('/api', fetcher)

  assert.equal(client.url('subscribe'), '/api/events')
  assert.equal(
    client.url('getPost', { params: { postId: 'p-1001' } }),
    '/api/posts/p-1001',
  )
})
