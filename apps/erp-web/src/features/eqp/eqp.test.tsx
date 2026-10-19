import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { overlaps, parseQrToken, QR_TOKEN_RE, type CalibrationStatus } from '@/api/eqp';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { EquipmentPage } from './EquipmentPage';
import { addDays, localToday, ReservationsPage } from './ReservationsPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);

const ENG = devUserId(5);
const ADMIN = devUserId(9);
const TOKEN = 'eqp1.EQ-MSR-CRK-0001.1792396430.AbCdEfGhIjKlMnOp';
const today = localToday();
const cal: CalibrationStatus[] = [
  { equipmentId: 'e-rh', code: 'EQ-NDT-RH-0001', typeCode: 'NDT-RH', typeName: '반발경도', status: 'REGISTERED', calibrationRequired: true, nextDueOn: addDays(today, -10), daysLeft: -10, calStatus: 'EXPIRED' },
  { equipmentId: 'e-upv', code: 'EQ-NDT-UPV-0001', typeCode: 'NDT-UPV', typeName: '초음파', status: 'AVAILABLE', calibrationRequired: true, nextDueOn: addDays(today, 20), daysLeft: 20, calStatus: 'DUE_SOON' },
  { equipmentId: 'e-crk', code: 'EQ-MSR-CRK-0001', typeCode: 'MSR-CRK', typeName: '균열폭', status: 'AVAILABLE', calibrationRequired: true, nextDueOn: addDays(today, 200), daysLeft: 200, calStatus: 'VALID' },
  { equipmentId: 'e-crk2', code: 'EQ-MSR-CRK-0002', typeCode: 'MSR-CRK', typeName: '균열폭', status: 'AVAILABLE', calibrationRequired: true, nextDueOn: addDays(today, 300), daysLeft: 300, calStatus: 'VALID' },
];
const reservation = { id: 'r1', equipment_id: 'e-crk', project_id: 'p1', from_date: addDays(today, 5), to_date: addDays(today, 7), status: 'CONFIRMED', conditional: false, version: 1 };
let checkedOut = false;

const server = useMswServer(
  mswHttp.get('*/api/v1/eqp/calibration-status', ({ request }) =>
    HttpResponse.json(new URL(request.url).searchParams.get('withinDays') ? cal.filter((c) => c.calStatus !== 'VALID') : cal)),
  mswHttp.get('*/api/v1/eqp/equipment', () => HttpResponse.json({ content: [], page: 0, size: 500, totalElements: 0, totalPages: 0 })),
  mswHttp.get('*/api/v1/eqp/reservations', () => HttpResponse.json([reservation])),
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p1', projectNo: 'PRJ-2026-0001', name: '(합성) 한빛타워', clientId: 'c1', status: 'ACTIVE' }])),
  mswHttp.post('*/api/v1/eqp/reservations', async ({ request }) => {
    await record(request);
    const b = (await request.json()) as { equipmentId: string };
    if (b.equipmentId === 'e-crk') {
      return problem(409, 'EQP-409-002', '이미 예약된 기간', '같은 기간 예약이 있습니다', {
        alternatives: [{ equipmentId: 'e-crk2', code: 'EQ-MSR-CRK-0002', validUntil: addDays(today, 300), score: 0.9 }],
      });
    }
    return HttpResponse.json({ ...reservation, id: 'r2', equipment_id: b.equipmentId, status: 'REQUESTED' }, { status: 201 });
  }),
  mswHttp.get('*/api/v1/eqp/scan/:token', () => HttpResponse.json({
    equipmentId: 'e-crk', code: 'EQ-MSR-CRK-0001', typeCode: 'MSR-CRK', typeName: '균열폭', status: checkedOut ? 'CHECKED_OUT' : 'AVAILABLE',
    activeCheckout: checkedOut ? { id: 'co-1', project_id: 'p1', out_by: ENG, out_at: `${today}T09:00:00Z` } : null,
    actions: checkedOut ? ['RETURN', 'REPORT_FAULT'] : ['CHECKOUT', 'REPORT_FAULT'],
  })),
  mswHttp.post('*/api/v1/eqp/checkouts', async ({ request }) => {
    await record(request);
    checkedOut = true;
    return HttpResponse.json({ checkoutId: 'co-1', equipmentId: 'e-crk', projectId: 'p1', reservationId: 'r1', syncConflict: false, equipmentStatus: 'CHECKED_OUT', result: 'OK', calibration: { basis: 'CALIBRATION', validUntil: addDays(today, 200), daysLeft: 200 } });
  }),
  mswHttp.post('*/api/v1/eqp/checkouts/:id/return', async ({ request }) => {
    await record(request);
    checkedOut = false;
    return HttpResponse.json({ checkoutId: 'co-1', equipmentId: 'e-crk', equipmentStatus: 'AVAILABLE', usageDays: 1, returnedOn: today, faultId: null });
  }),
  mswHttp.post('*/api/v1/eqp/equipment/:id/labels', () => HttpResponse.json({ equipmentId: 'e-crk', code: 'EQ-MSR-CRK-0001', token: TOKEN, qrUrl: `https://erp.atom-eng.local/q/${TOKEN}` })),
);

describe('EQP 유틸', () => {
  it('QR 라벨 URL · 토큰 파싱과 형식 검사', () => {
    expect(parseQrToken(`https://erp.atom-eng.local/q/${TOKEN}`)).toBe(TOKEN);
    expect(parseQrToken(`  ${TOKEN} `)).toBe(TOKEN);
    expect(QR_TOKEN_RE.test(TOKEN)).toBe(true);
    expect(QR_TOKEN_RE.test('eqp1.x')).toBe(false);
  });
  it('기간 겹침(양 끝 포함)', () => {
    expect(overlaps('2026-10-01', '2026-10-03', '2026-10-03', '2026-10-05')).toBe(true);
    expect(overlaps('2026-10-01', '2026-10-02', '2026-10-03', '2026-10-05')).toBe(false);
  });
});

describe('장비 화면', () => {
  it('교정 상태 칩과 교정 만료 목록, ADMIN QR 라벨 발급', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <EquipmentPage />);
    const grid = await screen.findByTestId('eqp-grid');
    expect(await within(grid).findByText('교정 만료 D+10')).toHaveClass('danger');
    expect(within(grid).getByText('교정 임박 D-20')).toHaveClass('warn');
    expect(within(grid).getAllByText(/교정 유효 D-/)[0]).toHaveClass('ok');
    await user.click(screen.getByRole('tab', { name: /교정 만료 목록/ }));
    const exp = screen.getByTestId('cal-expiring');
    expect(within(exp).getAllByRole('row')).toHaveLength(3); // 머리글 + 만료 · 임박
    await user.click(screen.getByRole('tab', { name: '장비 목록' }));
    await user.click(within(screen.getByTestId('eqp-grid')).getByRole('button', { name: 'QR 라벨 EQ-MSR-CRK-0001' }));
    const dialog = screen.getByRole('dialog', { name: 'QR 라벨 — EQ-MSR-CRK-0001' });
    await user.click(within(dialog).getByTestId('label-issue'));
    expect(await within(dialog).findByTestId('label-token')).toHaveTextContent(TOKEN);
  });
});

describe('예약 · 반출', () => {
  it('겹치는 예약은 화면 안 안내 + 대안 장비로 바꿔 다시 요청', async () => {
    const user = userEvent.setup();
    renderAs(ENG, ['ENG', 'EMPLOYEE'], <ReservationsPage />);
    await screen.findByRole('option', { name: 'EQ-MSR-CRK-0001 · 균열폭' });
    await user.selectOptions(screen.getByLabelText('예약 장비'), 'e-crk');
    await user.clear(screen.getByLabelText('예약 시작일'));
    await user.type(screen.getByLabelText('예약 시작일'), addDays(today, 6));
    await user.clear(screen.getByLabelText('예약 종료일'));
    await user.type(screen.getByLabelText('예약 종료일'), addDays(today, 8));
    expect(screen.getByText(/이 기간에 이미 예약이 있습니다/)).toBeInTheDocument();
    await user.click(screen.getByTestId('eqp-reserve'));
    const blocked = await screen.findByTestId('eqp-blocked');
    expect(blocked).toHaveTextContent('[EQP-409-002] 이미 예약된 기간');
    expect(toasts.error).toHaveLength(0); // 토스트 대신 화면 안 안내
    await user.click(within(blocked).getByRole('button', { name: /EQ-MSR-CRK-0002/ }));
    expect(screen.getByLabelText('예약 장비')).toHaveValue('e-crk2');
    await user.click(screen.getByTestId('eqp-reserve'));
    await waitFor(() => expect(toasts.success[0]).toContain('예약 요청'));
    expect(seen[1].body).toMatchObject({ equipmentId: 'e-crk2', projectId: 'p1', from: addDays(today, 6), to: addDays(today, 8) });
  });

  it('QR 토큰 → 스캔 → 반출(clientOpId · Idempotency-Key) → 반납', async () => {
    checkedOut = false;
    const user = userEvent.setup();
    renderAs(ENG, ['ENG', 'EMPLOYEE'], <ReservationsPage />);
    await user.type(await screen.findByLabelText('QR 토큰'), `https://erp.atom-eng.local/q/${TOKEN}`);
    await user.click(screen.getByTestId('eqp-scan'));
    expect(await screen.findByTestId('eqp-scan-result')).toHaveTextContent('EQ-MSR-CRK-0001');
    await screen.findAllByRole('option', { name: 'PRJ-2026-0001 (합성) 한빛타워' });
    await user.click(screen.getByTestId('eqp-checkout'));
    await waitFor(() => expect(toasts.success[0]).toContain('반출 완료'));
    const co = seen.find((s) => s.url === '/api/v1/eqp/checkouts')!;
    expect(co.body).toMatchObject({ qrToken: TOKEN, projectId: 'p1', preCheck: { exterior: 'OK' } });
    expect((co.body as { clientOpId: string }).clientOpId).toMatch(/^[0-9a-f-]{36}$/);
    expect(co.headers['idempotency-key']).toBeTruthy();
    await user.click(await screen.findByTestId('eqp-return'));
    expect(await screen.findByTestId('eqp-return-result')).toHaveTextContent('사용 1일');
    expect(seen.find((s) => s.url.endsWith('/return'))?.body).toEqual({ postCheck: { abnormal: false } });
  });

  it('예약 403 은 화면 안 오류 상자', async () => {
    server.use(mswHttp.get('*/api/v1/eqp/reservations', () => problem(403, 'CORE-403-001', '권한 없음', 'eqp:reservation:read')));
    renderAs(devUserId(6), ['QA'], <ReservationsPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('CORE-403-001');
  });
});
