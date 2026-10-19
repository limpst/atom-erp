import { expect, test } from '@playwright/test';
import { gotoAs, SHOTS } from './helpers';

test('프로젝트 목록 · 상세(투입 · 예산 · EVM · 수익성) + 다크 모드', async ({ page }) => {
  await gotoAs(page, '/projects', '최책임');
  await page.getByRole('gridcell', { name: /한빛타워 정밀안전진단/ }).first().click();
  await expect(page.getByTestId('members-table')).toContainText('최책임');
  await expect(page.getByTestId('evm-kpis')).toContainText('CPI');
  await expect(page.getByTestId('profit-kpis')).toContainText('계약금액');
  await page.screenshot({ path: `${SHOTS}/04-project-detail.png`, fullPage: true });
  await page.getByTestId('theme-toggle').click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.screenshot({ path: `${SHOTS}/05-project-detail-dark.png`, fullPage: true });
  await page.getByTestId('theme-toggle').click();
});

test('ENG 은 원가(CST) 권한이 없어 403 을 화면 안에서 안내', async ({ page }) => {
  await gotoAs(page, '/projects', '정참여');
  await page.getByRole('gridcell', { name: /한빛타워 정밀안전진단/ }).first().click();
  await expect(page.getByText('CORE-403-001').first()).toBeVisible();
});
