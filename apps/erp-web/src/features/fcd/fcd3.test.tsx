import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { csvCell, filenameFrom, toSafeCsv } from '@/api/download';
import { safeMasked, type RuleSetRow } from '@/api/fcd';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { approvalBlock, effectiveVersion, FcdRulesetsPage } from './FcdRulesetsPage';
import { FcdImportsPage } from './FcdImportsPage';
import { FcdCardsPage } from './FcdCardsPage';
import { FcdCasesPage } from './FcdCasesPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);

const FIN = devUserId(8);
const AUDITOR = devUserId(7);
const EXEC = devUserId(1);
const EMP = devUserId(5);
const TXNS = [
  { id: 't-old', masked_no: '999999******1234', employee_id: null, approval_no: '31000005', approved_at: '2026-05-12T12:10:00', amount_krw: 38000, cancelled_amount_krw: 0, status: 'APPROVED', merchant_name: '(예시)한식당', risk_score: 10, risk_grade: 'NORMAL' },
  { id: 't-new', masked_no: '999999******1234', employee_id: null, approval_no: null, approved_at: '2026-05-12T00:00:00', amount_krw: 38000, cancelled_amount_krw: 0, status: 'PURCHASED', merchant_name: '(예시)한식당본점', risk_score: null, risk_grade: null },
];
const rs = (over: Partial<RuleSetRow>): RuleSetRow => ({
  version: '2026.5.0', name: 'fcd', effective_from: '2026-04-01', effective_to: null, status: 'ACTIVE', authored_by: 'FIN-001',
  approved_by: 'AUD-005', sha256: 'a'.repeat(64), source: 'SEED', ...over,
});
let rulesets: RuleSetRow[] = [];
const ERR_CSV = '﻿행번호,컬럼,오류코드,원문값,메시지\r\n5,approved_date,DATE_INVALID,2026.13.45,날짜 형식\r\n6,amount,AMOUNT_INVALID,\'=1+2,금액 형식\r\n';

const server = useMswServer(
  mswHttp.get('*/api/v1/fcd/rule-sets', () => HttpResponse.json(rulesets)),
  mswHttp.post('*/api/v1/fcd/rule-sets', async ({ request }) => {
    await record(request);
    rulesets = [...rulesets, rs({ version: '2026.9.0-t', status: 'DRAFT', authored_by: AUDITOR, approved_by: null, source: 'API', effective_from: '2027-01-01' })];
    return HttpResponse.json({ version: '2026.9.0-t', status: 'DRAFT', rules: 31 }, { status: 201 });
  }),
  mswHttp.post('*/api/v1/fcd/rule-sets/:v', async ({ request, params }) => {
    await record(request);
    const version = String(params.v).replace(/:approve$/, '');
    const actor = request.headers.get('X-Actor-Id');
    const row = rulesets.find((r) => r.version === version)!;
    if (row.authored_by === actor) return problem(403, 'FCD-403-002', '4-eyes 위반', '4-eyes: 작성자 본인은 승인할 수 없습니다');
    rulesets = rulesets.map((r) => (r.version === version ? { ...r, status: 'ACTIVE', approved_by: actor } : r));
    return HttpResponse.json({ version, name: 'fcd', status: 'ACTIVE' });
  }),
  mswHttp.get('*/api/v1/fcd/imports', () => HttpResponse.json([
    { id: 'b1', channel: 'CSV', filename: 'statement-errors.csv', status: 'VALIDATED', file_kind: 'CSV', detected_encoding: 'UTF-8', rows_total: 4, rows_error: 2, counts: '{"NEW": 2, "ERROR": 2}', profile_code: 'P-ACARD-APPROVAL-CSV-1', uploaded_at: '2026-10-19T00:00:00Z', committed_at: null, rolled_back_at: null },
  ])),
  mswHttp.get('*/api/v1/fcd/imports/:id/errors.csv', () => new HttpResponse(ERR_CSV, {
    headers: { 'Content-Type': 'text/csv;charset=UTF-8', 'Content-Disposition': 'attachment; filename="errors-b1.csv"' },
  })),
  mswHttp.post('*/api/v1/fcd/imports/:id', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ id: 'b1', status: 'COMMITTED' });
  }),
  mswHttp.get('*/api/v1/fcd/transactions', () => HttpResponse.json(TXNS)),
  // FCD-18: 직원 본인(SELF) 응답은 요약만(hits · reasons 없음)
  mswHttp.get('*/api/v1/fcd/transactions/:id/risk', () => HttpResponse.json([
    { scored_at: '2026-05-12T03:00:00Z', rule_set_version: '2026.5.0', grade: 'EXPLAIN', hit_count: 2, summary: true },
  ])),
  mswHttp.get('*/api/v1/fcd/cases', () => HttpResponse.json([
    { id: 'c1', case_no: 'FCD-2026-000001', status: 'EXPLANATION_REQUESTED', grade: 'EXPLAIN', score: 65, subject_employee_id: EMP, due_on: '2026-05-20', audit_sample: false, score_dropped: false },
  ])),
  mswHttp.get('*/api/v1/fcd/cases/:id', () => HttpResponse.json({
    id: 'c1', case_no: 'FCD-2026-000001', status: 'EXPLANATION_REQUESTED', grade: 'EXPLAIN', score: 65, subject_employee_id: EMP,
    due_on: '2026-05-20', audit_sample: false, score_dropped: false, opened_reason: '확인이 필요한 사용이 있습니다(세부 탐지 기준은 공개하지 않습니다)',
    opened_at: '2026-05-13T00:00:00Z', suspension_candidate: false, label: null, resolution: null, resolved_by: null, resolved_at: null,
    txnIds: ['t-old'], history: [],
  })),
  mswHttp.post('*/api/v1/fcd/review-queue/:ym', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ period: '2026-05', policy: 'MONTHLY_BUDGET', nTxn: 0, budget: 0, selected: [], casesOpened: [], override: true });
  }),
  mswHttp.get('*/api/v1/fcd/dedup-candidates', () => HttpResponse.json([
    { id: 'dc-1234567', source_id: 's', txn_id: 't-old', new_txn_id: 't-new', score: 0.861, features: '{"time":0.8,"amount":1.0,"merchant":0.93}', decision: null, created_at: '2026-10-19T00:00:00Z' },
  ])),
  mswHttp.post('*/api/v1/fcd/dedup-candidates/:id', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ id: 'dc-1234567', decision: 'MERGE' });
  }),
  mswHttp.get('*/api/v1/fcd/cards', () => HttpResponse.json([
    { id: 'k1', card_company: 'ACARD', masked_no: '999999******1234', alias: null, card_type: 'PERSONAL_ASSIGNED', holder_employee_id: null, dept: '진단1팀', limit_per_txn: 300000, limit_daily: 450000, limit_monthly: 5000000, status: 'ACTIVE', deactivation_flag: true, deactivation_reason: '퇴사 예정' },
  ])),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([])),
);

describe('CSV · 다운로드 · 마스킹 유틸', () => {
  it('수식 주입 무력화(= + - @ 탭 CR) + RFC 4180 인용 + BOM', () => {
    expect(csvCell('=HYPERLINK("http://x")')).toBe('"\'=HYPERLINK(""http://x"")"');
    expect(csvCell('+1')).toBe("'+1");
    expect(csvCell('-5')).toBe("'-5");
    expect(csvCell('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvCell('\tx')).toBe("'\tx");
    expect(csvCell('a,b')).toBe('"a,b"');
    expect(csvCell(null)).toBe('');
    expect(csvCell(12)).toBe('12');
    const csv = toSafeCsv(['파일', '상태'], [['=cmd', 'OK']]);
    expect(csv.startsWith('﻿파일,상태\r\n')).toBe(true);
    expect(csv).toContain("'=cmd,OK");
  });
  it('Content-Disposition 파일명(filename* 우선)', () => {
    expect(filenameFrom("attachment; filename*=UTF-8''B01-1F.dxf; filename=\"x.dxf\"", 'f')).toBe('B01-1F.dxf');
    expect(filenameFrom('attachment; filename="errors-1.csv"', 'f')).toBe('errors-1.csv');
    expect(filenameFrom(null, 'fallback.bin')).toBe('fallback.bin');
  });
  it('카드 번호는 마스킹 값만, 긴 숫자열은 다시 가린다', () => {
    expect(safeMasked('999999******1234')).toBe('999999******1234');
    expect(safeMasked('9999990000001234')).toBe('999999******1234');
    expect(safeMasked(null)).toBe('—');
  });
  it('4-eyes 판정 · 오늘 적용 버전', () => {
    const d = rs({ status: 'DRAFT', authored_by: AUDITOR });
    expect(approvalBlock(d, AUDITOR, true)).toContain('작성자 본인');
    expect(approvalBlock(d, EXEC, true)).toBeNull();
    expect(approvalBlock(d, FIN, false)).toContain('권한 없음');
    expect(approvalBlock(rs({}), EXEC, true)).toContain('상태가 아님');
    expect(effectiveVersion([rs({ version: '2026.4.0' }), rs({}), rs({ version: '2027.1.0', effective_from: '2027-01-01' })], '2026-10-19')).toBe('2026.5.0');
  });
});

describe('탐지 룰셋 4-eyes', () => {
  it('작성자(AUDITOR)는 승인 버튼이 막히고, 다른 승인자(EXEC)는 승인', async () => {
    rulesets = [rs({})];
    const user = userEvent.setup();
    const view = renderAs(AUDITOR, ['AUDITOR'], <FcdRulesetsPage />);
    await screen.findByTestId('rs-2026.5.0');
    await user.type(screen.getByLabelText('룰셋 YAML'), 'ruleset: fcd');
    await user.click(screen.getByTestId('rs-draft'));
    await waitFor(() => expect(toasts.success[0]).toContain('초안 등록 — 2026.9.0-t (규칙 31개)'));
    expect(seen[0].headers['content-type']).toBe('application/yaml');
    const row = await screen.findByTestId('rs-2026.9.0-t');
    expect(within(row).getByRole('button', { name: '승인 2026.9.0-t' })).toBeDisabled();
    expect(within(row).getByTestId('rs-block-2026.9.0-t')).toHaveTextContent('작성자 본인 — 승인 불가(4-eyes)');
    view.unmount();

    renderAs(EXEC, ['EXEC'], <FcdRulesetsPage />);
    const row2 = await screen.findByTestId('rs-2026.9.0-t');
    await user.click(within(row2).getByRole('button', { name: '승인 2026.9.0-t' }));
    await waitFor(() => expect(toasts.success.at(-1)).toContain('승인 — 2026.9.0-t ACTIVE'));
    expect(seen.at(-1)!.url).toBe('/api/v1/fcd/rule-sets/2026.9.0-t:approve');
    expect(seen.at(-1)!.search).toBe('?name=fcd');
  });

  it('서버 4-eyes 거부(FCD-403-002)는 problem+json 토스트', async () => {
    // 화면 판정을 우회한 경우(예: 다른 탭에서 작성자로 바뀜)에도 서버가 막는다
    rulesets = [rs({ version: '2026.9.1-t', status: 'DRAFT', authored_by: 'someone-else', approved_by: null })];
    const user = userEvent.setup();
    renderAs(AUDITOR, ['AUDITOR'], <FcdRulesetsPage />);
    const row = await screen.findByTestId('rs-2026.9.1-t');
    rulesets = [{ ...rulesets[0], authored_by: AUDITOR }];
    await user.click(within(row).getByRole('button', { name: '승인 2026.9.1-t' }));
    await waitFor(() => expect(toasts.error[0]?.title).toBe('[FCD-403-002] 4-eyes 위반'));
  });
});

describe('가져오기 · 중복 확인', () => {
  it('행 오류 리포트 CSV 를 받아 앞부분 미리보기(수식 칸은 \' 로 무력화)', async () => {
    const user = userEvent.setup();
    renderAs(FIN, ['FIN'], <FcdImportsPage />);
    const grid = await screen.findByTestId('import-grid');
    await user.click(await within(grid).findByRole('button', { name: '오류 리포트 statement-errors.csv' }));
    const pv = await screen.findByTestId('error-csv-preview');
    expect(pv).toHaveTextContent('errors-b1.csv');
    expect(pv).toHaveTextContent("'=1+2");
    expect(pv).not.toHaveTextContent('﻿');
    await user.click(within(grid).getByRole('button', { name: '확정 statement-errors.csv' }));
    await waitFor(() => expect(seen.find((s) => s.url.endsWith(':commit'))).toBeTruthy());
  });

  it('중복 확인 큐: 두 거래 비교 → 병합', async () => {
    const user = userEvent.setup();
    renderAs(FIN, ['FIN'], <FcdImportsPage />);
    await waitFor(() => expect(screen.getByTestId('dedup-count')).toHaveTextContent('1건'));
    await user.click(screen.getByRole('tab', { name: /중복 확인 큐/ }));
    const q = screen.getByTestId('dedup-queue');
    expect(await within(q).findByText('(예시)한식당본점')).toBeInTheDocument();
    expect(within(q).getByText('0.861')).toBeInTheDocument();
    expect(q).toHaveTextContent('가맹점 0.93');
    await user.click(within(q).getByRole('button', { name: '병합 dc-12345' }));
    await waitFor(() => expect(seen.find((s) => s.url.endsWith(':decide'))?.body).toEqual({ decision: 'MERGE' }));
  });
});

describe('보안 검토 정책(FCD-11 · 13 · 18)', () => {
  it('FCD-11: 본인 카드 거래 중복 후보는 병합 · 별개 유지가 막히고 직무 분리 안내', async () => {
    server.use(mswHttp.get('*/api/v1/fcd/transactions', () => HttpResponse.json(TXNS.map((t) => ({ ...t, employee_id: FIN })))));
    const user = userEvent.setup();
    renderAs(FIN, ['FIN'], <FcdImportsPage />);
    await waitFor(() => expect(screen.getByTestId('dedup-count')).toHaveTextContent('1건'));
    await user.click(screen.getByRole('tab', { name: /중복 확인 큐/ }));
    const q = screen.getByTestId('dedup-queue');
    expect(await within(q).findByTestId('dedup-sod-dc-1234567')).toHaveTextContent('직무 분리');
    expect(within(q).getByRole('button', { name: '병합 dc-12345' })).toBeDisabled();
    expect(within(q).getByRole('button', { name: '별개 유지 dc-12345' })).toBeDisabled();
  });

  it('FCD-18: 직원 본인 케이스 화면은 룰 적중 요약만(규칙 코드 · 메시지 없음)', async () => {
    const user = userEvent.setup();
    renderAs(EMP, ['EMPLOYEE'], <FcdCasesPage />);
    const grid = await screen.findByTestId('cases-grid');
    await user.click(await within(grid).findByText('FCD-2026-000001'));
    expect(await screen.findByTestId('hit-summary-t-old')).toHaveTextContent('확인이 필요한 항목 2건 (세부 탐지 기준 비공개)');
    expect(screen.getByText(/세부 탐지 기준은 공개하지 않습니다/)).toBeInTheDocument();
    expect(screen.queryByText('검토 큐 실행')).toBeNull();
  });

  it('FCD-13: 검토 파라미터 재정의는 감사만 · 사유가 있어야 실행', async () => {
    const user = userEvent.setup();
    const view = renderAs(FIN, ['FIN'], <FcdCasesPage />);
    await screen.findByText('검토 큐 실행');
    expect(screen.queryByLabelText(/재정의 사유/)).toBeNull();
    view.unmount();

    renderAs(AUDITOR, ['AUDITOR'], <FcdCasesPage />);
    await user.type(await screen.findByLabelText(/재정의: 1,000건당 k/), '50');
    const runBtn = screen.getByRole('button', { name: '검토 큐 실행' });
    expect(runBtn).toBeDisabled();
    await user.type(screen.getByLabelText(/재정의 사유/), '합성 월말 점검');
    await user.click(runBtn);
    await waitFor(() => expect(seen.find((x) => x.url.endsWith(':run'))?.body)
      .toEqual({ policy: 'THRESHOLD', kPer1000: 50, reason: '합성 월말 점검' }));
  });
});

describe('법인카드 목록', () => {
  it('마스킹 번호 · 비활성 권고', async () => {
    renderAs(FIN, ['FIN'], <FcdCardsPage />);
    const grid = await screen.findByTestId('card-grid');
    expect(await within(grid).findByText('999999******1234')).toBeInTheDocument();
    expect(within(grid).getByText(/비활성 권고 — 퇴사 예정/)).toHaveClass('danger');
  });
});
