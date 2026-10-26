import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

/** "내 할 일" — 헤더 배지 · 모듈별 항목 · 처리 화면 링크, 본인이 만든 항목은 보이지 않음(직무 분리) */
test('내 할 일 — 임원: 할인 승인 · 예산 결재 → 링크로 처리', async ({ page }) => {
  await gotoAs(page, '/', '김임원');
  await expect(page.getByTestId('inbox-badge')).not.toContainText(/내 할 일 0$/);
  await page.getByTestId('inbox-badge').click();
  const items = page.getByTestId('inbox-items');
  await expect(items).toContainText('(합성) 4차 겸직 할인 B(다른 임원 승인)');
  await expect(items).toContainText('실행예산 Rev.0 결재');
  await page.screenshot({ path: `${SHOTS}/54-inbox-exec.png`, fullPage: true });
  const row = items.locator('tr', { hasText: '(합성) 4차 겸직 할인 B(다른 임원 승인)' });
  await row.getByRole('link').click();
  await expect(page).toHaveURL(/\/sal\/estimates\?opp=/);
  await expect(page.getByTestId('estimate-status')).toHaveText('할인 승인 대기');
  await page.getByTestId('approve-discount').click();
  await expect(page.getByText('임원 승인 완료')).toBeVisible();
  await page.goto('/inbox');
  await expect(page.getByTestId('inbox-items')).not.toContainText('(합성) 4차 겸직 할인 B(다른 임원 승인)');
});

test('내 할 일 — 겸직임원(SALES+EXEC)에게 자기 견적 할인 승인은 나오지 않는다', async ({ page }) => {
  await gotoAs(page, '/inbox', '겸직임원');
  await expect(page.getByTestId('inbox-src-sal-discount')).toContainText('견적 할인 승인 0');
  await expect(page.getByTestId('inbox-items')).not.toContainText('4차 겸직 할인');
});

test('내 할 일 — PM(책임자): 겸직책임 타임시트 · 감리 정정 대기 → 화면 이동', async ({ page }) => {
  await gotoAs(page, '/inbox', '최책임');
  const items = page.getByTestId('inbox-items');
  await expect(items).toContainText('D-015 겸직책임');
  await expect(items).toContainText(/감리일지 .* 정정 요청/);
  await page.screenshot({ path: `${SHOTS}/55-inbox-pm.png`, fullPage: true });
  await items.locator('tr', { hasText: 'D-015 겸직책임' }).getByRole('link').click();
  await expect(page).toHaveURL(/\/hrq\/timesheets\?emp=.*&week=/);
  await expect(page.getByLabel('직원 선택').locator('option:checked')).toHaveText(/겸직책임/);
  await expect(page.getByTestId('ts-entries')).toContainText('제출');

  // 문책임(PART)은 같은 프로젝트라도 책임자가 아니라 수신함에 없다
  await loginAs(page, '문책임2');
  await page.goto('/inbox');
  await expect(page.getByTestId('inbox-src-hrq-timesheet')).toContainText('타임시트 승인 0');
});
