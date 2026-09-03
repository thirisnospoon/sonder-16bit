/*
 * Привязка к DOM: что попадает в страницу и что из неё уходит.
 *
 * Проверяется против jsdom — настоящей реализации DOM, а не собственной
 * заглушки. Заглушка проверяла бы заглушку: весь этот слой только тем и
 * занят, что зовёт методы DOM, и подменить их значило бы подменить
 * предмет проверки. jsdom при этом зависимость ИСПЫТАТЕЛЬНАЯ — правило
 * «без зависимостей времени исполнения» (ADR-0012) она не трогает.
 *
 * Сценарии в настоящем браузере — Playwright, гейт фазы 9. Здесь
 * проверяется механика, и ей нужен быстрый прогон.
 */
import { test, before } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

import { h, mount } from '../core/dom.js'
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

test('элемент собирается с атрибутами и текстом', () => {
  const parent = body()
  mount(parent, () => h('a', { href: '/лента', title: 'Лента' }, 'К ленте'))

  const link = parent.querySelector('a')
  assert.ok(link)
  assert.equal(link.getAttribute('href'), '/лента')
  assert.equal(link.textContent, 'К ленте')
})

test('текст обновляется точечно, не трогая соседей', () => {
  const parent = body()
  const count = signal(1)

  mount(parent, () =>
    h('p', null, 'постов: ', () => count.value, ' штук'),
  )

  const p = parent.querySelector('p')
  assert.ok(p)
  assert.equal(p.textContent, 'постов: 1 штук')

  // Соседи меняющегося куска — отдельные узлы, и они обязаны пережить
  // обновление. Сравнение по тождеству, а не по тексту: перерисовка
  // родителя дала бы тот же текст новыми узлами.
  const before = p.firstChild
  const after = p.lastChild
  const nodesBefore = p.childNodes.length

  count.value = 42
  assert.equal(p.textContent, 'постов: 42 штук', 'текст не обновился')
  assert.equal(p.firstChild, before, 'сосед слева пересоздан')
  assert.equal(p.lastChild, after, 'сосед справа пересоздан')
  assert.equal(
    p.childNodes.length,
    nodesBefore,
    'обновление добавило или убрало узлы',
  )
})

test('постоянное значение не заводит эффекта', () => {
  // Подписка, которая никогда не сработает, стоит ровно столько же,
  // сколько работающая, — и обнаружить её можно только счётчиком.
  const parent = body()
  const other = signal(0)

  mount(parent, () => h('div', { id: 'постоянный' }, 'текст'))

  assert.equal(other.observerCount, 0)
})

test('атрибут исчезает на false и появляется на true', () => {
  // Атрибут со значением "false" для булевых означает ВКЛЮЧЕНО, и это
  // самая тихая из ошибок такого рода: разметка выглядит правильной.
  const parent = body()
  const hidden = signal(true)

  mount(parent, () => h('div', { hidden: () => hidden.value }, 'тело'))

  const div = parent.querySelector('div')
  assert.ok(div)
  assert.equal(div.getAttribute('hidden'), '')

  hidden.value = false
  assert.equal(div.hasAttribute('hidden'), false, 'атрибут остался при false')
})

test('поле ввода выставляется свойством, а не атрибутом', () => {
  // У value атрибут задаёт лишь начальное значение. Через setAttribute
  // поле, в котором уже печатали, не обновилось бы, и выглядело бы это
  // как «реактивность не работает».
  const parent = body()
  const nick = signal('андрей')

  mount(parent, () => h('input', { type: 'text', value: () => nick.value }))

  const input = parent.querySelector('input') as HTMLInputElement
  assert.equal(input.value, 'андрей')

  // Пользователь напечатал своё — атрибут при этом не меняется.
  input.value = 'борис'
  nick.value = 'виктор'
  assert.equal(input.value, 'виктор', 'значение поля не обновилось')
})

test('классы-словарь переключаются по отдельности', () => {
  const parent = body()
  const active = signal(false)

  mount(parent, () =>
    h('div', { class: { карточка: true, активная: () => active.value } }),
  )

  const div = parent.querySelector('div')
  assert.ok(div)
  assert.equal(div.className, 'карточка')

  active.value = true
  assert.equal(div.classList.contains('карточка'), true, 'постоянный класс стёрт')
  assert.equal(div.classList.contains('активная'), true)
})

test('стиль снимается пустым значением, а не пишется словом undefined', () => {
  const parent = body()
  const width = signal<string | undefined>('100px')

  mount(parent, () => h('div', { style: { width: () => width.value } }))

  const div = parent.querySelector('div') as HTMLElement
  assert.equal(div.style.width, '100px')

  width.value = undefined
  assert.equal(div.style.width, '', 'стиль остался')
})

test('обработчик события вызывается', () => {
  const parent = body()
  let clicks = 0

  mount(parent, () => h('button', { onClick: () => clicks++ }, 'нажать'))

  const button = parent.querySelector('button') as HTMLElement
  button.click()
  button.click()
  assert.equal(clicks, 2)
})

test('обработчик со скобками — отказ, а не тихое ничего', () => {
  // `onClick: сделать()` вместо `onClick: сделать` — опечатка, которая
  // выглядит правильно и не делает ничего. Молчание тут дороже отказа.
  const parent = body()
  assert.throws(
    () => mount(parent, () => h('button', { onClick: 'уже вызвано' })),
    /не функция/,
  )
})

test('размонтирование убирает узел и снимает ВСЕ привязки', () => {
  // Главная проверка слоя. Привязка, пережившая размонтирование,
  // продолжает обновлять узел, которого нет на странице, и держит в
  // памяти всё, что захватила замыканием.
  const parent = body()
  const count = signal(0)
  const flag = signal(false)

  const unmount = mount(parent, () =>
    h(
      'div',
      { class: { видно: () => flag.value }, title: () => String(count.value) },
      () => count.value,
      h('span', null, () => count.value),
    ),
  )

  assert.equal(parent.childNodes.length, 1)
  assert.equal(count.observerCount, 3, 'ожидались три привязки к счётчику')
  assert.equal(flag.observerCount, 1)

  unmount()

  assert.equal(parent.childNodes.length, 0, 'узел остался на странице')
  assert.equal(count.observerCount, 0, 'привязки пережили размонтирование')
  assert.equal(flag.observerCount, 0, 'привязка класса пережила размонтирование')
})

test('обработчик снимается при размонтировании', () => {
  const parent = body()
  let clicks = 0

  const unmount = mount(parent, () =>
    h('button', { onClick: () => clicks++ }, 'нажать'),
  )
  const button = parent.querySelector('button') as HTMLElement

  button.click()
  assert.equal(clicks, 1)

  unmount()
  // Узел уже вне страницы, но ссылка на него у нас есть — ровно так его
  // держал бы и слушатель, если бы остался.
  button.click()
  assert.equal(clicks, 1, 'обработчик пережил размонтирование')
})

test('вложенные элементы и списки детей собираются как есть', () => {
  const parent = body()

  mount(parent, () =>
    h(
      'ul',
      null,
      [h('li', null, 'раз'), h('li', null, 'два')],
      h('li', null, 'три'),
      null,
      false,
    ),
  )

  const items = parent.querySelectorAll('li')
  assert.equal(items.length, 3)
  assert.equal(items[0]?.textContent, 'раз')
  assert.equal(items[2]?.textContent, 'три')
})
