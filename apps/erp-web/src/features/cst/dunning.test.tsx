import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { renderAs, useMswServer } from '@/test/mocks';
import { devUserId } from '@/auth/devUsers';
import { CstDunningPage, summarize } from './CstDunningPage';
import type { DunningNotice } from '@/api/core';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const FIN = devUserId(3);
const EXEC = devUserId(1);
const NOTICES: DunningNotice[] = [
  { id: 'n1', invoiceId: 'i1', invoiceNo: 'INV-2026-000001', projectId: 'p1', clientName: '(합성) 관리단', level: 'L0', asOf: '2026-10-31', ageDays: 40,
    openKrw: 2200000, status: 'DRAFT', bodyText: '합성 알림 본문', createdBy: FIN, createdAt: '2026-11-01T09:00:00Z', approvedBy: null, sentAt: null },
  { id: 'n2', invoiceId: 'i2', invoiceNo: 'INV-2026-000002', projectId: 'p1', clientName: '(합성) 건설', level: 'L2', asOf: '2026-10-31', ageDays: 200,
    openKrw: 3000000, status: 'APPROVED', bodyText: '합성 공문 본문', createdBy: FIN, createdAt: '2026-11-01T09:00:00Z', approvedBy: EXEC, sentAt: null },
];
const calls: string[] = [];
const idOf = (url: string, suffix: string) => new URL(url).pathname.split('/').pop()!.replace(suffix, '');
useMswServer(
  mswHttp.get('*/api/v1/cst/dunning/notices', ({ request }) => {
    const st = new URL(request.url).searchParams.get('status');
    return HttpResponse.json(st ? NOTICES.filter((n) => n.status === st) : NOTICES);
  }),
  mswHttp.post('*/api/v1/cst/dunning:run', async ({ request }) => {
    const b = (await request.json()) as { asOf: string };
    calls.push(`run:${b.asOf}`);
    return HttpResponse.json({ asOf: b.asOf, created: 3, cancelled: 1, skippedOnHold: 0 });
  }),
  mswHttp.post(/\/api\/v1\/cst\/dunning\/notices\/[^/]+:approve$/, ({ request }) => { const params = { id: idOf(request.url, ':approve') }; calls.push(`approve:${params.id}`); return HttpResponse.json({ id: params.id, status: 'APPROVED' }); }),
  mswHttp.post(/\/api\/v1\/cst\/dunning\/notices\/[^/]+:sent$/, ({ request }) => { const params = { id: idOf(request.url, ':sent') }; calls.push(`sent:${params.id}`); return HttpResponse.json({ id: params.id, status: 'SENT' }); }),
  mswHttp.post(/\/api\/v1\/cst\/dunning\/invoices\/[^/]+:hold$/, async ({ request }) => {
    const params = { id: idOf(request.url, ':hold') };
    const b = (await request.json()) as { reason: string };
    calls.push(`hold:${params.id}:${b.reason}`);
    return HttpResponse.json({ invoiceId: params.id, status: 'ON_HOLD' });
  }),
);

describe('summarize', () => {
  it('상태별 건수 · 미수 합계', () => {
    const s = summarize(NOTICES);
    expect(s.DRAFT).toEqual({ count: 1, openKrw: 2200000 });
    expect(s.APPROVED).toEqual({ count: 1, openKrw: 3000000 });
    expect(s.SENT.count).toBe(0);
  });
});

describe('CstDunningPage', () => {
  it('재무: 목록 · KPI 가 보이고, 초안 만들기는 기준일로 호출, 결재된 건은 "보냄 기록", 보류는 사유 필수', async () => {
    renderAs(FIN, ['FIN'], <CstDunningPage />);
    await waitFor(() => expect(screen.getByTestId('dunning-table').textContent).toContain('INV-2026-000002'));
    expect(screen.getByTestId('dunning-kpis').textContent).toContain('초안(결재 대기)1건');
    expect(screen.queryByRole('button', { name: '결재' })).toBeNull();           // 재무는 결재 못 함
    await userEvent.click(screen.getByRole('button', { name: '초안 만들기(기준일 기준)' }));
    await waitFor(() => expect(calls.some((c) => c.startsWith('run:'))).toBe(true));
    await userEvent.click(screen.getByRole('button', { name: '보냄 기록' }));
    await waitFor(() => expect(calls).toContain('sent:n2'));
    await userEvent.click(screen.getAllByRole('button', { name: '보류' })[0]);
    const confirm = screen.getByRole('button', { name: '보류 확정' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText('보류 사유'), '하자 협의 중');
    await userEvent.click(confirm);
    await waitFor(() => expect(calls).toContain('hold:i1:하자 협의 중'));
  });
  it('임원: 다른 사람의 초안만 결재할 수 있고, 초안 만들기 · 보냄 버튼은 없다', async () => {
    renderAs(EXEC, ['EXEC'], <CstDunningPage />);
    await waitFor(() => expect(screen.getByTestId('dunning-table').textContent).toContain('INV-2026-000001'));
    expect(screen.queryByRole('button', { name: '초안 만들기(기준일 기준)' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: '결재' }));
    await waitFor(() => expect(calls).toContain('approve:n1'));
  });
  it('임원이 본인 초안이면 결재 버튼 대신 "본인 초안"', async () => {
    renderAs(FIN, ['EXEC'], <CstDunningPage />);
    await waitFor(() => expect(screen.getByTestId('dunning-table').textContent).toContain('본인 초안'));
    expect(screen.queryByRole('button', { name: '결재' })).toBeNull();
  });
  it('본문 보기 모달 · 상태 필터', async () => {
    renderAs(devUserId(5), ['AUDITOR'], <CstDunningPage />);
    await waitFor(() => expect(screen.getByTestId('dunning-table').textContent).toContain('INV-2026-000001'));
    expect(screen.queryByRole('button', { name: '보류' })).toBeNull();   // 감사는 읽기만
    await userEvent.click(screen.getAllByRole('button', { name: '본문' })[0]);
    expect(screen.getByTestId('dunning-body').textContent).toContain('합성 알림 본문');
  });
  it('기술자(ENG)는 화면 자체를 볼 수 없다', () => {
    renderAs(devUserId(9), ['ENG'], <CstDunningPage />);
    expect(screen.getByText(/재무 · 경영지원 · 임원 · 감사만/)).toBeInTheDocument();
  });
});
