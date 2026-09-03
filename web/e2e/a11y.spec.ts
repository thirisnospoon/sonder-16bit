/*
 * Доступность: уровень AA, измеренный инструментом.
 *
 * Заложенного в разметку недостаточно, пока никто не измерил. Ссылка
 * обхода, обводка фокуса и aria-live написаны — но написать можно и
 * неправильно, а проверить это глазами нельзя: контраст считается, а не
 * оценивается, и порядок заголовков виден только целиком.
 *
 * axe-core находит не всё, что бывает недоступным, и это надо понимать:
 * он ловит машинно-проверяемое. Ноль нарушений здесь означает «нет
 * нарушений из тех, что вообще ловятся», а не «страницей удобно
 * пользоваться».
 */
import AxeBuilder from '@axe-core/playwright'
import { test, expect, type Page } from '@playwright/test'

const УРОВНИ = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

async function проверить(page: Page): Promise<void> {
  const результат = await new AxeBuilder({ page }).withTags(УРОВНИ).analyze()

  // Сообщение перечисляет правила и узлы: «нарушений: 3» не говорит
  // ничего тому, кто будет это чинить.
  const описание = результат.violations
    .map(
      (v) =>
        `${v.id} (${v.impact ?? 'без оценки'}): ${v.help}\n    ` +
        v.nodes.map((n) => n.target.join(' ')).join('\n    '),
    )
    .join('\n  ')

  expect(результат.violations, 'нарушения доступности:\n  ' + описание).toEqual([])
}

test('лента гостя доступна', async ({ page }) => {
  await page.goto('/')
  // Ждём приглашение, а не кнопку входа: кнопок «Войти» на этом экране
  // две — в шапке и в приглашении, — и ожидание по имени попало бы в
  // обе сразу.
  await page.getByText('Здесь ваша лента').waitFor()
  await проверить(page)
})

test('форма входа доступна', async ({ page }) => {
  await page.goto('/вход')
  await page.getByLabel('Ник').waitFor()
  await проверить(page)
})

test('форма регистрации доступна', async ({ page }) => {
  await page.goto('/регистрация')
  await page.getByLabel('Как вас показывать').waitFor()
  await проверить(page)
})

test('лента вошедшего доступна', async ({ page }) => {
  const ник = 'a' + Date.now().toString(36)
  await page.goto('/регистрация')
  await page.getByLabel('Ник').fill(ник)
  await page.getByLabel('Как вас показывать').fill('Пользователь ' + ник)
  await page.getByLabel('Пароль').fill('достаточно-длинный-пароль')
  await page.getByRole('button', { name: 'Завести' }).click()
  await page.getByRole('button', { name: 'Отправить' }).waitFor()

  await проверить(page)
})

test('ссылка обхода есть и ведёт к содержимому', async ({ page }) => {
  // Без неё до содержимого приходится протабать всю шапку на каждой
  // странице, и это то самое неудобство, которого axe не видит.
  await page.goto('/')
  const ссылка = page.getByRole('link', { name: 'Перейти к содержимому' })
  await expect(ссылка).toHaveAttribute('href', '#содержимое')
  await expect(page.locator('#содержимое')).toHaveCount(1)
})
