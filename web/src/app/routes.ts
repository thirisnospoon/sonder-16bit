/*
 * Маршруты страниц.
 *
 * Отдельно от адресов API: те объявлены контрактом и порождаются
 * генератором, а эти принадлежат вебу и больше никому. Сложить их в один
 * список значило бы связать две вещи, которые меняются по разным
 * причинам.
 */
import type { RouteDef } from '../core/router.js'

export type PageName =
  | 'лента'
  | 'вход'
  | 'регистрация'
  | 'пост'
  | 'профиль'
  | 'модерация'
  | 'нет'

export const PAGES: readonly RouteDef<PageName>[] = [
  { name: 'лента', pattern: '/' },
  { name: 'вход', pattern: '/вход' },
  { name: 'регистрация', pattern: '/регистрация' },
  { name: 'пост', pattern: '/posts/:postId' },
  { name: 'профиль', pattern: '/users/:nick' },
  { name: 'модерация', pattern: '/модерация' },
  { name: 'нет', pattern: '/нет-такой-страницы' },
]
