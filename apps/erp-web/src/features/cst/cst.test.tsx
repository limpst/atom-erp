import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { correctionGroups, reversalPairs, type LedgerRow } from '@/api/core';
import { record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { CostLedgerPage, filterLedger } from './CostLedgerPage';
import { CstPeriodsPage } from './CstPeriodsPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);
vi.mock('@/components/LineChart', async () => (await import('@/test/mockModules')).lineChartModule);

const PM = devUserId(4);
const row = (over: Partial<LedgerRow>): LedgerRow => ({
  id: 'l1', periodYm: '202610', postingDate: '2026-10-12', sourceType: 'TIMESHEET', sourceId: 's1', costElement: 'LABOR',
  employeeId: devUserId(10), gradeCode: 'MASTER', workType: 'FIELD', hours: 8, qty: 1, amountStdKrw: 418000, amountKrw: null,
  amountKrwMasked: true, reversalOf: null, sourceRef: 'k:1', flags: [], ...over,
});
const ledger: LedgerRow[] = [
  row({ id: 'a' }),
  row({ id: 'b', sourceType: 'REVERSAL', hours: -8, amountStdKrw: -418000, reversalOf: 'a', sourceRef: 'k:2' }),
  row({ id: 'c', hours: 6, amountStdKrw: 313500, sourceRef: 'k:2' }),
  row({ id: 'd', employeeId: devUserId(5), gradeCode: 'INTER', amountStdKrw: 271000, periodYm: '202609' }),
];
let blockers: string[] = ['HRQ_MONTH_NOT_CLOSED', 'MANDAY_NOT_RECONCILED'];

useMswServer(
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p1', projectNo: 'PRJ-2026-0001', name: '(합성) 한빛타워', serviceType: 'PRECISE_DIAG', clientId: 'c1', status: 'IN_PROGRESS' }])),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([])),
  mswHttp.get('*/api/v1/cst/projects/:id/ledger', () => HttpResponse.json(ledger)),
  mswHttp.get('*/api/v1/cst/projects/:id/evm/snapshots', () => HttpResponse.json([
    { asOf: '2026-10-05', bac: 2e7, pv: 1e6, ev: 9e5, acStd: 8e5, sv: -1e5, spi: 0.9, cpiStd: 1.125, ac: null, cv: null, cpi: null, eac: null, etc: null, vac: null, tcpi: null, acBasis: 'STANDARD', alertLevel: 'YELLOW' },
    { asOf: '2026-10-12', bac: 2e7, pv: 2e6, ev: 2e6, acStd: 1.6e6, sv: 0, spi: 1, cpiStd: 1.25, ac: null, cv: null, cpi: null, eac: null, etc: null, vac: null, tcpi: null, acBasis: 'STANDARD', alertLevel: 'GREEN' },
  ])),
  mswHttp.get('*/api/v1/cst/periods', () => HttpResponse.json([
    { period_ym: '202608', status: 'CLOSED', closed_at: '2026-09-02T00:00:00Z', closed_by: null, reopened_at: null, reopened_by: null, reopen_reason: null, hrq_closed_at: '2026-09-01T00:00:00Z', recon_status: 'MATCHED', diff_mandays: 0, hrq_total_mandays: 0, cst_total_mandays: 0, reconciled_at: '2026-09-01T00:00:00Z' },
  ])),
  mswHttp.get('*/api/v1/cst/periods/:ym/reconciliations', () => HttpResponse.json([
    { id: 'r1', period_ym: '202608', source_event_id: 'e1234567-0000', hrq_total_mandays: 3, cst_total_mandays: 2, diff_mandays: 1, status: 'MISMATCH', created_at: '2026-09-01T00:00:00Z',
      detail: JSON.stringify({ groups: 1, diffs: [{ projectId: 'p1', employeeId: 'x', grade: 'INTER', workType: 'FIELD', hrqMandays: 3, cstMandays: 2, diff: 1 }] }) },
  ])),
  mswHttp.post('*/api/v1/cst/periods/:ym', async ({ request, params }) => {
    await record(request);
    const [ym, action] = String(params.ym).split(':');
    if (action === 'reopen') return HttpResponse.json({ periodYm: ym, status: 'REOPENED' });
    return HttpResponse.json({ periodYm: ym, closed: blockers.length === 0, blockers });
  }),
);

describe('원가 유틸', () => {
  it('정정 짝: 역분개와 원 행이 같은 번호, 짝만 거르기', () => {
    const pairs = reversalPairs(ledger);
    expect(pairs.get('a')).toBe(1);
    expect(pairs.get('b')).toBe(1);
    expect(pairs.has('c')).toBe(false);
    const groups = correctionGroups(ledger);
    expect(groups.get('a')).toEqual({ pair: 1, role: 'ORIGINAL' });
    expect(groups.get('b')).toEqual({ pair: 1, role: 'REVERSAL' });
    expect(groups.get('c')).toEqual({ pair: 1, role: 'REPLACEMENT' }); // 같은 업무 멱등키 k:2
    expect(groups.has('d')).toBe(false);
    const rows = ledger.map((r) => ({ ...r, pair: groups.get(r.id)?.pair ?? null, role: groups.get(r.id)?.role ?? null }));
    expect(filterLedger(rows, { sourceType: '', costElement: '', employeeId: '', periodYm: '', pairsOnly: true }).map((r) => r.id)).toEqual(['a', 'b', 'c']);
    expect(filterLedger(rows, { sourceType: 'REVERSAL', costElement: '', employeeId: '', periodYm: '', pairsOnly: false })).toHaveLength(1);
    expect(filterLedger(rows, { sourceType: '', costElement: '', employeeId: '', periodYm: '202609', pairsOnly: false }).map((r) => r.id)).toEqual(['d']);
  });
});

describe('원가 원장 · EVM', () => {
  it('정정 짝 배지 · 실원가 마스킹 안내 · 필터 · EVM 추이(표준 AC)', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <CostLedgerPage />, { route: '/?projectId=p1' });
    const grid = await screen.findByTestId('cost-ledger-grid');
    expect(await within(grid).findByText('#1 정정 전')).toHaveClass('warn');
    expect(within(grid).getByText('#1 역분개')).toHaveClass('danger');
    expect(within(grid).getByText('#1 정정 후')).toHaveClass('ok');
    expect(screen.getByText(/실원가 열은 실단가 열람 권한/)).toBeInTheDocument();
    expect(screen.getByTestId('ledger-count')).toHaveTextContent('4행 / 전체 4행 · 정정 짝 1쌍');
    await user.click(screen.getByLabelText('정정 짝만'));
    expect(screen.getByTestId('ledger-count')).toHaveTextContent('3행 / 전체 4행');
    const chart = await screen.findByTestId('evm-trend');
    expect(chart).toHaveAttribute('data-points', '2');
    expect(chart).toHaveTextContent('AC 표준원가: 800000,1600000');
    expect(chart).toHaveTextContent('CPI(표준): 1.125,1.25');
  });
});

describe('원가 회계기간', () => {
  it('ADMIN 마감: 체크리스트 미충족이면 막는 사유를 화면에 표시', async () => {
    blockers = ['HRQ_MONTH_NOT_CLOSED', 'MANDAY_NOT_RECONCILED'];
    const user = userEvent.setup();
    renderAs(devUserId(9), ['ADMIN'], <CstPeriodsPage />);
    await screen.findByTestId('cst-period-202608');
    await user.click(screen.getByTestId('cst-close'));
    const res = await screen.findByTestId('cst-close-result');
    expect(res).toHaveTextContent('HRQ 월 마감 전');
    expect(res).toHaveTextContent('MANDAY_NOT_RECONCILED');
    expect(toasts.warning[0]).toContain('마감 불가');
    await user.click(screen.getByTestId('cst-period-202608'));
    const run = await screen.findByTestId('recon-run');
    expect(run).toHaveTextContent('불일치(MISMATCH)');
    expect(run).toHaveTextContent('차이 1');
  });

  it('SYSADMIN 재오픈: 목록(cst:period:read)이 보이고 마감 행에서 사유와 함께 재오픈, 마감 버튼은 없음', async () => {
    const user = userEvent.setup();
    renderAs(devUserId(13), ['SYSADMIN'], <CstPeriodsPage />);
    await screen.findByTestId('cst-period-202608');
    expect(screen.queryByTestId('cst-close')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'CST 재오픈 2026-08' }));
    const dlg = screen.getByRole('dialog', { name: 'CST 2026-08 재오픈' });
    await user.type(within(dlg).getByLabelText('재오픈 사유'), '(합성) 원가 정정 반영');
    await user.click(within(dlg).getByTestId('reopen-confirm'));
    await waitFor(() => expect(toasts.success[0]).toContain('재오픈'));
    const req = seen.find((s) => s.url.endsWith(':reopen'));
    expect(req?.url).toMatch(/\/api\/v1\/cst\/periods\/202608:reopen$/);
    expect(req?.body).toEqual({ reason: '(합성) 원가 정정 반영' });
  });

  it('ADMIN 은 마감만, 재오픈 버튼 없음', async () => {
    renderAs(devUserId(9), ['ADMIN'], <CstPeriodsPage />);
    await screen.findByTestId('cst-period-202608');
    expect(screen.getByTestId('cst-close')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'CST 재오픈 2026-08' })).toBeNull();
  });
});
