import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { renderAs, useMswServer } from '@/test/mocks';
import { devUserId } from '@/auth/devUsers';
import { agingBars, CstReceivablesPage, fmtEok } from './CstReceivablesPage';
import { lastDayOfPrevMonth } from '@/api/core';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

let lastAsOf = '';
useMswServer(
  mswHttp.get('*/api/v1/cst/receivables/aging', ({ request }) => {
    lastAsOf = new URL(request.url).searchParams.get('asOf') ?? '';
    return HttpResponse.json([
      { bucket: 'B_31_60', invoiceCount: 1, openKrw: 2200000 },
      { bucket: 'E_181_PLUS', invoiceCount: 1, openKrw: 3000000 },
    ]);
  }),
  mswHttp.get('*/api/v1/cst/receivables/open-items', () => HttpResponse.json([
    { invoiceId: 'i1', invoiceNo: 'INV-2026-000001', projectId: 'p1', clientId: 'c1', clientName: '(합성) 관리단', issueDate: '2026-09-30',
      supplyKrw: 2000000, totalKrw: 2200000, allocatedKrw: 0, openKrw: 2200000, ageDays: 31, bucket: 'B_31_60' },
    { invoiceId: 'i2', invoiceNo: 'INV-2026-000002', projectId: 'p1', clientId: 'c2', clientName: '(합성) 건설', issueDate: '2026-04-14',
      supplyKrw: 4000000, totalKrw: 4400000, allocatedKrw: 1400000, openKrw: 3000000, ageDays: 200, bucket: 'E_181_PLUS' },
  ])),
  mswHttp.get('*/api/v1/cst/allocations/candidates', () => HttpResponse.json([
    { invoiceId: 'i1', invoiceNo: 'INV-2026-000001', clientName: '(합성) 관리단', issueDate: '2026-09-30', openKrw: 2200000, receiptId: 'r1',
      valueDate: '2026-10-05', receiptKrw: 2200000, remainingKrw: 2200000, payerText: '합성관리단', amountScore: 50, nameScore: 30, dateScore: 20,
      score: 100, autoCandidate: true },
  ])),
  mswHttp.post('*/api/v1/cst/allocations:propose', () => HttpResponse.json({ id: 'pr1', invoiceId: 'i1', receiptId: 'r1', amountKrw: 2200000,
    matchScore: 100, status: 'PENDING', proposedBy: 'u', decidedBy: null, rejectReason: null, allocationId: null }, { status: 201 })),
);

describe('순수 함수', () => {
  it('agingBars: 다섯 구간을 항상 채우고 가장 큰 구간이 100%', () => {
    const bars = agingBars([{ bucket: 'B_31_60', invoiceCount: 1, openKrw: 50 }, { bucket: 'E_181_PLUS', invoiceCount: 2, openKrw: 100 }]);
    expect(bars.map((b) => b.bucket)).toEqual(['A_0_30', 'B_31_60', 'C_61_90', 'D_91_180', 'E_181_PLUS']);
    expect(bars.find((b) => b.bucket === 'E_181_PLUS')?.pct).toBe(100);
    expect(bars.find((b) => b.bucket === 'B_31_60')?.pct).toBe(50);
    expect(bars.find((b) => b.bucket === 'A_0_30')?.count).toBe(0);
  });
  it('fmtEok · lastDayOfPrevMonth', () => {
    expect(fmtEok(255678659)).toBe('2.6억 원');
    expect(lastDayOfPrevMonth(new Date(2026, 10, 6))).toBe('2026-10-31');
    expect(lastDayOfPrevMonth(new Date(2026, 0, 15))).toBe('2025-12-31');
  });
});

describe('CstReceivablesPage', () => {
  it('재무: 기준일 기본값 = 지난달 말일, aging 과 미수 목록이 보이고 기준일을 바꾸면 다시 조회한다', async () => {
    renderAs(devUserId(3), ['FIN'], <CstReceivablesPage />);
    await waitFor(() => expect(screen.getByTestId('aging-table')).toBeInTheDocument());
    expect(screen.getByTestId('asof').textContent).toBe(lastDayOfPrevMonth());
    expect(lastAsOf).toBe(lastDayOfPrevMonth());
    expect(screen.getByText('90일 초과(D·E) — 독촉 대상').parentElement?.textContent).toContain('0.0억 원');   // 3,000,000 → 0.0억
    expect(screen.getByTestId('open-items').textContent).toContain('INV-2026-000002');
    const date = screen.getByLabelText('기준일') as HTMLInputElement;
    await userEvent.clear(date);
    await userEvent.type(date, '2026-09-30');
    await waitFor(() => expect(lastAsOf).toBe('2026-09-30'));
  });
  it('재무는 짝 후보를 보고 제안할 수 있고, 감사는 후보 표가 없다', async () => {
    renderAs(devUserId(3), ['FIN'], <CstReceivablesPage />);
    await waitFor(() => expect(screen.getByTestId('propose-INV-2026-000001')).toBeInTheDocument());
    await userEvent.click(screen.getByTestId('propose-INV-2026-000001'));
    await waitFor(() => expect(screen.getByTestId('propose-INV-2026-000001')).toBeEnabled());
  });
  it('감사(AUDITOR)는 읽기만 — 후보 · 제안 버튼 없음', async () => {
    renderAs(devUserId(3), ['AUDITOR'], <CstReceivablesPage />);
    await waitFor(() => expect(screen.getByTestId('aging-table')).toBeInTheDocument());
    expect(screen.queryByTestId('candidates')).toBeNull();
  });
  it('기술자(ENG)는 화면 자체를 볼 수 없다', () => {
    renderAs(devUserId(3), ['ENG'], <CstReceivablesPage />);
    expect(screen.getByText(/미수 현황은 재무/)).toBeInTheDocument();
  });
});
