import { expect, test } from '@playwright/test';
import { gotoAs, SHOTS } from './helpers';

/** FLD 관리: 미해결 충돌(시드 2건) 중 1F 구역 충돌을 병합 값으로 해결 → 잠금 가능 → 잠금 */
test('FLD 동기화 충돌 해결 → 구역 잠금', async ({ page }) => {
  await gotoAs(page, '/fld', '최책임');
  const project = await page.getByLabel('프로젝트').locator('option', { hasText: '한빛타워' }).getAttribute('value');
  await page.getByLabel('프로젝트').selectOption(project!);
  await expect(page.getByTestId('fld-round')).toContainText('1회차');
  const zones = page.getByTestId('fld-zones');
  await expect(zones).toContainText('미해결 충돌');
  await expect(zones).toContainText('필수 측정 누락');
  await expect(zones).toContainText('사진 검증 실패');
  const conflicts = page.getByTestId('fld-conflicts');
  await expect(conflicts.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByTestId('fld-photos')).toContainText('해시 검증 완료');
  await expect(page.getByTestId('fld-photos')).toContainText('검증 실패(재업로드)');
  await page.screenshot({ path: `${SHOTS}/23-fld-before-resolve.png`, fullPage: true });

  await page.getByRole('button', { name: '해결 1F-전체 attrs.width_mm' }).click();
  const dialog = page.getByRole('dialog', { name: '동기화 충돌 해결' });
  await expect(dialog.getByTestId('fld-server-value')).toHaveText('0.25');
  await expect(dialog.getByTestId('fld-client-value')).toHaveText('0.3');
  await dialog.getByLabel('직접 값 입력(병합)').check();
  await dialog.getByLabel('병합 값').fill('0.28');
  await page.screenshot({ path: `${SHOTS}/24-fld-resolve-dialog.png` });
  await dialog.getByTestId('fld-resolve-confirm').click();
  await expect(page.getByText(/충돌 해결 — 직접 값 입력\(병합\)/)).toBeVisible();
  await expect(conflicts.locator('tbody tr')).toHaveCount(1);

  const lock = zones.getByRole('button', { name: '잠금 1F-전체' });
  await expect(lock).toBeEnabled();
  await lock.click();
  await expect(page.getByText('구역 1F-전체 잠금')).toBeVisible();
  await expect(zones.locator('tr', { hasText: '1F-전체' })).toContainText('잠금');
  await expect(zones.getByRole('button', { name: '잠금 2F-전체' })).toBeDisabled();
  await page.screenshot({ path: `${SHOTS}/25-fld-after-lock.png`, fullPage: true });
});
