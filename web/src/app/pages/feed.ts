/*
 * Лента.
 *
 * Читается проекцией и потому не проходит через ядро; обновления
 * приходят потоком из того же конвейера outbox, что питает проекции —
 * значит, событие не может опередить чтение.
 *
 * СОБЫТИЕ НЕ НЕСЁТ ПОСТА, оно несёт его идентичность. Дорисовать
 * карточку по одному лишь идентификатору нельзя, и подставлять
 * придуманное — тем более: пришедшее событие поднимает ПРИЗНАК новизны,
 * а тело берётся тем же чтением, что и всё остальное.
 */
import { each, h, when, type Child } from '../../core/dom.js'
import { effect, onCleanup, signal } from '../../core/reactive.js'
import { LIMITS } from '../../generated/limits.js'
import type { Client } from '../../core/api.js'
import type { FeedPage, Post } from '../../generated/api.js'
import type { Router } from '../../core/router.js'
import type { Session } from '../session.js'
import type { Stream } from '../../core/stream.js'
import type { PageName } from '../routes.js'
import { когда, метка, отказ, пусто } from '../parts.js'

export function страницаЛенты(
  client: Client,
  session: Session,
  router: Router<PageName>,
  stream: Stream,
): Child {
  const посты = signal<readonly Post[]>([])
  const дальше = signal<string | undefined>(undefined)
  const ещё = signal(false)
  const идёт = signal(true)
  const ошибка = signal<unknown>(null)
  const новых = signal(0)

  async function загрузить(курсор?: string): Promise<void> {
    идёт.value = true
    ошибка.value = null
    try {
      const страница: FeedPage = await client.call('getFeed', null, {
        query: { cursor: курсор, limit: 20 },
      })
      посты.update((прежние) =>
        курсор === undefined ? страница.items : [...прежние, ...страница.items],
      )
      дальше.value = страница.nextCursor
      ещё.value = страница.hasMore
      if (курсор === undefined) {
        новых.value = 0
      }
    } catch (error) {
      ошибка.value = error
    } finally {
      идёт.value = false
    }
  }

  void загрузить()

  /**
   * Свои посты, о которых ещё не пришло событие.
   *
   * Различать своё и чужое приходится затем, что ведут они себя
   * по-разному: своё обновляет ленту сразу, чужое — только поднимает
   * счётчик. Нажавший «отправить» вправе увидеть результат без второго
   * нажатия; читающий ленту — не вправе получить прыжок содержимого
   * под курсором.
   *
   * Значение — запасной таймер. ЖДАТЬ СОБЫТИЕ БЕЗ СРОКА НЕЛЬЗЯ: поток
   * мог ещё не подняться к моменту отправки, мог оборваться, мог
   * упереться в перезапуск оболочки. Ни один из этих случаев не
   * оправдывает пропавший на экране пост, который на сервере есть.
   */
  const ждём = new Map<string, ReturnType<typeof setTimeout>>()

  /**
   * Сколько ждать своего события, прежде чем перечитать самим.
   *
   * Дренаж outbox ходит раз в секунду, и событию нужно пройти его,
   * рассылку и сеть. Три секунды — заметный запас сверх этого, и всё
   * ещё меньше, чем человек станет терпеть, глядя на пустое место.
   */
  const ЖДАТЬ_СОБЫТИЕ_МС = 3000

  function перестатьЖдать(id: string): void {
    const таймер = ждём.get(id)
    if (таймер !== undefined) {
      clearTimeout(таймер)
      ждём.delete(id)
    }
  }

  // Уход со страницы не должен оставлять за собой таймеры: они держат
  // замыкание, а через него — всю страницу целиком.
  onCleanup(() => {
    for (const таймер of ждём.values()) {
      clearTimeout(таймер)
    }
    ждём.clear()
  })

  // Приход события НЕ дорисовывает ленту сам: подставленный пост
  // разошёлся бы с тем, что отдаёт проекция, и разошёлся бы молча. Он
  // означает лишь, что проекция УЖЕ обновлена — поток питается из того
  // же конвейера outbox, и контракт обещает, что клиент не увидит
  // события раньше, чем оно попадёт в чтение. Значит, читать можно.
  effect(() => {
    const пришло = stream.last.value
    if (пришло === null || пришло.type !== 'post.created') {
      return
    }
    const payload = пришло.payload as { readonly postId?: string }
    const id = payload.postId
    if (id !== undefined && ждём.has(id)) {
      перестатьЖдать(id)
      void загрузить()
      return
    }
    новых.update((прежде) => прежде + 1)
  })

  const черновик = signal('')
  const пишем = signal(false)

  async function написать(event: Event): Promise<void> {
    event.preventDefault()
    if (пишем.value || черновик.value.trim() === '') {
      return
    }
    пишем.value = true
    ошибка.value = null
    try {
      const создан = await client.call('createPost', { body: черновик.value })
      черновик.value = ''
      // Ленту НЕ перечитываем прямо сейчас: проекция ещё не догнала —
      // событие идёт через outbox, а дренаж ходит раз в секунду. Запрос
      // вернул бы прежнюю ленту, и автор не увидел бы своего поста, пока
      // не нажмёт «показать новое». Ждём события о СВОЁМ посте: его
      // приход и означает, что читать уже есть что.
      //
      // Ждём со сроком. Событие может и не прийти — поток мог не
      // подняться к этой секунде, оборваться, упереться в перезапуск
      // оболочки, — а пост на сервере при этом есть. Ожидание без срока
      // превратило бы отказ доставки в пропавшую запись.
      const id = создан.postId
      ждём.set(
        id,
        setTimeout(() => {
          ждём.delete(id)
          void загрузить()
        }, ЖДАТЬ_СОБЫТИЕ_МС),
      )
    } catch (error) {
      ошибка.value = error
    } finally {
      пишем.value = false
    }
  }

  return h(
    'main',
    { class: 'полоса', id: 'содержимое' },

    h(
      'header',
      { style: { display: 'flex', 'align-items': 'baseline', gap: '12px' } },
      h('h1', { class: 'заголовок' }, 'Лента'),
      метка(stream.open),
    ),

    () => (ошибка.value === null ? null : отказ(ошибка.value)),

    when(
      () => session.who.value.state === 'свой',
      () =>
        h(
          'form',
          { class: 'карточка', onSubmit: написать, style: { 'margin-bottom': '16px' } },
          h(
            'div',
            { class: 'поле' },
            h(
              'label',
              { class: 'поле__подпись', for: 'пост' },
              'Что нового',
            ),
            h('textarea', {
              class: 'поле__текст',
              id: 'пост',
              maxlength: LIMITS.postBodyMaxLen,
              placeholder: 'Не больше тысячи знаков',
              value: () => черновик.value,
              onInput: (event: Event) => {
                черновик.value = (event.target as HTMLTextAreaElement).value
              },
            }),
          ),
          h(
            'div',
            { style: { display: 'flex', 'align-items': 'center', gap: '12px' } },
            h(
              'button',
              {
                class: 'кнопка',
                type: 'submit',
                disabled: () => пишем.value || черновик.value.trim() === '',
              },
              () => (пишем.value ? 'Отправляем…' : 'Отправить'),
            ),
            h(
              'span',
              { class: 'подпись' },
              () =>
                `${черновик.value.length} из ${LIMITS.postBodyMaxLen}`,
            ),
          ),
        ),
    ),

    when(
      () => новых.value > 0,
      () =>
        h(
          'button',
          {
            class: 'кнопка кнопка--тихая',
            style: { width: '100%', 'margin-bottom': '12px' },
            onClick: () => void загрузить(),
          },
          () => `Показать новое (${новых.value})`,
        ),
    ),

    h(
      'ul',
      { class: 'лента' },
      each(
        () => посты.value,
        (пост) => пост.postId,
        (пост) => карточкаПоста(пост, router),
      ),
    ),

    () =>
      посты.value.length === 0 && !идёт.value && ошибка.value === null
        ? пусто(
            'Здесь пока пусто.',
            'Подпишитесь на кого-нибудь — и лента наполнится.',
          )
        : null,

    when(
      () => ещё.value,
      () =>
        h(
          'button',
          {
            class: 'кнопка кнопка--тихая',
            style: { width: '100%', 'margin-top': '12px' },
            disabled: () => идёт.value,
            onClick: () => void загрузить(дальше.value),
          },
          () => (идёт.value ? 'Загружаем…' : 'Показать ещё'),
        ),
    ),
  )
}

function карточкаПоста(пост: () => Post, router: Router<PageName>): Node {
  return h(
    'li',
    { class: 'карточка' },
    h(
      'div',
      { class: 'пост__шапка' },
      h(
        'a',
        {
          class: 'пост__автор',
          href: () => router.href('профиль', { nick: пост().author.nick }),
        },
        () => пост().author.displayName,
      ),
      h('span', { class: 'подпись' }, () => '@' + пост().author.nick),
      h('span', { class: 'подпись' }, '·'),
      () => когда(пост().createdAt),
    ),
    h('p', { class: 'пост__тело' }, () => пост().body),
  )
}
