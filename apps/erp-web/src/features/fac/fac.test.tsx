import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { dDay, flattenTree, type Building, type TreeNode } from '@/api/fac';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { BuildingsPage } from './BuildingsPage';
import { BuildingDetailPage } from './BuildingDetailPage';
import { DuesPage } from './DuesPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);

const PM = devUserId(4);
const b1: Building = {
  id: 'b-1', siteId: 's-1', name: '(합성) 한빛타워', dongName: '', pnu: '1156011000110170007', roadAddress: '서울 합성구 합성로 1101',
  mainPurposeNm: '업무시설', structureNm: '철근콘크리트구조', grossFloorArea: 12000.5, floorsAbove: 5, floorsBelow: 1,
  approvalDate: '2001-05-20', status: 'VERIFIED', dataSource: 'REGISTER', useRestrictionFlag: false, version: 0,
};
const b2: Building = { ...b1, id: 'b-2', name: '(합성) 한빛타워 별관동', status: 'DRAFT', dataSource: 'MANUAL', pnu: null };
const tree: TreeNode[] = [
  { id: 'n1', nodeType: 'FLOOR', memberType: null, code: '1F', name: '1층', floorLevel: 1, gridRef: null, path: 'b.f1', children: [
    { id: 'n2', nodeType: 'MEMBER', memberType: 'COL', code: '1F-C-X1Y1', name: null, floorLevel: 1, gridRef: 'X1Y1', path: 'b.f1.m1', children: [] },
  ] },
];
const titles = [
  { mgmBldrgstPk: 'pk-101', buildingName: '(합성) 합성로 2001 빌딩', dongName: '101동', mainPurposeNm: '업무시설', structureNm: 'RC', grossFloorArea: 8200, floorsAbove: 15, floorsBelow: 1, approvalDate: '2001-05-20' },
  { mgmBldrgstPk: 'pk-102', buildingName: '(합성) 합성로 2001 빌딩', dongName: '102동', mainPurposeNm: '업무시설', structureNm: 'RC', grossFloorArea: 8100, floorsAbove: 15, floorsBelow: 1, approvalDate: '2001-05-20' },
];

const server = useMswServer(
  mswHttp.get('*/api/v1/core/clients', () => HttpResponse.json([{ id: 'c-a', name: '(합성) 한빛자산관리', clientType: 'PRIVATE' }])),
  mswHttp.get('*/api/v1/fac/buildings', () => HttpResponse.json({ content: [b1, b2], page: 0, size: 200, totalElements: 2, totalPages: 1 })),
  mswHttp.get('*/api/v1/fac/register-lookup', ({ request }) => {
    const a = new URL(request.url).searchParams.get('address');
    if (a === '합성로 2001') {
      return HttpResponse.json({
        address: { roadAddress: '서울 합성구 합성로 2001', jibunAddress: '', admCd: '1156011000', pnu: { value: '1156011000120010007' }, buildingName: null },
        register: { available: true, siteName: null, titles, failureReason: null }, failureReason: null,
      });
    }
    return HttpResponse.json({ address: null, register: null, failureReason: 'ADDRESS_NOT_FOUND' });
  }),
  mswHttp.post('*/api/v1/fac/buildings', async ({ request }) => {
    await record(request);
    const b = (await request.json()) as Record<string, unknown>;
    if (b.dataSource === 'MANUAL' && !b.force) {
      return problem(409, 'FAC-409-001', '중복 시설물 후보', '중복 시설물 후보가 존재합니다', {
        candidates: [{ id: 'b-2', name: '(합성) 한빛타워 별관동', nameSimilarity: 0.82, matchedBy: 'NAME_ADDRESS' }],
      });
    }
    return HttpResponse.json({ ...b1, id: 'b-new', name: String(b.name ?? b1.name) }, { status: 201, headers: { ETag: '"v0"' } });
  }),
  mswHttp.get('*/api/v1/fac/buildings/:id', ({ params }) => HttpResponse.json(params.id === 'b-2' ? b2 : b1, { headers: { ETag: '"v0"' } })),
  mswHttp.get('*/api/v1/fac/buildings/:id/nodes', () => HttpResponse.json(tree)),
  mswHttp.get('*/api/v1/fac/buildings/:id/grades', () => HttpResponse.json([
    { id: 'g1', buildingId: 'b-1', grade: 'C', inspectionType: 'DETAILED', performedOn: '2026-10-16', reportId: 'r1', performer: 'ATOM', source: 'REPORT', correctionOf: 'g0', recordedBy: PM },
    { id: 'g0', buildingId: 'b-1', grade: 'B', inspectionType: 'DETAILED', performedOn: '2026-10-16', reportId: 'r1', performer: 'ATOM', source: 'REPORT', correctionOf: null, recordedBy: PM },
  ])),
  mswHttp.get('*/api/v1/fac/buildings/:id/dues', () => HttpResponse.json([])),
  mswHttp.post('*/api/v1/fac/buildings/:id/merge', async ({ request }) => {
    await record(request);
    return HttpResponse.json({ sourceId: 'b-2', targetId: 'b-1', nodes: 3, drawings: 0, grades: 0, duesCancelled: 1, repairs: 0, parcels: 0 });
  }),
  mswHttp.get('*/api/v1/fac/dues', async ({ request }) => {
    await record(request);
    return HttpResponse.json([
      { id: 'd1', buildingId: 'b-1', buildingName: '(합성) 한빛타워', dongName: '', roadAddress: null, inspectionType: 'DETAILED', baseDate: null, dueDate: '2026-10-16', status: 'OVERDUE', daysLeft: -3 },
      { id: 'd2', buildingId: 'b-2', buildingName: '(합성) 별관', dongName: '', roadAddress: null, inspectionType: 'REGULAR', baseDate: '2026-04-01', dueDate: '2026-10-29', status: 'APPROACHING_30', daysLeft: 10 },
      { id: 'd3', buildingId: 'b-2', buildingName: '(합성) 별관', dongName: '', roadAddress: null, inspectionType: 'BMA_CHECK', baseDate: null, dueDate: '2027-10-19', status: 'SCHEDULED', daysLeft: 365 },
    ]);
  }),
);

describe('FAC 유틸', () => {
  it('D-day 배지: 경과 · 30일 · 90일 · 그 외', () => {
    expect(dDay(-3)).toEqual({ label: 'D+3 경과', tone: 'danger' });
    expect(dDay(0)).toEqual({ label: 'D-Day', tone: 'danger' });
    expect(dDay(45)).toEqual({ label: 'D-45', tone: 'warn' });
    expect(dDay(200).tone).toBe('ok');
  });
  it('위치 트리 평탄화(깊이)', () => {
    expect(flattenTree(tree).map((x) => [x.node.code, x.depth])).toEqual([['1F', 0], ['1F-C-X1Y1', 1]]);
  });
});

describe('시설물 등록 마법사', () => {
  it('주소 조회 → 다동이면 표제부 선택 필수 → 등록(lookupAddress · mgmBldrgstPk · Idempotency-Key) → 상세로 이동', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <BuildingsPage />, { route: '/fac/buildings', path: '/fac/buildings' });
    expect(await within(await screen.findByTestId('fac-grid')).findByText('(합성) 한빛타워')).toBeInTheDocument();
    await user.type(screen.getByLabelText('도로명주소'), '합성로 2001');
    await user.click(screen.getByTestId('fac-lookup'));
    expect(await screen.findByText(/PNU 1156011000120010007/)).toBeInTheDocument();
    expect(screen.getByTestId('fac-register')).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('표제부(동)'), 'pk-102');
    await user.selectOptions(screen.getByLabelText('소유 고객사'), 'c-a');
    await user.click(screen.getByTestId('fac-register'));
    await waitFor(() => expect(screen.getByTestId('navigated')).toHaveTextContent('/fac/buildings/b-new'));
    expect(seen[0].body).toEqual({ ownerClientId: 'c-a', lookupAddress: '합성로 2001', mgmBldrgstPk: 'pk-102' });
    expect(seen[0].headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(toasts.success[0]).toContain('대장 자동 입력');
  });

  it('주소 없음 → 수기 입력 전환 → 중복 후보 409 → 사유 남기고 강제 등록', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <BuildingsPage />, { route: '/fac/buildings', path: '/fac/buildings' });
    await user.type(screen.getByLabelText('도로명주소'), '없는로 1');
    await user.click(screen.getByTestId('fac-lookup'));
    expect(await screen.findByText(/주소를 찾지 못했습니다/)).toBeInTheDocument();
    await user.click(screen.getByTestId('fac-to-manual'));
    expect(screen.getByLabelText('도로명주소(수기)')).toHaveValue('없는로 1');
    await user.type(screen.getByLabelText('건물명'), '(합성) 한빛타워 별관');
    await user.type(screen.getByLabelText('지상 층수'), '3');
    await user.click(screen.getByTestId('fac-register'));
    const dup = await screen.findByTestId('fac-duplicates');
    expect(dup).toHaveTextContent('중복 시설물 후보 1건');
    expect(dup).toHaveTextContent('이름 유사도 82%');
    const firstKey = seen[0].headers['idempotency-key'];
    await user.type(within(dup).getByLabelText('강제 등록 사유'), '(합성) 별도 동 확인');
    await user.click(within(dup).getByRole('button', { name: '사유 남기고 등록' }));
    await waitFor(() => expect(screen.getByTestId('navigated')).toHaveTextContent('/fac/buildings/b-new'));
    expect(seen[1].body).toMatchObject({ dataSource: 'MANUAL', name: '(합성) 한빛타워 별관', floorsAbove: 3, force: true, forceReason: '(합성) 별도 동 확인' });
    expect(seen[1].headers['idempotency-key']).not.toBe(firstKey);
  });
});

describe('점검 도래 · 상세', () => {
  it('도래 목록 D-day 배지와 기간 필터(withinDays)', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <DuesPage />);
    const grid = await screen.findByTestId('due-grid');
    expect(await within(grid).findByText('D+3 경과')).toHaveClass('badge', 'danger');
    expect(within(grid).getByText('D-10')).toHaveClass('danger');
    expect(within(grid).getByText('D-365')).toHaveClass('ok');
    expect(seen[0].search).toBe('?withinDays=365');
    await user.selectOptions(screen.getByLabelText('도래 기간'), '30');
    await waitFor(() => expect(seen.at(-1)?.search).toBe('?withinDays=30'));
  });

  it('상세: 위치 트리 · 등급 이력(읽기 전용, 정정본 표시) · 병합 대화상자', async () => {
    const user = userEvent.setup();
    renderAs(PM, ['PM'], <BuildingDetailPage />, { route: '/fac/buildings/b-2', path: '/fac/buildings/:id' });
    expect(await screen.findByText('1F-C-X1Y1')).toBeInTheDocument();
    expect(screen.getByText('b.f1.m1')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: /등급 이력/ }));
    const grades = screen.getByTestId('fac-grades');
    expect(within(grades).getByText('정정본')).toBeInTheDocument();
    expect(within(grades).queryByRole('button')).toBeNull();

    await user.click(screen.getByTestId('fac-merge-open'));
    const dialog = screen.getByRole('dialog', { name: '중복 시설물 병합' });
    await within(dialog).findByRole('option', { name: /\(합성\) 한빛타워 · 서울/ });
    expect(within(dialog).queryByRole('option', { name: /별관동/ })).toBeNull(); // 자기 자신 제외
    await user.selectOptions(within(dialog).getByLabelText('존속 시설물'), 'b-1');
    await user.type(within(dialog).getByLabelText('병합 사유'), '(합성) 이중 등록');
    await user.click(within(dialog).getByTestId('merge-confirm'));
    expect(await within(dialog).findByTestId('merge-report')).toHaveTextContent('3');
    expect(seen.find((s) => s.url.endsWith('/merge'))).toMatchObject({ url: '/api/v1/fac/buildings/b-2/merge', body: { targetId: 'b-1', reason: '(합성) 이중 등록' } });
  });

  it('도래 403 은 화면 안 오류 상자', async () => {
    server.use(mswHttp.get('*/api/v1/fac/dues', () => problem(403, 'CORE-403-001', '권한 없음', 'fac:due:read')));
    renderAs(devUserId(5), ['ENG'], <DuesPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('[CORE-403-001] 권한 없음');
  });
});
