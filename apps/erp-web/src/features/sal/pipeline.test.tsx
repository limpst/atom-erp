import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { canMove, parseBidRates, type BidNotice, type Opportunity } from '@/api/sal';
import { problem, record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { filterNotices, SalPipelinePage } from './SalPipelinePage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const SALES = devUserId(2);
const EXEC = devUserId(1);
const ADMIN = devUserId(9);

const opp = (oppNo: string, stage: Opportunity['stage'], extra: Partial<Opportunity> = {}): Opportunity => ({
  id: `id-${oppNo}`, oppNo, source: 'INQUIRY', bidNoticeId: null, clientId: 'c1', facilityId: null, dueInspectionType: null,
  dueDate: null, serviceType: 'PRECISE_DIAG', title: `(합성) ${oppNo} 기회`, stage, expectedAmount: 10_000_000, probability: 50,
  expectedClose: null, ownerId: SALES, lostReason: null, criteriaChanged: false, version: 3, ...extra,
});
const opps = [opp('OPP-1', 'LEAD'), opp('OPP-2', 'QUALIFYING', { bidNoticeId: 'n1' }), opp('OPP-3', 'NEGOTIATING'), opp('OPP-4', 'WON')];
const iso = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString();
const notices: BidNotice[] = [
  { id: 'n1', bidNo: 'R4-0001', bidSeq: '000', title: '(합성) 청사 정밀점검', orgName: '(합성) 합성시', demandOrgName: null, estPrice: 182_000_000, closeAt: iso(6.5), openAt: null, noticeKind: '등록', status: 'NEW', fitScore: null, supersededBy: null, updatedAt: '' },
  { id: 'n2', bidNo: 'R4-0003', bidSeq: '000', title: '(합성) 가상교 설계', orgName: '(합성) 도로공사', demandOrgName: null, estPrice: 95_000_000, closeAt: iso(3.5), openAt: null, noticeKind: '등록', status: 'SUPERSEDED', fitScore: null, supersededBy: 'n3', updatedAt: '' },
  { id: 'n3', bidNo: 'R4-0003', bidSeq: '001', title: '(합성) 가상교 설계(정정)', orgName: '(합성) 도로공사', demandOrgName: null, estPrice: 98_000_000, closeAt: iso(5.5), openAt: null, noticeKind: '정정', status: 'NEW', fitScore: null, supersededBy: null, updatedAt: '' },
  { id: 'n4', bidNo: 'R4-0004', bidSeq: '000', title: '(합성) 표본학교 진단', orgName: '(합성) 교육청', demandOrgName: null, estPrice: 31_000_000, closeAt: iso(20), openAt: null, noticeKind: '취소', status: 'CANCELLED', fitScore: null, supersededBy: null, updatedAt: '' },
];

const page = (content: Opportunity[]) => ({ content, page: 0, size: 300, totalElements: content.length, totalPages: 1 });
let moveReply: (body: Record<string, unknown>) => Response = (b) =>
  HttpResponse.json({ ...opps[0], stage: b.toStage, version: 4 }, { headers: { ETag: '"v4"' } });

useMswServer(
  mswHttp.get('*/api/v1/sal/opportunities', () => HttpResponse.json(page(opps))),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([{ id: SALES, empNo: 'D-002', name: '박영업', status: 'ACTIVE' }])),
  mswHttp.post('*/api/v1/sal/opportunities/:id', async ({ request }) => {
    await record(request);
    return moveReply((await request.clone().json()) as Record<string, unknown>);
  }),
  mswHttp.get('*/api/v1/sal/bid-notices', ({ request }) => {
    const q = new URL(request.url).searchParams;
    const st = q.get('status');
    return HttpResponse.json(notices.filter((n) => !st || n.status === st));
  }),
  mswHttp.get('*/api/v1/sal/collect-logs', () => HttpResponse.json([
    { id: 3, jobType: 'NOTICE', rangeFrom: iso(-2), rangeTo: iso(0), startedAt: iso(0), finishedAt: iso(0), success: true, fetched: 5, upserted: 5, inserted: 5, error: null },
  ])),
  mswHttp.post('*/api/v1/sal/:cmd', async ({ request, params }) => {
    if (params.cmd !== 'bid-notices:collect-now') return problem(404, 'X', 'x', 'x');
    await record(request);
    return HttpResponse.json({ logId: 4, jobType: 'NOTICE', from: '', to: '', success: true, fetched: 5, upserted: 0, inserted: 0, error: null });
  }),
  mswHttp.post('*/api/v1/sal/opportunities/:id/simulations', async ({ request }) => {
    await record(request);
    return HttpResponse.json({
      id: 's1', opportunityId: 'id-OPP-2', criteriaCode: 'LOCAL_GOV_SERVICE_V2025', passScore: 95, lowerLimitRate: 0.87745,
      inputs: { noticeDate: '2026-10-25', estPrice: 182000000, serviceType: 'PRECISE_INSPECTION', similarYears: 5, similarAmount: 0, performanceRecords: [] },
      capability: { items: [
        { key: 'SIMILAR_PERFORMANCE', raw: 0, ratio: 0, score: 9, max: 15 },
        { key: 'ENGINEERS', raw: 6, ratio: 1.2, score: 15, max: 15 },
        { key: 'CREDIT', raw: 'A0', ratio: null, score: 14, max: 15 },
      ], total: 38 },
      scenarios: [
        { bidRate: 0.877, priceScore: 0, total: 38, pass: false, belowLowerLimit: true },
        { bidRate: 0.88, priceScore: 55, total: 93, pass: false, belowLowerLimit: false },
        { bidRate: 0.9, priceScore: 54.1, total: 96.1, pass: true, belowLowerLimit: false },
      ],
      recommendedBidRate: 0.9, notice: '⚠️ 예시 산식',
    });
  }),
);

describe('SAL 유틸', () => {
  it('투찰률 입력: % 와 비율을 모두 받는다', () => {
    expect(parseBidRates('87.7, 88  0.9,abc')).toEqual([0.877, 0.88, 0.9]);
    expect(parseBidRates('')).toEqual([]);
  });
  it('전이표: 리드 → 검토 가능, 리드 → 수주 불가', () => {
    expect(canMove('LEAD', 'QUALIFYING')).toBe(true);
    expect(canMove('LEAD', 'WON')).toBe(false);
  });
  it('공고 필터: 마감 일수 · 최소 가격 · 정정 전/취소 숨기기', () => {
    expect(filterNotices(notices, { closingWithin: 7 }).map((n) => n.id)).toEqual(['n1', 'n2', 'n3']);
    expect(filterNotices(notices, { minPrice: 96_000_000 }).map((n) => n.id)).toEqual(['n1', 'n3']);
    expect(filterNotices(notices, { hideClosed: true }).map((n) => n.id)).toEqual(['n1', 'n3']);
  });
});

describe('영업 파이프라인 보드', () => {
  it('단계 열 · 카드, 단계 버튼 → :move 에 If-Match(응답 version)', async () => {
    moveReply = (b) => HttpResponse.json({ ...opps[0], stage: b.toStage, version: 4 }, { headers: { ETag: '"v4"' } });
    const user = userEvent.setup();
    renderAs(SALES, ['SALES'], <SalPipelinePage />);
    const lead = await screen.findByTestId('col-LEAD');
    expect(within(lead).getByText('(합성) OPP-1 기회')).toBeInTheDocument();
    expect(within(screen.getByTestId('col-WON')).getByText('(합성) OPP-4 기회')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'OPP-1 → 검토' }));
    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].url).toBe('/api/v1/sal/opportunities/id-OPP-1:move');
    expect(seen[0].headers['if-match']).toBe('"v3"');
    expect(seen[0].body).toEqual({ toStage: 'QUALIFYING' });
    await waitFor(() => expect(toasts.success[0]).toContain('검토'));
  });

  it('서버 403 · 422 는 보드 위 배너(토스트 아님), 실주는 사유를 고르게 한다', async () => {
    moveReply = () => problem(403, 'CORE-403-001', '권한 없음', '권한 없음: sal:opportunity:update');
    const user = userEvent.setup();
    renderAs(EXEC, ['EXEC'], <SalPipelinePage />);
    await user.click(await screen.findByRole('button', { name: 'OPP-1 → 검토' }));
    const banner = await screen.findByTestId('board-refused');
    expect(banner).toHaveTextContent('[CORE-403-001]');
    expect(banner).toHaveTextContent('이 역할로는');
    expect(toasts.error).toHaveLength(0);

    moveReply = () => problem(422, 'SAL-422-010', 'Lost 사유 필요', 'Lost 사유 코드 필수(BR-SAL-010)');
    await user.click(screen.getByRole('button', { name: 'OPP-3 → 실주' }));
    const dialog = await screen.findByRole('dialog', { name: '실주(으)로 이동' });
    await user.selectOptions(within(dialog).getByLabelText('실주 사유'), 'PRICE');
    await user.click(within(dialog).getByTestId('move-confirm'));
    await waitFor(() => expect(screen.getByTestId('board-refused')).toHaveTextContent('SAL-422-010'));
    expect(seen.at(-1)?.body).toEqual({ toStage: 'LOST', lostReason: 'PRICE' });
  });
});

describe('공고 수집', () => {
  it('목록 · 필터 · ADMIN 지금 수집', async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, ['ADMIN'], <SalPipelinePage />, { route: '/?tab=notices' });
    const table = await screen.findByTestId('notices');
    expect(await within(table).findByText('(합성) 청사 정밀점검')).toBeInTheDocument();
    expect(within(table).getByText('정정됨(이전 차수)')).toBeInTheDocument();
    expect(screen.getByTestId('collect-last')).toHaveTextContent('신규 5');
    await user.click(screen.getByLabelText('정정 · 취소 숨기기'));
    expect(within(table).queryByText('(합성) 표본학교 진단')).toBeNull();
    await user.type(screen.getByLabelText('마감 일수'), '6');
    expect(within(table).queryByText('(합성) 청사 정밀점검')).toBeNull();
    expect(within(table).getByText('(합성) 가상교 설계(정정)')).toBeInTheDocument();
    await user.click(screen.getByTestId('collect-now'));
    await waitFor(() => expect(toasts.success[0]).toContain('받은 5건'));
  });
});

describe('적격심사 시뮬레이션', () => {
  it('입력 → 기존 /simulations API, 항목별 점수 · 시나리오 판정 표시', async () => {
    const user = userEvent.setup();
    renderAs(SALES, ['SALES'], <SalPipelinePage />, { route: '/?tab=sim' });
    const sel = await screen.findByLabelText('시뮬레이션 기회');
    await waitFor(() => expect((sel as HTMLSelectElement).value).toBe('id-OPP-2')); // 공고 연결 기회 우선
    await user.clear(screen.getByLabelText('투찰률 목록'));
    await user.type(screen.getByLabelText('투찰률 목록'), '87.7, 88, 90');
    await user.click(screen.getByTestId('sim-run'));
    const result = await screen.findByTestId('sim-result');
    expect(seen[0].url).toBe('/api/v1/sal/opportunities/id-OPP-2/simulations');
    expect(seen[0].body).toMatchObject({ bidRates: [0.877, 0.88, 0.9], engineersQualified: 6, engineersRequired: 5, creditGrade: 'A0', disabledStandardWorkplace: false });
    expect(within(result).getByText('유사 용역 실적(5년)')).toBeInTheDocument();
    expect(within(screen.getByTestId('sim-scenarios')).getByText('하한율 미만 — 탈락')).toBeInTheDocument();
    expect(within(screen.getByTestId('sim-scenarios')).getByText('통과')).toBeInTheDocument();
    expect(result).toHaveTextContent('90.00%');
  });
});
