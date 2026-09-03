/*
 * Поток обновлений: Server-Sent Events.
 *
 * Сервер шлёт ИМЕНОВАННЫЕ события — имя равно типу события домена, — и
 * слушатель ставится на каждое имя отдельно. Событие, на которое никто
 * не подписан, приходит и пропадает молча: ни ошибки, ни следа. Поэтому
 * список имён берётся из сгенерированного каталога, а не набирается
 * здесь: новое событие иначе уехало бы в браузер и не было услышано
 * никем.
 *
 * ПЕРЕПОДКЛЮЧЕНИЕ ОСТАВЛЕНО БРАУЗЕРУ. EventSource переподключается сам и
 * сам присылает Last-Event-ID, то есть делает ровно то, что пришлось бы
 * писать заново, — и делает это, пока вкладка жива, с учётом сна машины
 * и смены сети. Своя надстройка сверху означала бы два механизма
 * переподключения, спорящих друг с другом.
 */
import { signal, type ReadSignal } from './reactive.js'
import { EVENT_TYPES, type EventPayloads, type EventType } from '../generated/events.js'

/** Пришедшее событие: тип и разобранная полезная нагрузка. */
export interface Arrived<K extends EventType = EventType> {
  readonly type: K
  readonly id: string
  readonly payload: EventPayloads[K]
}

export interface Stream {
  /** Последнее пришедшее событие. Пусто, пока ничего не приходило. */
  readonly last: ReadSignal<Arrived | null>
  /** Открыт ли поток прямо сейчас. */
  readonly open: ReadSignal<boolean>
  /** Сколько событий пришло. Для диагностики и проверок. */
  readonly received: ReadSignal<number>
  close(): void
}

/** То немногое от EventSource, чем пользуется поток. */
export interface EventSourceLike {
  addEventListener(type: string, listener: (event: MessageEvent) => void): void
  close(): void
  onopen: ((event: Event) => void) | null
  onerror: ((event: Event) => void) | null
}

export interface StreamOptions {
  /**
   * Чем открыть поток. По умолчанию — EventSource браузера.
   *
   * Подменяется в проверках: настоящий EventSource требует сервера, а
   * проверять надо разбор и подписку, а не браузер.
   */
  readonly open?: (url: string) => EventSourceLike
  /** Куда сообщать о событии, которое не разобралось. */
  readonly onBadPayload?: (type: string, raw: string, reason: unknown) => void
}

function defaultOpen(url: string): EventSourceLike {
  // Кука сессии нужна и здесь: поток персональный.
  return new EventSource(url, { withCredentials: true }) as EventSourceLike
}

/**
 * Открыть поток обновлений.
 *
 * @param url адрес, собранный клиентом из контракта
 */
export function openStream(url: string, options: StreamOptions = {}): Stream {
  const last = signal<Arrived | null>(null)
  const open = signal(false)
  const received = signal(0)

  const source = (options.open ?? defaultOpen)(url)

  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (event: MessageEvent) => {
      let payload: unknown
      try {
        payload = JSON.parse(String(event.data))
      } catch (reason) {
        // Неразобранная нагрузка — это расхождение сторон, и молчать о
        // нём нельзя: событие пропало бы, а лента разошлась бы с базой
        // без единого следа.
        options.onBadPayload?.(type, String(event.data), reason)
        return
      }
      received.update((previous) => previous + 1)
      last.value = {
        type,
        id: event.lastEventId,
        payload: payload as EventPayloads[EventType],
      }
    })
  }

  source.onopen = (): void => {
    open.value = true
  }
  source.onerror = (): void => {
    // Не отказ: EventSource переподключается сам, и «ошибка» здесь
    // означает лишь разрыв. Показывать её пользователем как поломку
    // значило бы пугать его каждым переездом в метро.
    open.value = false
  }

  return {
    last,
    open,
    received,
    close(): void {
      source.close()
      open.value = false
    },
  }
}
