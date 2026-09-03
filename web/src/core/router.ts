/*
 * Маршрутизация.
 *
 * Адрес — это состояние, и держится оно сигналом, как всякое другое:
 * страница не «перерисовывается по событию навигации», а зависит от
 * сигнала пути ровно так же, как счётчик зависит от числа.
 *
 * АДРЕСА СОБИРАЮТСЯ ОТСЮДА, А НЕ ПИШУТСЯ РУКАМИ. Строка «/posts/» + id,
 * набранная в разметке, — это второй экземпляр описания маршрута, и
 * расходится он молча: ссылка ведёт в никуда, а заметить это можно
 * только пройдя по ней. Поэтому есть {@link Router.href}, а образец
 * маршрута существует ровно в одном месте.
 */
import { signal, type ReadSignal } from './reactive.js'

export interface RouteDef<N extends string> {
  readonly name: N
  /** Образец вида `/posts/:postId`. Части с двоеточием — переменные. */
  readonly pattern: string
}

export interface Match<N extends string> {
  readonly name: N
  readonly params: Readonly<Record<string, string>>
  readonly path: string
  readonly query: URLSearchParams
}

export interface Router<N extends string> {
  readonly current: ReadSignal<Match<N>>
  /** Перейти по адресу. Заменой, если новая запись в истории не нужна. */
  navigate(path: string, options?: { readonly replace?: boolean }): void
  /** Собрать адрес по имени маршрута и переменным. */
  href(name: N, params?: Readonly<Record<string, string | number>>): string
  /** Подключить слушателей истории и перехват ссылок. Возвращает снятие. */
  start(): () => void
}

interface Compiled<N extends string> {
  readonly name: N
  readonly parts: readonly string[]
  readonly pattern: string
}

function compile<N extends string>(route: RouteDef<N>): Compiled<N> {
  if (!route.pattern.startsWith('/')) {
    throw new Error(
      `образец маршрута ${route.name} не начинается со слэша: ${route.pattern}`,
    )
  }
  return { name: route.name, parts: segments(route.pattern), pattern: route.pattern }
}

function segments(path: string): string[] {
  return path.split('/').filter((part) => part !== '')
}

function matchOne<N extends string>(
  compiled: Compiled<N>,
  parts: readonly string[],
): Record<string, string> | null {
  if (compiled.parts.length !== parts.length) {
    return null
  }
  const params: Record<string, string> = {}
  for (let i = 0; i < compiled.parts.length; i++) {
    const expected = compiled.parts[i] as string
    const actual = parts[i] as string
    if (expected.startsWith(':')) {
      // Пустая переменная — это не совпадение: `/posts//` не адресует
      // никакого поста, и притворяться, что адресует, значит уехать в
      // запрос с пустым идентификатором.
      if (actual === '') {
        return null
      }
      params[expected.slice(1)] = decodeURIComponent(actual)
      continue
    }
    if (expected !== actual) {
      return null
    }
  }
  return params
}

/**
 * Собрать маршрутизатор.
 *
 * @param notFound имя маршрута для всего, что не совпало. Обязательное:
 *                 «ничего не совпало» бывает всегда, и решать это молчанием
 *                 значит показывать пустую страницу без объяснений
 */
export function createRouter<N extends string>(
  routes: readonly RouteDef<N>[],
  notFound: N,
): Router<N> {
  const compiled = routes.map(compile)

  const byName = new Map<N, Compiled<N>>()
  for (const route of compiled) {
    if (byName.has(route.name)) {
      throw new Error(`маршрут ${route.name} объявлен дважды`)
    }
    byName.set(route.name, route)
  }
  if (!byName.has(notFound)) {
    throw new Error(
      `маршрут для ненайденного (${notFound}) не объявлен: ` +
        'на неизвестный адрес показывать было бы нечего',
    )
  }

  function resolve(full: string): Match<N> {
    const at = full.indexOf('?')
    const path = at === -1 ? full : full.slice(0, at)
    const query = new URLSearchParams(at === -1 ? '' : full.slice(at + 1))
    const parts = segments(path)

    for (const route of compiled) {
      const params = matchOne(route, parts)
      if (params !== null) {
        return { name: route.name, params, path, query }
      }
    }
    return { name: notFound, params: {}, path, query }
  }

  function here(): string {
    return window.location.pathname + window.location.search
  }

  const current = signal<Match<N>>(resolve(here()))

  function navigate(path: string, options?: { readonly replace?: boolean }): void {
    const next = resolve(path)
    if (options?.replace === true) {
      window.history.replaceState(null, '', path)
    } else {
      window.history.pushState(null, '', path)
    }
    current.value = next
  }

  function href(
    name: N,
    params: Readonly<Record<string, string | number>> = {},
  ): string {
    const route = byName.get(name)
    if (route === undefined) {
      throw new Error(`маршрут ${name} не объявлен`)
    }
    const parts = route.parts.map((part) => {
      if (!part.startsWith(':')) {
        return part
      }
      const key = part.slice(1)
      const value = params[key]
      if (value === undefined) {
        // Молчаливая подстановка «undefined» дала бы адрес, который
        // выглядит настоящим и ведёт в никуда.
        throw new Error(
          `для адреса ${name} не задана переменная ${key}`,
        )
      }
      return encodeURIComponent(String(value))
    })
    return '/' + parts.join('/')
  }

  function start(): () => void {
    const onPopState = (): void => {
      // Кнопка «назад» меняет адрес мимо navigate: без этого страница
      // осталась бы прежней, а адрес — новым, и пользователь увидел бы
      // не то, что написано в строке браузера.
      current.value = resolve(here())
    }

    const onClick = (event: MouseEvent): void => {
      // Перехватываем только обычный левый клик по своей ссылке.
      // Ctrl, Shift и средняя кнопка означают «открыть иначе», и
      // отнимать это у пользователя нельзя.
      if (
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      ) {
        return
      }
      const target = event.target as Element | null
      const anchor = target?.closest('a')
      if (anchor === null || anchor === undefined) {
        return
      }
      if (anchor.hasAttribute('download') || anchor.target !== '') {
        return
      }
      const url = anchor.getAttribute('href')
      if (url === null || !url.startsWith('/') || url.startsWith('//')) {
        // Чужой адрес — не наше дело. `//` это тоже чужой: протокол
        // подставляется текущий, а хост берётся из адреса.
        return
      }
      event.preventDefault()
      navigate(url)
    }

    window.addEventListener('popstate', onPopState)
    document.addEventListener('click', onClick)
    return () => {
      window.removeEventListener('popstate', onPopState)
      document.removeEventListener('click', onClick)
    }
  }

  return { current, navigate, href, start }
}
