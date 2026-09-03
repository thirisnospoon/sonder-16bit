/*
 * Точка входа.
 *
 * Собирает клиента, сессию, маршрутизатор и поток обновлений и вешает
 * страницу на дерево. Больше здесь ничего не происходит: всякая логика,
 * заведённая тут, оказалась бы вне досягаемости проверок — этот файл
 * единственный, который нельзя вызвать из теста без браузера.
 */
import { createClient } from '../core/api.js'
import { h, mount, type Child } from '../core/dom.js'
import { createRouter } from '../core/router.js'
import { потокСессии } from './stream.js'
import { createSession } from './session.js'
import { PAGES } from './routes.js'
import { страницаВхода } from './pages/login.js'
import { страницаЛенты } from './pages/feed.js'
import { страницаПрофиля } from './pages/profile.js'
import { страницаПоста } from './pages/post.js'
import { страницаМодерации } from './pages/moderation.js'
import { пусто } from './parts.js'

const client = createClient('/api')
const session = createSession(client)
const router = createRouter(PAGES, 'нет')
const stream = потокСессии(client, session)

const корень = document.getElementById('приложение')
if (корень === null) {
  throw new Error('в разметке нет узла приложения')
}

router.start()
void session.refresh()

mount(корень, () =>
  h(
    'div',
    null,
    шапка(),
    // Страница — обычное динамическое содержимое: сменился маршрут,
    // сменилось поддерево. Ничего сверх этого маршрутизации не нужно.
    () => страница(),
  ),
)

function шапка(): Node {
  return h(
    'header',
    { class: 'шапка' },
    h('a', { class: 'шапка__марка', href: router.href('лента') }, 'Sonder'),
    h(
      'nav',
      { class: 'шапка__навигация', 'aria-label': 'Основная' },
      // Одно ветвление на три состояния, а не when внутри when.
      // Вложенное условие пришлось бы разворачивать вручную, и
      // внутреннее перестало бы быть отслеживаемым — то есть шапка
      // застыла бы на том, что увидела при первом показе.
      () => правыйУголШапки(),
    ),
  )
}

function правыйУголШапки(): Child {
  const кто = session.who.value
  if (кто.state === 'свой') {
    return h(
      'div',
      { style: { display: 'flex', gap: '8px', 'align-items': 'center' } },
      h(
        'a',
        {
          class: 'подпись',
          href: router.href('профиль', { nick: кто.me.nick }),
        },
        кто.me.displayName,
      ),
      кто.me.role === 'USER'
        ? null
        : h(
            'a',
            { class: 'подпись', href: router.href('модерация') },
            'Модерация',
          ),
      h(
        'button',
        {
          class: 'кнопка кнопка--тихая',
          onClick: () => {
            void session.logout()
            router.navigate(router.href('вход'))
          },
        },
        'Выйти',
      ),
    )
  }
  if (кто.state === 'гость') {
    return h('a', { class: 'кнопка', href: router.href('вход') }, 'Войти')
  }
  // «Неизвестно» и «нет связи» кнопки входа НЕ показывают: мигнуть ею
  // тому, кто уже вошёл, хуже, чем показать её мгновением позже.
  return null
}

function страница(): Child {
  const текущий = router.current.value
  switch (текущий.name) {
    case 'лента':
      return страницаЛенты(client, session, router, stream)
    case 'вход':
      return страницаВхода(session, router, 'вход', client)
    case 'регистрация':
      return страницаВхода(session, router, 'регистрация', client)
    case 'профиль':
      return страницаПрофиля(
        client,
        session,
        router,
        текущий.params['nick'] ?? '',
      )
    case 'пост':
      return страницаПоста(
        client,
        session,
        router,
        текущий.params['postId'] ?? '',
      )
    case 'модерация':
      // Право модерировать проверяет ядро. Страница показывается всем
      // вошедшим, и отказ на ней — обычный исход, а не неожиданность:
      // спрятанная кнопка защитой не является.
      return страницаМодерации(client)
    default:
      return h(
        'main',
        { class: 'полоса', id: 'содержимое' },
        h('h1', { class: 'заголовок' }, 'Такой страницы нет'),
        h(
          'div',
          { class: 'карточка' },
          пусто('Адрес не совпал ни с одной страницей.', текущий.path),
        ),
      )
  }
}
