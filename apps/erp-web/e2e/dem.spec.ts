import { expect, test } from '@playwright/test';
import { gotoAs, SHOTS } from './helpers';

/** DEM 감리(읽기 위주): 자격 만료 임박 경고 · 감리일지 잠금 표시 · 미결 의견으로 시공용 발행 차단 */
test('DEM 감리자 경고 · 일지 잠금 · FOR_CONSTRUCTION 차단', async ({ page }) => {
  await gotoAs(page, '/dem', '최책임');
  await expect(page.getByTestId('dem-supervisors')).toContainText('D-005 정참여');
  await expect(page.getByTestId('dem-cred-warning')).toContainText('만료 임박 감리원 1명');
  await expect(page.getByTestId('dem-supervisors')).toContainText('자격 만료 임박');
  // 만료 자격(한품질)으로 지정 시도 → 422 사유를 화면 안에
  await page.getByTestId('dem-assign-employee').selectOption({ label: 'D-006 한품질' });
  await expect(page.getByTestId('dem-candidate-creds')).toContainText('자격 만료');
  await page.getByTestId('dem-assign').click();
  await expect(page.getByTestId('dem-assign-refused')).toContainText('DEM-422-002');
  await page.screenshot({ path: `${SHOTS}/26-dem-supervisors.png`, fullPage: true });

  await page.getByRole('tab', { name: '감리일지' }).click();
  const logs = page.getByTestId('dem-logs');
  await expect(logs.getByText('🔒 잠금')).toHaveCount(2);
  await expect(logs).toContainText('제출(잠금 전)');
  await logs.getByText('🔒 잠금').first().click();
  await expect(page.getByText(/잠긴 일지입니다\(읽기 전용\)/)).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/27-dem-daily-logs.png`, fullPage: true });

  await page.getByTestId('dem-wps').getByRole('button', { name: '구조 설계' }).click();
  await page.getByRole('tab', { name: /도서/ }).click();
  await expect(page.getByTestId('dem-docs')).toContainText('S-101');
  await page.getByLabel('발행 S-101 B').check();
  await expect(page.getByTestId('dem-fc-gate')).toContainText('S-101 1건');
  await expect(page.getByTestId('dem-issue')).toBeDisabled();
  await page.getByRole('button', { name: '의견 S-101 A' }).click();
  await expect(page.getByTestId('dem-comments')).toContainText('C-001');
  await page.screenshot({ path: `${SHOTS}/28-dem-drawings-gate.png`, fullPage: true });
});
