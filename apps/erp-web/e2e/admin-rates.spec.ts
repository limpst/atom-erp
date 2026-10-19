import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

/** 로컬(KST) 날짜 'YYYY-MM-DD' */
function iso(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
/** ISO 주차 'YYYY-Www' */
function isoWeek(d: Date) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const y0 = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  return `${t.getUTCFullYear()}-W${String(Math.ceil(((t.getTime() - y0.getTime()) / 86400000 + 1) / 7)).padStart(2, '0')}`;
}
/** 2주 전 월요일 — 시드가 쓰지 않은 주(시드 타임시트는 지난주) */
function mondayTwoWeeksAgo() {
  const d = new Date();
  d.setDate(d.getDate() - 14);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d;
}

test('CST 표준 노임 단가 설정(ADMIN 초안 → EXEC 승인) → 타임시트 승인 → 원가 원장 표준원가 행', async ({ page }) => {
  const mon = mondayTwoWeeksAgo();
  const from = iso(mon);
  // ⚠️ 예시 값(합성) — 중급(INTER) 일 단가 300,000원, 8시간 = 1인·일 → 표준원가 300,000원
  await gotoAs(page, '/admin/labor-rates', '서경영');
  await page.getByLabel('등급').selectOption('INTER');
  await page.getByLabel('값', { exact: true }).fill('300000');
  await page.getByLabel('적용 시작').fill(from);
  await page.getByLabel('근거').fill('⚠️ 예시 — (합성) E2E 중급 단가');
  await page.getByTestId('rate-register').click();
  await expect(page.getByText(/초안 등록 — INTER/)).toBeVisible();
  await expect(page.getByTestId('rate-drafts')).toContainText('승인 대기 1건');

  // EXEC 승인(등록자 ≠ 승인자)
  await loginAs(page, '김임원');
  await page.getByRole('button', { name: `승인 INTER (중급) ${from}` }).click();
  await expect(page.getByText(new RegExp(`승인 — INTER \\(중급\\) 300,000원 ${from}부터 적용`))).toBeVisible();
  await expect(page.getByTestId('rate-grid')).toContainText('적용 중');
  await page.screenshot({ path: `${SHOTS}/10-labor-rate-approved.png`, fullPage: true });

  // ENG: 2주 전 월요일 8시간 제출
  await loginAs(page, '정참여');
  await page.goto('/hrq/timesheets');
  await expect(page.getByTestId('ts-week')).toBeVisible();
  const loaded = page.waitForResponse((r) => r.url().includes(`/weeks/${isoWeek(mon)}`) && r.request().method() === 'GET');
  await page.getByRole('button', { name: '이전 주' }).click();
  await loaded;
  await expect(page.getByTestId('iso-week')).toHaveText(isoWeek(mon));
  const week = page.getByTestId('ts-week');
  const projectSelect = week.getByLabel('프로젝트').first();
  const projectId = await projectSelect.locator('option', { hasText: '한빛타워' }).first().getAttribute('value');
  await projectSelect.selectOption(projectId!);
  await week.getByLabel('월 시간').first().fill('8');
  await expect(week.getByLabel('수 시간').first()).toHaveValue('');
  await page.getByTestId('ts-submit').click();
  await expect(page.getByText('주간 타임시트 제출')).toBeVisible();

  // PM 승인 → CST 인건비 전기(outbox)
  await loginAs(page, '최책임');
  await page.goto('/hrq/timesheets');
  await page.getByRole('button', { name: '이전 주' }).click();
  await expect(page.getByTestId('ts-entries')).toContainText(from);
  await page.getByRole('button', { name: '제출분 전체 선택' }).click();
  await page.getByTestId('ts-approve').click();
  await expect(page.getByText(/1건 승인/)).toBeVisible();

  // 프로젝트 원가 원장: 새 단가로 계산된 TIMESHEET 행
  await page.goto('/projects');
  await expect(async () => {
    await page.getByRole('gridcell', { name: /한빛타워 정밀안전진단/ }).first().click();
    await expect(page).toHaveURL(/\/projects\/[0-9a-f-]{36}/, { timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
  const ledger = page.getByTestId('ledger-grid');
  await expect(async () => {
    await page.reload();
    await expect(ledger).toContainText('300,000원', { timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
  await page.screenshot({ path: `${SHOTS}/11-ledger-new-rate.png`, fullPage: true });
});
