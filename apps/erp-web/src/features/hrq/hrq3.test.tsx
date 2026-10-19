import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { credentialChip, totalShortage, type ComplianceEvaluation, type CredentialRow, type MonthCloseRow } from '@/api/hrq';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { HrqPeoplePage } from './HrqPeoplePage';
import { HrqCompliancePage } from './HrqCompliancePage';
import { HrqMonthClosePage } from './HrqMonthClosePage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);
vi.mock('@/components/LineChart', async () => (await import('@/test/mockModules')).lineChartModule);

const ADMIN = devUserId(9);
const ENG = devUserId(5);
const day = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const cred = (over: Partial<CredentialRow>): CredentialRow => ({
  id: 'c1', employee_id: ENG, emp_no: 'D-005', employee_name: '정참여', type_code: 'SAFETY_DIAG_CERT', type_name: '안전진단 관련 민간자격(예시)',
  has_expiry: true, cert_no: '(합성) SDC-1', acquired_on: day(-3), expires_on: day(200), status: 'PENDING', reject_reason: null,
  approved_by: null, approved_at: null, created_at: day(-3), ...over,
});
let creds: CredentialRow[] = [];
const ev = (date: string, shortage: number): ComplianceEvaluation => ({
  date, allSatisfied: shortage === 0, fields: [{
    field: 'ARCH', satisfied: shortage === 0, outcomes: [
      { rule: { id: 'r1', field: 'ARCH', minGrade: 'SENIOR', minCount: 2, requiredCredential: null, legalReference: '⚠️ 예시' }, assignedEmpNos: ['D-004'], shortage },
    ],
  }],
});
let months: MonthCloseRow[] = [
  { year_month: '202608', status: 'CLOSED', total_mandays: 12.5, closed_at: '2026-09-01T00:00:00Z', closed_by: ADMIN, reopened_at: null, reopened_by: null, reopen_reason: null },
];

const server = useMswServer(
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([
    { id: ENG, empNo: 'D-005', name: '정참여', field: 'ARCH', employmentType: 'FULLTIME', status: 'ACTIVE', registeredField: true, hiredOn: '2020-03-02', resignPlannedOn: null, resignedOn: null, version: 0 },
  ])),
  mswHttp.get('*/api/v1/hrq/credentials', () => HttpResponse.json(creds)),
  mswHttp.post('*/api/v1/hrq/credentials/:id', async ({ request, params }) => {
    await record(request);
    const [id, action] = String(params.id).split(':');
    creds = creds.map((c) => (c.id === id ? { ...c, status: action === 'approve' ? 'VALID' : 'REJECTED' } : c));
    return HttpResponse.json({ status: 'OK' });
  }),
  mswHttp.get('*/api/v1/hrq/compliance', () => HttpResponse.json(ev(day(0), 1))),
  mswHttp.get('*/api/v1/hrq/compliance\\:forecast', () => HttpResponse.json({
    from: day(0), to: day(365), firstShortfall: day(0), shortage: { 'ARCH:SENIOR': 1 }, series: [ev(day(0), 1), ev(day(40), 2)],
  })),
  mswHttp.get('*/api/v1/hrq/month-close', () => HttpResponse.json(months)),
  mswHttp.post('*/api/v1/hrq/month-close/:ym', async ({ request, params }) => {
    await record(request);
    const p = String(params.ym);
    if (p.endsWith(':reopen')) {
      months = months.map((m) => ({ ...m, status: 'REOPENED', reopen_reason: 'x' }));
      return HttpResponse.json(months[0]);
    }
    return problem(409, 'HRQ-409-004', '미승인 타임시트', '미승인(DRAFT·SUBMITTED) 타임시트 2건');
  }),
);

describe('HRQ 유틸', () => {
  it('자격 만료 칩: 대기 · 무기한 · 임박 · 만료', () => {
    const t = day(0);
    expect(credentialChip({ status: 'PENDING', expires_on: day(10) }, t).label).toBe('승인 대기');
    expect(credentialChip({ status: 'VALID', expires_on: null }, t)).toMatchObject({ label: '유효 (무기한)', tone: 'ok' });
    expect(credentialChip({ status: 'EXPIRING', expires_on: day(20) }, t)).toMatchObject({ label: '만료 임박 D-20', tone: 'warn' });
    expect(credentialChip({ status: 'VALID', expires_on: day(-3) }, t)).toMatchObject({ label: '만료 D+3', tone: 'danger' });
    expect(credentialChip({ status: 'VALID', expires_on: day(200) }, t).tone).toBe('ok');
    expect(totalShortage(ev(t, 3))).toBe(3);
  });
});

describe('인력 · 자격', () => {
  it('ADMIN: 승인 대기 자격 승인 → 칩 유효, 반려는 사유 필수', async () => {
    creds = [cred({}), cred({ id: 'c2', employee_name: '문책임', emp_no: 'D-010', type_name: '건축기사(예시)', expires_on: null })];
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <HrqPeoplePage />);
    const grid = await screen.findByTestId('cred-grid');
    expect(await within(grid).findAllByText('승인 대기')).toHaveLength(2);
    expect(screen.getByTestId('kpi-pending')).toHaveTextContent('2건');
    await user.click(within(grid).getByRole('button', { name: '승인 정참여 안전진단 관련 민간자격(예시)' }));
    await waitFor(() => expect(toasts.success[0]).toContain('자격 승인'));
    expect(seen[0].url).toBe('/api/v1/hrq/credentials/c1:approve');
    expect(seen[0].headers['idempotency-key']).toBeTruthy();
    expect(await within(grid).findByText(/유효 D-/)).toHaveClass('ok');

    await user.click(within(grid).getByRole('button', { name: '반려 문책임 건축기사(예시)' }));
    const dlg = screen.getByRole('dialog', { name: '자격 반려 — 문책임' });
    expect(within(dlg).getByTestId('cred-reject-confirm')).toBeDisabled();
    await user.type(within(dlg).getByLabelText('반려 사유'), '(합성) 증빙 불명확');
    await user.click(within(dlg).getByTestId('cred-reject-confirm'));
    await waitFor(() => expect(seen[1]?.body).toEqual({ reason: '(합성) 증빙 불명확' }));
  });

  it('ADMIN 이 아니면 승인 · 반려 버튼이 없다', async () => {
    creds = [cred({})];
    renderAs(devUserId(4), ['PM'], <HrqPeoplePage />);
    const grid = await screen.findByTestId('cred-grid');
    await within(grid).findByText('승인 대기');
    expect(within(grid).queryByRole('button', { name: /승인/ })).toBeNull();
  });
});

describe('등록요건', () => {
  it('오늘 미달 · 최초 미달일 표시선 · 예측 계열', async () => {
    creds = [cred({ status: 'VALID' })];
    renderAs(ADMIN, ['ADMIN'], <HrqCompliancePage />);
    expect(await screen.findByTestId('compliance-today')).toHaveTextContent('미달');
    expect(await screen.findByTestId('first-shortfall')).toHaveTextContent(day(0));
    const chart = screen.getByTestId('forecast-chart');
    expect(chart).toHaveAttribute('data-points', '3'); // 변화점 2 + 예측 끝날(계단 연장)
    expect(within(chart).getByTestId('chart-marker')).toHaveTextContent(`최초 미달 ${day(0)}`);
    expect(chart).toHaveTextContent('미달 인원(명): 1,2,2');
    await waitFor(() => expect(screen.getByTestId('cred-valid')).toHaveTextContent('1건'));
  });
});

describe('HRQ 월 마감', () => {
  it('미승인 409 는 토스트, 재오픈은 사유 5자 이상', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <HrqMonthClosePage />);
    await screen.findByTestId('hrq-month-202608');
    await user.click(screen.getByTestId('hrq-close'));
    await waitFor(() => expect(toasts.error[0]?.title).toBe('[HRQ-409-004] 미승인 타임시트'));
    await user.click(screen.getByRole('button', { name: 'HRQ 재오픈 2026-08' }));
    const dlg = screen.getByRole('dialog', { name: 'HRQ 2026-08 재오픈' });
    await user.type(within(dlg).getByLabelText('재오픈 사유'), '짧음');
    expect(within(dlg).getByTestId('reopen-confirm')).toBeDisabled();
    await user.type(within(dlg).getByLabelText('재오픈 사유'), ' — 정정 필요');
    await user.click(within(dlg).getByTestId('reopen-confirm'));
    await waitFor(() => expect(seen.find((s) => s.url.endsWith(':reopen'))?.body).toEqual({ reason: '짧음 — 정정 필요' }));
    expect(await within(screen.getByTestId('hrq-month-202608')).findByText('재오픈')).toHaveClass('warn');
  });
});
void server;
