import { describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { todayIso } from '@/features/admin/common';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { clearDraft, listDrafts, loadDraft, saveDraft } from './logDraft';
import { DemLogWritePage } from './DemLogWritePage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const PM = devUserId(4);
const ENG = devUserId(5);
const today = todayIso();
const project = {
  projectId: 'p-dem', buildingId: 'b1', status: 'IN_PROGRESS', completedOn: null, version: 4,
  workPackages: [{ id: 'wp-sup', packageType: 'DEM_SUPERVISION', facilityId: 'b1', status: 'DRAFT', locale: 'ko', currency: 'KRW', supervisorGap: false }],
};
const template = {
  id: 't1', phaseCode: 'UPPER_DEMOLITION', versionNo: 1, effectiveFrom: '2026-01-01', legalBasis: null, example: true,
  items: [
    { id: 'i1', seq: 1, title: '(예시) 비산 방지막', photoRequired: true, defaultSeverity: 'MEDIUM' },
    { id: 'i2', seq: 2, title: '(예시) 해체 순서', photoRequired: false, defaultSeverity: 'HIGH' },
  ],
};
const lockedLog = (amendments: unknown[]) => ({
  id: 'l-old', workPackageId: 'wp-sup', logDate: '2026-10-20', authorId: ENG, phaseCode: 'UPPER_DEMOLITION', progressPct: 40,
  workersCount: 12, status: 'LOCKED', lateSync: false, lockedAt: '2026-10-21T00:00:00Z', contentSha256: 'a'.repeat(64), version: 2,
  remarks: '(합성) 상부 해체', weather: null, equipment: null, checklist: [], amendments,
});

let network: 'up' | 'down' = 'up';
let amendReply: () => Response = () => HttpResponse.json(lockedLog([]), { status: 201 });

useMswServer(
  mswHttp.get('*/api/v1/dem/projects', () => HttpResponse.json([project])),
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p-dem', projectNo: 'PRJ-2026-0002', name: '(합성) 해체 감리' }])),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([
    { id: ENG, empNo: 'D-005', name: '정참여', status: 'ACTIVE' }, { id: PM, empNo: 'D-004', name: '최책임', status: 'ACTIVE' },
  ])),
  mswHttp.get('*/api/v1/dem/checklist-templates/:phase', () => HttpResponse.json(template)),
  mswHttp.get('*/api/v1/dem/work-packages/:wp/daily-logs', () => HttpResponse.json([
    { id: 'l-old', workPackageId: 'wp-sup', logDate: '2026-10-20', authorId: ENG, phaseCode: 'UPPER_DEMOLITION', progressPct: 40, workersCount: 12, status: 'LOCKED', locked: true, lateSync: false, lockedAt: '2026-10-21T00:00:00Z', contentSha256: 'a'.repeat(64), amendments: 1, pendingAmendments: 1, version: 2 },
  ])),
  mswHttp.put('*/api/v1/dem/daily-logs/:wp/:date', async ({ request, params }) => {
    if (network === 'down') return HttpResponse.error();
    await record(request);
    return HttpResponse.json({
      id: 'l-new', workPackageId: params.wp, logDate: params.date, status: 'SUBMITTED', version: 0, contentSha256: null,
      lateSync: false, replayed: false, amendmentPending: false, correctiveOrders: [{ orderNo: 'CO-2026-0009', severity: 'HIGH', dueAt: '', stopWorkRequested: false }], warnings: [],
    }, { headers: { ETag: '"v0"' } });
  }),
  mswHttp.get('*/api/v1/dem/daily-logs/:id', () => HttpResponse.json(lockedLog([
    { id: 'am-pm', fieldPath: 'workersCount', oldValue: 12, newValue: 11, reason: '(합성) 인원 오기', status: 'APPLIED', amendedBy: PM, amendedAt: '2026-10-21T01:00:00Z' },
    { id: 'am-eng', fieldPath: '$', oldValue: null, newValue: { remarks: '늦은 동기화' }, reason: 'LATE_SYNC_AFTER_LOCK', status: 'PENDING', amendedBy: ENG, amendedAt: '2026-10-21T02:00:00Z' },
  ]))),
  mswHttp.post('*/api/v1/dem/daily-logs/:id/amendments', async ({ request }) => {
    await record(request);
    return amendReply();
  }),
  mswHttp.post('*/api/v1/dem/amendments/:cmd', async ({ request }) => {
    await record(request);
    return problem(404, 'CORE-404-000', '없음', 'No static resource');
  }),
);

describe('감리일지 오프라인 초안(localStorage)', () => {
  it('업무 패키지 + 일자 키, 다시 저장해도 clientOpId 유지, 지우면 새 ID', () => {
    const body = { phaseCode: 'UPPER_DEMOLITION', submit: false };
    const a = saveDraft('wp1', '2026-10-26', body, 'DRAFT');
    const b = saveDraft('wp1', '2026-10-26', { ...body, remarks: '수정' }, 'PENDING_SEND');
    expect(b.clientOpId).toBe(a.clientOpId);
    expect(loadDraft('wp1', '2026-10-26')?.body.remarks).toBe('수정');
    expect(saveDraft('wp1', '2026-10-25', body, 'DRAFT').clientOpId).not.toBe(a.clientOpId);
    expect(listDrafts()[0].state).toBe('PENDING_SEND');
    clearDraft('wp1', '2026-10-26');
    expect(saveDraft('wp1', '2026-10-26', body, 'DRAFT').clientOpId).not.toBe(a.clientOpId);
  });
});

describe('감리일지 작성 · 정정 화면', () => {
  it('오프라인 제출 → 이 기기 보관(전송 대기) → online 이벤트에 같은 clientOpId 로 재전송', async () => {
    network = 'down';
    const user = userEvent.setup();
    renderAs(ENG, ['ENG'], <DemLogWritePage />);
    const checklist = await screen.findByTestId('dem-checklist');
    await user.selectOptions(within(checklist).getByLabelText('결과 2'), 'FAIL');
    await user.type(screen.getByLabelText('작업 인원'), '12');
    await user.type(screen.getByLabelText('특기 사항'), '(합성) 상부 해체 3일차');
    await user.click(screen.getByTestId('log-submit'));
    expect(await screen.findByTestId('log-offline')).toBeInTheDocument();
    const draft = loadDraft('wp-sup', today)!;
    expect(draft.state).toBe('PENDING_SEND');
    expect(screen.getByTestId('draft-chip')).toHaveTextContent(`전송 대기 · 작업 ID ${draft.clientOpId.slice(0, 8)}`);
    expect(seen).toHaveLength(0);

    network = 'up';
    await act(async () => { window.dispatchEvent(new Event('online')); });
    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].method).toBe('PUT');
    expect(seen[0].url).toBe(`/api/v1/dem/daily-logs/wp-sup/${today}`);
    expect(seen[0].body).toMatchObject({
      clientOpId: draft.clientOpId, phaseCode: 'UPPER_DEMOLITION', workersCount: 12, submit: true,
      checklist: [{ templateItemId: 'i1', result: 'NA' }, { templateItemId: 'i2', result: 'FAIL' }],
    });
    await waitFor(() => expect(toasts.success.join()).toContain('같은 작업 ID 로 다시 보냈습니다'));
    expect(loadDraft('wp-sup', today)).toBeNull();
  });

  it('온라인 제출 → 저장 완료 배너(시정지시 번호), 초안 없음', async () => {
    network = 'up';
    const user = userEvent.setup();
    renderAs(ENG, ['ENG'], <DemLogWritePage />);
    await screen.findByTestId('dem-checklist');
    await user.click(screen.getByTestId('log-submit'));
    expect(await screen.findByTestId('log-sent')).toHaveTextContent('CO-2026-0009');
    expect(loadDraft('wp-sup', today)).toBeNull();
  });

  it('잠긴 일지: 정정 이력 · 본인 요청은 승인 버튼 없음, 정정 요청 본문, 승인 API 미구현은 화면 안 안내', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <DemLogWritePage />, { route: '/?wp=wp-sup&log=l-old' });
    const am = await screen.findByTestId('dem-amendments');
    expect(within(am).getByText('적용됨')).toBeInTheDocument();
    expect(within(am).getByText('승인 대기')).toBeInTheDocument();
    // 다른 사람(정참여)의 대기 정정 → PM 에게 승인 · 반려 버튼
    await user.click(within(am).getByRole('button', { name: /^정정 승인 am-eng/ }));
    expect(await screen.findByTestId('correction-refused')).toHaveTextContent('DEM-03b');
    expect(seen[0].url).toBe('/api/v1/dem/amendments/am-eng:approve');

    await user.selectOptions(screen.getByLabelText('정정 항목'), 'workersCount');
    await user.type(screen.getByLabelText('정정 값'), '10');
    await user.type(screen.getByLabelText('정정 사유'), '(합성) 현장 기록 대조');
    await user.click(screen.getByTestId('correction-request'));
    await waitFor(() => expect(seen[1]?.url).toBe('/api/v1/dem/daily-logs/l-old/amendments'));
    expect(seen[1].body).toEqual({ fieldPath: 'workersCount', newValue: 10, reason: '(합성) 현장 기록 대조' });
    expect(seen[1].headers['idempotency-key']).toBeTruthy();
  });

  it('작성자 본인 화면: 본인 대기 정정에는 승인 버튼 대신 안내, 권한 없는 정정 요청은 403 을 화면 안에', async () => {
    amendReply = () => problem(403, 'CORE-403-001', '권한 없음', '권한 없음: dem:log-amendment:create');
    const user = userEvent.setup();
    renderAs(ENG, ['ENG'], <DemLogWritePage />, { route: '/?wp=wp-sup&log=l-old' });
    const am = await screen.findByTestId('dem-amendments');
    expect(within(am).queryByRole('button', { name: /^정정 승인/ })).toBeNull();
    expect(within(am).getByText('본인 요청 — 다른 사람이 승인')).toBeInTheDocument();
    await user.type(screen.getByLabelText('정정 값'), '(합성) 특기 사항 보완');
    await user.type(screen.getByLabelText('정정 사유'), '(합성) 누락');
    await user.click(screen.getByTestId('correction-request'));
    expect(await screen.findByTestId('correction-refused')).toHaveTextContent('CORE-403-001');
    expect(toasts.error).toHaveLength(0);
  });
});
