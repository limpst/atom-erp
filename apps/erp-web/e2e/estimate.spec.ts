import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

/** 스모크 E2E (실제 백엔드): 견적 작성 → 발행 */
test('SAL 견적 작성 → 발행', async ({ page }) => {
  await gotoAs(page, '/sal/estimates', '박영업');
  await page.getByRole('gridcell', { name: '(합성) 푸른물류센터 정밀점검' }).click();
  await page.getByTestId('fee-discountPct').fill('3');
  await page.getByTestId('create-estimate').click();
  await expect(page.getByTestId('estimate-status')).toHaveText('작성 중');
  await page.getByTestId('issue-estimate').click();
  await expect(page.getByTestId('estimate-status')).toHaveText('발행');
  await page.screenshot({ path: `${SHOTS}/01-estimate-issued.png`, fullPage: true });
});

test('SAL 할인 한도 초과 → 임원 승인 → 재발행', async ({ page }) => {
  await gotoAs(page, '/sal/estimates', '박영업');
  await page.getByRole('gridcell', { name: '(합성) 푸른물류센터 정밀점검' }).click();
  await page.getByTestId('fee-discountPct').fill('8');
  await page.getByTestId('create-estimate').click();
  await expect(page.getByTestId('estimate-status')).toHaveText('작성 중');
  await page.getByTestId('issue-estimate').click();
  await expect(page.getByTestId('estimate-status')).toHaveText('할인 승인 대기');

  await loginAs(page, '김임원');
  await expect(page.getByTestId('approve-discount')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/02-discount-pending-exec.png`, fullPage: true });
  await page.getByTestId('approve-discount').click();
  await expect(page.getByText('임원 승인 완료')).toBeVisible();

  await loginAs(page, '박영업');
  await page.getByTestId('issue-estimate').click();
  await expect(page.getByTestId('estimate-status')).toHaveText('발행');
});

test('problem+json 오류 토스트 (오류 코드 표시)', async ({ page }) => {
  await gotoAs(page, '/sal/estimates', '박영업');
  await page.getByRole('gridcell', { name: '(합성) 푸른물류센터 정밀점검' }).click();
  await page.getByTestId('fee-grossAreaM2').fill('250000'); // 요율 면적 구간 밖 → SAL-422-005
  await page.getByTestId('create-estimate').click();
  await expect(page.getByText(/\[SAL-422-005\]/)).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/03-problem-toast.png` });
});
