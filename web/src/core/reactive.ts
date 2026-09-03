/*
 * Реактивное ядро на сигналах.
 *
 * Единственное место фронта, где есть собственная механика; всё
 * остальное строится поверх (ADR-0012). Клона React с виртуальным DOM
 * здесь нет и не будет: сигналы обновляют точечно, без сравнения
 * деревьев.
 *
 * АЛГОРИТМ: ленивое вытягивание с отметками состояния.
 *
 * Наивная реализация — «записали в сигнал, сразу позвали всех
 * подписчиков» — даёт две беды, и обе видны пользователю. Первая:
 * лишние прогоны. Значение прошло через два вычисления к одному
 * эффекту, и эффект отработал дважды. Вторая, хуже: эффект успевает
 * увидеть мир наполовину обновлённым — одно вычисление уже пересчитано,
 * другое ещё нет. На экране это моргание и рассогласованные числа.
 *
 * Поэтому запись делает ровно две вещи: помечает всё, что ниже по
 * течению, и складывает эффекты в очередь. Пересчёт происходит при
 * разборе очереди и только для тех, у кого источники ДЕЙСТВИТЕЛЬНО
 * изменились: пометка «возможно устарел» проверяется вытягиванием, а не
 * принимается на веру.
 */

/** Состояние узла относительно его источников. */
const CLEAN = 0
/** Кто-то выше по течению мог измениться — надо проверить. */
const CHECK = 1
/** Источник точно изменился — пересчитывать. */
const DIRTY = 2

type State = typeof CLEAN | typeof CHECK | typeof DIRTY

interface Node {
  /** Кто читает этот узел. */
  observers: Set<Computation>
}

interface Computation extends Node {
  /** Что читает этот узел. */
  sources: Set<Node>
  state: State
  disposed: boolean
  /** Пересчитать себя. Для эффекта — выполнить. */
  run(): void
  /** Снять за собой: подписки и всё, что оставил прошлый прогон. */
  cleanups: Array<() => void>
  /** Владелец, снимающий этот узел вместе с собой. */
  owner: Owner | null
}

interface Owner {
  children: Set<Computation>
  cleanups: Array<() => void>
  disposed: boolean
}

/** Кто сейчас читает. Только этот узел получает подписку. */
let listener: Computation | null = null
/** Чьим детям принадлежат создаваемые вычисления. */
let owner: Owner | null = null

/** Очередь эффектов текущей пачки. */
let queue: Computation[] = []
/** Глубина batch: пока больше нуля, очередь не разбирается. */
let batching = 0
/** Идёт ли разбор очереди прямо сейчас. */
let flushing = false

/**
 * Читаемое значение.
 *
 * Свойство, а не вызов функции: `count.value` в разметке читается как
 * значение и не даёт спутать «взять» с «передать» — ошибка, на которой
 * в подобных слоях спотыкаются чаще всего.
 */
export interface ReadSignal<T> {
  readonly value: T
  /** Сколько узлов сейчас подписано. Для диагностики и проверок утечек. */
  readonly observerCount: number
}

export interface WriteSignal<T> extends ReadSignal<T> {
  value: T
  /** Записать, посчитав новое значение от старого. */
  update(next: (previous: T) => T): void
}

/** Сравнение «то же самое значение» — по умолчанию Object.is. */
export type Equals<T> = (a: T, b: T) => boolean

function defaultEquals<T>(a: T, b: T): boolean {
  return Object.is(a, b)
}

class SignalNode<T> implements Node {
  observers = new Set<Computation>()

  constructor(
    private current: T,
    private readonly equals: Equals<T>,
  ) {}

  read(): T {
    subscribe(this)
    return this.current
  }

  write(next: T): void {
    if (this.equals(this.current, next)) {
      // Запись того же значения — не изменение. Без этой проверки
      // присваивание в цикле рендера пересчитывало бы всё дерево на
      // каждом кадре, ничего не меняя на экране.
      return
    }
    this.current = next
    markDownstream(this)
    flushIfIdle()
  }
}

function subscribe(node: Node): void {
  const current = listener
  if (current === null || current.disposed) {
    return
  }
  node.observers.add(current)
  current.sources.add(node)
}

/**
 * Пометить всё, что ниже по течению.
 *
 * Прямые наблюдатели становятся DIRTY — их источник точно изменился.
 * Всё, что за ними, — CHECK: изменился ли их источник на самом деле,
 * выяснится вытягиванием. Вычисление, вернувшее прежнее значение,
 * обязано остановить волну; иначе пересчитывалось бы всё дерево при
 * любой записи.
 */
function markDownstream(node: Node): void {
  for (const observer of node.observers) {
    mark(observer, DIRTY)
  }
}

function mark(computation: Computation, state: State): void {
  if (computation.disposed || computation.state >= state) {
    return
  }
  const wasClean = computation.state === CLEAN
  computation.state = state

  if (isEffect(computation)) {
    if (wasClean) {
      queue.push(computation)
    }
    return
  }
  // Вычисление: вниз по течению уходит «возможно устарел», а не
  // «устарел». Настоящий ответ узнаётся только пересчётом.
  if (wasClean) {
    for (const observer of computation.observers) {
      mark(observer, CHECK)
    }
  }
}

function isEffect(computation: Computation): boolean {
  return (computation as EffectNode).isEffect === true
}

/**
 * Прочитать состояние заново.
 *
 * Функцией, а не полем напрямую, и не ради красоты. Обновление источника
 * МЕНЯЕТ наше состояние: источник помечает нас DIRTY изнутри своего
 * пересчёта. Проверка типов этого не видит — после `state === CHECK` она
 * считает поле навсегда равным CHECK и объявляет следующее сравнение
 * бессмысленным. Вызов возвращает ей правду: значение перечитывается, и
 * оно может быть любым.
 */
function stateOf(computation: Computation): State {
  return computation.state
}

/** Отцепиться от всех источников: подписки живут ровно один прогон. */
function unlink(computation: Computation): void {
  for (const source of computation.sources) {
    source.observers.delete(computation)
  }
  computation.sources.clear()
}

function runCleanups(computation: Computation): void {
  if (computation.cleanups.length === 0) {
    return
  }
  const list = computation.cleanups
  computation.cleanups = []
  // Снимаем в обратном порядке: последнее заведённое снимается первым,
  // как и положено вложенным ресурсам.
  for (let i = list.length - 1; i >= 0; i--) {
    const cleanup = list[i]
    if (cleanup !== undefined) {
      cleanup()
    }
  }
}

function execute(computation: Computation): void {
  if (computation.disposed) {
    return
  }
  // Дети прошлого прогона снимаются вместе с ним. Эффект, заводящий
  // эффекты, иначе накапливал бы их: каждый прогон добавлял бы новый, а
  // старый оставался бы подписанным. Ровно эта утечка не видна ничем,
  // кроме растущей памяти, — потому она и вынесена в гейт фазы.
  disposeChildren(computation)
  runCleanups(computation)
  unlink(computation)

  const previousListener = listener
  const previousOwner = owner
  listener = computation
  owner = computation as unknown as Owner
  try {
    computation.run()
  } finally {
    listener = previousListener
    owner = previousOwner
  }
  computation.state = CLEAN
}

/**
 * Обновить, если и правда устарел.
 *
 * CHECK означает «кто-то выше мог измениться». Проверяется это
 * вытягиванием: спрашиваем источники, и если после их обновления мы всё
 * ещё CHECK, значит ни один не изменился — пересчитывать нечего.
 */
function updateIfNecessary(computation: Computation): void {
  if (computation.state === CLEAN || computation.disposed) {
    return
  }
  if (computation.state === CHECK) {
    for (const source of computation.sources) {
      const asComputation = source as Computation
      if (asComputation.sources !== undefined) {
        updateIfNecessary(asComputation)
      }
      if (stateOf(computation) === DIRTY) {
        break
      }
    }
  }
  if (stateOf(computation) === DIRTY) {
    execute(computation)
  } else {
    computation.state = CLEAN
  }
}

class ComputedNode<T> implements Computation, Node {
  observers = new Set<Computation>()
  sources = new Set<Node>()
  state: State = DIRTY
  disposed = false
  cleanups: Array<() => void> = []
  owner: Owner | null = null
  children = new Set<Computation>()

  private current!: T
  private initialized = false

  constructor(
    private readonly fn: () => T,
    private readonly equals: Equals<T>,
  ) {}

  run(): void {
    const next = this.fn()
    if (this.initialized && this.equals(this.current, next)) {
      // Значение не изменилось — волна останавливается здесь.
      return
    }
    this.current = next
    this.initialized = true
    // Наблюдатели узнают о настоящем изменении, а не о подозрении.
    for (const observer of this.observers) {
      mark(observer, DIRTY)
    }
  }

  read(): T {
    updateIfNecessary(this)
    subscribe(this)
    return this.current
  }
}

class EffectNode implements Computation {
  readonly isEffect = true
  observers = new Set<Computation>()
  sources = new Set<Node>()
  state: State = DIRTY
  disposed = false
  cleanups: Array<() => void> = []
  owner: Owner | null = null
  children = new Set<Computation>()

  constructor(private readonly fn: () => void) {}

  run(): void {
    this.fn()
  }
}

function adopt(computation: Computation): void {
  const parent = owner
  if (parent === null) {
    return
  }
  computation.owner = parent
  parent.children.add(computation)
}

/**
 * Значение, которое можно менять.
 *
 * @param equals чем считать «то же самое». Для объектов, меняемых на
 *               месте, сюда передают `() => false`: Object.is увидел бы
 *               ту же ссылку и молча проглотил изменение.
 */
export function signal<T>(
  initial: T,
  equals: Equals<T> = defaultEquals,
): WriteSignal<T> {
  const node = new SignalNode(initial, equals)
  return {
    get value(): T {
      return node.read()
    },
    set value(next: T) {
      node.write(next)
    },
    get observerCount(): number {
      return node.observers.size
    },
    update(next: (previous: T) => T): void {
      // Читаем БЕЗ подписки: обновление по старому значению не делает
      // пишущего читателем. Иначе вызов внутри эффекта подписал бы этот
      // эффект на им же изменённый сигнал — и получился бы цикл.
      node.write(next(untrack(() => node.read())))
    },
  }
}

/** Значение, выведенное из других. Считается лениво и запоминается. */
export function computed<T>(
  fn: () => T,
  equals: Equals<T> = defaultEquals,
): ReadSignal<T> {
  const node = new ComputedNode(fn, equals)
  adopt(node)
  return {
    get value(): T {
      return node.read()
    },
    get observerCount(): number {
      return node.observers.size
    },
  }
}

/**
 * Действие, повторяемое при изменении прочитанного.
 *
 * Возвращает снятие. Снять эффект — обязанность того, кто его завёл:
 * иначе он останется подписанным на сигналы навсегда, и вместе с ним в
 * памяти останется всё, что он держит замыканием. Внутри `root` за это
 * отвечает владелец.
 */
export function effect(fn: () => void): () => void {
  const node = new EffectNode(fn)
  adopt(node)
  execute(node)
  return () => dispose(node)
}

/**
 * Снять за собой перед следующим прогоном и при снятии совсем.
 *
 * Привязывается к ВЛАДЕЛЬЦУ, а не к тому, кто сейчас читает, и разница
 * не умозрительная. Обработчик события заводится в области монтирования,
 * где никто ничего не читает; привязка к читателю отказывала бы там, где
 * снятие как раз нужнее всего — слушатель держит ссылку на замыкание, а
 * через него на всё, что оно захватило.
 *
 * Внутри эффекта владельцем является сам эффект, поэтому снятие
 * по-прежнему происходит перед каждым его повтором.
 *
 * Владельца нет вовсе — отказ. Молчаливое «ничего не произошло» тут хуже:
 * снятие, которое никто не выполнит, живёт до перезагрузки страницы.
 */
export function onCleanup(fn: () => void): void {
  if (owner === null) {
    throw new Error(
      'onCleanup вне области: снимать нечего и некому. ' +
        'Похоже, вызов уехал из эффекта или из mount наружу',
    )
  }
  owner.cleanups.push(fn)
}

/** Прочитать, не подписываясь. */
export function untrack<T>(fn: () => T): T {
  const previous = listener
  listener = null
  try {
    return fn()
  } finally {
    listener = previous
  }
}

/**
 * Одна пачка изменений — один прогон эффектов.
 *
 * Без этого два присваивания подряд дают два прогона, и между ними
 * эффект видит мир наполовину обновлённым.
 */
export function batch<T>(fn: () => T): T {
  batching++
  try {
    return fn()
  } finally {
    batching--
    flushIfIdle()
  }
}

function flushIfIdle(): void {
  if (batching > 0 || flushing || queue.length === 0) {
    return
  }
  flush()
}

function flush(): void {
  // Очередь может расти по ходу разбора: эффект вправе писать в сигнал.
  //
  // ЗАЩЁЛКА, А НЕ ВЛОЖЕННЫЙ РАЗБОР. Запись изнутри эффекта звала бы
  // разбор повторно, и тогда эффект видел бы очередь наполовину
  // разобранной — то самое несогласованное состояние, ради которого
  // очередь и заведена. Заодно вложенность прятала расхождение: пока
  // внешний эффект ещё выполняется, он помечен грязным и в очередь не
  // попадает, поэтому взаимная запись двух эффектов случайно СХОДИЛАСЬ
  // вместо того, чтобы упереться в счётчик оборотов.
  flushing = true
  try {
    let rounds = 0
    while (queue.length > 0) {
      if (++rounds > 1000) {
        queue = []
        throw new Error(
          'эффекты не сходятся: тысяча оборотов очереди. ' +
            'Похоже, два эффекта пишут в сигналы друг друга',
        )
      }
      const current = queue
      queue = []
      for (const node of current) {
        updateIfNecessary(node)
      }
    }
  } finally {
    flushing = false
  }
}

/**
 * Область владения: всё заведённое внутри снимается вместе с ней.
 *
 * Это ответ на утечки подписок. Компонент заводит эффекты внутри своей
 * области, и при размонтировании снимается ОДНА область, а не каждый
 * эффект поимённо — забыть один из списка нельзя, потому что списка нет.
 *
 * Область принадлежит тому, внутри кого заведена, и снимается вместе с
 * ним. Умолчание выбрано в пользу этого, а не в пользу отвязанности:
 * забытая область течёт молча, а лишнее снятие видно сразу.
 */
export function root<T>(fn: (dispose: () => void) => T): T {
  return makeRoot(fn, true)
}

/**
 * Область, НИКОМУ не принадлежащая.
 *
 * Нужна там, где заведённое обязано пережить того, кто его завёл.
 * Единственный такой случай в слое — строка списка: её строит эффект
 * списка, но повторный прогон этого эффекта снимает своих детей, и
 * строка вместе с привязками умерла бы при первом же изменении, оставив
 * на странице узел, который больше никогда не обновится.
 *
 * Снимает такую область только тот, кто держит возвращённое снятие. Кто
 * держит — тот и отвечает: у списка это карта строк и снятие при
 * размонтировании.
 */
export function detachedRoot<T>(fn: (dispose: () => void) => T): T {
  return makeRoot(fn, false)
}

function makeRoot<T>(fn: (dispose: () => void) => T, attach: boolean): T {
  const node: Computation & Owner = {
    observers: new Set(),
    sources: new Set(),
    state: CLEAN,
    disposed: false,
    cleanups: [],
    owner,
    children: new Set(),
    run(): void {
      /* область сама ничего не считает */
    },
  }
  if (attach) {
    adopt(node)
  } else {
    node.owner = null
  }

  const previousOwner = owner
  const previousListener = listener
  owner = node as unknown as Owner
  // Область не читает: подписка, случайно взятая на себя областью,
  // пережила бы все свои эффекты.
  listener = null
  try {
    return fn(() => dispose(node))
  } finally {
    owner = previousOwner
    listener = previousListener
  }
}

function disposeChildren(computation: Computation): void {
  const children = (computation as unknown as Owner).children
  if (children === undefined || children.size === 0) {
    return
  }
  // Копия: dispose ребёнка вычёркивает его из этого же множества.
  for (const child of Array.from(children)) {
    dispose(child)
  }
  children.clear()
}

/**
 * Снять вычисление или область вместе со всем, что внутри.
 *
 * Наружу не выходит намеренно: снимать положено тем, что вернули
 * `effect` и `root`. Отдельная функция, принимающая внутренний узел,
 * означала бы, что снаружи можно снять чужого ребёнка мимо владельца, —
 * и владение перестало бы что-либо гарантировать.
 */
function dispose(computation: Computation): void {
  if (computation.disposed) {
    return
  }
  computation.disposed = true

  disposeChildren(computation)
  runCleanups(computation)
  unlink(computation)

  const parent = computation.owner
  if (parent !== null && parent.children !== undefined) {
    parent.children.delete(computation)
  }
}
