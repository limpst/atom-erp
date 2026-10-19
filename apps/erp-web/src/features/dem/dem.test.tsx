import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { constructionBlockers, credentialWarning, type DemDocument, type RevisionStatus } from '@/api/dem';
import { todayIso } from '@/features/admin/common';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { DemPage } from './DemPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);

const PM = devUserId(4);
const ENG = devUserId(5);
const PM2 = devUserId(10);
const QA = devUserId(6);
const today = todayIso();
const plus = (n: number) => {
  const d = new Date(`${today}T00:00:00`);
  d.setDate(d.getDate() + n);
  return d.toLocaleDateString('sv-SE');
};
const project = {
  projectId: 'p-dem', buildingId: 'b1', status: 'IN_PROGRESS', completedOn: null, version: 4,
  workPackages: [
    { id: 'wp-sup', packageType: 'DEM_SUPERVISION', facilityId: 'b1', status: 'DRAFT', locale: 'ko', currency: 'KRW', supervisorGap: false },
    { id: 'wp-str', packageType: 'STR_DESIGN', facilityId: 'b1', status: 'DRAFT', locale: 'ko', currency: 'KRW', supervisorGap: false },
  ],
};
const snap = (typeCode: string, status: string, expiresOn: string | null) => ({
  checkedOn: today, requiredAnyOf: ['ARCH_ENG'], qualified: true, reasons: [],
  credentials: [{ credentialId: `c-${typeCode}`, typeCode, status, expiresOn, expiredEventReceived: false, valid: true }],
});
const rev = (id: string, docNo: string, code: string, status: RevisionStatus) => ({
  id, documentId: `d-${docNo}`, docNo, revCode: code, issuedRev: status === 'ISSUED' ? '0' : null, status, fileAttachmentId: 'f',
  changeSummary: '(합성)', approvedBy: PM, transmittalId: null, issuedAt: null,
});
const docs: DemDocument[] = [
  { id: 'd-S-101', workPackageId: 'wp-str', docNo: 'S-101', title: '(합성) 지하 1층', discipline: 'STR', isInput: false, unresolvedComments: 1,
    revisions: [rev('r101a', 'S-101', 'A', 'ISSUED'), rev('r101b', 'S-101', 'B', 'APPROVED')] },
  { id: 'd-S-102', workPackageId: 'wp-str', docNo: 'S-102', title: '(합성) 지상 1층', discipline: 'STR', isInput: false, unresolvedComments: 0,
    revisions: [rev('r102a', 'S-102', 'A', 'APPROVED')] },
];

useMswServer(
  mswHttp.get('*/api/v1/dem/projects', () => HttpResponse.json([project])),
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p-dem', projectNo: 'PRJ-2026-0002', name: '(합성) 푸른아파트 해체공사 감리' }])),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([
    { id: ENG, empNo: 'D-005', name: '정참여', status: 'ACTIVE' }, { id: PM2, empNo: 'D-010', name: '문책임', status: 'ACTIVE' },
    { id: QA, empNo: 'D-006', name: '한품질', status: 'ACTIVE' },
  ])),
  mswHttp.get('*/api/v1/hrq/employees/:id', () => HttpResponse.json({
    credentials: [{ id: 'cq', typeCode: 'SAFETY_DIAG_CERT', certNo: 'SYN', expiresOn: plus(-30), status: 'EXPIRED' }],
  })),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/supervisors', () => HttpResponse.json({
    assignments: [
      { id: 'a1', workPackageId: 'wp-sup', employeeId: ENG, role: 'CHIEF', validFrom: plus(-10), validTo: null, status: 'ACTIVE', releaseReason: null, version: 0, hrqCheckSnapshot: snap('ARCH_ENG', 'VALID', null) },
      { id: 'a2', workPackageId: 'wp-sup', employeeId: PM2, role: 'ASSISTANT', validFrom: plus(-10), validTo: null, status: 'ACTIVE', releaseReason: null, version: 0, hrqCheckSnapshot: snap('SAFETY_DIAG_CERT', 'EXPIRING', plus(20)) },
    ],
    alerts: [],
  })),
  mswHttp.post('*/api/v1/dem/work-packages/:wp/supervisors', async ({ request }) => {
    await record(request);
    return problem(422, 'DEM-422-002', '감리 자격 없음', '감리 자격 확인 실패', { reasons: ['CREDENTIAL_EXPIRED', 'NO_VALID_QUALIFICATION'] });
  }),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/daily-logs', () => HttpResponse.json([
    { id: 'l3', workPackageId: 'wp-sup', logDate: today, authorId: ENG, phaseCode: 'UPPER_DEMOLITION', progressPct: 55, workersCount: 12, status: 'SUBMITTED', locked: false, lateSync: false, lockedAt: null, contentSha256: null, amendments: 0, pendingAmendments: 0, version: 0 },
    { id: 'l2', workPackageId: 'wp-sup', logDate: plus(-1), authorId: ENG, phaseCode: 'UPPER_DEMOLITION', progressPct: 40, workersCount: 12, status: 'LOCKED', locked: true, lateSync: false, lockedAt: `${today}T00:00:00Z`, contentSha256: 'a'.repeat(64), amendments: 1, pendingAmendments: 1, version: 1 },
  ])),
  mswHttp.get('*/api/v1/dem/daily-logs/:id', () => HttpResponse.json({
    id: 'l2', workPackageId: 'wp-sup', logDate: plus(-1), authorId: ENG, phaseCode: 'UPPER_DEMOLITION', progressPct: 40, workersCount: 12,
    status: 'LOCKED', lateSync: false, lockedAt: `${today}T00:00:00Z`, contentSha256: 'a'.repeat(64), version: 1, remarks: '(합성) 상부 해체',
    weather: null, equipment: null, amendments: [], checklist: [{ id: 'c1', seq: 3, title: '해체 순서 준수', result: 'FAIL', severity: 'MEDIUM', note: null, correctiveOrderNo: 'CO-2026-0001' }],
  })),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/documents', () => HttpResponse.json(docs)),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/transmittals', () => HttpResponse.json([])),
  mswHttp.get('*/api/v1/dem/revisions/:id/comments', () => HttpResponse.json([
    { id: 'cm1', revisionId: 'r101a', commentNo: 'C-001', source: 'CLIENT', severity: 'MAJOR', body: '(합성) 피복 근거', status: 'RESPONDED', unresolved: true, authorId: QA, replies: [] },
  ])),
  mswHttp.post('*/api/v1/dem/comments/:id/replies', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ id: 'cm1', revisionId: 'r101a', commentNo: 'C-001', source: 'CLIENT', severity: 'MAJOR', body: '', status: 'ACCEPTED', unresolved: false, authorId: QA, replies: [] });
  }),
  mswHttp.post('*/api/v1/dem/transmittals', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ id: 't2', transmittalNo: 'TR-2026-0002', purpose: 'FOR_CONSTRUCTION', recipientName: '', issuedAt: '', revisions: [] }, { status: 201 });
  }),
);

describe('DEM 유틸', () => {
  it('자격 경고: 만료 · 30일 이내 임박 · 정상', () => {
    expect(credentialWarning({ status: 'EXPIRED', expiresOn: '2026-01-01', expiredEventReceived: false }, '2026-10-19')?.label).toBe('자격 만료');
    expect(credentialWarning({ status: 'VALID', expiresOn: null, expiredEventReceived: true }, '2026-10-19')?.tone).toBe('danger');
    expect(credentialWarning({ status: 'EXPIRING', expiresOn: '2026-11-08', expiredEventReceived: false }, '2026-10-19')?.label).toBe('자격 만료 임박(D-20)');
    expect(credentialWarning({ status: 'VALID', expiresOn: '2027-12-31', expiredEventReceived: false }, '2026-10-19')).toBeNull();
  });
  it('시공용 발행 차단: 선택 리비전의 도서에 미결 의견이 있으면', () => {
    expect(constructionBlockers(docs, ['r101b']).map((d) => d.docNo)).toEqual(['S-101']);
    expect(constructionBlockers(docs, ['r102a'])).toEqual([]);
  });
});

describe('해체 · 감리 화면', () => {
  it('감리자: 자격 만료 임박 경고, 만료 자격 직원 지정은 422 사유를 화면 안에', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <DemPage />);
    const table = await screen.findByTestId('dem-supervisors');
    expect(await within(table).findByText('D-005 정참여')).toBeInTheDocument();
    expect(within(table).getByText(/자격 만료 임박\(D-20\)/)).toHaveClass('warn');
    expect(screen.getByTestId('dem-cred-warning')).toHaveTextContent('1명');
    await user.selectOptions(screen.getByTestId('dem-assign-employee'), QA);
    expect(await screen.findByTestId('dem-candidate-creds')).toHaveTextContent('자격 만료');
    await user.click(screen.getByTestId('dem-assign'));
    const refused = await screen.findByTestId('dem-assign-refused');
    expect(refused).toHaveTextContent('[DEM-422-002]');
    expect(refused).toHaveTextContent('자격 만료, 유효한 감리 자격 없음');
    expect(toasts.error).toHaveLength(0);
    expect(seen[0].body).toMatchObject({ employeeId: QA, role: 'ASSISTANT', validFrom: today });
  });

  it('감리일지: 잠금/잠금 전 구분과 잠긴 일지 상세(읽기 전용)', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <DemPage />);
    await user.click(await screen.findByRole('tab', { name: '감리일지' }));
    const logs = await screen.findByTestId('dem-logs');
    expect(await within(logs).findByText('🔒 잠금')).toBeInTheDocument();
    expect(within(logs).getByText('제출(잠금 전)')).toBeInTheDocument();
    expect(within(logs).getByText('1 (대기 1)')).toBeInTheDocument();
    await user.click(within(logs).getByText('🔒 잠금'));
    expect(await screen.findByText(/잠긴 일지입니다\(읽기 전용\)/)).toBeInTheDocument();
    expect(screen.getByText('CO-2026-0001')).toBeInTheDocument();
  });

  it('도서: 미결 의견이 있으면 시공용 발행 버튼이 막히고, 없는 도서는 발행 · 의견 회신', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <DemPage />);
    await user.click(await screen.findByRole('button', { name: '구조 설계' }));
    await user.click(screen.getByRole('tab', { name: /도서/ }));
    await screen.findByTestId('dem-docs');
    await user.click(await screen.findByLabelText('발행 S-101 B'));
    expect(screen.getByTestId('dem-fc-gate')).toHaveTextContent('S-101 1건');
    expect(screen.getByTestId('dem-issue')).toBeDisabled();
    // 검토용은 막지 않는다
    await user.selectOptions(screen.getByLabelText('발행 목적'), 'FOR_REVIEW');
    expect(screen.queryByTestId('dem-fc-gate')).toBeNull();
    await user.selectOptions(screen.getByLabelText('발행 목적'), 'FOR_CONSTRUCTION');
    await user.click(screen.getByLabelText('발행 S-101 B'));
    await user.click(screen.getByLabelText('발행 S-102 A'));
    await user.click(screen.getByTestId('dem-issue'));
    await waitFor(() => expect(toasts.success[0]).toContain('TR-2026-0002 시공용 발행'));
    expect(seen[0].body).toMatchObject({ workPackageId: 'wp-str', purpose: 'FOR_CONSTRUCTION', revisionIds: ['r102a'] });

    await user.click(screen.getByRole('button', { name: '의견 S-101 A' }));
    const comments = await screen.findByTestId('dem-comments');
    await user.click(await within(comments).findByRole('button', { name: 'C-001 수용' }));
    await waitFor(() => expect(seen[1]?.body).toMatchObject({ resultingStatus: 'ACCEPTED' }));
  });
});
