import { expect, test, type Download } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

async function bytes(d: Download): Promise<Buffer> {
  const s = await d.createReadStream();
  const chunks: Buffer[] = [];
  for await (const c of s) chunks.push(c as Buffer);
  return Buffer.concat(chunks);
}

test('CST 원가 원장: 정정 짝 강조 · 필터 · EVM 추이 차트', async ({ page }) => {
  await gotoAs(page, '/cst/ledger', '최책임');
  const grid = page.getByTestId('cost-ledger-grid');
  await expect(grid.getByText('#1 역분개')).toBeVisible();
  await expect(grid.getByText('#1 정정 후')).toBeVisible();
  await expect(page.getByTestId('ledger-count')).toContainText('정정 짝 1쌍');
  await expect(page.getByText(/실원가 열은 실단가 열람 권한/)).toBeVisible();
  const chart = page.getByTestId('evm-trend');
  await expect(chart).toBeVisible();
  expect(Number(await chart.getAttribute('data-points'))).toBeGreaterThanOrEqual(7);
  await expect(chart.locator('canvas')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/38-cst-ledger-evm.png`, fullPage: true });
  await page.getByLabel('정정 짝만').check();
  await expect(page.getByTestId('ledger-count')).toContainText(/^3행/);
  await page.getByTestId('theme-toggle').click();
  await page.screenshot({ path: `${SHOTS}/39-cst-ledger-pairs-dark.png`, fullPage: true });
  await page.getByTestId('theme-toggle').click();
});

test('FCD 가져오기: 행 오류 CSV(수식 무력화) 다운로드 · 중복 확인 큐 병합 · 카드 목록(마스킹)', async ({ page }) => {
  await gotoAs(page, '/fcd/imports', '윤재무');
  const grid = page.getByTestId('import-grid');
  const [dl] = await Promise.all([
    page.waitForEvent('download'),
    grid.getByRole('button', { name: '오류 리포트 statement-2026-06-errors.csv' }).click(),
  ]);
  expect(dl.suggestedFilename()).toMatch(/^errors-.*\.csv$/);
  const csv = (await bytes(dl)).toString('utf-8');
  expect(csv.charCodeAt(0)).toBe(0xfeff); // Excel 한글용 BOM
  expect(csv).toContain("'=1+2");          // 수식처럼 보이는 원문은 ' 로 무력화
  expect(csv).not.toMatch(/,=1\+2/);
  await expect(page.getByTestId('error-csv-preview')).toContainText("'=1+2");
  await page.screenshot({ path: `${SHOTS}/40-fcd-import-errors.png`, fullPage: true });

  await page.getByRole('tab', { name: /중복 확인 큐/ }).click();
  const q = page.getByTestId('dedup-queue');
  await expect(q).toContainText('(예시)한식당본점');
  await page.screenshot({ path: `${SHOTS}/41-fcd-dedup-queue.png`, fullPage: true });
  await q.getByRole('button', { name: /^병합 / }).first().click();
  await expect(page.getByText(/병합 — 중복 거래 제거/)).toBeVisible();
  await expect(page.getByTestId('dedup-count')).toHaveText('0건');

  await page.goto('/fcd/cards');
  const cards = page.getByTestId('card-grid');
  await expect(cards).toContainText('999999******1234');
  expect(await cards.innerText()).not.toMatch(/\d{13,19}/);
  await page.screenshot({ path: `${SHOTS}/42-fcd-cards-masked.png`, fullPage: true });
});

test('AIP: Job 상태 · 모델 레지스트리 별칭 · 제안 결정 · 추론 감사(해시만)', async ({ page }) => {
  await gotoAs(page, '/aip', '한품질');
  const jobs = page.getByTestId('aip-jobs');
  await expect(jobs.getByText('성공').first()).toBeVisible();
  await expect(jobs.getByText('중단(DEAD)')).toBeVisible();
  await expect(jobs.getByText('대기').first()).toBeVisible();
  await expect(jobs.getByText('취소', { exact: true })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/43-aip-jobs.png`, fullPage: true });
  await page.getByRole('tab', { name: '모델 레지스트리' }).click();
  await expect(page.getByTestId('aip-models')).toContainText('production · 균열 탐지 100%');
  await page.screenshot({ path: `${SHOTS}/44-aip-models.png`, fullPage: true });

  await loginAs(page, '정참여');
  await page.goto('/aip?tab=suggestions');
  await expect(page.getByRole('tab', { name: /결정 대기 \(1\)/ })).toBeVisible();
  await page.getByTestId('aip-suggestions').getByRole('button', { name: /^수락 / }).first().click();
  await expect(page.getByText(/제안 수락/)).toBeVisible();
  await page.getByRole('tab', { name: /결정됨 \(2\)/ }).click();
  await page.screenshot({ path: `${SHOTS}/45-aip-suggestions-decided.png`, fullPage: true });

  await loginAs(page, '오감사');
  await page.goto('/aip');
  const audit = page.getByTestId('aip-audit');
  await expect(audit.getByText(/^[0-9a-f]{64}$/).first()).toBeVisible();
  await expect(audit).not.toContainText('minConfidence');
  await page.screenshot({ path: `${SHOTS}/46-aip-audit-hashes.png`, fullPage: true });
});

test('RPT 리비전 상세: 블록 읽기 · 앵커 오버레이 · 등급 트리 · HWPX · DXF 다운로드', async ({ page }) => {
  await gotoAs(page, '/reports', '최책임');
  const sel = page.getByLabel('프로젝트 선택');
  await expect(sel.locator('option', { hasText: '한빛타워' }).first()).toBeAttached();
  await sel.selectOption((await sel.locator('option', { hasText: '한빛타워' }).first().getAttribute('value'))!);
  await expect(async () => {
    await page.getByRole('gridcell', { name: /별관 정밀안전점검 보고서/ }).first().click();
    await expect(page).toHaveURL(/\/reports\/[0-9a-f-]{36}$/, { timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
  await page.getByRole('link', { name: '리비전 상세 Rev.2' }).click();
  await expect(page.getByTestId('rev-detail-status')).toHaveText(/발행|납품/);
  await expect(page.getByTestId('block-view')).toContainText('🔒 잠금(수치)');
  await expect(page.getByText('추정 위치(ESTIMATED)')).toBeVisible();
  await expect(page.getByTestId('grade-tree')).toContainText('균열 최대폭');
  await page.locator('.anchor-rect', { hasText: 'TABLE' }).first().click();
  await expect(page.locator('.block.highlight')).toHaveCount(1);
  await page.screenshot({ path: `${SHOTS}/47-rpt-revision-detail.png`, fullPage: true });

  const [hwpx] = await Promise.all([page.waitForEvent('download'), page.getByTestId('dl-hwpx').click()]);
  expect(hwpx.suggestedFilename()).toBe('report.hwpx');
  expect((await bytes(hwpx)).subarray(0, 2).toString('latin1')).toBe('PK'); // ZIP(OWPML) 컨테이너
  const dxfButton = page.locator('[data-testid^="dl-dxf-"]').first();
  const [dxf] = await Promise.all([page.waitForEvent('download'), dxfButton.click()]);
  expect(dxf.suggestedFilename()).toMatch(/\.dxf$/);
  const text = (await bytes(dxf)).toString('latin1');
  expect(text).toContain('SECTION');
  expect(text).toContain('AC1009');
  await expect(page.getByTestId('dl-result')).toContainText('.dxf');
});
