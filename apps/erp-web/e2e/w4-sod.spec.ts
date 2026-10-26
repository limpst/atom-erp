import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { gotoAs } from './helpers';
import { api, apiJson, etag, isoWeek, projectIdByName, U } from './helpers4';

/**
 * 직무 분리(SoD) E2E — 실제 백엔드. 화면을 우회해 작성자 · 비책임자 헤더로 직접 API 를 불러도 서버가 거부해야 한다.
 * 제목에 @policy 가 붙은 시험은 다른 에이전트가 넣는 서버 정책(HRQ-01 · CST-01 · SAL-06)이 병합돼야 통과한다
 * (지금 실패하는 것이 정상 — `npx playwright test --grep-invert @policy` 로 제외 가능).
 */

interface TsRow { id: string; projectId: string; workType: string; status: string; employeeId: string }

async function e15Entries(page: Page) {
  return apiJson<TsRow[]>(page, 'GET', `/hrq/timesheets/${U.PM_ENG_EXEC.id}/weeks/${isoWeek()}`, U.PM_ENG_EXEC);
}

// ------------------------------------------------------------------ FCD 룰셋 4-eyes(이미 서버에 있음)
test('SoD FCD — 룰셋 작성자 본인 승인 403 FCD-403-002, 다른 승인자 승인', async ({ page }) => {
  await page.goto('/');
  const version = `2026.9.${Date.now() % 100000}-sod`;
  const src = readFileSync(resolve(process.cwd(), '../../modules/fcd/src/main/resources/fcd/rulesets/fcd-2026.5.0.yaml'), 'utf-8')
    .replace(/^version: .*$/m, `version: ${version}`).replace(/^effective_from: .*$/m, "effective_from: '2027-01-01'")
    .replace(/^status: .*$/m, 'status: DRAFT');
  const draft = await api(page, 'POST', '/fcd/rule-sets', U.AUDITOR, { raw: src, contentType: 'application/yaml' });
  expect(draft.status(), await draft.text()).toBeLessThan(300);
  const own = await api(page, 'POST', `/fcd/rule-sets/${version}:approve?name=fcd`, U.AUDITOR);
  expect(own.status()).toBe(403);
  expect((await own.json()).code).toBe('FCD-403-002');
  const other = await api(page, 'POST', `/fcd/rule-sets/${version}:approve?name=fcd`, U.EXEC);
  expect(other.status(), await other.text()).toBe(200);
});

// ------------------------------------------------------------------ HRQ 타임시트 — 프로젝트 RESP 만, 본인 금지
test('SoD HRQ — 프로젝트 책임자(최책임 RESP)는 겸직책임의 한빛타워 타임시트를 승인한다', async ({ page }) => {
  await page.goto('/');
  const main = await projectIdByName(page, /한빛타워 정밀안전진단/);
  const field = (await e15Entries(page)).find((r) => r.projectId === main && r.workType === 'FIELD' && r.status === 'SUBMITTED');
  expect(field, '시드: 겸직책임 FIELD 3h 제출').toBeTruthy();
  const ok = await api(page, 'POST', '/hrq/timesheets/approvals', U.PM, { body: { entryIds: [field!.id] } });
  expect(ok.status(), await ok.text()).toBe(200);
});

test('SoD HRQ-01 — 같은 프로젝트 PART(문책임2)의 승인은 거부 @policy', async ({ page }) => {
  await page.goto('/');
  const main = await projectIdByName(page, /한빛타워 정밀안전진단/);
  const report = (await e15Entries(page)).find((r) => r.projectId === main && r.workType === 'REPORT' && r.status === 'SUBMITTED');
  expect(report, '시드: 겸직책임 REPORT 1h 제출').toBeTruthy();
  const r = await api(page, 'POST', '/hrq/timesheets/approvals', U.PM2, { body: { entryIds: [report!.id] } });
  expect(r.status(), await r.text()).toBe(403);
  // 화면: 문책임2 의 "내 할 일" 에도 없다(이미 통과 — 수신함 API 가 RESP 기준)
  await gotoAs(page, '/inbox', '문책임2');
  await expect(page.getByTestId('inbox-items')).not.toContainText('D-015 겸직책임');
});

test('SoD HRQ-01 — 책임자 본인 타임시트는 본인이 승인할 수 없다 @policy', async ({ page }) => {
  await page.goto('/');
  const p4 = await projectIdByName(page, /4차 직무분리 연습/);
  const own = (await e15Entries(page)).find((r) => r.projectId === p4 && r.status === 'SUBMITTED');
  expect(own, '시드: 겸직책임 연습 프로젝트 2h 제출(본인이 RESP)').toBeTruthy();
  const r = await api(page, 'POST', '/hrq/timesheets/approvals', U.PM_ENG_EXEC, { body: { entryIds: [own!.id] } });
  expect(r.status(), await r.text()).toBe(403);
});

// ------------------------------------------------------------------ SAL 할인 — 작성자 ≠ 승인자(다중 역할)
test('SoD SAL-06 — SALES+EXEC 겸직 작성자는 자기 견적 할인을 승인할 수 없다 @policy', async ({ page }) => {
  await page.goto('/');
  const opps = await apiJson<{ content: { id: string; title: string }[] }>(page, 'GET', '/sal/opportunities?size=300', U.SALES);
  const opp = opps.content.find((o) => o.title.includes('4차 겸직 할인 A'));
  expect(opp).toBeTruthy();
  const versions = await apiJson<{ id: string; status: string }[]>(page, 'GET', `/sal/opportunities/${opp!.id}/estimates`, U.SALES);
  const pending = versions.find((v) => v.status === 'PENDING_APPROVAL');
  expect(pending).toBeTruthy();
  const est = await apiJson<{ version: number }>(page, 'GET', `/sal/estimates/${pending!.id}`, U.SALES);
  const r = await api(page, 'POST', `/sal/estimates/${pending!.id}:approve-discount`, U.SALES_EXEC,
    { body: { decision: 'APPROVE' }, ifMatch: etag(est.version) });
  expect(r.status(), await r.text()).toBe(403);
  // 다른 임원은 승인할 수 있다
  const ok = await api(page, 'POST', `/sal/estimates/${pending!.id}:approve-discount`, U.EXEC,
    { body: { decision: 'APPROVE' }, ifMatch: etag(est.version) });
  expect(ok.status(), await ok.text()).toBe(200);
});

// ------------------------------------------------------------------ CST 예산 — SUBMITTED 만, 작성 · 수정자 ≠ 승인자
test('SoD CST-01 — 초안(DRAFT)은 바로 결재할 수 없고, 올린 사람 본인은 결재 불가 · 다른 결재자 승인 @policy', async ({ page }) => {
  await page.goto('/');
  const p = await projectIdByName(page, /4차 예산 결재 연습/);
  const revs = await apiJson<{ id: string; status: string; version: number }[]>(page, 'GET', `/cst/projects/${p}/budget-revisions`, U.EXEC);
  const draft = revs.find((r) => r.status === 'DRAFT');
  expect(draft, '시드: 두 번째 계약 실행예산 Rev.0 초안').toBeTruthy();
  // 1) DRAFT 바로 결재 → 거부(SUBMITTED 만)
  const direct = await api(page, 'POST', `/cst/projects/${p}/budget-revisions/${draft!.id}:approve`, U.EXEC, { ifMatch: etag(draft!.version) });
  expect(direct.status(), await direct.text()).toBeGreaterThanOrEqual(400);
  // 2) 겸직책임(PM · RESP)이 결재 요청(submit) → 같은 사람이 EXEC 로 결재 → 403
  //    ⚠️ 제출 엔드포인트 이름은 CST-01 구현에 맞춰 확인 필요(가정: POST …/budget-revisions/{id}:submit, If-Match)
  const submitted = await apiJson<{ version: number; status: string }>(page, 'POST',
    `/cst/projects/${p}/budget-revisions/${draft!.id}:submit`, U.PM_ENG_EXEC, { ifMatch: etag(draft!.version) });
  expect(submitted.status).toBe('SUBMITTED');
  const own = await api(page, 'POST', `/cst/projects/${p}/budget-revisions/${draft!.id}:approve`, U.PM_ENG_EXEC, { ifMatch: etag(submitted.version) });
  expect(own.status(), await own.text()).toBe(403);
  // 수신함에도 본인에게는 없다
  const inbox = await apiJson<{ revisionId: string }[]>(page, 'GET', '/cst/inbox/budget-approvals', U.PM_ENG_EXEC);
  expect(inbox.map((x) => x.revisionId)).not.toContain(draft!.id);
  const ok = await api(page, 'POST', `/cst/projects/${p}/budget-revisions/${draft!.id}:approve`, U.EXEC, { ifMatch: etag(submitted.version) });
  expect(ok.status(), await ok.text()).toBe(200);
});
