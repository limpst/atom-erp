import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { FldSetupPage } from './FldSetupPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const PM = devUserId(4);
const ENG = devUserId(5);
const zone = (id: string, name: string) => ({ id, roundId: 'r1', zoneName: name, assigneeId: ENG, floorNodeId: 'f1', status: 'NOT_STARTED', defectCount: 0 });
let rounds = [{ id: 'r1', projectId: 'p1', buildingId: 'b1', roundNo: 1, surveyType: 'PRECISE_INSPECTION', evidenceMode: false, status: 'IN_PROGRESS', completedAt: null, lastSeq: 9, zones: [zone('z1', '1F-전체')] }];
let roundReply: () => Response;

useMswServer(
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p1', projectNo: 'PRJ-2026-0001', name: '(합성) 한빛타워 정밀안전진단' }])),
  mswHttp.get('*/api/v1/core/projects/:id/members', () => HttpResponse.json([
    { id: 'm1', employeeId: ENG, empNo: 'D-005', roleInProject: 'PART', fromDate: '2026-01-01', toDate: '2026-12-31' },
    { id: 'm2', employeeId: PM, empNo: 'D-004', roleInProject: 'RESP', fromDate: '2026-01-01', toDate: '2026-12-31' },
  ])),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([
    { id: ENG, empNo: 'D-005', name: '정참여', status: 'ACTIVE' }, { id: PM, empNo: 'D-004', name: '최책임', status: 'ACTIVE' },
  ])),
  mswHttp.get('*/api/v1/fac/buildings', () => HttpResponse.json({ content: [
    { id: 'b1', name: '(합성) 한빛타워', dongName: '', status: 'ACTIVE' }, { id: 'b2', name: '(합성) 별관', dongName: '', status: 'ACTIVE' },
  ], page: 0, size: 200, totalElements: 2, totalPages: 1 })),
  mswHttp.get('*/api/v1/fac/buildings/:id/nodes', () => HttpResponse.json([
    { id: 'f1', nodeType: 'FLOOR', memberType: null, code: '1F', name: null, floorLevel: 1, gridRef: null, path: 'b.1F', children: [] },
    { id: 'f2', nodeType: 'FLOOR', memberType: null, code: '2F', name: null, floorLevel: 2, gridRef: null, path: 'b.2F', children: [] },
  ])),
  mswHttp.get('*/api/v1/fld/rounds', () => HttpResponse.json(rounds)),
  mswHttp.post('*/api/v1/fld/rounds', async ({ request }) => {
    await record(request);
    return roundReply();
  }),
  mswHttp.post('*/api/v1/fld/rounds/:id/zones', async ({ request }) => {
    await record(request);
    const b = (await request.clone().json()) as { zoneName: string };
    if (b.zoneName === '1F-전체') return problem(422, 'FLD-422-001', '검증 실패', '같은 회차에 같은 구역 이름이 있습니다: 1F-전체');
    return HttpResponse.json(zone('z9', b.zoneName), { status: 201 });
  }),
);

describe('조사 회차 · 구역 설정', () => {
  it('회차 만들기: 프로젝트 · 시설물 · 종류 · 증거 모드 → POST /fld/rounds, 재시도는 같은 멱등 키', async () => {
    let calls = 0;
    roundReply = () => {
      calls++;
      if (calls === 1) return HttpResponse.json({ status: 503, title: '잠시 후 다시' }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } });
      const r = { id: 'r2', projectId: 'p1', buildingId: 'b2', roundNo: 2, surveyType: 'REGULAR', evidenceMode: true, status: 'IN_PROGRESS', completedAt: null, lastSeq: 0, zones: [] };
      rounds = [...rounds, r];
      return HttpResponse.json(r, { status: 201 });
    };
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <FldSetupPage />);
    await screen.findByRole('option', { name: '(합성) 별관' });
    await user.selectOptions(screen.getByLabelText('대상 시설물'), 'b2');
    await user.selectOptions(screen.getByLabelText('조사 종류'), 'REGULAR');
    await user.click(screen.getByLabelText('증거 모드'));
    await user.click(screen.getByTestId('round-create'));
    await waitFor(() => expect(toasts.error).toHaveLength(1));
    await user.click(screen.getByTestId('round-create'));
    await waitFor(() => expect(toasts.success[0]).toContain('2회차 정기점검 생성'));
    expect(seen[0].body).toMatchObject({ projectId: 'p1', buildingId: 'b2', surveyType: 'REGULAR', evidenceMode: true, roundNo: null });
    expect(seen[1].headers['idempotency-key']).toBe(seen[0].headers['idempotency-key']);
    expect(await screen.findByText('아직 구역이 없습니다 — 아래에서 추가하세요')).toBeInTheDocument();
  });

  it('구역 추가: 층(FLOOR 노드) · 투입 인원 담당 → POST zones, 중복 이름 422 는 화면 안', async () => {
    rounds = rounds.slice(0, 1);
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <FldSetupPage />);
    const zones = await screen.findByTestId('zones');
    expect(await within(zones).findByText('1F-전체')).toBeInTheDocument();
    await user.type(screen.getByLabelText('구역 이름'), '1F-전체');
    await waitFor(() => expect(screen.getByLabelText('층').querySelectorAll('option')).toHaveLength(3));
    await user.selectOptions(screen.getByLabelText('담당 조사자'), ENG);
    await user.click(screen.getByTestId('zone-add'));
    expect(await screen.findByTestId('zone-refused')).toHaveTextContent('FLD-422-001');
    await user.clear(screen.getByLabelText('구역 이름'));
    await user.type(screen.getByLabelText('구역 이름'), '2F-동측');
    await user.selectOptions(screen.getByLabelText('층'), 'f2');
    await user.click(screen.getByTestId('zone-add'));
    await waitFor(() => expect(toasts.success[0]).toContain('구역 2F-동측 추가'));
    expect(seen[1].url).toBe('/api/v1/fld/rounds/r1/zones');
    expect(seen[1].body).toEqual({ zoneName: '2F-동측', floorNodeId: 'f2', assigneeId: ENG });
  });
});
