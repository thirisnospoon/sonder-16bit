/*
 * Профиль: кто это, сколько у него постов и подписаны ли мы.
 *
 * Подписка и отписка — команды: они проходят через ядро, и решает их
 * ядро. Отсюда правило страницы: НИЧЕГО НЕ ПРЕДПОЛАГАТЬ О РЕЗУЛЬТАТЕ.
 * Нарисовать «вы подписаны» сразу по нажатию — заманчиво и быстро, но
 * ядро вправе отказать (сам на себя, заблокирован, уже подписан), и
 * пользователь останется с картинкой, которой не соответствует ничего.
 */
import { h, when, type Child } from '../../core/dom.js'
import { signal } from '../../core/reactive.js'
import type { Client } from '../../core/api.js'
import type { UserProfile } from '../../generated/api.js'
import type { Session } from '../session.js'
import { отказ, пусто } from '../parts.js'

export function страницаПрофиля(
  client: Client,
  session: Session,
  nick: string,
): Child {
  const профиль = signal<UserProfile | null>(null)
  const идёт = signal(true)
  const ошибка = signal<unknown>(null)
  const меняем = signal(false)

  async function загрузить(): Promise<void> {
    идёт.value = true
    ошибка.value = null
    try {
      профиль.value = await client.call('getUser', null, { params: { nick } })
    } catch (error) {
      ошибка.value = error
      профиль.value = null
    } finally {
      идёт.value = false
    }
  }

  void загрузить()

  async function переключить(): Promise<void> {
    const текущий = профиль.value
    if (текущий === null || меняем.value) {
      return
    }
    меняем.value = true
    ошибка.value = null
    try {
      await client.call(текущий.following ? 'unfollow' : 'follow', null, {
        params: { nick },
      })
      // Перечитываем, а не переключаем флаг у себя: правду о подписке
      // знает проекция, и счётчики меняются вместе с ней.
      await загрузить()
    } catch (error) {
      ошибка.value = error
    } finally {
      меняем.value = false
    }
  }

  function свой(): boolean {
    const кто = session.who.value
    return кто.state === 'свой' && кто.me.nick === nick
  }

  return h(
    'main',
    { class: 'полоса', id: 'содержимое' },
    () => (ошибка.value === null ? null : отказ(ошибка.value)),
    () => {
      const данные = профиль.value
      if (данные === null) {
        return идёт.value
          ? пусто('Загружаем…')
          : ошибка.value === null
            ? пусто('Такого пользователя нет.')
            : null
      }
      return h(
        'article',
        { class: 'карточка' },
        h('h1', { class: 'заголовок' }, данные.displayName),
        h('p', { class: 'подпись' }, '@' + данные.nick),
        h(
          'dl',
          {
            style: {
              display: 'flex',
              gap: '24px',
              margin: '16px 0 0',
            },
          },
          счётчик('Постов', данные.postCount),
          счётчик('Подписчиков', данные.followerCount),
          счётчик('Подписок', данные.followingCount),
        ),
        when(
          () => session.who.value.state === 'свой' && !свой(),
          () =>
            h(
              'button',
              {
                class: данные.following
                  ? 'кнопка кнопка--тихая'
                  : 'кнопка',
                style: { 'margin-top': '16px' },
                disabled: () => меняем.value,
                onClick: () => void переключить(),
              },
              () =>
                меняем.value
                  ? 'Отправляем…'
                  : данные.following
                    ? 'Отписаться'
                    : 'Подписаться',
            ),
        ),
      )
    },
  )
}

function счётчик(подпись: string, значение: number): Child {
  return h(
    'div',
    null,
    h('dt', { class: 'подпись' }, подпись),
    h('dd', { style: { margin: '0', 'font-weight': '600' } }, String(значение)),
  )
}
