/*
 * Отдельный пост.
 *
 * Нужен ради ссылки: на пост ссылаются, и адрес должен вести к чему-то,
 * а не к ленте, где его ещё надо искать.
 *
 * Удаление — команда: право на неё определяет ядро, а не эта страница.
 * Кнопка показывается автору по внешнему признаку, но отказ на ней
 * возможен и обрабатывается как всякий другой; прятать кнопку — это
 * удобство, а не защита.
 */
import { h, when, type Child } from '../../core/dom.js'
import { signal } from '../../core/reactive.js'
import type { Client } from '../../core/api.js'
import type { Post } from '../../generated/api.js'
import type { Router } from '../../core/router.js'
import type { Session } from '../session.js'
import type { PageName } from '../routes.js'
import { когда, отказ, пусто } from '../parts.js'

export function страницаПоста(
  client: Client,
  session: Session,
  router: Router<PageName>,
  postId: string,
): Child {
  const пост = signal<Post | null>(null)
  const идёт = signal(true)
  const ошибка = signal<unknown>(null)
  const удаляем = signal(false)

  async function загрузить(): Promise<void> {
    идёт.value = true
    ошибка.value = null
    try {
      пост.value = await client.call('getPost', null, { params: { postId } })
    } catch (error) {
      ошибка.value = error
      пост.value = null
    } finally {
      идёт.value = false
    }
  }

  void загрузить()

  async function удалить(): Promise<void> {
    if (удаляем.value) {
      return
    }
    удаляем.value = true
    ошибка.value = null
    try {
      await client.call('deletePost', null, { params: { postId } })
      router.navigate(router.href('лента'))
    } catch (error) {
      ошибка.value = error
    } finally {
      удаляем.value = false
    }
  }

  function свой(): boolean {
    const кто = session.who.value
    const данные = пост.value
    return (
      кто.state === 'свой' &&
      данные !== null &&
      данные.author.userId === кто.me.userId
    )
  }

  return h(
    'main',
    { class: 'полоса', id: 'содержимое' },
    () => (ошибка.value === null ? null : отказ(ошибка.value)),
    () => {
      const данные = пост.value
      if (данные === null) {
        return идёт.value
          ? пусто('Загружаем…')
          : ошибка.value === null
            ? пусто('Такого поста нет.', 'Возможно, его удалили.')
            : null
      }
      return h(
        'article',
        { class: 'карточка' },
        h(
          'div',
          { class: 'пост__шапка' },
          h(
            'a',
            {
              class: 'пост__автор',
              href: router.href('профиль', { nick: данные.author.nick }),
            },
            данные.author.displayName,
          ),
          h('span', { class: 'подпись' }, '@' + данные.author.nick),
          h('span', { class: 'подпись' }, '·'),
          когда(данные.createdAt),
        ),
        h('p', { class: 'пост__тело' }, данные.body),
        when(
          () => свой(),
          () =>
            h(
              'button',
              {
                class: 'кнопка кнопка--опасная',
                style: { 'margin-top': '16px' },
                disabled: () => удаляем.value,
                onClick: () => void удалить(),
              },
              () => (удаляем.value ? 'Удаляем…' : 'Удалить'),
            ),
        ),
      )
    },
  )
}
