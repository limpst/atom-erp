import { expect, test } from '@playwright/test';
import { gotoAs, SHOTS } from './helpers';

const num = (s: string) => Number(s.replace(/[^0-9]/g, ''));

test('HRQ 자격 승인(ADMIN) → 등록요건 화면의 자격 현황 · 예측 변화점이 바뀐다', async ({ page }) => {
  await gotoAs(page, '/hrq/compliance', '서경영');
  await expect(page.getByTestId('compliance-today')).toBeVisible();
  await expect(page.getByTestId('cred-pending')).not.toHaveText('0건');
  const valid0 = num(await page.getByTestId('cred-valid').innerText());
  const pending0 = num(await page.getByTestId('cred-pending').innerText());
  const points0 = num((await page.getByTestId('forecast-points').innerText()).match(/평가 시점 (\d+)개/)![1]);
  await expect(page.getByTestId('first-shortfall')).not.toHaveText('…');

  // 시드: 정참여 안전진단 관련 민간자격(예시) 승인 대기(200일 뒤 만료)
  await page.goto('/hrq/people');
  const grid = page.getByTestId('cred-grid');
  await expect(grid).toContainText('승인 대기');
  await page.screenshot({ path: `${SHOTS}/31-hrq-credentials-pending.png`, fullPage: true });
  await grid.getByRole('button', { name: /^승인 정참여 안전진단/ }).click();
  await expect(page.getByText(/자격 승인 — 정참여/)).toBeVisible();
  await expect(grid.getByText(/^유효 D-200$/)).toBeVisible();

  await page.goto('/hrq/compliance');
  await expect(page.getByTestId('cred-valid')).toHaveText(`${valid0 + 1}건`);
  await expect(page.getByTestId('cred-pending')).toHaveText(`${pending0 - 1}건`);
  // 새 유효 자격의 만료 다음 날(오늘 + 201일)이 예측 변화점으로 추가된다
  await expect(page.getByTestId('forecast-points')).toContainText(`평가 시점 ${points0 + 1}개`);
  await expect(page.getByTestId('forecast-chart')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/32-hrq-compliance-forecast.png`, fullPage: true });
  await page.getByTestId('theme-toggle').click();
  await page.screenshot({ path: `${SHOTS}/33-hrq-compliance-dark.png`, fullPage: true });
});
