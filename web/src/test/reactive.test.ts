/*
 * Реактивное ядро: что оно обязано делать и чего обязано НЕ делать.
 *
 * Проверок на «не делать» здесь больше, и это намеренно. Слой, который
 * пересчитывает лишнее, работает — просто медленно и с морганием; слой,
 * который течёт подписками, работает — просто до перезагрузки страницы.
 * Ни одну из этих бед не видно по экрану, поэтому их считают, а не
 * разглядывают.
 *
 * Раннер — встроенный в Node `node:test`: ещё одна зависимость ради
 * describe/it была бы платой ни за что (ADR-0012).
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  batch,
  computed,
  effect,
  onCleanup,
  root,
  signal,
  untrack,
} from '../core/reactive.js'

test('сигнал отдаёт записанное', () => {
  const count = signal(1)
  assert.equal(count.value, 1)
  count.value = 2
  assert.equal(count.value, 2)
})

test('эффект выполняется сразу и на каждое изменение', () => {
  const count = signal(0)
  const seen: number[] = []

  effect(() => {
    seen.push(count.value)
  })

  assert.deepEqual(seen, [0], 'эффект не выполнился при заведении')
  count.value = 1
  count.value = 2
  assert.deepEqual(seen, [0, 1, 2])
})

test('запись того же значения не будит никого', () => {
  // Иначе присваивание в цикле рендера пересчитывало бы всё дерево на
  // каждом кадре, ничего не меняя на экране.
  const count = signal(0)
  let runs = 0

  effect(() => {
    count.value
    runs++
  })

  count.value = 0
  count.value = 0
  assert.equal(runs, 1, 'эффект разбудили записью того же значения')
})

test('вычисление считается лениво и запоминается', () => {
  const count = signal(2)
  let evaluations = 0

  const doubled = computed(() => {
    evaluations++
    return count.value * 2
  })

  assert.equal(evaluations, 0, 'вычисление посчиталось до первого чтения')
  assert.equal(doubled.value, 4)
  assert.equal(doubled.value, 4)
  assert.equal(evaluations, 1, 'второе чтение пересчитало то же самое')

  count.value = 3
  assert.equal(doubled.value, 6)
  assert.equal(evaluations, 2)
})

test('вычисление, вернувшее прежнее значение, останавливает волну', () => {
  // Правило «чётное ли число» меняется реже самого числа. Эффект,
  // смотрящий на правило, не должен просыпаться на каждое число.
  const count = signal(0)
  const isEven = computed(() => count.value % 2 === 0)
  let runs = 0

  effect(() => {
    isEven.value
    runs++
  })

  assert.equal(runs, 1)
  count.value = 2
  assert.equal(runs, 1, 'эффект разбудили, хотя чётность не изменилась')
  count.value = 3
  assert.equal(runs, 2)
})

test('ромб: эффект видит согласованный мир и выполняется один раз', () => {
  // Классическая ловушка. Значение приходит к эффекту двумя путями, и
  // наивная реализация выполняет его дважды, причём первый раз — с одним
  // пересчитанным слагаемым и одним старым. На экране это моргание и
  // числа, которые не сходятся между собой.
  const source = signal(1)
  const left = computed(() => source.value * 2)
  const right = computed(() => source.value * 10)
  const seen: number[] = []

  effect(() => {
    seen.push(left.value + right.value)
  })

  assert.deepEqual(seen, [12])
  source.value = 2
  assert.deepEqual(seen, [12, 24], 'эффект увидел мир наполовину обновлённым')
})

test('пачка изменений будит эффект один раз', () => {
  const first = signal(1)
  const second = signal(2)
  const seen: number[] = []

  effect(() => {
    seen.push(first.value + second.value)
  })

  batch(() => {
    first.value = 10
    second.value = 20
  })

  assert.deepEqual(seen, [3, 30], 'между двумя записями эффект успел выполниться')
})

test('untrack читает, не подписываясь', () => {
  const tracked = signal(0)
  const hidden = signal(0)
  let runs = 0

  effect(() => {
    tracked.value
    untrack(() => hidden.value)
    runs++
  })

  hidden.value = 1
  assert.equal(runs, 1, 'подписка взялась изнутри untrack')
  tracked.value = 1
  assert.equal(runs, 2)
})

test('update не делает пишущего читателем', () => {
  // Иначе вызов внутри эффекта подписал бы этот эффект на им же
  // изменённый сигнал, и получился бы вечный цикл.
  const count = signal(0)
  const other = signal(0)
  let runs = 0

  effect(() => {
    other.value
    count.update((previous) => previous + 1)
    runs++
  })

  assert.equal(runs, 1)
  assert.equal(count.value, 1)
  other.value = 1
  assert.equal(runs, 2)
  assert.equal(count.value, 2)
})

test('условная зависимость отпускается, когда перестала читаться', () => {
  // Ветка, которую перестали читать, не должна будить эффект. Слой,
  // копящий подписки навсегда, работает — просто пересчитывает всё
  // подряд, и заметить это можно только по счётчику.
  const useLeft = signal(true)
  const left = signal('л')
  const right = signal('п')
  let runs = 0

  effect(() => {
    useLeft.value ? left.value : right.value
    runs++
  })

  assert.equal(right.observerCount, 0, 'подписка взята на непрочитанную ветку')

  right.value = 'п2'
  assert.equal(runs, 1)

  useLeft.value = false
  assert.equal(runs, 2)
  assert.equal(left.observerCount, 0, 'подписка на брошенную ветку осталась')

  left.value = 'л2'
  assert.equal(runs, 2, 'эффект разбудила ветка, которую он больше не читает')
})

test('снятый эффект отписывается от всего', () => {
  const count = signal(0)
  let runs = 0

  const stop = effect(() => {
    count.value
    runs++
  })

  assert.equal(count.observerCount, 1)
  stop()
  assert.equal(count.observerCount, 0, 'подписка пережила снятие эффекта')

  count.value = 1
  assert.equal(runs, 1, 'снятый эффект выполнился')
})

test('onCleanup срабатывает перед каждым повтором и при снятии', () => {
  const count = signal(0)
  const log: string[] = []

  const stop = effect(() => {
    const at = count.value
    log.push(`пуск ${at}`)
    onCleanup(() => log.push(`снятие ${at}`))
  })

  count.value = 1
  stop()

  assert.deepEqual(log, ['пуск 0', 'снятие 0', 'пуск 1', 'снятие 1'])
})

test('onCleanup снаружи эффекта — отказ, а не молчание', () => {
  // Молчаливое «ничего не произошло» тут хуже отказа: подписка,
  // которую никто не снимет, живёт до перезагрузки страницы.
  assert.throws(() => onCleanup(() => {}), /вне эффекта/)
})

test('область снимает всё, что внутри неё', () => {
  // Это и есть ответ на утечки подписок: снимается ОДНА область, а не
  // каждый эффект поимённо — забыть один из списка нельзя, потому что
  // списка нет.
  const count = signal(0)
  let runs = 0

  const stop = root((dispose) => {
    effect(() => {
      count.value
      runs++
    })
    effect(() => {
      count.value
      runs++
    })
    return dispose
  })

  assert.equal(count.observerCount, 2)
  assert.equal(runs, 2)

  stop()
  assert.equal(count.observerCount, 0, 'область снята, а подписки остались')

  count.value = 1
  assert.equal(runs, 2, 'эффекты снятой области выполнились')
})

test('эффект, заводящий эффекты, не копит их', () => {
  // Самая тихая утечка из всех: каждый прогон добавляет подписчика, а
  // старый остаётся. Ничего не ломается — просто память растёт, и
  // обработчиков становится вдвое больше на каждое обновление.
  const outer = signal(0)
  const inner = signal(0)
  let innerRuns = 0

  const stop = effect(() => {
    outer.value
    effect(() => {
      inner.value
      innerRuns++
    })
  })

  assert.equal(inner.observerCount, 1)

  outer.value = 1
  outer.value = 2
  assert.equal(
    inner.observerCount,
    1,
    `вложенных эффектов накопилось ${inner.observerCount}`,
  )

  innerRuns = 0
  inner.value = 1
  assert.equal(innerRuns, 1, 'вложенный эффект выполнился больше одного раза')

  stop()
  assert.equal(inner.observerCount, 0, 'вложенный эффект пережил внешний')
})

test('область внутри области снимается вместе с внешней', () => {
  const count = signal(0)

  const stop = root((dispose) => {
    root(() => {
      effect(() => {
        count.value
      })
    })
    return dispose
  })

  assert.equal(count.observerCount, 1)
  stop()
  assert.equal(count.observerCount, 0, 'вложенная область пережила внешнюю')
})

test('вычисление отпускает источники, когда его перестают читать', () => {
  // Вычисление, которое никто не читает, обязано отцепиться от своих
  // источников вместе с эффектом, который его читал. Иначе цепочка
  // держится за сигнал вечно.
  const count = signal(0)
  const doubled = computed(() => count.value * 2)

  const stop = effect(() => {
    doubled.value
  })

  assert.equal(doubled.observerCount, 1)
  stop()
  assert.equal(doubled.observerCount, 0, 'подписка на вычисление осталась')
})

test('взаимная запись эффектов не вешает вкладку молча', () => {
  // Два эффекта, пишущие в сигналы друг друга, — ошибка программиста, и
  // единственный плохой исход тут не ошибка, а тишина: вкладка встаёт
  // намертво, и почему — не сказано нигде.
  //
  // Заведение второго эффекта каскада НЕ вызывает, и первая редакция
  // этой проверки ждала отказа именно там — ошибочно. Пока эффект
  // выполняется, он уже помечен грязным, и повторная пометка его не
  // ставит в очередь: каскад гаснет сам. Расходятся они на записи,
  // когда оба уже живы и чисты.
  const a = signal(0)
  const b = signal(0)
  const other = signal(0)

  effect(() => {
    a.value
    other.value
    b.update((previous) => previous + 1)
  })

  effect(() => {
    b.value
    a.update((previous) => previous + 1)
  })

  assert.throws(() => {
    other.value = 1
  }, /не сходятся/)
})
