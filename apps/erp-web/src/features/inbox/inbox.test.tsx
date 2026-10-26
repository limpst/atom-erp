import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { sourcesFor } from '@/api/inbox';
import { problem, renderAs, seen, record, useMswServer } from '@/test/mocks';
import { InboxBadge, InboxPage } from './InboxPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const EXEC = devUserId(1);
const PM = devUserId(4);
const AUDITOR = devUserId(7);
const ENG = devUserId(5);

useMswServer(
  mswHttp.get('*/api/v1/sal/inbox/discount-approvals', async ({ request }) => {
    await record(request);
    return HttpResponse.json([{ estimateId: 'e1', estimateNo: 'EST-2026-0002', versionNo: 1, opportunityId: 'o1', oppNo: 'OPP-2026-0006', opportunityTitle: '(합성) 겸직 할인 B', discountRate: 0.08, discountLimit: 0.05, offerAmount: 1, createdBy: devUserId(14), requestedAt: '2026-10-26T01:00:00Z' }]);
  }),
  mswHttp.get('*/api/v1/cst/inbox/budget-approvals', () => HttpResponse.json([{ revisionId: 'b1', projectId: 'p4', revNo: 0, status: 'DRAFT', bacKrw: 15543260, contractAmountKrw: 37938000, expectedMarginPct: 59.03, createdBy: devUserId(0), requestedAt: null, version: 0 }])),
  mswHttp.get('*/api/v1/cst/rates', () => HttpResponse.json([
    { id: 'r1', rateKind: 'STD_LABOR', keyCode: 'INTER', value: 300000, validFrom: '2026-10-12', validTo: null, sourceRef: '', version: 0, status: 'DRAFT', createdBy: devUserId(9), createdAt: null },
    { id: 'r2', rateKind: 'STD_LABOR', keyCode: 'SENIOR', value: 1, validFrom: '2000-01-01', validTo: null, sourceRef: '', version: 1, status: 'APPROVED', createdBy: devUserId(9), createdAt: null },
    { id: 'r3', rateKind: 'STD_LABOR', keyCode: 'MASTER', value: 2, validFrom: '2026-11-01', validTo: null, sourceRef: '', version: 0, status: 'DRAFT', createdBy: EXEC, createdAt: null },
  ])),
  mswHttp.get('*/api/v1/fcd/rule-sets', () => HttpResponse.json([
    { version: '2026.6.0-dev', name: 'fcd', effective_from: '2027-01-01', effective_to: null, status: 'DRAFT', authored_by: devUserId(8), approved_by: null, sha256: 'x', source: '' },
    { version: '2026.9.1-mine', name: 'fcd', effective_from: '2027-01-01', effective_to: null, status: 'DRAFT', authored_by: AUDITOR, approved_by: null, sha256: 'y', source: '' },
    { version: '2026.5.0', name: 'fcd', effective_from: '2026-01-01', effective_to: null, status: 'ACTIVE', authored_by: devUserId(8), approved_by: EXEC, sha256: 'z', source: '' },
  ])),
  mswHttp.get('*/api/v1/fcd/cases', () => HttpResponse.json([
    { id: 'c1', case_no: 'FCD-2026-0001', status: 'SUBMITTED', grade: 'HIGH', score: 80, subject_employee_id: ENG, due_on: '2026-11-01', audit_sample: false, score_dropped: false },
    { id: 'c2', case_no: 'FCD-2026-0002', status: 'EXPLANATION_REQUESTED', grade: 'HIGH', score: 70, subject_employee_id: ENG, due_on: '2026-11-02', audit_sample: false, score_dropped: false },
  ])),
  mswHttp.get('*/api/v1/aip/models', () => HttpResponse.json([
    { id: 'm1', name: 'crack-seg', version: '2.0.0', base_model: null, stage: 'CANARY', artifact_uri: '', approved_by: 'qa', exec_approved_by: null, deployments: [] },
  ])),
  mswHttp.get('*/api/v1/hrq/inbox/timesheet-approvals', () => HttpResponse.json([{ employeeId: devUserId(15), empNo: 'D-015', employeeName: '겸직책임', projectId: 'p1-aaaaaaaa', isoWeek: '2026-W44', entries: 2, hours: 4, firstDate: '2026-10-26', submittedAt: null }])),
  mswHttp.get('*/api/v1/qar/inbox/assignments', () => problem(403, 'CORE-403-002', '범위 밖', '프로젝트 범위 밖')),
  mswHttp.get('*/api/v1/dem/projects', () => HttpResponse.json([{ projectId: 'pd', buildingId: null, status: 'IN_PROGRESS', completedOn: null, version: 0, workPackages: [
    { id: 'wp-sup', packageType: 'DEM_SUPERVISION', facilityId: null, status: 'DRAFT', locale: 'ko', currency: 'KRW', supervisorGap: false },
  ] }])),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/daily-logs', () => HttpResponse.json([
    { id: 'l1', workPackageId: 'wp-sup', logDate: '2026-10-25', authorId: ENG, phaseCode: 'UPPER_DEMOLITION', progressPct: 1, workersCount: 1, status: 'LOCKED', locked: true, lateSync: false, lockedAt: null, contentSha256: null, amendments: 1, pendingAmendments: 1, version: 1 },
  ])),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/documents', () => HttpResponse.json([
    { id: 'd1', workPackageId: 'wp-sup', docNo: 'S-201', title: '(합성) 도면', discipline: 'STR', isInput: false, unresolvedComments: 0,
      revisions: [{ id: 'rv1', documentId: 'd1', docNo: 'S-201', revCode: 'A', issuedRev: null, status: 'INTERNAL_REVIEW', fileAttachmentId: 'f', changeSummary: null, approvedBy: null, transmittalId: null, issuedAt: null }] },
  ])),
  mswHttp.get('*/api/v1/dem/daily-logs/:id', () => HttpResponse.json({ id: 'l1', amendments: [
    { id: 'a1', fieldPath: '$', oldValue: null, newValue: {}, reason: 'LATE_SYNC_AFTER_LOCK', status: 'PENDING', amendedBy: ENG, amendedAt: '2026-10-26T00:00:00Z' },
  ] })),
);

describe('내 할 일 출처', () => {
  it('역할별 출처: EXEC 는 할인 · 예산 · 요율 · 룰셋 · 모델, ENG 는 QAR · 법인카드 소명만', () => {
    expect(sourcesFor(['EXEC']).map((s) => s.id)).toEqual(['sal-discount', 'cst-budget', 'cst-rate', 'qar-assignment', 'fcd-ruleset', 'aip-promotion']);
    expect(sourcesFor(['ENG', 'EMPLOYEE']).map((s) => s.id)).toEqual(['qar-assignment', 'fcd-case']);
    expect(sourcesFor(['SALES'])).toEqual([]);
  });
});

describe('내 할 일 화면', () => {
  it('EXEC: 모듈별 항목 · 링크, 본인이 만든 요율 초안은 제외, 403 출처는 조용히 0건', async () => {
    renderAs(EXEC, ['EXEC'], <InboxPage />);
    const table = await screen.findByTestId('inbox-items');
    const sal = await within(table).findByRole('link', { name: 'EST-2026-0002 v1 — 할인 8% 승인' });
    expect(sal).toHaveAttribute('href', '/sal/estimates?opp=o1');
    expect(await within(table).findByRole('link', { name: '실행예산 Rev.0 결재 (DRAFT)' })).toHaveAttribute('href', '/projects/p4');
    expect(await within(table).findByText('STD_LABOR INTER 300,000 승인')).toBeInTheDocument();
    expect(within(table).queryByText(/MASTER/)).toBeNull();
    expect(await within(table).findByText('룰셋 2026.6.0-dev 승인')).toBeInTheDocument();
    expect(await within(table).findByText('crack-seg 2.0.0 → PRODUCTION 승인(EXEC)')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('inbox-src-qar-assignment')).toHaveTextContent('QAR 검토 배정 0'));
    expect(seen.filter((s) => s.url.endsWith('/sal/inbox/discount-approvals'))).toHaveLength(1);
  });

  it('AUDITOR: 본인이 쓴 룰셋은 4-eyes 로 빠지고, 소명 제출된 카드 건은 판정 대기', async () => {
    renderAs(AUDITOR, ['AUDITOR'], <InboxPage />);
    const table = await screen.findByTestId('inbox-items');
    expect(await within(table).findByText('룰셋 2026.6.0-dev 승인')).toBeInTheDocument();
    expect(within(table).queryByText(/2026\.9\.1-mine/)).toBeNull();
    expect(await within(table).findByText('FCD-2026-0001 판정 대기')).toBeInTheDocument();
    expect(within(table).queryByText('FCD-2026-0002 판정 대기')).toBeNull();
  });

  it('PM: 타임시트(내가 책임자) · 다른 사람의 정정 요청 · 내부 검토 리비전, 헤더 배지 건수', async () => {
    renderAs(PM, ['PM'], <><InboxBadge /><InboxPage /></>);
    const table = await screen.findByTestId('inbox-items');
    expect(await within(table).findByRole('link', { name: 'D-015 겸직책임 — 2026-W44 2건 · 4시간' })).toHaveAttribute('href', `/hrq/timesheets?emp=${devUserId(15)}&week=2026-W44`);
    expect(await within(table).findByRole('link', { name: '감리일지 2026-10-25 정정 요청 1건' })).toHaveAttribute('href', '/dem/logs?wp=wp-sup&log=l1');
    expect(await within(table).findByText('S-201 Rev.A 내부 검토 승인')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('inbox-badge')).toHaveTextContent('내 할 일 3'));
  });

  it('ENG(정정 요청자 본인): 자기 정정 요청은 수신함에 없고, 소명 요청 건이 보인다', async () => {
    renderAs(ENG, ['ENG', 'EMPLOYEE'], <InboxPage />);
    const table = await screen.findByTestId('inbox-items');
    expect(await within(table).findByText('FCD-2026-0002 소명 요청')).toBeInTheDocument();
    expect(within(table).queryByText(/정정 요청/)).toBeNull();
  });
});
