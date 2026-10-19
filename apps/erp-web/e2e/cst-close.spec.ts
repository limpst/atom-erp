import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

const DAYS = ['일', '월', '화', '수', '목', '금', '토'];
const pad = (n: number) => String(n).padStart(2, '0');
function isoWeek(d: Date) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const y0 = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  return `${t.getUTCFullYear()}-W${String(Math.ceil(((t.getTime() - y0.getTime()) / 86400000 + 1) / 7)).padStart(2, '0')}`;
}

/** 지난달(YYYY-MM) · 그 달 마지막 날 · 오늘이 속한 주에서 그 날이 속한 주까지 몇 주 전인지 */
function target() {
  const now = new Date();
  const last = new Date(now.getFullYear(), now.getMonth(), 0); // 지난달 말일(시드 투입 시작 = 한 달 전 이후)
  const monday = (d: Date) => {
    const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    x.setDate(x.getDate() - ((x.getDay() + 6) % 7));
    return x;
  };
  const weeksBack = Math.round((monday(now).getTime() - monday(last).getTime()) / (7 * 86400000));
  return { ym: `${last.getFullYear()}-${pad(last.getMonth() + 1)}`, day: last, label: `${DAYS[last.getDay()]} 시간`, weeksBack };
}

async function openWeek(page: import('@playwright/test').Page, weeksBack: number, day: Date) {
  await page.goto('/hrq/timesheets');
  await expect(page.getByTestId('ts-week')).toBeVisible();
  // 타임시트 화면은 지난주에서 시작한다
  for (let i = 1; i < weeksBack; i++) await page.getByRole('button', { name: '이전 주' }).click();
  for (let i = weeksBack; i < 1; i++) await page.getByRole('button', { name: '다음 주' }).click();
  await expect(page.getByTestId('iso-week')).toHaveText(isoWeek(day));
  await page.waitForLoadState('networkidle');
}

async function enterHours(page: import('@playwright/test').Page, label: string) {
  const week = page.getByTestId('ts-week');
  const projectSelect = week.getByLabel('프로젝트').first();
  const projectId = await projectSelect.locator('option', { hasText: '한빛타워' }).first().getAttribute('value');
  await projectSelect.selectOption(projectId!);
  await week.getByLabel(label).first().fill('4');
  await page.getByTestId('ts-submit').click();
}

test('CST 월 마감 → 그 달 전기(타임시트) 차단 → SYSADMIN · ADMIN 재오픈 → 다시 전기', async ({ page }) => {
  test.setTimeout(180_000);
  const t = target();
  const key = t.ym.replace('-', '');

  // 1. CST 마감 시도 — HRQ 월 마감 · 대사 전이라 막는 사유가 화면에 나온다
  await gotoAs(page, '/cst/periods', '서경영');
  await page.getByLabel('CST 마감 월').fill(t.ym);
  await page.getByTestId('cst-close').click();
  const result = page.getByTestId('cst-close-result');
  await expect(result).toContainText('HRQ_MONTH_NOT_CLOSED');
  await page.screenshot({ path: `${SHOTS}/34-cst-close-blocked.png`, fullPage: true });

  // 2. HRQ 월 마감(ADMIN) → hrq.manday.closed → CST 인·일 대사(비동기)
  await page.goto('/hrq/month-close');
  await page.getByLabel('HRQ 마감 월').fill(t.ym);
  await page.getByTestId('hrq-close').click();
  await expect(page.getByTestId('hrq-close-result')).toContainText(`${t.ym}:`);
  await expect(page.getByTestId(`hrq-month-${key}`)).toContainText('마감');

  // 3. CST 마감 — 대사 MATCHED 가 올 때까지 다시 시도
  await page.goto('/cst/periods');
  await page.getByLabel('CST 마감 월').fill(t.ym);
  await expect(async () => {
    await page.getByTestId('cst-close').click();
    await expect(page.getByTestId('cst-close-result')).toContainText('마감 완료', { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  const periodRow = page.getByTestId(`cst-period-${key}`);
  await expect(periodRow).toContainText('마감');
  await expect(periodRow).toContainText('MATCHED');
  await periodRow.click();
  await expect(page.getByTestId('recon-run').first()).toContainText('일치(MATCHED)');
  await page.screenshot({ path: `${SHOTS}/35-cst-closed-recon.png`, fullPage: true });

  // 4. 마감 월 전기 차단: ENG 가 그 달 날짜에 타임시트 제출 → HRQ-409-001
  await loginAs(page, '정참여');
  await openWeek(page, t.weeksBack, t.day);
  await enterHours(page, t.label);
  await expect(page.getByTestId('ts-month-closed')).toContainText('HRQ-409-001');
  await page.screenshot({ path: `${SHOTS}/36-posting-blocked-closed-month.png`, fullPage: true });

  // 5. SYSADMIN 이 CST 재오픈(사유) — 목록 권한 없이 월 입력
  await loginAs(page, '시스템관리');
  await page.goto('/cst/periods');
  await page.getByLabel('CST 재오픈 월').fill(t.ym);
  await page.getByTestId('cst-reopen').click();
  await page.getByLabel('재오픈 사유').fill('(합성) E2E — 마감 후 타임시트 누락분 반영');
  await page.getByTestId('reopen-confirm').click();
  await expect(page.getByText(`CST ${t.ym} 재오픈`)).toBeVisible();

  // 6. ADMIN 이 HRQ 재오픈(사유) → CST 목록에 재오픈 표시
  await loginAs(page, '서경영');
  await page.goto('/hrq/month-close');
  await page.getByRole('button', { name: `HRQ 재오픈 ${t.ym}` }).click();
  await page.getByLabel('재오픈 사유').fill('(합성) E2E — 누락 타임시트 반영');
  await page.getByTestId('reopen-confirm').click();
  await expect(page.getByTestId(`hrq-month-${key}`)).toContainText('재오픈');
  await page.goto('/cst/periods');
  await expect(page.getByTestId(`cst-period-${key}`)).toContainText('재오픈');

  // 7. 다시 전기 가능
  await loginAs(page, '정참여');
  await openWeek(page, t.weeksBack, t.day);
  await enterHours(page, t.label);
  await expect(page.getByText('주간 타임시트 제출')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/37-posting-after-reopen.png`, fullPage: true });
});
