/*
 * Списки с ключами и условное содержимое.
 *
 * Главное здесь — не «список отрисовался», а «переживший элемент остался
 * ТЕМ ЖЕ узлом». Разница видна не по разметке, а по тому, что происходит
 * с пользователем: фокус в поле, прокрутка, выделение и анимация живут
 * на узле, и перерисовка их обрывает. Поэтому узлы сравниваются по
 * тождеству, а не по содержимому.
 */
import { test, before } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

import { each, h, mount, when } from '../core/dom.js'
import { signal } from '../core/reactive.js'

before(() => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>')
  const global = globalThis as unknown as Record<string, unknown>
  global['document'] = dom.window.document
  global['Node'] = dom.window.Node
  global['HTMLElement'] = dom.window.HTMLElement
})

function body(): HTMLElement {
  document.body.innerHTML = ''
  return document.body
}

interface Post {
  id: string
  body: string
}

function texts(parent: Element): string[] {
  return Array.from(parent.querySelectorAll('li')).map(
    (li) => li.textContent ?? '',
  )
}

test('список отрисовывается по порядку', () => {
  const parent = body()
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два' },
  ])

  mount(parent, () =>
    h(
      'ul',
      null,
      each(
        () => posts.value,
        (post) => post.id,
        (post) => h('li', null, () => post().body),
      ),
    ),
  )

  assert.deepEqual(texts(parent), ['раз', 'два'])
})

test('переживший элемент остаётся ТЕМ ЖЕ узлом', () => {
  // Вся суть ключа. Сравнение по содержимому прошло бы и при полной
  // перерисовке — а вместе с ней ушли бы фокус и прокрутка.
  const parent = body()
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два' },
  ])

  mount(parent, () =>
    h(
      'ul',
      null,
      each(
        () => posts.value,
        (post) => post.id,
        (post) => h('li', null, () => post().body),
      ),
    ),
  )

  const before1 = parent.querySelectorAll('li')[0]
  const before2 = parent.querySelectorAll('li')[1]

  posts.value = [
    { id: 'p0', body: 'ноль' },
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два изменён' },
  ]

  const after = parent.querySelectorAll('li')
  assert.deepEqual(texts(parent), ['ноль', 'раз', 'два изменён'])
  assert.equal(after[1], before1, 'узел p1 пересоздан, хотя ключ тот же')
  assert.equal(after[2], before2, 'узел p2 пересоздан из-за смены текста')
})

test('перестановка двигает узлы, а не пересоздаёт их', () => {
  const parent = body()
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два' },
    { id: 'p3', body: 'три' },
  ])

  mount(parent, () =>
    h(
      'ul',
      null,
      each(
        () => posts.value,
        (post) => post.id,
        (post) => h('li', null, () => post().body),
      ),
    ),
  )

  const nodes = Array.from(parent.querySelectorAll('li'))
  posts.update((previous) => [...previous].reverse())

  const after = Array.from(parent.querySelectorAll('li'))
  assert.deepEqual(texts(parent), ['три', 'два', 'раз'])
  assert.equal(after[0], nodes[2], 'узлы пересозданы вместо перестановки')
  assert.equal(after[2], nodes[0])
})

test('ушедший элемент уносит свои привязки', () => {
  // Строка, снятая из списка, не должна оставлять живых подписок:
  // они обновляли бы узел, которого нет на странице.
  const parent = body()
  const mark = signal('!')
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два' },
  ])

  mount(parent, () =>
    h(
      'ul',
      null,
      each(
        () => posts.value,
        (post) => post.id,
        (post) => h('li', null, () => post().body + mark.value),
      ),
    ),
  )

  assert.equal(mark.observerCount, 2)

  posts.value = [{ id: 'p1', body: 'раз' }]
  assert.equal(mark.observerCount, 1, 'привязка ушедшей строки осталась жить')

  posts.value = []
  assert.equal(mark.observerCount, 0)
  assert.equal(parent.querySelectorAll('li').length, 0)
})

test('размонтирование снимает весь список', () => {
  const parent = body()
  const mark = signal('!')
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два' },
  ])

  const unmount = mount(parent, () =>
    h(
      'ul',
      null,
      each(
        () => posts.value,
        (post) => post.id,
        (post) => h('li', null, () => post().body + mark.value),
      ),
    ),
  )

  assert.equal(mark.observerCount, 2)
  unmount()
  assert.equal(mark.observerCount, 0, 'строки пережили размонтирование')
  assert.equal(posts.observerCount, 0, 'сам список пережил размонтирование')
})

test('номер строки обновляется без пересоздания узла', () => {
  const parent = body()
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p2', body: 'два' },
  ])

  mount(parent, () =>
    h(
      'ol',
      null,
      each(
        () => posts.value,
        (post) => post.id,
        (post, index) => h('li', null, () => `${index() + 1}. ${post().body}`),
      ),
    ),
  )

  const first = parent.querySelectorAll('li')[0]
  assert.deepEqual(texts(parent), ['1. раз', '2. два'])

  posts.update((previous) => [{ id: 'p0', body: 'ноль' }, ...previous])
  assert.deepEqual(texts(parent), ['1. ноль', '2. раз', '3. два'])
  assert.equal(
    parent.querySelectorAll('li')[1],
    first,
    'сдвиг номера пересоздал узел',
  )
})

test('повторяющийся ключ — отказ, а не перепутанные строки', () => {
  // Молчаливое совпадение ключей перепутывает строки между собой, и
  // перерисовкой это не чинится: узел с чужими данными выглядит
  // совершенно нормально.
  const parent = body()
  const posts = signal<readonly Post[]>([
    { id: 'p1', body: 'раз' },
    { id: 'p1', body: 'тоже раз' },
  ])

  assert.throws(
    () =>
      mount(parent, () =>
        h(
          'ul',
          null,
          each(
            () => posts.value,
            (post) => post.id,
            (post) => h('li', null, () => post().body),
          ),
        ),
      ),
    /дважды/,
  )
})

test('when показывает ветвь и меняет её при смене условия', () => {
  const parent = body()
  const signedIn = signal(false)

  mount(parent, () =>
    h(
      'div',
      null,
      when(
        () => signedIn.value,
        () => h('span', { id: 'свой' }, 'здравствуйте'),
        () => h('a', { id: 'вход' }, 'войти'),
      ),
    ),
  )

  assert.ok(parent.querySelector('#вход'))
  assert.equal(parent.querySelector('#свой'), null)

  signedIn.value = true
  assert.ok(parent.querySelector('#свой'))
  assert.equal(parent.querySelector('#вход'), null, 'прежняя ветвь осталась')
})

test('when не пересобирает ветвь из-за изменений внутри неё', () => {
  // Иначе ввод одной буквы в поле внутри ветви пересоздавал бы это поле
  // и терял фокус на каждом нажатии.
  const parent = body()
  const signedIn = signal(true)
  const name = signal('андрей')

  mount(parent, () =>
    h(
      'div',
      null,
      when(
        () => signedIn.value,
        () => h('span', null, () => name.value),
      ),
    ),
  )

  const span = parent.querySelector('span')
  assert.ok(span)
  assert.equal(span.textContent, 'андрей')

  name.value = 'борис'
  assert.equal(span.textContent, 'борис')
  assert.equal(
    parent.querySelector('span'),
    span,
    'ветвь пересобрана из-за изменения внутри неё',
  )
})

test('ветвь уносит свои привязки при смене условия', () => {
  const parent = body()
  const signedIn = signal(true)
  const name = signal('андрей')

  mount(parent, () =>
    h(
      'div',
      null,
      when(
        () => signedIn.value,
        () => h('span', null, () => name.value),
        () => 'войдите',
      ),
    ),
  )

  assert.equal(name.observerCount, 1)
  signedIn.value = false
  assert.equal(name.observerCount, 0, 'привязка скрытой ветви осталась жить')
  assert.equal(parent.querySelector('span'), null)
})

test('меняющееся содержимое не стирает соседей', () => {
  const parent = body()
  const shown = signal(true)

  mount(parent, () =>
    h(
      'div',
      null,
      h('b', null, 'до'),
      when(
        () => shown.value,
        () => h('i', null, 'середина'),
      ),
      h('u', null, 'после'),
    ),
  )

  const div = parent.querySelector('div')
  assert.ok(div)
  assert.equal(div.querySelector('b')?.textContent, 'до')
  assert.equal(div.querySelector('u')?.textContent, 'после')

  shown.value = false
  assert.equal(div.querySelector('i'), null)
  assert.equal(div.querySelector('b')?.textContent, 'до', 'сосед стёрт')
  assert.equal(div.querySelector('u')?.textContent, 'после', 'сосед стёрт')
})
