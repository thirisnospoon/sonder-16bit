/*
 * СГЕНЕРИРОВАНО. Не править руками.
 *
 * Источник: contracts/domain/limits.yaml
 * Генератор: tools/gen-limits/gen_limits.py
 * Перегенерация: ./sonder codegen
 */

// Подсказка для интерфейса, а не правило: решение всё равно
// принимает ядро. Проверка на клиенте — удобство пользователя.
export const LIMITS = {
  nickMinLen: 3,
  nickMaxLen: 20,
  displayNameMaxLen: 60,
  postBodyMaxLen: 1000,
  commentBodyMaxLen: 500,
  banReasonMaxLen: 200,
  postsPerHour: 20,
  commentsPerHour: 60,
} as const

export type LimitName = keyof typeof LIMITS
