import { expect, type Page } from '@playwright/test';

export const SHOTS = 'e2e/screenshots';

/** 개발 사용자 전환(헤더 인증) — 상단 선택 상자 */
export async function loginAs(page: Page, name: string) {
  const picker = page.getByTestId('dev-user-picker');
  await expect(picker).toBeVisible();
  const value = await picker.locator('option', { hasText: name }).first().getAttribute('value');
  await picker.selectOption(value!);
}

export async function gotoAs(page: Page, path: string, name: string) {
  await page.goto('/');
  await loginAs(page, name);
  await page.goto(path);
}
