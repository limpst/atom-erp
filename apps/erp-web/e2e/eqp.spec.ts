import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const plus = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return iso(d);
};

/** EQP: ENG 예약(겹침 안내 확인) → ADMIN 승인 · QR 토큰 발급 → ENG QR 반출(예약 연결) → 반납 */
test('EQP 예약 → 승인 → QR 반출 → 반납', async ({ page }) => {
  const code = 'EQ-MSR-CRK-0001';
  await gotoAs(page, '/eqp/reservations', '정참여');
  await expect(page.getByTestId('eqp-calendar')).toContainText(code);
  const project = await page.getByLabel('예약 프로젝트').locator('option', { hasText: '한빛타워' }).first().getAttribute('value');
  const equipment = await page.getByLabel('예약 장비').locator('option', { hasText: code }).getAttribute('value');

  // 1) 시드 예약(오늘+5~+7, 확정)과 겹치는 요청 → 화면 안 409 안내
  await page.getByLabel('예약 장비').selectOption(equipment!);
  await page.getByLabel('예약 프로젝트').selectOption(project!);
  await page.getByLabel('예약 시작일').fill(plus(6));
  await page.getByLabel('예약 종료일').fill(plus(8));
  await expect(page.getByText(/이 기간에 이미 예약이 있습니다/)).toBeVisible();
  await page.getByTestId('eqp-reserve').click();
  await expect(page.getByTestId('eqp-blocked')).toContainText('EQP-409-002');
  await page.screenshot({ path: `${SHOTS}/15-eqp-overlap-blocked.png`, fullPage: true });

  // 2) 오늘~내일 예약
  await page.getByLabel('예약 시작일').fill(plus(0));
  await page.getByLabel('예약 종료일').fill(plus(1));
  await page.getByTestId('eqp-reserve').click();
  await expect(page.getByText(`예약 요청 — ${plus(0)} ~ ${plus(1)} (승인 대기)`)).toBeVisible();

  // 3) ADMIN 승인 + QR 토큰 발급
  await loginAs(page, '서경영');
  await page.goto('/eqp/reservations');
  await page.getByRole('button', { name: `승인 ${code} ${plus(0)}` }).click();
  await expect(page.getByText(`예약 확정 — ${plus(0)} ~ ${plus(1)}`)).toBeVisible();
  await page.goto('/eqp/equipment');
  await expect(page.getByTestId('eqp-grid')).toContainText('교정 만료');
  await page.screenshot({ path: `${SHOTS}/16-eqp-calibration-chips.png`, fullPage: true });
  await page.getByRole('button', { name: `QR 라벨 ${code}` }).click();
  await page.getByTestId('label-issue').click();
  const token = (await page.getByTestId('label-token').innerText()).trim();
  expect(token).toMatch(/^eqp1\./);
  await page.getByRole('button', { name: '닫기' }).first().click();

  // 4) ENG QR 반출 → 반납
  await loginAs(page, '정참여');
  await page.goto('/eqp/reservations');
  await page.getByLabel('QR 토큰').fill(`https://erp.atom-eng.local/q/${token}`);
  await page.getByTestId('eqp-scan').click();
  await expect(page.getByTestId('eqp-scan-result')).toContainText('사용 가능');
  await page.getByLabel('반출 프로젝트').selectOption(project!);
  await page.getByTestId('eqp-checkout').click();
  await expect(page.getByTestId('eqp-checkout-result')).toContainText('예약 연결됨');
  await expect(page.getByTestId('eqp-scan-result')).toContainText('반출 중');
  await expect(page.getByTestId('eqp-reservations')).toContainText('사용 중');
  await page.screenshot({ path: `${SHOTS}/17-eqp-checked-out.png`, fullPage: true });
  await page.getByTestId('eqp-return').click();
  await expect(page.getByTestId('eqp-return-result')).toContainText('사용 1일');
  await expect(page.getByTestId('eqp-reservations')).toContainText('반납');
  await page.screenshot({ path: `${SHOTS}/18-eqp-returned.png`, fullPage: true });
});
