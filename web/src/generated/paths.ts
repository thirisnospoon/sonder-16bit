/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/openapi/social-v1.yaml
 * Генератор: tools/gen-api-types/gen_api_types.py
 * Перегенерация: ./sonder codegen
 */

/**
 * Образцы адресов, объявленные контрактом.
 *
 * Отсюда их берёт клиент; маршруты страниц объявляются отдельно —
 * они принадлежат вебу, а не контракту.
 */
export const API_PATHS = {
  login: '/auth/login',
  logout: '/auth/logout',
  me: '/auth/me',
  register: '/users',
  getUser: '/users/{nick}',
  follow: '/users/{nick}/follow',
  unfollow: '/users/{nick}/follow',
  getFeed: '/feed',
  createPost: '/posts',
  getPost: '/posts/{postId}',
  deletePost: '/posts/{postId}',
  banUser: '/admin/users/{nick}/ban',
  subscribe: '/events',
} as const

/**
 * Метод каждой операции.
 *
 * ЗНАЧЕНИЕМ, а не только типом: клиенту метод нужен в рантайме, и
 * набранная рядом таблица была бы вторым экземпляром контракта —
 * тем самым, против которого весь этот генератор и написан.
 */
export const API_METHODS = {
  login: 'POST',
  logout: 'POST',
  me: 'GET',
  register: 'POST',
  getUser: 'GET',
  follow: 'PUT',
  unfollow: 'DELETE',
  getFeed: 'GET',
  createPost: 'POST',
  getPost: 'GET',
  deletePost: 'DELETE',
  banUser: 'POST',
  subscribe: 'GET',
} as const
