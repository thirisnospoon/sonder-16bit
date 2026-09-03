/*
 * Вход и регистрация.
 *
 * Одна страница на две формы: они отличаются одним полем и адресом, и
 * разводить их по файлам значило бы дважды написать одно и то же
 * обращение с отказом.
 */
import { h, type Child } from '../../core/dom.js'
import { signal } from '../../core/reactive.js'
import { LIMITS } from '../../generated/limits.js'
import { отказ } from '../parts.js'
import type { Session } from '../session.js'
import type { Router } from '../../core/router.js'
import type { PageName } from '../routes.js'
import type { Client } from '../../core/api.js'

export function страницаВхода(
  session: Session,
  router: Router<PageName>,
  режим: 'вход' | 'регистрация',
  client: Client,
): Child {
  const ник = signal('')
  const имя = signal('')
  const пароль = signal('')
  const идёт = signal(false)
  const ошибка = signal<unknown>(null)

  const регистрация = режим === 'регистрация'

  async function отправить(event: Event): Promise<void> {
    event.preventDefault()
    if (идёт.value) {
      // Двойное нажатие по медленной сети — обычное дело, и вторая
      // отправка регистрации завела бы вторую учётную запись.
      return
    }
    идёт.value = true
    ошибка.value = null
    try {
      if (регистрация) {
        await client.call('register', {
          nick: ник.value,
          displayName: имя.value,
          password: пароль.value,
        })
      }
      await session.login(ник.value, пароль.value)
      router.navigate(router.href('лента'))
    } catch (error) {
      ошибка.value = error
    } finally {
      идёт.value = false
    }
  }

  return h(
    'main',
    { class: 'полоса', id: 'содержимое' },
    h('h1', { class: 'заголовок' }, регистрация ? 'Регистрация' : 'Вход'),
    h(
      'form',
      { class: 'карточка', onSubmit: отправить, novalidate: true },
      () => (ошибка.value === null ? null : отказ(ошибка.value)),

      h(
        'div',
        { class: 'поле' },
        h('label', { class: 'поле__подпись', for: 'ник' }, 'Ник'),
        h('input', {
          class: 'поле__ввод',
          id: 'ник',
          name: 'nick',
          // Подсказка браузеру: без неё менеджер паролей не поймёт, что
          // это за поле, и не предложит сохранить вход.
          autocomplete: 'username',
          required: true,
          minlength: LIMITS.nickMinLen,
          maxlength: LIMITS.nickMaxLen,
          value: () => ник.value,
          onInput: (event: Event) => {
            ник.value = (event.target as HTMLInputElement).value
          },
        }),
      ),

      regВидимо(регистрация, имя),

      h(
        'div',
        { class: 'поле' },
        h('label', { class: 'поле__подпись', for: 'пароль' }, 'Пароль'),
        h('input', {
          class: 'поле__ввод',
          id: 'пароль',
          name: 'password',
          type: 'password',
          autocomplete: регистрация ? 'new-password' : 'current-password',
          required: true,
          minlength: 8,
          value: () => пароль.value,
          onInput: (event: Event) => {
            пароль.value = (event.target as HTMLInputElement).value
          },
        }),
      ),

      h(
        'button',
        { class: 'кнопка', type: 'submit', disabled: () => идёт.value },
        () => (идёт.value ? 'Отправляем…' : регистрация ? 'Завести' : 'Войти'),
      ),
    ),
    h(
      'p',
      { class: 'подпись', style: { 'margin-top': '16px' } },
      регистрация ? 'Уже есть учётная запись? ' : 'Ещё нет учётной записи? ',
      h(
        'a',
        { href: router.href(регистрация ? 'вход' : 'регистрация') },
        регистрация ? 'Войти' : 'Завести',
      ),
    ),
  )
}

/** Поле отображаемого имени есть только у регистрации. */
function regВидимо(
  регистрация: boolean,
  имя: { value: string },
): Child {
  if (!регистрация) {
    return null
  }
  return h(
    'div',
    { class: 'поле' },
    h('label', { class: 'поле__подпись', for: 'имя' }, 'Как вас показывать'),
    h('input', {
      class: 'поле__ввод',
      id: 'имя',
      name: 'displayName',
      autocomplete: 'nickname',
      required: true,
      maxlength: LIMITS.displayNameMaxLen,
      value: () => имя.value,
      onInput: (event: Event) => {
        имя.value = (event.target as HTMLInputElement).value
      },
    }),
  )
}
