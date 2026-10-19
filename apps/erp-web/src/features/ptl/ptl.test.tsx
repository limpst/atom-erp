import { beforeAll, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { PortalLayout } from './PortalLayout';
import { PortalHomePage, PortalNotificationsPage, PortalReportsPage, PortalRequestsPage } from './PortalPages';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const CUST_A = devUserId(11);
const CUST_B = devUserId(12);
const report = (id: string, title: string, revNo: number, status: 'PUBLISHED' | 'SUPERSEDED') => ({
  id, reportId: `rep-${title}`, revisionId: `rev-${id}`, revNo, facilityId: 'b1', reportType: 'RT_PRC', title, inspectionDate: '2026-10-16',
  safetyGrade: revNo === 2 ? 'C' : 'B', pageCount: 3, deliveredAt: '2026-10-19T07:00:00Z', status, downloadable: status === 'PUBLISHED',
  banner: status === 'SUPERSEDED' ? '이 보고서는 정정본으로 대체되었습니다. 최신 정정본을 확인하세요(열람만 가능).' : null,
  supersededByRevisionId: null, supersededReason: status === 'SUPERSEDED' ? '(합성) 균열폭 측정 오기 정정' : null, supersededAt: null,
});
/** 테넌트별 데이터 — 서버 RLS 흉내: X-Client-Org-Id 로만 고른다 */
const DATA: Record<string, { reports: unknown[]; buildings: unknown[] }> = {
  'c-a': {
    reports: [report('pa2', '(합성) 한빛타워 별관 정밀점검', 2, 'PUBLISHED'), report('pa1', '(합성) 한빛타워 별관 정밀점검', 1, 'SUPERSEDED')],
    buildings: [{ id: 'b1', name: '(합성) 한빛타워 별관', dongName: '', roadAddress: '서울 합성구 합성로 1101-2', status: 'CLASSIFIED', currentGrade: 'C', gradePerformedOn: '2026-10-16',
      nextInspection: { buildingId: 'b1', buildingName: '(합성) 한빛타워 별관', inspectionType: 'REGULAR', dueDate: '2027-04-16', dDay: 179, severity: 'GREEN', facStatus: 'SCHEDULED' } }],
  },
  'c-b': { reports: [report('pb1', '(합성) 푸른아파트 정기점검', 1, 'PUBLISHED')], buildings: [] },
};
const tenant = (r: Request) => DATA[r.headers.get('x-client-org-id') ?? ''] ?? { reports: [], buildings: [] };

useMswServer(
  mswHttp.get('*/api/v1/ptl/reports', ({ request }) => HttpResponse.json(tenant(request).reports)),
  mswHttp.get('*/api/v1/ptl/buildings', ({ request }) => HttpResponse.json(tenant(request).buildings)),
  mswHttp.get('*/api/v1/ptl/inspection-schedule', () => HttpResponse.json([
    { buildingId: 'b1', buildingName: '(합성) 한빛타워 별관', inspectionType: 'DETAILED', dueDate: '2026-10-01', dDay: -18, severity: 'OVERDUE', facStatus: 'SCHEDULED' },
  ])),
  mswHttp.post('*/api/v1/ptl/reports/:id', async ({ request }) => {
    await record(request);
    return new HttpResponse(new Blob(['%PDF-1.7 watermarked']), {
      headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="report-x-rev2-dl1.pdf"', 'X-Download-Id': 'dl-0001-aaaa', 'X-Content-SHA256': 'f'.repeat(64) },
    });
  }),
  mswHttp.get('*/api/v1/ptl/service-requests', () => HttpResponse.json([])),
  mswHttp.post('*/api/v1/ptl/service-requests', async ({ request }) => {
    await record(request);
    const b = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json({ id: 'sr1', ...b, serviceType: 'EMERGENCY_INSP', status: 'SUBMITTED', slaDueAt: '2026-10-19T09:00:00Z', createdAt: '2026-10-19T07:00:00Z' }, { status: 201 });
  }),
  mswHttp.get('*/api/v1/ptl/notifications', ({ request }) => {
    const unread = new URL(request.url).searchParams.get('unread') === 'true';
    const all = [
      { id: 'n1', kind: 'REPORT_DELIVERED', category: 'INFO', title: '보고서가 납품되었습니다', body: 'Rev.2', refType: 'PublishedReport', refId: 'x', createdAt: '2026-10-19T07:00:00Z', readAt: null },
      { id: 'n2', kind: 'GRADE_RECORDED', category: 'INFO', title: '안전등급이 기록되었습니다', body: 'C', refType: 'Building', refId: 'b1', createdAt: '2026-10-19T07:00:00Z', readAt: '2026-10-19T08:00:00Z' },
    ];
    return HttpResponse.json(unread ? all.filter((n) => !n.readAt) : all);
  }),
  mswHttp.post('*/api/v1/ptl/notifications/:id', async ({ request }) => {
    await record(request);
    return new HttpResponse(null, { status: 204 });
  }),
);

beforeAll(() => {
  URL.createObjectURL = vi.fn(() => 'blob:x');
  URL.revokeObjectURL = vi.fn();
});

const portal = (
  <Routes>
    <Route path="/portal" element={<PortalLayout />}>
      <Route index element={<PortalHomePage />} />
      <Route path="reports" element={<PortalReportsPage />} />
      <Route path="requests" element={<PortalRequestsPage />} />
      <Route path="notifications" element={<PortalNotificationsPage />} />
    </Route>
  </Routes>
);

describe('고객 포털', () => {
  it('내부 직원은 포털 대신 안내', () => {
    renderAs(devUserId(4), ['PM'], portal, { route: '/portal', path: '*' });
    expect(screen.getByText(/고객\(CUSTOMER\) 계정 전용/)).toBeInTheDocument();
  });

  it('내 시설물 · 일정(D-day) · 안 읽은 알림 수', async () => {
    renderAs(CUST_A, ['CUSTOMER'], portal, { route: '/portal', path: '*', clientOrgId: 'c-a' });
    const b = await screen.findByTestId('ptl-buildings');
    expect(await within(b).findByText('C등급')).toHaveClass('warn');
    expect(within(b).getByText('D-179')).toHaveClass('ok');
    expect(within(await screen.findByTestId('ptl-schedule')).getByText('D+18 경과')).toHaveClass('danger');
    expect(await screen.findByRole('link', { name: '알림 (1)' })).toBeInTheDocument();
    expect(seen).toHaveLength(0);
  });

  it('보고서: 대체본 배너 · 열람만, 최신본 워터마크 다운로드(X-Download-Id)', async () => {
    const user = userEvent.setup();
    renderAs(CUST_A, ['CUSTOMER'], portal, { route: '/portal/reports', path: '*', clientOrgId: 'c-a' });
    const list = await screen.findByTestId('ptl-reports');
    expect(await within(list).findByTestId('ptl-superseded-banner')).toHaveTextContent('정정본으로 대체');
    expect(within(list).getByTestId('ptl-superseded-banner')).toHaveTextContent('균열폭 측정 오기 정정');
    expect(within(list).getByRole('button', { name: /Rev\.1/ })).toBeDisabled();
    await user.click(within(list).getByRole('button', { name: /Rev\.2/ }));
    expect(await screen.findByTestId('ptl-download-info')).toHaveTextContent('download-id dl-0001-aaaa');
    expect(seen[0]).toMatchObject({ method: 'POST', url: '/api/v1/ptl/reports/pa2:download' });
    expect(seen[0].headers['x-client-org-id']).toBe('c-a');
    expect(seen[0].headers['x-roles']).toBe('CUSTOMER');
    expect(URL.createObjectURL).toHaveBeenCalled();
  });

  it('테넌트 격리: 고객 B 화면에는 B 의 보고서만(요청 헤더 = B 의 고객사)', async () => {
    renderAs(CUST_B, ['CUSTOMER'], portal, { route: '/portal/reports', path: '*', clientOrgId: 'c-b' });
    const list = await screen.findByTestId('ptl-reports');
    expect(await within(list).findByText('(합성) 푸른아파트 정기점검')).toBeInTheDocument();
    expect(within(list).queryByText(/한빛타워/)).toBeNull();
  });

  it('서비스 요청: 긴급은 사유 필수 → 접수(Idempotency-Key)', async () => {
    const user = userEvent.setup();
    renderAs(CUST_A, ['CUSTOMER'], portal, { route: '/portal/requests', path: '*', clientOrgId: 'c-a' });
    await user.selectOptions(await screen.findByLabelText('요청 종류'), 'EMERGENCY');
    await user.type(screen.getByLabelText('요청 내용'), '(합성) 지진 후 외벽 균열 확대');
    expect(screen.getByTestId('ptl-submit')).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('긴급 사유'), 'EARTHQUAKE');
    await user.click(screen.getByTestId('ptl-submit'));
    await waitFor(() => expect(toasts.success[0]).toContain('긴급 점검 요청 접수'));
    expect(seen[0].body).toEqual({ kind: 'EMERGENCY', reasonCode: 'EARTHQUAKE', description: '(합성) 지진 후 외벽 균열 확대' });
    expect(seen[0].headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('알림 읽음 처리', async () => {
    const user = userEvent.setup();
    renderAs(CUST_A, ['CUSTOMER'], portal, { route: '/portal/notifications', path: '*', clientOrgId: 'c-a' });
    await user.click(await screen.findByRole('button', { name: '읽음 보고서가 납품되었습니다' }));
    await waitFor(() => expect(seen[0]?.url).toBe('/api/v1/ptl/notifications/n1:read'));
  });
});
