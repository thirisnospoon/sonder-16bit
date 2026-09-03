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
import { effect, onCleanup, root } from './reactive.js'

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
  if (isFunction(child)) {
    // Меняющееся содержимое живёт в отдельном текстовом узле: замена
    // textContent у родителя стирала бы соседей.
    const node = document.createTextNode('')
    parent.appendChild(node)
    effect(() => {
      const next = (child as () => Child)()
      node.data =
        next === null || next === undefined || next === false ? '' : String(next)
    })
    return
  }
  parent.appendChild(document.createTextNode(String(child)))
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
