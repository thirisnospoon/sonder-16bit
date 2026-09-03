/*
 * Основные сценарии в настоящем браузере.
 *
 * Всё, что здесь происходит, проходит через ВСЮ систему: страница на
 * собственных сигналах, nginx, оболочка на Java, Firebird и
 * шестнадцатибитное ядро под DOSBox. Пост, появившийся в ленте, побывал
 * решением, принятым программой под эмулятором DOS.
 *
 * Проверки написаны от лица пользователя: они ищут на странице то, что
 * он видит, а не классы и идентификаторы. Селектор по вёрстке ломается
 * от перестановки блоков и не ломается от исчезновения смысла — это
 * ровно наоборот тому, что нужно.
 */
import { test, expect, type Page } from '@playwright/test'

/** Новый ник на каждый прогон: база живёт между запусками. */
function свежийНик(): string {
  return 'u' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

const ПАРОЛЬ = 'достаточно-длинный-пароль'

async function зарегистрироваться(page: Page, ник: string): Promise<void> {
  await page.goto('/регистрация')
  await page.getByLabel('Ник').fill(ник)
  await page.getByLabel('Как вас показывать').fill('Пользователь ' + ник)
  await page.getByLabel('Пароль').fill(ПАРОЛЬ)
  await page.getByRole('button', { name: 'Завести' }).click()
  // Успех виден по тому, что появилась форма поста: она есть только у
  // вошедшего. Ждать «редиректа» бессмысленно — адрес меняется мгновенно.
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeVisible()
}

test('гость видит вход, а не ленту с формой поста', async ({ page }) => {
  await page.goto('/')

  // Форма поста есть только у вошедшего.
  await expect(page.getByRole('button', { name: 'Отправить' })).toHaveCount(0)
  // Именно в ШАПКЕ: она видна с любой страницы, и предложение войти
  // должно жить там, а не только на приглашении конкретной страницы.
  await expect(
    page.getByRole('navigation', { name: 'Основная' })
      .getByRole('link', { name: 'Войти' }),
  ).toBeVisible()
})

/*
 * ЧТО ГОСТЬ ВИДИТ НА САМОМ ДЕЛЕ, а не чего он не видит.
 *
 * Проверка выше говорит только об отсутствии формы поста — и была
 * зелёной, пока первый встречный получал на первом экране красную
 * карточку «Нужно войти заново. SESSION_INVALID». Ему сообщали, что
 * истекла сессия, которой у него не было: страница спрашивала личную
 * ленту, не дождавшись ответа, кто пришёл, и честно рисовала отказ.
 *
 * Отсюда правило: у страницы, закрытой для гостя, проверяется ДВОЙНОЕ
 * условие — что приглашение есть и что отказа нет. Одного первого
 * мало: приглашение и отказ прекрасно уживаются на одном экране.
 */
const ЗАКРЫТЫЕ: ReadonlyArray<readonly [string, string, string]> = [
  ['/', 'лента', 'Здесь ваша лента'],
  ['/users/кто-угодно', 'профиль', 'Профили видны вошедшим'],
  [
    '/posts/00000000-0000-0000-0000-000000000000',
    'пост',
    'Пост виден вошедшим',
  ],
]

for (const [адрес, имя, заголовок] of ЗАКРЫТЫЕ) {
  test(`гостю на странице «${имя}» приглашение, а не отказ`, async ({
    page,
  }) => {
    await page.goto(адрес)

    await expect(page.getByText(заголовок)).toBeVisible()
    await expect(
      page.getByRole('link', { name: 'Завести учётную запись' }),
    ).toBeVisible()

    // Ни одного кода отказа на экране. Код показывается рядом с фразой
    // именно затем, чтобы его можно было назвать, — здесь это работает
    // против него самого.
    await expect(page.locator('.отказ')).toHaveCount(0)
    await expect(page.getByText('SESSION_INVALID')).toHaveCount(0)
    await expect(page.getByText('Нужно войти заново')).toHaveCount(0)
  })
}

test('гость не получает от страницы отказов сети', async ({ page }) => {
  // Личное чтение гостю не принадлежит, и просить его страница не
  // должна. Запрос, отвечающий 401 по замыслу, всё равно попадает в
  // консоль браузера как ошибка — и он же означает, что страница
  // пошла за тем, чего ей не отдадут.
  const отказы: string[] = []
  page.on('response', (ответ) => {
    if (ответ.status() === 401) {
      отказы.push(`${ответ.status()} ${new URL(ответ.url()).pathname}`)
    }
  })

  await page.goto('/')
  await expect(page.getByText('Здесь ваша лента')).toBeVisible()

  // `/auth/me` — исключение и единственное: спросить, кто пришёл, можно
  // только спросив, и 401 здесь ОТВЕТ, а не отказ.
  expect(отказы.filter((о) => !о.endsWith('/api/auth/me'))).toEqual([])
})

test('регистрация, пост и лента', async ({ page }) => {
  const ник = свежийНик()
  await зарегистрироваться(page, ник)

  // Шапка знает, кто мы: имя пришло с сервера, а не выведено из формы.
  await expect(page.getByRole('link', { name: 'Пользователь ' + ник })).toBeVisible()

  const текст = 'Решение принято под DOS ' + ник
  await page.getByLabel('Что нового').fill(текст)
  await page.getByRole('button', { name: 'Отправить' }).click()

  // Пост прошёл через ядро, событие через outbox, дренаж через секунду —
  // отсюда ожидание. Мгновенной ленты тут не бывает по устройству.
  await expect(page.getByText(текст)).toBeVisible({ timeout: 20_000 })
})

test('выход возвращает к гостю', async ({ page }) => {
  const ник = свежийНик()
  await зарегистрироваться(page, ник)

  await page.getByRole('button', { name: 'Выйти' }).click()

  await expect(page.getByRole('link', { name: 'Войти' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отправить' })).toHaveCount(0)
})

test('вход по прежним учётным данным восстанавливает сессию', async ({ page }) => {
  const ник = свежийНик()
  await зарегистрироваться(page, ник)
  await page.getByRole('button', { name: 'Выйти' }).click()
  await expect(page.getByRole('link', { name: 'Войти' })).toBeVisible()

  await page.getByRole('link', { name: 'Войти' }).click()
  await page.getByLabel('Ник').fill(ник)
  await page.getByLabel('Пароль').fill(ПАРОЛЬ)
  await page.getByRole('button', { name: 'Войти', exact: true }).click()

  await expect(page.getByRole('button', { name: 'Отправить' })).toBeVisible()
})

test('неверный пароль отвечает объяснением, а не молчанием', async ({ page }) => {
  const ник = свежийНик()
  await зарегистрироваться(page, ник)
  await page.getByRole('button', { name: 'Выйти' }).click()

  await page.goto('/вход')
  await page.getByLabel('Ник').fill(ник)
  await page.getByLabel('Пароль').fill('совсем-не-тот-пароль')
  await page.getByRole('button', { name: 'Войти', exact: true }).click()

  const отказ = page.getByRole('alert')
  await expect(отказ).toBeVisible()
  await expect(отказ).toContainText('не подошли')
  // Код рядом с фразой: обращение в поддержку начинается с него, а не
  // со слов «ну там красным написало».
  await expect(отказ).toContainText('CREDENTIALS_INVALID')
})

test('прямая ссылка открывает свою страницу, а не ленту', async ({ page }) => {
  // Проверяется сразу две вещи: nginx отдаёт index.html на любой адрес,
  // и маршрутизатор на клиенте разбирает его сам. Сломай любую — тут
  // окажется лента или страница «не найдено».
  const ник = свежийНик()
  await зарегистрироваться(page, ник)

  await page.goto('/users/' + ник)

  await expect(
    page.getByRole('heading', { name: 'Пользователь ' + ник }),
  ).toBeVisible()
  await expect(page.getByText('@' + ник)).toBeVisible()
})

test('несуществующий адрес говорит об этом прямо', async ({ page }) => {
  await page.goto('/такой-страницы-нет')
  await expect(
    page.getByRole('heading', { name: 'Такой страницы нет' }),
  ).toBeVisible()
})

test('подписка на себя отвергается ядром, а не прячется кнопкой', async ({
  page,
}) => {
  // Кнопки подписки на своём профиле нет — но это удобство, а не
  // защита. Правило принадлежит ядру, и здесь проверяется, что
  // интерфейс не притворяется, будто решает сам.
  const ник = свежийНик()
  await зарегистрироваться(page, ник)
  await page.goto('/users/' + ник)

  await expect(page.getByRole('button', { name: 'Подписаться' })).toHaveCount(0)
})
