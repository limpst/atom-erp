import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { loginAs, SHOTS } from './helpers';

/** 고객 포털: 테넌트 격리(고객 A ↔ B) · 정정본 대체 배너 · 워터마크 PDF 다운로드 */
async function asCustomer(page: Page, name: string) {
  await page.goto('/');
  await loginAs(page, name);
  await expect(page).toHaveURL(/\/portal$/);
}

/** 화면이 보내는 것과 같은 헤더로 직접 호출(교차 고객 조회 시도) */
async function portalFetch(page: Page, path: string): Promise<{ status: number; body: { code?: string } & Array<{ id: string }> }> {
  return page.evaluate(async (p) => {
    const s = JSON.parse(localStorage.getItem('atom-erp.dev-session')!);
    const r = await fetch(p, { headers: { 'X-Actor-Id': s.actorId, 'X-Roles': s.roles.join(','), 'X-Client-Org-Id': s.clientOrgId } });
    return { status: r.status, body: (await r.json()) as { code?: string } & Array<{ id: string }> };
  }, path);
}

test('PTL 고객 A/B 테넌트 격리 + 대체 배너 + 워터마크 다운로드', async ({ page }) => {
  // 고객 B: 자기 보고서 하나 — id 를 기억
  await asCustomer(page, '(고객B) 푸른 담당');
  await page.getByRole('link', { name: '보고서' }).click();
  const listB = page.getByTestId('ptl-reports');
  await expect(listB).toContainText('(합성) 푸른아파트 정기안전점검 보고서');
  await expect(listB).not.toContainText('한빛타워');
  const reportsB = await portalFetch(page, '/api/v1/ptl/reports');
  const idB = reportsB.body[0].id as string;
  await page.getByRole('link', { name: '내 시설물 · 일정' }).click();
  await expect(page.getByTestId('ptl-buildings')).toContainText('(합성) 합성로 1202 빌딩');
  await expect(page.getByTestId('ptl-buildings')).not.toContainText('한빛타워');
  await page.screenshot({ path: `${SHOTS}/19-portal-tenant-b.png`, fullPage: true });

  // 고객 A: 자기 시설물 · 일정만, B 의 시설물 · 보고서는 보이지 않음
  await asCustomer(page, '(고객A) 한빛 담당');
  const buildings = page.getByTestId('ptl-buildings');
  await expect(buildings).toContainText('(합성) 한빛타워 별관');
  await expect(buildings).not.toContainText('합성로 1202');
  await expect(page.getByTestId('ptl-schedule')).toContainText(/D[+-]\d+/);
  await page.screenshot({ path: `${SHOTS}/20-portal-home-a.png`, fullPage: true });
  await page.getByRole('link', { name: '보고서' }).click();
  const list = page.getByTestId('ptl-reports');
  await expect(list).toContainText('Rev.2');
  await expect(list).not.toContainText('푸른아파트');
  // 대체된 1판: 배너 · 열람만
  await expect(page.getByTestId('ptl-superseded-banner')).toContainText('정정본으로 대체');
  await expect(page.getByRole('button', { name: /Rev\.1$/ })).toBeDisabled();
  // 교차 고객 직접 조회도 404(존재 숨김)
  const cross = await portalFetch(page, `/api/v1/ptl/reports/${idB}`);
  expect(cross.status).toBe(404);
  expect(cross.body.code).toBe('PTL-404-001');
  // PTL-01: 포털 계정으로 내부 API(발행본 원본 · 프로젝트 등) 직접 호출 → 403 CORE-403-010
  for (const internal of ['/api/v1/rpt/reports', '/api/v1/core/projects']) {
    const r = await portalFetch(page, internal);
    expect(r.status).toBe(403);
    expect(r.body.code).toBe('CORE-403-010');
  }

  // 최신본 워터마크 다운로드
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /Rev\.2$/ }).click(),
  ]);
  const file = `test-results/ptl-${Date.now()}.pdf`;
  await download.saveAs(file);
  expect(download.suggestedFilename()).toMatch(/^report-.*-rev2-.*\.pdf$/);
  expect(readFileSync(file).subarray(0, 5).toString('latin1')).toBe('%PDF-');
  await expect(page.getByTestId('ptl-download-info')).toContainText('download-id');
  await page.screenshot({ path: `${SHOTS}/21-portal-reports-superseded.png`, fullPage: true });

  // 다크 모드 + 알림
  await page.getByTestId('theme-toggle').click();
  await page.getByRole('link', { name: /알림/ }).click();
  await expect(page.getByTestId('ptl-notifications')).toContainText('보고서 정정본이 발행되었습니다');
  await page.screenshot({ path: `${SHOTS}/22-portal-notifications-dark.png`, fullPage: true });
  await page.getByTestId('theme-toggle').click();

  // 직원으로 돌아오면 내부 화면
  await loginAs(page, '최책임');
  await expect(page).not.toHaveURL(/\/portal/);
});
