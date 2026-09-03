#!/usr/bin/env node
/*
 * Медиана оценок Lighthouse по нескольким прогонам и приговор по порогу.
 *
 * Медиана, а не среднее: среднее один выброс утягивает за собой, а
 * выброс тут — норма жизни. Три прогона, средний по величине; при
 * чётном числе берётся нижний из двух средних, потому что завышать
 * оценку в свою пользу нельзя даже на пол-балла.
 *
 * Печатается ВЕСЬ разброс, а не только итог. Медиана 92 при разбросе
 * 91..93 и медиана 92 при разбросе 74..96 — это разные новости, и
 * вторая означает, что мерить надо на менее занятой машине, а не что
 * система хороша.
 *
 * Заодно печатаются метрики, из которых складывается оценка
 * производительности: «стало хуже на восемь баллов» без них
 * превращается в гадание, а с ними — в конкретный вопрос, почему
 * выросло время до отрисовки.
 */
'use strict'

const fs = require('fs')

const [, , thresholdRaw, ...files] = process.argv
const threshold = Number(thresholdRaw)

if (!Number.isFinite(threshold) || files.length === 0) {
  console.error('нужно: lighthouse-median.js <порог> <отчёт.json>...')
  process.exit(2)
}

/** Разделы в порядке, в каком их показывает сама Lighthouse. */
const РАЗДЕЛЫ = [
  ['performance', 'производительность'],
  ['accessibility', 'доступность'],
  ['best-practices', 'практики'],
  ['seo', 'поисковая пригодность'],
]

/** Метрики, из которых складывается оценка производительности. */
const МЕТРИКИ = [
  ['first-contentful-paint', 'первая отрисовка'],
  ['largest-contentful-paint', 'главная отрисовка'],
  ['total-blocking-time', 'блокировка потока'],
  ['cumulative-layout-shift', 'сдвиг разметки'],
  ['speed-index', 'индекс скорости'],
]

const отчёты = files.map((file) => {
  const raw = fs.readFileSync(file, 'utf8')
  const отчёт = JSON.parse(raw)
  if (отчёт.runtimeError && отчёт.runtimeError.code !== 'NO_ERROR') {
    console.error(`прогон ${file} не состоялся: ${отчёт.runtimeError.message}`)
    process.exit(1)
  }
  return отчёт
})

function медиана(значения) {
  const по_порядку = [...значения].sort((a, b) => a - b)
  // Нижний из двух средних при чётном числе: в свою пользу не округляем.
  return по_порядку[Math.floor((по_порядку.length - 1) / 2)]
}

console.log('')
console.log(`адрес:    ${отчёты[0].finalDisplayedUrl ?? отчёты[0].finalUrl}`)
console.log(`прогонов: ${отчёты.length}`)
console.log('')

let провалено = 0

for (const [ключ, имя] of РАЗДЕЛЫ) {
  const баллы = отчёты.map((о) => {
    const раздел = о.categories[ключ]
    if (раздел === undefined || раздел.score === null) {
      console.error(`в отчёте нет раздела ${ключ}`)
      process.exit(1)
    }
    return Math.round(раздел.score * 100)
  })

  const итог = медиана(баллы)
  const годен = итог >= threshold
  if (!годен) {
    провалено += 1
  }

  const разброс = `${Math.min(...баллы)}..${Math.max(...баллы)}`
  console.log(
    `  ${имя.padEnd(22)} ${String(итог).padStart(3)}  ` +
      `(разброс ${разброс.padEnd(8)}) ${годен ? '' : 'НИЖЕ ПОРОГА'}`,
  )
}

console.log('')
console.log('  метрики производительности (медиана):')
for (const [ключ, имя] of МЕТРИКИ) {
  const значения = отчёты
    .map((о) => о.audits[ключ])
    .filter((audit) => audit !== undefined && audit.numericValue !== undefined)
    .map((audit) => audit.numericValue)
  if (значения.length === 0) {
    continue
  }
  const единица = отчёты[0].audits[ключ].numericUnit === 'millisecond' ? ' мс' : ''
  const итог = медиана(значения)
  const показ = единица === '' ? итог.toFixed(3) : Math.round(итог)
  console.log(`    ${имя.padEnd(20)} ${String(показ).padStart(6)}${единица}`)
}

console.log('')
if (провалено === 0) {
  console.log(`все разделы не ниже ${threshold}`)
  process.exit(0)
}
console.log(`РАЗДЕЛОВ НИЖЕ ПОРОГА: ${провалено}`)
process.exit(1)
