/*
 * Привязка сигналов к DOM.
 *
 * Виртуального DOM здесь нет: сигнал знает ровно то место, которое от
 * него зависит, и меняет его напрямую. Сравнивать деревья, чтобы найти
 * то, что и так известно, — работа, которой в этой модели просто нет
 * (ADR-0012).
 *
 * ВСЁ ЖИВОЕ ПРИНАДЛЕЖИТ ОБЛАСТИ. Каждая привязка — это эффект, а каждый
 * обработчик события снимается через onCleanup. Поэтому размонтирование
 * снимает ОДНУ область, а не обходит дерево, вспоминая, что к чему было
 * привязано. Список, который надо помнить, однажды окажется неполным.
 */
import {
  detachedRoot,
  effect,
  onCleanup,
  root,
  signal,
  untrack,
  type WriteSignal,
} from './reactive.js'

/** Значение, которое может быть постоянным или пересчитываемым. */
export type Reactive<T> = T | (() => T)

export type Child =
  | Node
  | string
  | number
  | false
  | null
  | undefined
  | (() => Child)
  | readonly Child[]
  | AnyList

/**
 * Список в разметке.
 *
 * Без параметра типа намеренно: в объединении Child он стоял бы рядом с
 * разнородными списками, а функции внутри {@link ListChild} принимают T
 * и потому по типам несовместимы между собой. Тип элемента проверяется
 * там, где список СОБИРАЮТ, — в {@link each}, — и это единственное
 * место, где он вообще известен.
 */
export interface AnyList {
  readonly kind: 'список'
}

export interface ListChild<T> extends AnyList {
  readonly items: () => readonly T[]
  readonly key: (item: T, index: number) => string | number
  readonly render: (item: () => T, index: () => number) => Node
}

function isList(child: unknown): child is AnyList {
  return (
    typeof child === 'object' &&
    child !== null &&
    (child as AnyList).kind === 'список'
  )
}

/**
 * Свойства элемента.
 *
 * `class` и `style` разобраны отдельно, потому что ими пользуются чаще
 * всего и строкой их собирать неудобно. Всё прочее — атрибуты; свойства
 * DOM выставляются по короткому списку ниже.
 */
export interface Props {
  class?: Reactive<string | undefined> | Record<string, Reactive<boolean>>
  style?: Record<string, Reactive<string | number | undefined>>
  /** Отдать созданный элемент наружу. */
  ref?: (element: HTMLElement) => void
  [key: string]: unknown
}

/**
 * Имена, которые обязаны выставляться СВОЙСТВОМ, а не атрибутом.
 *
 * У них атрибут задаёт лишь начальное значение, а дальше живёт своей
 * жизнью: `input.setAttribute('value', x)` не меняет того, что видит
 * пользователь, если он уже что-то печатал. Ошибка тихая — поле просто
 * не обновляется, и выглядит это как «реактивность не работает».
 */
const PROPERTIES = new Set([
  'value',
  'checked',
  'selected',
  'indeterminate',
  'disabled',
  'readOnly',
  'multiple',
  'muted',
  'volume',
  'textContent',
  'innerHTML',
])

function isFunction(value: unknown): value is () => unknown {
  return typeof value === 'function'
}

/**
 * Привязать вычисляемое значение.
 *
 * Постоянное выставляется один раз и эффекта не заводит: эффект на
 * неизменное — это подписка, которая никогда не сработает, и цена ей
 * ровно та же, что и работающей.
 */
function bind<T>(value: Reactive<T>, apply: (next: T) => void): void {
  if (isFunction(value)) {
    effect(() => {
      apply((value as () => T)())
    })
    return
  }
  apply(value)
}

function applyClass(element: HTMLElement, value: Props['class']): void {
  if (value === undefined) {
    return
  }
  if (typeof value === 'string' || isFunction(value)) {
    bind(value as Reactive<string | undefined>, (next) => {
      element.className = next ?? ''
    })
    return
  }
  // Словарь «имя класса → включён ли». Каждое имя своим эффектом: иначе
  // изменение одного флага переписывало бы className целиком и стирало
  // бы классы, выставленные не отсюда.
  for (const [name, on] of Object.entries(value)) {
    bind(on, (next) => {
      element.classList.toggle(name, next)
    })
  }
}

function applyStyle(element: HTMLElement, style: Props['style']): void {
  if (style === undefined) {
    return
  }
  for (const [name, value] of Object.entries(style)) {
    bind(value, (next) => {
      if (next === undefined || next === '') {
        element.style.removeProperty(name)
      } else {
        element.style.setProperty(name, String(next))
      }
    })
  }
}

function applyAttribute(element: HTMLElement, name: string, value: unknown): void {
  bind(value as Reactive<unknown>, (next) => {
    if (next === false || next === null || next === undefined) {
      // Отсутствие атрибута и атрибут со значением "false" — разные
      // вещи: второе для булевых атрибутов означает ВКЛЮЧЕНО.
      element.removeAttribute(name)
      return
    }
    element.setAttribute(name, next === true ? '' : String(next))
  })
}

function applyProperty(element: HTMLElement, name: string, value: unknown): void {
  bind(value as Reactive<unknown>, (next) => {
    ;(element as unknown as Record<string, unknown>)[name] = next
  })
}

function applyEvent(element: HTMLElement, name: string, handler: unknown): void {
  if (!isFunction(handler)) {
    throw new Error(
      `обработчик ${name} не функция: ${String(handler)}. ` +
        'Похоже, вызов написан со скобками и подставился его результат',
    )
  }
  const type = name.slice(2).toLowerCase()
  const listener = handler as EventListener
  element.addEventListener(type, listener)
  // Снятие обязательно даже при удалении узла: слушатель держит ссылку
  // на замыкание, а через него — на всё, что оно захватило.
  onCleanup(() => element.removeEventListener(type, listener))
}

function appendChild(parent: Node, child: Child): void {
  if (child === null || child === undefined || child === false) {
    return
  }
  if (Array.isArray(child)) {
    for (const item of child as readonly Child[]) {
      appendChild(parent, item)
    }
    return
  }
  if (child instanceof Node) {
    parent.appendChild(child)
    return
  }
  if (isList(child)) {
    appendList(parent, child as ListChild<unknown>)
    return
  }
  if (isFunction(child)) {
    appendDynamic(parent, child as () => Child)
    return
  }
  parent.appendChild(document.createTextNode(String(child)))
}

/**
 * Содержимое, которое пересчитывается.
 *
 * Живёт между двумя якорями, а не заменяет содержимое родителя: у
 * родителя есть соседи, и `textContent = x` стёр бы их. Якоря — пустые
 * комментарии: видны в инспекторе, в разметке места не занимают.
 *
 * Возвращённые узлы вставляются как есть, а не строкой, — иначе
 * условное содержимое нельзя было бы выразить вовсе.
 */
function appendDynamic(parent: Node, child: () => Child): void {
  const start = document.createComment('')
  const end = document.createComment('')
  parent.appendChild(start)
  parent.appendChild(end)

  effect(() => {
    const next = child()
    clearBetween(start, end)
    insertBetween(start, end, next)
  })
}

function clearBetween(start: Node, end: Node): void {
  const parent = start.parentNode
  if (parent === null) {
    return
  }
  let node = start.nextSibling
  while (node !== null && node !== end) {
    const following = node.nextSibling
    parent.removeChild(node)
    node = following
  }
}

function insertBetween(start: Node, end: Node, value: Child): void {
  const parent = start.parentNode
  if (parent === null) {
    return
  }
  if (value === null || value === undefined || value === false) {
    return
  }
  if (Array.isArray(value)) {
    for (const item of value as readonly Child[]) {
      insertBetween(start, end, item)
    }
    return
  }
  if (value instanceof Node) {
    parent.insertBefore(value, end)
    return
  }
  if (isFunction(value) || isList(value)) {
    // Динамика внутри динамики означала бы эффект, заводимый на каждом
    // пересчёте, — ровно та утечка, от которой слой и сторожат.
    throw new Error(
      'динамическое содержимое вернуло ещё одно динамическое: ' +
        'разверните его отдельным when или each',
    )
  }
  parent.insertBefore(document.createTextNode(String(value)), end)
}

/**
 * Одна из двух ветвей.
 *
 * Ветвь пересобирается ТОЛЬКО при смене условия. Достигается это тем,
 * что сама ветвь вызывается без отслеживания: иначе любое чтение внутри
 * неё подписывало бы на себя внешний эффект, и один изменившийся счётчик
 * пересобирал бы всю ветку целиком — с потерей фокуса и прокрутки.
 *
 * Привязки внутри ветви при этом работают как обычно: `untrack` снимает
 * только текущего ЧИТАТЕЛЯ, владельца он не трогает, поэтому созданные
 * внутри эффекты отслеживают своё и снимаются вместе с ветвью.
 */
export function when(
  condition: () => boolean,
  then: () => Child,
  otherwise?: () => Child,
): () => Child {
  return () => {
    const value = condition()
    return untrack(() => {
      if (value) {
        return then()
      }
      return otherwise === undefined ? null : otherwise()
    })
  }
}

/**
 * Список с ключами.
 *
 * КЛЮЧ ОБЯЗАТЕЛЕН, и это не педантизм. Без него список
 * перерисовывается целиком при любом изменении: теряется фокус в полях,
 * сбрасывается прокрутка, обрываются анимации. С ключом переживший
 * элемент остаётся ТЕМ ЖЕ узлом DOM, а не похожим.
 *
 * @param render получает элемент и его номер ФУНКЦИЯМИ: значения
 *               меняются, а узел остаётся, и передача по значению
 *               означала бы пересборку узла ради изменившегося текста
 */
export function each<T>(
  items: () => readonly T[],
  key: (item: T, index: number) => string | number,
  render: (item: () => T, index: () => number) => Node,
): ListChild<T> {
  return { kind: 'список', items, key, render }
}

interface Row<T> {
  node: Node
  item: WriteSignal<T>
  index: WriteSignal<number>
  dispose: () => void
}

function appendList<T>(parent: Node, list: ListChild<T>): void {
  const start = document.createComment('')
  const end = document.createComment('')
  parent.appendChild(start)
  parent.appendChild(end)

  let rows = new Map<string | number, Row<T>>()
  // Снятие строк — забота и размонтирования тоже: без этого области
  // строк пережили бы весь список.
  onCleanup(() => {
    for (const row of rows.values()) {
      row.dispose()
    }
    rows.clear()
  })

  effect(() => {
    const next = list.items()
    const owner = start.parentNode
    if (owner === null) {
      return
    }

    const kept = new Map<string | number, Row<T>>()
    const order: Array<Row<T>> = []

    next.forEach((item, index) => {
      const id = list.key(item, index)
      if (kept.has(id)) {
        // Молчаливое совпадение ключей — худший исход: строки
        // перепутываются между собой, и перерисовкой это не чинится.
        throw new Error(
          'ключ ' + String(id) + ' встретился в списке дважды',
        )
      }
      const existing = rows.get(id)
      if (existing !== undefined) {
        // Значения обновляются, узел остаётся тем же. В этом весь ключ.
        existing.item.value = item
        existing.index.value = index
        kept.set(id, existing)
        order.push(existing)
        return
      }
      // ОТВЯЗАННАЯ область, и это не мелочь. Строку строит эффект
      // списка, а его повторный прогон снимает своих детей: обычная
      // область умерла бы при первом же изменении списка, оставив на
      // странице узел, который больше никогда не обновится. Снимает
      // строку тот, кто её держит, — карта ниже и снятие всего списка.
      const created = detachedRoot<Row<T>>((disposeRow) => {
        const value = signal(item)
        const at = signal(index)
        const node = list.render(
          () => value.value,
          () => at.value,
        )
        return { node, item: value, index: at, dispose: disposeRow }
      })
      kept.set(id, created)
      order.push(created)
    })

    for (const [id, row] of rows) {
      if (!kept.has(id)) {
        row.dispose()
        if (row.node.parentNode === owner) {
          owner.removeChild(row.node)
        }
      }
    }
    rows = kept

    // Расстановка с конца: каждый узел встаёт перед уже поставленным.
    // Двигается только тот, кто и правда не на месте: insertBefore узла,
    // который уже там, всё равно снял бы его и вставил заново, а это
    // потерянный фокус и оборванная анимация.
    let after: Node = end
    for (let i = order.length - 1; i >= 0; i--) {
      const row = order[i]
      if (row === undefined) {
        continue
      }
      if (row.node.nextSibling !== after || row.node.parentNode !== owner) {
        owner.insertBefore(row.node, after)
      }
      after = row.node
    }
  })
}

/**
 * Создать элемент.
 *
 * Возвращает НАСТОЯЩИЙ узел DOM, а не описание. Промежуточное дерево
 * пришлось бы потом сравнивать с настоящим — та самая работа, ради
 * отсутствия которой всё и затевалось.
 */
export function h(
  tag: string,
  props: Props | null = null,
  ...children: Child[]
): HTMLElement {
  const element = document.createElement(tag)

  if (props !== null) {
    for (const [name, value] of Object.entries(props)) {
      if (value === undefined && name !== 'class' && name !== 'style') {
        continue
      }
      if (name === 'class') {
        applyClass(element, value as Props['class'])
      } else if (name === 'style') {
        applyStyle(element, value as Props['style'])
      } else if (name === 'ref') {
        ;(value as (element: HTMLElement) => void)(element)
      } else if (name.startsWith('on') && name.length > 2) {
        applyEvent(element, name, value)
      } else if (PROPERTIES.has(name)) {
        applyProperty(element, name, value)
      } else {
        applyAttribute(element, name, value)
      }
    }
  }

  for (const child of children) {
    appendChild(element, child)
  }
  return element
}

/**
 * Вставить дерево в страницу и вернуть снятие.
 *
 * Всё, что заведено внутри `render`, принадлежит области монтирования:
 * размонтирование снимает её целиком. Забыть отдельную привязку нельзя,
 * потому что отдельных привязок никто не считает.
 */
export function mount(parent: Element, render: () => Node): () => void {
  return root((disposeRoot) => {
    const node = render()
    parent.appendChild(node)
    return () => {
      disposeRoot()
      // Узел убирается ПОСЛЕ снятия: обработчики снимаются с ещё
      // присоединённого элемента, и порядок тут не косметический —
      // снятие с уже выброшенного узла молча не сделало бы ничего.
      if (node.parentNode === parent) {
        parent.removeChild(node)
      }
    }
  })
}
