/*
 * Модерация: блокировка учётной записи.
 *
 * Право модерировать определяет ЯДРО, а не эта страница. Здесь только
 * форма; спрятать её от обычного пользователя — удобство, и ровно
 * поэтому отказ обрабатывается как всякий другой: обойти интерфейс
 * умеет любой, а решение всё равно выносит ядро (ADR-0011).
 *
 * Причина обязательна и уходит в событие: разбор спорных случаев
 * начинается с вопроса «за что», и ответ на него должен быть записан
 * тогда же, когда принято решение, а не восстанавливаться по памяти.
 */
import { h, type Child } from '../../core/dom.js'
import { signal } from '../../core/reactive.js'
import { LIMITS } from '../../generated/limits.js'
import type { Client } from '../../core/api.js'
import { отказ } from '../parts.js'

export function страницаМодерации(client: Client): Child {
  const ник = signal('')
  const причина = signal('')
  const идёт = signal(false)
  const ошибка = signal<unknown>(null)
  const готово = signal<string | null>(null)

  async function заблокировать(event: Event): Promise<void> {
    event.preventDefault()
    if (идёт.value) {
      return
    }
    идёт.value = true
    ошибка.value = null
    готово.value = null
    try {
      await client.call(
        'banUser',
        { reason: причина.value },
        { params: { nick: ник.value } },
      )
      готово.value = ник.value
      ник.value = ''
      причина.value = ''
    } catch (error) {
      ошибка.value = error
    } finally {
      идёт.value = false
    }
  }

  return h(
    'main',
    { class: 'полоса', id: 'содержимое' },
    h('h1', { class: 'заголовок' }, 'Модерация'),
    () => (ошибка.value === null ? null : отказ(ошибка.value)),
    () =>
      готово.value === null
        ? null
        : h(
            'div',
            {
              class: 'отказ',
              role: 'status',
              style: {
                'border-color': 'var(--граница)',
                background: 'transparent',
              },
            },
            h('span', null, `Учётная запись @${готово.value} заблокирована.`),
          ),
    h(
      'form',
      { class: 'карточка', onSubmit: заблокировать },
      h(
        'div',
        { class: 'поле' },
        h('label', { class: 'поле__подпись', for: 'кого' }, 'Кого'),
        h('input', {
          class: 'поле__ввод',
          id: 'кого',
          required: true,
          minlength: LIMITS.nickMinLen,
          maxlength: LIMITS.nickMaxLen,
          placeholder: 'ник без собаки',
          value: () => ник.value,
          onInput: (event: Event) => {
            ник.value = (event.target as HTMLInputElement).value
          },
        }),
      ),
      h(
        'div',
        { class: 'поле' },
        h('label', { class: 'поле__подпись', for: 'причина' }, 'За что'),
        h('textarea', {
          class: 'поле__текст',
          id: 'причина',
          required: true,
          maxlength: LIMITS.banReasonMaxLen,
          placeholder: 'Причина попадёт в событие и останется в истории',
          value: () => причина.value,
          onInput: (event: Event) => {
            причина.value = (event.target as HTMLTextAreaElement).value
          },
        }),
      ),
      h(
        'button',
        {
          class: 'кнопка кнопка--опасная',
          type: 'submit',
          disabled: () =>
            идёт.value || ник.value === '' || причина.value.trim() === '',
        },
        () => (идёт.value ? 'Отправляем…' : 'Заблокировать'),
      ),
    ),
  )
}
