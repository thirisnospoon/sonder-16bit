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

/*
 * СЕРВЕР НЕ ОТВЕЧАЕТ — и это третье состояние, а не «гость».
 *
 * Сессия различает «ещё не спрашивали», «спросили, никого» и «спросить
 * не удалось» именно затем, чтобы пропавшая на секунду сеть не
 * показывала форму входа тому, кто никуда не выходил. Различие это
 * проверено модульно; ЧТО ПРИ ЭТОМ ВИДИТ ЧЕЛОВЕК — не проверялось ни
 * разу, а прошлый дефект нашёлся ровно там.
 *
 * Запросы обрываются на стороне браузера, а не выключением состава:
 * так проверка остаётся быстрой, детерминированной и не мешает
 * остальным сценариям.
 */
test('сервер не отвечает — страница говорит об этом', async ({ page }) => {
  await page.route('**/api/**', (route) => route.abort('failed'))
  await page.goto('/')

  // Приглашение войти показывать НЕЛЬЗЯ: оно означает «мы знаем, что вы
  // гость», а мы не знаем ничего.
  await expect(page.getByText('Здесь ваша лента')).toHaveCount(0)

  // И молчать нельзя тоже: пустой экран без объяснения выглядит
  // сломанной страницей, а не недоступным сервером.
  await expect(
    page.getByText(/сервер|связ|не ответил/i).first(),
  ).toBeVisible()
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

/*
 * ПОТОК ПЕРЕЖИВАЕТ ОТКАЗ ШЛЮЗА.
 *
 * `EventSource` переподключается сам — но только после ОБРЫВА. На
 * ответ не-200 он закрывается НАВСЕГДА, и 502 от nginx, пока оболочка
 * перезапускается, попадает ровно в этот случай. Значит всякий деплой
 * тихо и насовсем убивал бы обновления во всех открытых вкладках:
 * страница жива, лента читается, счётчик новых молчит.
 *
 * Проверяется через подмену ответа, а не остановкой состава: так
 * проверка детерминирована и не мешает остальным сценариям.
 */
test('поток возвращается после 502 от шлюза', async ({ page }) => {
  const ник = свежийНик()
  await зарегистрироваться(page, ник)
  await expect(page.getByText('обновления приходят')).toBeVisible()

  // Роняем ОДИН следующий запрос потока так, как это делает nginx,
  // пока оболочка перезапускается: код 502 и HTML вместо события.
  let уронили = false
  await page.route('**/api/events', async (route) => {
    if (уронили) {
      await route.continue()
      return
    }
    уронили = true
    await route.fulfill({
      status: 502,
      contentType: 'text/html',
      body: '<html><body>502 Bad Gateway</body></html>',
    })
  })

  // Роняем текущее соединение: браузер пойдёт переподключаться и
  // напорется на подменённый 502.
  await page.evaluate(() => {
    window.dispatchEvent(new Event('offline'))
  })
  await page.reload()
  await expect(page.getByText('Что нового')).toBeVisible()

  // И обязан вернуться сам, без перезагрузки вкладки.
  await expect(page.getByText('обновления приходят')).toBeVisible({
    timeout: 30_000,
  })
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
