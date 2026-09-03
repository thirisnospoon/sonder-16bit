/*
 * Клиент REST, построенный по контракту.
 *
 * Метод, путь, тело запроса и тело ответа берутся из сгенерированного
 * объявления операций, а не из строк в коде: путь, набранный руками, —
 * второй экземпляр контракта, и расходится он молча. Запрос уходит по
 * адресу, которого нет, и узнают об этом по 404 у пользователя.
 *
 * ОТКАЗ СЕРВЕРА И ОТСУТСТВИЕ ОТВЕТА — РАЗНЫЕ ВЕЩИ. Первое приходит с
 * кодом из contracts/errors/errors.yaml и означает решение; второе
 * означает, что решения нет вовсе. Свести их к одному исключению значило
 * бы выдумать код отказа от имени системы — ровно то, чего оболочка не
 * делает за ядро (ADR-0011).
 */
import type { ApiError, Operations, OperationName } from '../generated/api.js'
import { API_METHODS, API_PATHS } from '../generated/paths.js'

/** Сервер ответил отказом: у отказа есть код из контракта. */
export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string | undefined,
    readonly traceId: string | undefined,
  ) {
    super(`${code} (${status})${detail === undefined ? '' : ': ' + detail}`)
    this.name = 'ApiFailure'
  }
}

/** Ответа не было вовсе: сеть, обрыв, отменённый запрос. */
export class ApiUnreachable extends Error {
  // Поле названо reason, а не cause: cause у Error уже есть, значит
  // своё, и подменять его другим смыслом — верный способ однажды
  // прочитать не то поле.
  constructor(
    readonly operation: string,
    readonly reason: unknown,
  ) {
    super(`${operation}: сервер не ответил`)
    this.name = 'ApiUnreachable'
  }
}

export interface CallOptions {
  /** Переменные пути: `{postId}` и прочие. */
  readonly params?: Readonly<Record<string, string | number>>
  /** Строка запроса. Пустые значения не отправляются. */
  readonly query?: Readonly<Record<string, string | number | undefined>>
  readonly signal?: AbortSignal
}

type Body<N extends OperationName> = Operations[N]['body']
type Reply<N extends OperationName> = Operations[N]['reply']

export interface Client {
  call<N extends OperationName>(
    name: N,
    body: Body<N>,
    options?: CallOptions,
  ): Promise<Reply<N>>
  /** Полный адрес операции — например, чтобы открыть поток событий. */
  url(name: OperationName, options?: CallOptions): string
}

function fillPath(
  pattern: string,
  params: Readonly<Record<string, string | number>>,
): string {
  return pattern.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = params[name]
    if (value === undefined) {
      // Подстановка «undefined» дала бы адрес, который выглядит
      // настоящим и ведёт в никуда.
      throw new Error(`для адреса не задана переменная ${name}`)
    }
    return encodeURIComponent(String(value))
  })
}

function buildQuery(
  query: Readonly<Record<string, string | number | undefined>> | undefined,
): string {
  if (query === undefined) {
    return ''
  }
  const search = new URLSearchParams()
  for (const [name, value] of Object.entries(query)) {
    if (value === undefined || value === '') {
      // Пустой параметр и отсутствующий — разные вещи для сервера, и
      // отправлять `?cursor=` вместо ничего значит просить первую
      // страницу пустым курсором.
      continue
    }
    search.set(name, String(value))
  }
  const text = search.toString()
  return text === '' ? '' : '?' + text
}

async function readFailure(
  response: Response,
): Promise<{ code: string; detail?: string; traceId?: string }> {
  try {
    const parsed = (await response.json()) as ApiError
    if (typeof parsed.code === 'string' && parsed.code !== '') {
      const out: { code: string; detail?: string; traceId?: string } = {
        code: parsed.code,
      }
      if (typeof parsed.detail === 'string') {
        out.detail = parsed.detail
      }
      if (typeof parsed.traceId === 'string') {
        out.traceId = parsed.traceId
      }
      return out
    }
  } catch {
    // Тело не разобралось — ниже подставится признак, честно говорящий
    // об этом. Выдуманный доменный код соврал бы о причине.
  }
  return { code: 'HTTP_' + String(response.status) }
}

/**
 * Собрать клиента.
 *
 * @param base общий префикс, объявленный контрактом в `servers`
 */
export function createClient(base = '/api', fetcher = globalThis.fetch): Client {
  function url(name: OperationName, options: CallOptions = {}): string {
    const pattern = API_PATHS[name]
    return base + fillPath(pattern, options.params ?? {}) + buildQuery(options.query)
  }

  async function call<N extends OperationName>(
    name: N,
    body: Body<N>,
    options: CallOptions = {},
  ): Promise<Reply<N>> {
    const method = API_METHODS[name]
    const address = url(name, options)

    const init: RequestInit = {
      method,
      // Сессия живёт в куке, и без этого она не поедет: клиент и
      // сервер могут стоять на разных портах в разработке.
      credentials: 'same-origin',
      headers:
        body === null
          ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
    }
    if (body !== null) {
      init.body = JSON.stringify(body)
    }
    if (options.signal !== undefined) {
      init.signal = options.signal
    }

    let response: Response
    try {
      response = await fetcher(address, init)
    } catch (reason) {
      throw new ApiUnreachable(name, reason)
    }

    if (!response.ok) {
      const failure = await readFailure(response)
      throw new ApiFailure(
        response.status,
        failure.code,
        failure.detail,
        failure.traceId,
      )
    }

    if (response.status === 204) {
      return null as Reply<N>
    }
    return (await response.json()) as Reply<N>
  }

  return { call, url }
}
