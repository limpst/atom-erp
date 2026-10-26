import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

test('FCD 케이스: 소명 요청 → 본인 소명 → 감사 판정', async ({ page }) => {
  await gotoAs(page, '/fcd/cases', '윤재무');
  // 시드: OPEN 1건, 소명 요청 1건
  await page.getByRole('gridcell', { name: '열림' }).first().click();
  await page.getByTestId('request-explanation').click();
  await expect(page.getByTestId('case-status')).toHaveText('소명 요청');
  const title = await page.locator('h3', { hasText: 'FCD-' }).first().innerText();
  const caseNo = title.match(/FCD-\d{4}-\d+/)![0];

  await loginAs(page, '정참여');
  await page.getByRole('gridcell', { name: caseNo }).click();
  await page.getByLabel('소명 내용').fill('(합성) 현장 긴급 자재 구매 — 영수증 첨부 예정');
  await page.getByRole('button', { name: '소명 제출' }).click();
  await expect(page.getByTestId('case-status')).toHaveText('소명 제출');

  await loginAs(page, '오감사');
  await page.getByRole('gridcell', { name: caseNo }).click();
  await page.getByLabel('사유', { exact: true }).fill('(합성) 긴급 구매 증빙 확인');
  await page.getByRole('button', { name: '판정 확정' }).click();
  await expect(page.getByTestId('case-status')).toHaveText('판정: 정당');
  await page.screenshot({ path: `${SHOTS}/07-fcd-resolved.png`, fullPage: true });
});
