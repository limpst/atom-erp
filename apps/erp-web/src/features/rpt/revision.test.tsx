import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { gradeTree, type GradeDetail } from '@/api/rpt';
import { renderAs, seen, toasts, useMswServer, record } from '@/test/mocks';
import { RevisionDetailPage } from './RevisionDetailPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const ev = (v: number, ids: string[]) => [
  { indicator: 'crack_width_mm', value: v, unit: 'mm', rating: 'c', threshold: 0.3, defectIds: ids },
  { indicator: 'spall_m2_per_member', value: 0, unit: 'm2/member', rating: 'a', threshold: 0.01, defectIds: [] },
];
const grade: GradeDetail = {
  revisionId: 'rv1', weightedScore: 3, computedGrade: 'C', finalGrade: 'C', overridden: false, overrideReason: null,
  resultSha256: 'e'.repeat(64), decidedBy: null, ruleSet: { COND_RATING: 'BLD_RC@2026.1' },
  members: [
    { buildingCode: 'B01', floorCode: '1F', memberType: 'COLUMN', count: 10, rating: 'c', score: 3, weight: 0.25, evidence: ev(0.25, ['D-0001']) },
    { buildingCode: 'B01', floorCode: '2F', memberType: 'COLUMN', count: 10, rating: 'b', score: 2, weight: 0.25, evidence: ev(0.15, ['D-0002']) },
  ],
  scopeTree: { floorScores: [{ buildingCode: 'B01', floorCode: '1F', score: 3 }, { buildingCode: 'B01', floorCode: '2F', score: 2 }], buildingScores: [{ buildingCode: 'B01', score: 2.5 }], appliedFloorRules: [] },
};

useMswServer(
  mswHttp.get('*/api/v1/rpt/revisions/:id', () => HttpResponse.json({
    id: 'rv1', reportId: 'r1', revNo: 2, status: 'PUBLISHED', snapshotId: 's', changeNote: null, contentHash: 'h', pdfSha256: 'p'.repeat(64),
    hwpxSha256: 'h'.repeat(64), pageCount: 3, publishedAt: '2026-10-19T00:00:00Z', deliveredAt: null, supersededBy: null, version: 5, grade: null,
  }, { headers: { ETag: '"v5"' } })),
  mswHttp.get('*/api/v1/rpt/reports/:id', () => HttpResponse.json({ id: 'r1', title: '(합성) 정밀안전점검 보고서', revisions: [] })),
  mswHttp.get('*/api/v1/rpt/revisions/:id/grade', () => HttpResponse.json(grade)),
  mswHttp.get('*/api/v1/rpt/revisions/:id/sections', () => HttpResponse.json([
    { sectionKey: 'ch1.overview', title: '1. 개요', orderNo: 1, source: 'AUTO', version: 0, blocks: [
      { blockId: 'blk-1', kind: 'PARAGRAPH', text: '(합성) 점검 개요 문단', data: null, locked: true, textHash: '1'.repeat(64), source: 'AUTO' },
    ] },
    { sectionKey: 'ch4.quantities', title: '4. 결함물량표', orderNo: 2, source: 'AUTO', version: 0, blocks: [
      { blockId: 'blk-2', kind: 'TABLE', text: '', data: { columns: ['동', '층'], rows: [['B01', '1F']] }, locked: true, textHash: '2'.repeat(64), source: 'AUTO' },
    ] },
    { sectionKey: 'ch9.opinion', title: '9. 종합 의견', orderNo: 3, source: 'HUMAN', version: 2, blocks: [
      { blockId: 'blk-3', kind: 'PARAGRAPH', text: '(합성) 책임기술자 의견', data: null, locked: false, textHash: '3'.repeat(64), source: 'HUMAN' },
    ] },
  ])),
  mswHttp.get('*/api/v1/rpt/revisions/:id/anchors', () => HttpResponse.json({
    revisionId: 'rv1', source: 'ESTIMATED', unit: 'mm', pageCount: 3, sha256: 'a'.repeat(64), anchors: [
      { blockId: 'blk-1', sectionKey: 'ch1.overview', kind: 'PARAGRAPH', page: 2, rects: [{ page: 2, x: 20, y: 32, w: 170, h: 24 }], textHash: '1' },
      { blockId: 'blk-2', sectionKey: 'ch4.quantities', kind: 'TABLE', page: 2, rects: [{ page: 2, x: 20, y: 68, w: 170, h: 22 }], textHash: '2' },
    ],
  })),
  mswHttp.get('*/api/v1/rpt/revisions/:id/files/report.hwpx', async ({ request }) => {
    await record(request);
    return new HttpResponse(new Uint8Array([0x50, 0x4b, 3, 4, 1, 2]), { headers: { 'Content-Type': 'application/hwp+zip', 'Content-Disposition': 'attachment; filename="report.hwpx"' } });
  }),
  mswHttp.get('*/api/v1/rpt/revisions/:id/drawings/:b/:f', async ({ request }) => {
    await record(request);
    return new HttpResponse('0\nSECTION\n', { headers: { 'Content-Type': 'application/dxf', 'Content-Disposition': "attachment; filename*=UTF-8''B01-2F.dxf" } });
  }),
);

describe('등급 트리', () => {
  it('동 → 층 → 부재(근거) 로 묶고 scopeTree 점수를 붙인다', () => {
    const t = gradeTree(grade);
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ label: '동 B01', score: 2.5 });
    expect(t[0].children.map((c) => [c.label, c.score])).toEqual([['층 1F', 3], ['층 2F', 2]]);
    expect(t[0].children[0].children[0]).toMatchObject({ label: 'COLUMN', rating: 'c', count: 10 });
    expect(t[0].children[0].children[0].evidence?.[0].defectIds).toEqual(['D-0001']);
  });
});

describe('리비전 상세', () => {
  it('블록 읽기 보기 · 앵커 오버레이(추정) 클릭 → 블록 강조 · 근거 트리 · HWPX/DXF 다운로드', async () => {
    const user = userEvent.setup();
    renderAs(devUserId(4), ['PM'], <RevisionDetailPage />, { route: '/reports/r1/revisions/rv1', path: '/reports/:id/revisions/:revId' });
    expect(await screen.findByTestId('rev-detail-status')).toHaveTextContent('발행');
    const blocks = await screen.findByTestId('block-view');
    expect(within(blocks).getAllByText('🔒 잠금(수치)')).toHaveLength(2);
    expect(within(blocks).getByText('사람 편집')).toBeInTheDocument();
    expect(within(blocks).getByRole('cell', { name: 'B01' })).toBeInTheDocument();
    expect(await screen.findByText('추정 위치(ESTIMATED)')).toBeInTheDocument();
    const page2 = screen.getByTestId('anchor-page-2');
    await user.click(within(page2).getByText('TABLE'));
    expect(screen.getByTestId('block-blk-2')).toHaveClass('highlight');
    expect(within(screen.getByTestId('anchor-list')).getAllByRole('row')).toHaveLength(3);
    const tree = screen.getByTestId('grade-tree');
    expect(tree).toHaveTextContent('동 B01');
    expect(tree).toHaveTextContent('균열 최대폭');
    expect(tree).toHaveTextContent('D-0002');

    await user.click(screen.getByTestId('dl-hwpx'));
    await waitFor(() => expect(screen.getByTestId('dl-result')).toHaveTextContent('report.hwpx · 6 bytes'));
    await user.click(screen.getByTestId('dl-dxf-B01-2F'));
    await waitFor(() => expect(screen.getByTestId('dl-result')).toHaveTextContent('B01-2F.dxf'));
    expect(seen.map((s) => s.url)).toEqual(['/api/v1/rpt/revisions/rv1/files/report.hwpx', '/api/v1/rpt/revisions/rv1/drawings/B01/2F.dxf']);
    expect(toasts.error).toHaveLength(0);
  });
});
