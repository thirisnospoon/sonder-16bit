/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/openapi/social-v1.yaml
 * Генератор: tools/gen-api-types/gen_api_types.py
 * Перегенерация: ./sonder codegen
 */

export type Id = string

/** Формат проверяет ядро, а не оболочка. */
export type Nick = string

export type ApiError = {
  /**
   * Код из contracts/errors/errors.yaml. Список кодов и их категории
   * генерируются в web/src/generated/errors.ts.
   */
  readonly code: string
  readonly detail?: string
  readonly traceId: Id
}

export type LoginRequest = {
  readonly nick: Nick
  readonly password: string
}

export type RegisterRequest = {
  readonly nick: Nick
  readonly displayName: string
  readonly password: string
}

export type Role = 'USER' | 'MODERATOR' | 'ADMIN'

export type UserRef = {
  readonly userId: Id
  readonly nick: Nick
  readonly displayName: string
}

export type Me = UserRef & {
  readonly role: Role
}

export type UserProfile = UserRef & {
  readonly postCount: number
  readonly followerCount: number
  readonly followingCount: number
  /** Подписан ли текущий пользователь на этого. */
  readonly following: boolean
}

export type Post = {
  readonly postId: Id
  readonly author: UserRef
  readonly body: string
  readonly createdAt: string
}

export type CreatePostRequest = {
  /**
   * Границы дублируются здесь только для подсказки в интерфейсе.
   * Решение всё равно принимает ядро: проверка на клиенте — удобство,
   * а не правило.
   */
  readonly body: string
}

export type BanRequest = {
  readonly reason: string
}

export type FeedPage = {
  readonly items: readonly Post[]
  readonly nextCursor?: string
  readonly hasMore: boolean
}

/**
 * Операции контракта: метод, путь, тело запроса и тело ответа.
 *
 * Клиент строится ПО ЭТОМУ объявлению, а не по строкам в коде.
 * Путь, набранный руками, — второй экземпляр контракта, и
 * расходится он молча: запрос уходит по адресу, которого нет.
 */
export interface Operations {
  /** POST /auth/login */
  login: {
    readonly method: 'POST'
    readonly path: '/auth/login'
    readonly body: LoginRequest
    readonly reply: null
  }
  /** POST /auth/logout */
  logout: {
    readonly method: 'POST'
    readonly path: '/auth/logout'
    readonly body: null
    readonly reply: null
  }
  /** GET /auth/me */
  me: {
    readonly method: 'GET'
    readonly path: '/auth/me'
    readonly body: null
    readonly reply: Me
  }
  /** POST /users */
  register: {
    readonly method: 'POST'
    readonly path: '/users'
    readonly body: RegisterRequest
    readonly reply: UserRef
  }
  /** GET /users/{nick} */
  getUser: {
    readonly method: 'GET'
    readonly path: '/users/{nick}'
    readonly body: null
    readonly reply: UserProfile
  }
  /** PUT /users/{nick}/follow */
  follow: {
    readonly method: 'PUT'
    readonly path: '/users/{nick}/follow'
    readonly body: null
    readonly reply: null
  }
  /** DELETE /users/{nick}/follow */
  unfollow: {
    readonly method: 'DELETE'
    readonly path: '/users/{nick}/follow'
    readonly body: null
    readonly reply: null
  }
  /** GET /feed */
  getFeed: {
    readonly method: 'GET'
    readonly path: '/feed'
    readonly body: null
    readonly reply: FeedPage
  }
  /** POST /posts */
  createPost: {
    readonly method: 'POST'
    readonly path: '/posts'
    readonly body: CreatePostRequest
    readonly reply: Post
  }
  /** GET /posts/{postId} */
  getPost: {
    readonly method: 'GET'
    readonly path: '/posts/{postId}'
    readonly body: null
    readonly reply: Post
  }
  /** DELETE /posts/{postId} */
  deletePost: {
    readonly method: 'DELETE'
    readonly path: '/posts/{postId}'
    readonly body: null
    readonly reply: null
  }
  /** POST /admin/users/{nick}/ban */
  banUser: {
    readonly method: 'POST'
    readonly path: '/admin/users/{nick}/ban'
    readonly body: BanRequest
    readonly reply: null
  }
  /** GET /events */
  subscribe: {
    readonly method: 'GET'
    readonly path: '/events'
    readonly body: null
    readonly reply: null
  }
}

export type OperationName = keyof Operations
