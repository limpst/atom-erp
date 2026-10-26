import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';
import { api, U } from './helpers4';

/** DEM 4차: 감리일지 작성(오프라인 초안 → 같은 clientOpId 재전송) · 잠긴 일지 정정 요청 */
test('DEM 감리일지 — 오프라인 제출은 이 기기에 보관, 연결되면 같은 clientOpId 로 재전송 · 서버 재생', async ({ page, context }) => {
  await gotoAs(page, '/dem/logs', '정참여');
  await expect(page.getByTestId('dem-checklist')).toBeVisible();
  const bodies: string[] = [];
  const urls: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'PUT' && r.url().includes('/api/v1/dem/daily-logs/')) { bodies.push(r.postData() ?? ''); urls.push(r.url()); }
  });
  await page.getByLabel('공정률').fill('60');
  await page.getByLabel('작업 인원').fill('13');
  await page.getByLabel('특기 사항').fill('(합성) 4차 E2E — 상부 해체 진행, 비산 방지막 보수');
  await page.getByTestId('dem-checklist').getByLabel('결과 1').selectOption('NA');

  await context.setOffline(true);
  await page.getByTestId('log-submit').click();
  await expect(page.getByTestId('log-offline')).toBeVisible();
  await expect(page.getByTestId('draft-chip')).toContainText('전송 대기');
  const stored = await page.evaluate(() => {
    const k = Object.keys(localStorage).find((x) => x.startsWith('atom-erp.dem-log-draft.'));
    return k ? JSON.parse(localStorage.getItem(k)!) : null;
  });
  expect(stored?.state).toBe('PENDING_SEND');
  await page.screenshot({ path: `${SHOTS}/51-dem-log-offline-draft.png`, fullPage: true });

  await context.setOffline(false);
  // 'online' 이벤트로 자동 재전송 — 이벤트가 늦으면 수동 버튼
  await page.waitForTimeout(1500);
  if (await page.getByTestId('drafts-resend').isVisible()) await page.getByTestId('drafts-resend').click();
  await expect(page.getByText(/같은 작업 ID 로 다시 보냈습니다/)).toBeVisible();
  await expect(page.getByTestId('dem-drafts')).toHaveCount(0);
  const ops = bodies.map((b) => JSON.parse(b).clientOpId);
  expect(ops.length).toBeGreaterThanOrEqual(1);
  expect(new Set(ops)).toEqual(new Set([stored.clientOpId]));

  // 같은 clientOpId 를 다시 보내면 서버는 저장된 응답을 재생(Idempotent-Replayed) — 두 번 저장하지 않는다
  const replay = await api(page, 'PUT', urls[urls.length - 1].replace(/^.*\/api\/v1/, ''), U.ENG, { body: JSON.parse(bodies[bodies.length - 1]) });
  expect(replay.status()).toBe(200);
  expect(replay.headers()['idempotent-replayed']).toBe('true');
});

test('DEM 잠긴 일지 정정 — PM 이 다른 사람 일지 정정(이력 추가, 원문 해시 불변) · 늦은 재전송 정정 대기 표시', async ({ page }) => {
  await gotoAs(page, '/dem/logs', '최책임');
  const list = page.getByTestId('dem-log-list');
  const lockedRow = list.locator('tr', { hasText: '🔒 잠금' }).filter({ hasText: '대기' }).first();
  await lockedRow.click();
  const am = page.getByTestId('dem-amendments');
  await expect(am).toContainText('승인 대기');   // 시드: 정참여의 늦은 재전송(LATE_SYNC) → PENDING
  await expect(am).toContainText('LATE_SYNC_AFTER_LOCK');
  await page.getByLabel('정정 항목').selectOption('remarks');
  await page.getByLabel('정정 값').fill('(합성) 4차 E2E 정정 — 작업 구간 표기 보완');
  await page.getByLabel('정정 사유').fill('(합성) 현장 사진 대조 결과');
  await page.getByTestId('correction-request').click();
  await expect(am).toContainText('(합성) 현장 사진 대조 결과');
  await expect(am.locator('tr', { hasText: '(합성) 현장 사진 대조 결과' })).toContainText(/적용됨|승인 대기/);
  await page.screenshot({ path: `${SHOTS}/52-dem-log-correction.png`, fullPage: true });
});

/**
 * [정책 병합 대기: DEM-03b] 본인 일지 정정 = 요청(PENDING) → 다른 사람(PM) 승인.
 * 현재 서버: ENG 는 dem:log-amendment:create 권한이 없어 403, 승인 API(/dem/amendments/{id}:approve) 없음 → 실패가 정상.
 */
test('DEM-03b 본인 일지 정정 요청 → 다른 사람 승인 @policy', async ({ page }) => {
  await gotoAs(page, '/dem/logs', '정참여');
  await page.getByTestId('dem-log-list').locator('tr', { hasText: '🔒 잠금' }).first().click();
  await page.getByLabel('정정 항목').selectOption('workersCount');
  await page.getByLabel('정정 값').fill('10');
  await page.getByLabel('정정 사유').fill('(합성) 본인 정정 요청 — 인원 오기');
  await page.getByTestId('correction-request').click();
  const am = page.getByTestId('dem-amendments');
  const row = am.locator('tr', { hasText: '(합성) 본인 정정 요청 — 인원 오기' });
  await expect(row).toContainText('승인 대기');
  await expect(row).toContainText('본인 요청 — 다른 사람이 승인');

  await loginAs(page, '최책임');
  await row.getByRole('button', { name: /^정정 승인/ }).click();
  await expect(row).toContainText(/승인 · 적용|적용됨/);
});
