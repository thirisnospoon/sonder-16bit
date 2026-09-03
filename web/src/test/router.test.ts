/*
 * Маршрутизация: что считается совпадением и что попадает в адресную
 * строку.
 *
 * Отдельно проверяется то, чего маршрутизатор трогать НЕ должен: чужие
 * ссылки, клики с модификаторами, ссылки на скачивание. Перехватить
 * лишнее здесь дороже, чем не перехватить: пользователь теряет
 * привычное поведение браузера и не понимает почему.
 */
import { test, before, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

import { createRouter, type RouteDef } from '../core/router.js'
import { effect } from '../core/reactive.js'

type Name = 'лента' | 'пост' | 'профиль' | 'нет'

const ROUTES: readonly RouteDef<Name>[] = [
  { name: 'лента', pattern: '/' },
  { name: 'пост', pattern: '/posts/:postId' },
  { name: 'профиль', pattern: '/users/:nick/posts' },
  { name: 'нет', pattern: '/404' },
]

let dom: JSDOM

before(() => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'https://sonder.example/',
  })
  const global = globalThis as unknown as Record<string, unknown>
  global['window'] = dom.window
  global['document'] = dom.window.document
  global['Node'] = dom.window.Node
  global['HTMLElement'] = dom.window.HTMLElement
  global['MouseEvent'] = dom.window.MouseEvent
})

beforeEach(() => {
  dom.window.history.replaceState(null, '', '/')
  dom.window.document.body.innerHTML = ''
})

test('образец с переменной совпадает и отдаёт её значение', () => {
  dom.window.history.replaceState(null, '', '/posts/p-1001')
  const router = createRouter(ROUTES, 'нет')

  assert.equal(router.current.value.name, 'пост')
  assert.equal(router.current.value.params['postId'], 'p-1001')
})

test('несовпавший адрес приводит к маршруту «не найдено»', () => {
  dom.window.history.replaceState(null, '', '/чего-то-нет')
  const router = createRouter(ROUTES, 'нет')

  assert.equal(router.current.value.name, 'нет')
})

test('лишняя часть пути не считается совпадением', () => {
  // `/posts/p-1/лишнее` не адресует пост. Приблизительное совпадение
  // увело бы в запрос с идентификатором, которого никто не просил.
  dom.window.history.replaceState(null, '', '/posts/p-1/лишнее')
  const router = createRouter(ROUTES, 'нет')

  assert.equal(router.current.value.name, 'нет')
})

test('переменная в кодировке процентов раскодируется', () => {
  dom.window.history.replaceState(null, '', '/users/%D0%B0%D0%BD%D0%B4%D1%80%D0%B5%D0%B9/posts')
  const router = createRouter(ROUTES, 'нет')

  assert.equal(router.current.value.name, 'профиль')
  assert.equal(router.current.value.params['nick'], 'андрей')
})

test('маршрут с кириллицей совпадает и в процентной кодировке', () => {
  // Браузер отдаёт location.pathname закодированным, а образец написан
  // буквами. Сравнение посимвольно давало бы «страница не найдена» на
  // ВСЯКОМ маршруте с кириллицей — молча, без единого следа. Нашла это
  // проверка в настоящем браузере, а не эта: до неё сравнивать было не
  // с чем, потому что jsdom подставлял путь как есть.
  const routes: readonly RouteDef<'вход' | 'нет'>[] = [
    { name: 'вход', pattern: '/вход' },
    { name: 'нет', pattern: '/404' },
  ]

  dom.window.history.replaceState(
    null,
    '',
    '/%D0%B2%D1%85%D0%BE%D0%B4',
  )
  assert.equal(createRouter(routes, 'нет').current.value.name, 'вход')

  // И буквами тоже: адрес, набранный руками, обязан работать так же.
  dom.window.history.replaceState(null, '', '/вход')
  assert.equal(createRouter(routes, 'нет').current.value.name, 'вход')
})

test('испорченная процентная последовательность не роняет страницу', () => {
  // Кривой адрес в строке браузера — обычное дело. Отказ разбора уронил
  // бы приложение целиком там, где достаточно показать «не найдено».
  dom.window.history.replaceState(null, '', '/posts/%E0%A4%A')
  const router = createRouter(ROUTES, 'нет')
  assert.equal(router.current.value.name, 'пост')
  assert.equal(router.current.value.params['postId'], '%E0%A4%A')
})

test('строка запроса разбирается и в путь не попадает', () => {
  dom.window.history.replaceState(null, '', '/?after=p-7&limit=20')
  const router = createRouter(ROUTES, 'нет')

  assert.equal(router.current.value.name, 'лента')
  assert.equal(router.current.value.path, '/')
  assert.equal(router.current.value.query.get('after'), 'p-7')
  assert.equal(router.current.value.query.get('limit'), '20')
})

test('адрес собирается по имени, а не пишется руками', () => {
  // Строка «/posts/» + id в разметке — второй экземпляр описания
  // маршрута, и расходится он молча.
  const router = createRouter(ROUTES, 'нет')

  assert.equal(router.href('пост', { postId: 'p-1001' }), '/posts/p-1001')
  assert.equal(router.href('профиль', { nick: 'андрей' }), '/users/%D0%B0%D0%BD%D0%B4%D1%80%D0%B5%D0%B9/posts')
  assert.equal(router.href('лента'), '/')
})

test('пропущенная переменная адреса — отказ, а не «undefined» в ссылке', () => {
  const router = createRouter(ROUTES, 'нет')
  assert.throws(() => router.href('пост'), /не задана переменная postId/)
})

test('маршрут для ненайденного обязателен', () => {
  assert.throws(
    () => createRouter([{ name: 'лента', pattern: '/' }], 'нет' as 'лента'),
    /не объявлен/,
  )
})

test('переход меняет и адрес, и сигнал', () => {
  const router = createRouter(ROUTES, 'нет')
  const seen: string[] = []
  effect(() => {
    seen.push(router.current.value.name)
  })

  router.navigate('/posts/p-2')

  assert.equal(dom.window.location.pathname, '/posts/p-2')
  assert.deepEqual(seen, ['лента', 'пост'], 'сигнал не догнал адрес')
})

test('кнопка «назад» возвращает и адрес, и страницу', () => {
  // Без слушателя popstate адрес стал бы прежним, а страница осталась
  // новой: пользователь видит не то, что написано в строке браузера.
  const router = createRouter(ROUTES, 'нет')
  const stop = router.start()

  router.navigate('/posts/p-2')
  assert.equal(router.current.value.name, 'пост')

  dom.window.history.back()
  // jsdom исполняет переход по истории отложенно, поэтому событие
  // подаётся напрямую: проверяется реакция на него, а не сам браузер.
  dom.window.history.replaceState(null, '', '/')
  dom.window.dispatchEvent(new dom.window.PopStateEvent('popstate'))

  assert.equal(router.current.value.name, 'лента', 'страница не вернулась')
  stop()
})

test('клик по своей ссылке перехватывается без перезагрузки', () => {
  const router = createRouter(ROUTES, 'нет')
  const stop = router.start()

  const link = document.createElement('a')
  link.setAttribute('href', router.href('пост', { postId: 'p-3' }))
  document.body.appendChild(link)

  const event = new dom.window.MouseEvent('click', {
    bubbles: true,
    cancelable: true,
    button: 0,
  })
  link.dispatchEvent(event)

  assert.equal(event.defaultPrevented, true, 'браузер перезагрузит страницу')
  assert.equal(router.current.value.name, 'пост')
  assert.equal(router.current.value.params['postId'], 'p-3')
  stop()
})

test('чужие ссылки и особые клики не перехватываются', () => {
  const router = createRouter(ROUTES, 'нет')
  const stop = router.start()

  const cases: Array<{ readonly why: string; readonly make: () => [HTMLAnchorElement, MouseEvent] }> = [
    {
      why: 'чужой хост',
      make: () => {
        const a = document.createElement('a')
        a.setAttribute('href', 'https://example.com/posts/p-9')
        return [a, new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })]
      },
    },
    {
      why: 'адрес без хоста, но с двумя слэшами',
      make: () => {
        const a = document.createElement('a')
        a.setAttribute('href', '//example.com/posts/p-9')
        return [a, new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })]
      },
    },
    {
      why: 'ссылка на скачивание',
      make: () => {
        const a = document.createElement('a')
        a.setAttribute('href', '/posts/p-9')
        a.setAttribute('download', '')
        return [a, new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })]
      },
    },
    {
      why: 'открыть в новой вкладке',
      make: () => {
        const a = document.createElement('a')
        a.setAttribute('href', '/posts/p-9')
        a.setAttribute('target', '_blank')
        return [a, new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })]
      },
    },
    {
      why: 'клик с Ctrl',
      make: () => {
        const a = document.createElement('a')
        a.setAttribute('href', '/posts/p-9')
        return [a, new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true })]
      },
    },
    {
      why: 'средняя кнопка',
      make: () => {
        const a = document.createElement('a')
        a.setAttribute('href', '/posts/p-9')
        return [a, new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 1 })]
      },
    },
  ]

  // Соглядатай ставится ПОСЛЕ маршрутизатора, поэтому видит его решение
  // и только затем гасит событие сам. Без этого jsdom честно пытается
  // уйти по чужому адресу, упирается в свой таймаут и добавляет к
  // прогону сорок секунд — а набор, который идёт минуту, перестают
  // гонять.
  let prevented = false
  const spy = (event: Event): void => {
    prevented = event.defaultPrevented
    event.preventDefault()
  }
  document.addEventListener('click', spy)

  for (const item of cases) {
    const [anchor, event] = item.make()
    document.body.appendChild(anchor)
    anchor.dispatchEvent(event)
    assert.equal(prevented, false, `перехвачено лишнее: ${item.why}`)
    assert.notEqual(
      router.current.value.params['postId'],
      'p-9',
      `переход состоялся, хотя не должен: ${item.why}`,
    )
  }

  document.removeEventListener('click', spy)
  stop()
})

test('снятие отключает и историю, и перехват ссылок', () => {
  const router = createRouter(ROUTES, 'нет')
  const stop = router.start()
  stop()

  const link = document.createElement('a')
  link.setAttribute('href', '/posts/p-4')
  document.body.appendChild(link)

  const event = new dom.window.MouseEvent('click', {
    bubbles: true,
    cancelable: true,
    button: 0,
  })
  link.dispatchEvent(event)

  assert.equal(event.defaultPrevented, false, 'перехват пережил снятие')
  assert.equal(router.current.value.name, 'лента')
})

test('образец без ведущего слэша — отказ при сборке', () => {
  assert.throws(
    () => createRouter([{ name: 'нет', pattern: '404' }], 'нет'),
    /не начинается со слэша/,
  )
})

test('дважды объявленный маршрут — отказ', () => {
  assert.throws(
    () =>
      createRouter(
        [
          { name: 'нет', pattern: '/404' },
          { name: 'нет', pattern: '/не-найдено' },
        ],
        'нет',
      ),
    /объявлен дважды/,
  )
})
