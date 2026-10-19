import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

test('HRQ 타임시트 PM 승인 → 정정(리비전)', async ({ page }) => {
  // 시드: 지난주 수요일 6h(REPORT)가 제출 상태로 남아 있다
  await gotoAs(page, '/hrq/timesheets', '최책임');
  const entries = page.getByTestId('ts-entries');
  await expect(entries).toContainText('제출');
  await page.getByRole('button', { name: '제출분 전체 선택' }).click();
  await page.getByTestId('ts-approve').click();
  await expect(page.getByText(/건 승인/)).toBeVisible();
  await expect(entries).not.toContainText('제출');

  // 정정: 첫 승인 항목 8h → 7h
  await entries.getByRole('button', { name: '정정' }).first().click();
  await page.getByRole('spinbutton').first().fill('7');
  await page.getByTestId('correct-reason').fill('(합성) 현장 조기 종료');
  await page.getByRole('button', { name: '정정 저장' }).click();
  await expect(page.getByText(/정정 완료 — Rev.2/)).toBeVisible();
  await expect(entries).toContainText('정정됨');
  await page.screenshot({ path: `${SHOTS}/06-timesheet-corrected.png`, fullPage: true });

  // ENG 본인 주간 입력 화면
  await loginAs(page, '정참여');
  await expect(page.getByTestId('ts-week')).toBeVisible();
});
