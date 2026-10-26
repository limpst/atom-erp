import { expect, test } from '@playwright/test';
import { gotoAs, SHOTS } from './helpers';

/** FLD 4차: 데스크톱 관리 — 조사 회차 만들기 → 구역(층 · 담당) 설정, 중복 구역 이름은 서버 거부를 화면 안에 */
test('FLD 회차 생성 · 구역 설정', async ({ page }) => {
  await gotoAs(page, '/fld/setup', '최책임');
  const projectSel = page.getByLabel('프로젝트');
  const value = await projectSel.locator('option', { hasText: '한빛타워 정밀안전진단' }).first().getAttribute('value');
  await projectSel.selectOption(value!);
  await expect(page.getByTestId('round-list')).toContainText('1회차');
  const building = page.getByLabel('대상 시설물');
  const bValue = await building.locator('option', { hasText: '한빛타워' }).first().getAttribute('value');
  await building.selectOption(bValue!);
  await page.getByLabel('조사 종류').selectOption('REGULAR');
  await page.getByTestId('round-create').click();
  await expect(page.getByText(/2회차 정기점검 생성/)).toBeVisible();
  await expect(page.getByTestId('round-list')).toContainText('2회차');

  await page.getByLabel('구역 이름').fill('1F-동측(4차)');
  await page.getByLabel('층').selectOption({ label: '1F' });
  await page.getByLabel('담당 조사자').selectOption({ label: 'D-005 정참여 (PART)' });
  await page.getByTestId('zone-add').click();
  await expect(page.getByTestId('zones')).toContainText('1F-동측(4차)');
  await expect(page.getByTestId('zones')).toContainText('D-005 정참여');
  // 같은 이름 다시 → 서버 422 화면 안
  await page.getByLabel('구역 이름').fill('1F-동측(4차)');
  await page.getByLabel('담당 조사자').selectOption({ label: 'D-005 정참여 (PART)' });
  await page.getByTestId('zone-add').click();
  await expect(page.getByTestId('zone-refused')).toContainText('같은 회차에 같은 구역 이름');
  await page.screenshot({ path: `${SHOTS}/53-fld-round-zone-setup.png`, fullPage: true });
});
