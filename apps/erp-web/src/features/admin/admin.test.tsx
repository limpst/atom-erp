import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { http as mswHttp, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createQueryClient } from '@/app/queryClient';
import { AuthProvider } from '@/auth/auth';
import { devUserId } from '@/auth/devUsers';
import { etagStore } from '@/api/http';
import { formatExpertise, parseExpertise, rateInEffect, type Rate } from '@/api/admin';
import { SalesGroupPage } from './SalesGroupPage';
import { ReviewerProfilesPage } from './ReviewerProfilesPage';
import { LaborRatesPage } from './LaborRatesPage';

/** 토스트 캡처 */
const toasts = vi.hoisted(() => ({ success: [] as string[], error: [] as { title: string; description?: string }[] }));
vi.mock('sonner', () => ({
  toast: {
    success: (m: string) => toasts.success.push(m),
    error: (title: string, o?: { description?: string }) => toasts.error.push({ title, description: o?.description }),
  },
  Toaster: () => null,
}));

/** AG Grid 대신 단순 표(jsdom 은 레이아웃이 없어 가상 스크롤 행이 그려지지 않는다) — columnDefs 의 valueGetter · cellRenderer 를 그대로 쓴다 */
vi.mock('@/components/DataGrid', () => ({
  DataGrid: ({ rowData, columnDefs, testId }: { rowData?: unknown[]; columnDefs: Record<string, unknown>[]; testId?: string }) => (
    <table data-testid={testId}>
      <tbody>
        {(rowData ?? []).map((data, i) => (
          <tr key={i}>
            {columnDefs.map((c, j) => {
              const R = c.cellRenderer as ((p: { data: unknown }) => ReactNode) | undefined;
              const value = c.valueGetter
                ? (c.valueGetter as (p: { data: unknown }) => unknown)({ data })
                : (data as Record<string, unknown>)[c.field as string];
              return <td key={j}>{R ? <R data={data} /> : String(value ?? '')}</td>;
            })}
          </tr>
        ))}
      </tbody>
    </table>
  ),
}));

const ADMIN = devUserId(9);
const EXEC = devUserId(1);
const LEAD = devUserId(3);
const QA = devUserId(6);

const seen: { method: string; url: string; headers: Record<string, string>; body: unknown }[] = [];
async function record(r: Request) {
  const text = await r.clone().text();
  seen.push({
    method: r.method, url: new URL(r.url).pathname, headers: Object.fromEntries(r.headers.entries()),
    body: text ? JSON.parse(text) : undefined,
  });
}
const problem = (status: number, code: string, title: string, detail: string) =>
  HttpResponse.json({ type: 'about:blank', title, status, detail, code, traceId: 't-1' }, {
    status, headers: { 'Content-Type': 'application/problem+json' },
  });

const employees = [
  { id: LEAD, empNo: 'D-003', name: '이영업팀장', status: 'ACTIVE' },
  { id: devUserId(2), empNo: 'D-002', name: '박영업', status: 'ACTIVE' },
  { id: QA, empNo: 'D-006', name: '한품질', status: 'ACTIVE' },
  { id: ADMIN, empNo: 'D-009', name: '서경영', status: 'ACTIVE' },
];
const member = { employeeId: LEAD, groupCode: 'SALES_LEAD', active: true, note: '(합성) 1팀장', createdAt: '2026-10-19T00:00:00Z', createdBy: ADMIN, updatedAt: null, updatedBy: null, version: 0 };
const draft: Rate = {
  id: 'r-draft', rateKind: 'STD_LABOR', keyCode: 'INTER', value: 300000, validFrom: '2026-10-05', validTo: null,
  sourceRef: '⚠️ 예시', version: 0, status: 'DRAFT', createdBy: ADMIN, createdAt: null, approvedBy: null,
  approvedAt: null, rejectReason: null, retiredAt: null,
};
const approved: Rate = { ...draft, id: 'r-ok', value: 271000, validFrom: '2000-01-01', status: 'APPROVED', version: 1, approvedBy: EXEC };

const server = setupServer(
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json(employees)),
  mswHttp.get('*/api/v1/sal/sales-group-members', () => HttpResponse.json([member])),
  mswHttp.post('*/api/v1/sal/sales-group-members', async ({ request }) => {
    await record(request);
    const b = (await request.json()) as { employeeId: string };
    if (b.employeeId === LEAD) return problem(409, 'SAL-409-011', '이미 등록된 영업 그룹 소속', '이미 등록된 소속입니다');
    return HttpResponse.json({ ...member, employeeId: b.employeeId }, { status: 201, headers: { ETag: '"v0"' } });
  }),
  mswHttp.patch('*/api/v1/sal/sales-group-members/:emp/:group', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ ...member, active: false, version: 1 });
  }),
  mswHttp.get('*/api/v1/qar/reviewer-profiles', () => HttpResponse.json([])),
  mswHttp.post('*/api/v1/qar/reviewer-profiles', async ({ request }) => {
    await record(request);
    const b = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json({ ...b, stepCode: 'QA_REVIEW', active: true, openAssignments: 0, note: null, createdAt: '', updatedAt: null, updatedBy: null, version: 0 }, { status: 201 });
  }),
  mswHttp.get('*/api/v1/cst/rates', () => HttpResponse.json([approved, draft])),
  mswHttp.post('*/api/v1/cst/rates', async ({ request }) => {
    await record(request);
    const b = (await request.json()) as { value: number };
    if (b.value > 9_000_000) return problem(422, 'CST-422-001', 'CST 요청값 오류', '신규 적용일은 직전 요율 시작일 이후여야 합니다');
    return HttpResponse.json({ ...draft, ...b, id: 'r-new' }, { status: 201 });
  }),
  mswHttp.post('*/api/v1/cst/rates/:cmd', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ ...draft, status: 'APPROVED', version: 1, approvedBy: EXEC });
  }),
);

let origFetch: typeof fetch;
beforeAll(() => {
  server.listen();
  origFetch = globalThis.fetch;
  globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) =>
    origFetch(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init)) as typeof fetch;
});
afterEach(() => {
  server.resetHandlers();
  seen.length = 0;
  toasts.success.length = 0;
  toasts.error.length = 0;
  etagStore.clear();
  localStorage.clear();
});
afterAll(() => {
  server.close();
  globalThis.fetch = origFetch;
});

function renderAs(actorId: string, roles: string[], page: ReactNode) {
  localStorage.setItem('atom-erp.dev-session', JSON.stringify({ actorId, name: '합성', roles }));
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <AuthProvider>{page}</AuthProvider>
    </QueryClientProvider>,
  );
}

describe('영업팀장 그룹 화면', () => {
  it('목록 · 등록(Idempotency-Key) · 비활성화(If-Match) · 중복 409 토스트', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <SalesGroupPage />);
    const grid = await screen.findByTestId('sg-grid');
    expect(await within(grid).findByText('D-003 이영업팀장')).toBeInTheDocument();
    expect(within(grid).getByText('(합성) 1팀장')).toBeInTheDocument();

    // 이미 소속된 직원은 선택지에서 빠진다
    const select = screen.getByTestId('sg-employee');
    expect(within(select).queryByText('D-003 이영업팀장')).toBeNull();
    await user.selectOptions(select, devUserId(2));
    await user.click(screen.getByTestId('sg-add'));
    await waitFor(() => expect(toasts.success[0]).toContain('영업팀장 그룹 등록'));
    expect(seen[0]).toMatchObject({ method: 'POST', url: '/api/v1/sal/sales-group-members', body: { employeeId: devUserId(2), groupCode: 'SALES_LEAD' } });
    expect(seen[0].headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);

    await user.click(within(grid).getByRole('button', { name: '비활성화 D-003 이영업팀장' }));
    await waitFor(() => expect(seen[1]?.method).toBe('PATCH'));
    expect(seen[1].url).toBe(`/api/v1/sal/sales-group-members/${LEAD}/SALES_LEAD`);
    expect(seen[1].headers['if-match']).toBe('"v0"');
    expect(seen[1].body).toEqual({ active: false });
  });

  it('problem+json 409 → [코드] 제목 토스트', async () => {
    server.use(mswHttp.get('*/api/v1/sal/sales-group-members', () => HttpResponse.json([])));
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <SalesGroupPage />);
    await screen.findByRole('option', { name: 'D-003 이영업팀장' });
    await user.selectOptions(screen.getByTestId('sg-employee'), LEAD);
    await user.click(screen.getByTestId('sg-add'));
    await waitFor(() => expect(toasts.error[0]?.title).toBe('[SAL-409-011] 이미 등록된 영업 그룹 소속'));
    expect(toasts.error[0].description).toContain('traceId t-1');
  });

  it('ADMIN 이 아니면 화면 대신 안내', () => {
    renderAs(devUserId(2), ['SALES'], <SalesGroupPage />);
    expect(screen.getByText(/ADMIN 역할만/)).toBeInTheDocument();
  });
});

describe('QA 검토자 화면', () => {
  it('전문성 · 자격을 파싱해 등록', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <ReviewerProfilesPage />);
    await screen.findByRole('option', { name: 'D-006 한품질' });
    await user.selectOptions(screen.getByTestId('rv-employee'), QA);
    await user.clear(screen.getByLabelText('동시 검토 상한'));
    await user.type(screen.getByLabelText('동시 검토 상한'), '3');
    await user.type(screen.getByLabelText('전문성'), 'RT_PSD=3, RT_PRC=1');
    await user.type(screen.getByLabelText('자격'), '(합성) 건축구조 검토, (합성) 내진');
    await user.click(screen.getByTestId('rv-add'));
    await waitFor(() => expect(toasts.success[0]).toContain('QA 검토자 등록'));
    expect(seen[0].body).toEqual({
      employeeId: QA, maxOpen: 3, expertise: { RT_PSD: 3, RT_PRC: 1 }, qualifications: ['(합성) 건축구조 검토', '(합성) 내진'], note: '',
    });
  });

  it('전문성 형식 오류는 요청 없이 토스트', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <ReviewerProfilesPage />);
    await screen.findByRole('option', { name: 'D-006 한품질' });
    await user.selectOptions(screen.getByTestId('rv-employee'), QA);
    await user.type(screen.getByLabelText('전문성'), 'psd=many');
    await user.click(screen.getByTestId('rv-add'));
    expect(toasts.error[0].description).toContain('전문성 형식 오류');
    expect(seen).toHaveLength(0);
  });
});

describe('표준 노임 단가 화면', () => {
  it('ADMIN: 초안 등록 → 422 problem 토스트', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <LaborRatesPage />);
    expect(await screen.findByTestId('rate-drafts')).toHaveTextContent('승인 대기 1건');
    // ADMIN 에게는 승인 버튼이 없다(EXEC 전용)
    expect(screen.queryByRole('button', { name: /^승인 / })).toBeNull();
    await user.selectOptions(screen.getByLabelText('등급'), 'SENIOR');
    await user.type(screen.getByLabelText('값'), '350000');
    await user.click(screen.getByTestId('rate-register'));
    await waitFor(() => expect(toasts.success[0]).toContain('초안 등록'));
    expect(seen[0].body).toMatchObject({ rateKind: 'STD_LABOR', keyCode: 'SENIOR', value: 350000, validTo: null });

    await user.clear(screen.getByLabelText('값'));
    await user.type(screen.getByLabelText('값'), '9900000');
    await user.click(screen.getByTestId('rate-register'));
    await waitFor(() => expect(toasts.error[0]?.title).toBe('[CST-422-001] CST 요청값 오류'));
  });

  it('EXEC: 승인은 If-Match 로, 등록자 본인 초안은 승인 버튼 비활성(4-eyes)', async () => {
    const user = userEvent.setup();
    renderAs(EXEC, ['EXEC'], <LaborRatesPage />);
    const approve = await screen.findByRole('button', { name: '승인 INTER (중급) 2026-10-05' });
    expect(approve).toBeEnabled();
    await user.click(approve);
    await waitFor(() => expect(toasts.success[0]).toContain('승인'));
    expect(seen[0]).toMatchObject({ method: 'POST', url: '/api/v1/cst/rates/r-draft:approve' });
    expect(seen[0].headers['if-match']).toBe('"v0"');
  });

  it('등록자와 승인자가 같으면 승인 버튼이 비활성', async () => {
    renderAs(ADMIN, ['ADMIN', 'EXEC'], <LaborRatesPage />);
    expect(await screen.findByRole('button', { name: '승인 INTER (중급) 2026-10-05' })).toBeDisabled();
  });
});

describe('관리 API 유틸', () => {
  it('전문성 파싱 · 표시 왕복', () => {
    expect(parseExpertise('RT_PSD=3,\nRT_PRC:1')).toEqual({ RT_PSD: 3, RT_PRC: 1 });
    expect(formatExpertise({ RT_PSD: 3 })).toBe('RT_PSD=3');
    expect(() => parseExpertise('RT_PSD=-1')).toThrow(/형식 오류/);
  });
  it('적용 중 판정은 승인 행 · [시작, 종료)', () => {
    expect(rateInEffect(approved, '2026-10-19')).toBe(true);
    expect(rateInEffect({ ...approved, validTo: '2026-10-19' }, '2026-10-19')).toBe(false);
    expect(rateInEffect(draft, '2026-10-19')).toBe(false);
  });
});
