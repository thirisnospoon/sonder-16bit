/*
 * Поток обновлений, привязанный к сессии.
 *
 * ОТКРЫВАТЬ ЕГО ЗАРАНЕЕ НЕЛЬЗЯ, и это не оптимизация. `/events`
 * персонален и гостю отвечает 401, а `EventSource` по спецификации при
 * ответе не 200 ЗАКРЫВАЕТ соединение НАВСЕГДА и не переподключается —
 * в отличие от обрыва, после которого он возвращается сам.
 *
 * Страница открывала поток при загрузке, до того как выяснится, кто
 * пришёл. Гость получал 401, поток умирал молча, и дальше вход уже
 * ничего не менял: обновления не приходили НИКОМУ и НИКОГДА. Заметить
 * это по экрану нельзя — лента и так читается запросом, а признак
 * «обновления не приходят» выглядит как временная неполадка сети.
 *
 * Поэтому поток открывается ровно тогда, когда сессия стала своей, и
 * закрывается, когда перестала.
 */
import type { Client } from '../core/api.js'
import { effect, signal } from '../core/reactive.js'
import {
  openStream,
  type Arrived,
  type Stream,
  type StreamOptions,
} from '../core/stream.js'
import type { Session } from './session.js'

export interface SessionStream extends Stream {
  /** Закрыть насовсем: страница уходит. */
  close(): void
}

export function потокСессии(
  client: Client,
  session: Session,
  options: StreamOptions = {},
): SessionStream {
  // Сигналы снаружи ОДНИ И ТЕ ЖЕ на всё время жизни страницы: иначе
  // подписавшийся на них однажды перестал бы получать обновления при
  // первом же переоткрытии потока.
  const last = signal<Arrived | null>(null)
  const open = signal(false)
  const received = signal(0)

  let живой: Stream | null = null

  effect(() => {
    const нужен = session.who.value.state === 'свой'

    if (!нужен) {
      if (живой !== null) {
        живой.close()
        живой = null
      }
      open.value = false
      return
    }

    if (живой === null) {
      живой = openStream(client.url('subscribe'), options)
    }

    // Подкачка заводится КАЖДЫЙ прогон, а не только при открытии.
    // Эффекты подкачки — дети этого прогона, и повторный прогон их
    // снимает; уцелевшая проверка «поток уже есть» оставила бы сигналы
    // без источника, и обновления снова перестали бы приходить.
    const поток = живой
    effect(() => {
      const пришло = поток.last.value
      if (пришло !== null) {
        last.value = пришло
      }
    })
    effect(() => {
      open.value = поток.open.value
    })
    effect(() => {
      received.value = поток.received.value
    })
  })

  return {
    last,
    open,
    received,
    close(): void {
      if (живой !== null) {
        живой.close()
        живой = null
      }
      open.value = false
    },
  }
}
