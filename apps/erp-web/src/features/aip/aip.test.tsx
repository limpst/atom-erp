import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { auditHashesOnly, promotionNeeds, type Job, type ModelRow, type Suggestion } from '@/api/aip';
import { record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { AipPage } from './AipPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);
vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule);

const ENG = devUserId(5);
const job = (over: Partial<Job>): Job => ({
  id: 'j1', serviceCode: 'CRACK_DETECT', mode: 'ASYNC', priority: 1, status: 'SUCCEEDED', projectId: 'p1', attempts: 1, maxAttempts: 3,
  progressPct: 100, routeAlias: 'production', modelVersionId: 'm1', inputSha256: 'f'.repeat(64), deadlineAt: null, cancelRequested: false,
  result: {}, error: null, queuedAt: '2026-10-19T00:00:00Z', startedAt: null, finishedAt: null, requestedBy: ENG, ...over,
});
const model = (over: Partial<ModelRow>): ModelRow => ({
  id: 'm1', name: 'crack', version: '1.0.0', base_model: 'gemma-4-12b-it', stage: 'PRODUCTION', artifact_uri: 'vllm://x',
  approved_by: null, exec_approved_by: null, deployments: [{ serviceCode: 'CRACK_DETECT', alias: 'production', trafficPct: 100 }], ...over,
});
const sugg = (over: Partial<Suggestion>): Suggestion => ({
  id: 's-aaaaaaaa1', job_id: 'j1', project_id: 'p1', photo_attachment_id: 'a1', defect_type: 'CRACK', crack_pattern: 'HORIZONTAL',
  width_max_mm: 0.3, width_mean_mm: 0.3, width_uncert_mm: 0.043, length_m: 0.038, mm_per_px: 0.0428, scale_source: 'ARUCO', confidence: 0.8,
  description: '(합성) 수평 균열', flags: ['NEAR_GRADE_BOUNDARY_0_3MM'], status: 'SUGGESTED', created_at: '2026-10-19T00:00:00Z',
  decision: null, reason_code: null, decided_by: null, decided_at: null, ...over,
});
let suggestions: Suggestion[] = [];

useMswServer(
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p1', projectNo: 'PRJ-2026-0001', name: '(합성) 한빛타워' }])),
  mswHttp.get('*/api/v1/aip/jobs', () => HttpResponse.json([
    job({}), job({ id: 'j2', status: 'QUEUED', serviceCode: 'KB_SEARCH', projectId: null }),
    job({ id: 'j3', status: 'DEAD', error: { code: 'DEADLINE_EXCEEDED' } }), job({ id: 'j4', status: 'CANCELLED' }),
  ])),
  mswHttp.get('*/api/v1/aip/jobs/:id/events', () => HttpResponse.json([{ id: 1, at: '2026-10-19T00:00:00Z', kind: 'STATE', payload: { to: 'QUEUED' } }])),
  mswHttp.delete('*/api/v1/aip/jobs/:id', async ({ request }) => {
    await record(request);
    return HttpResponse.json(job({ id: 'j2', status: 'CANCELLED' }));
  }),
  mswHttp.get('*/api/v1/aip/models', () => HttpResponse.json([model({}), model({ id: 'm9', name: 'crack', version: '1.1.0', stage: 'EVALUATED', deployments: null })])),
  mswHttp.post('*/api/v1/aip/models/:id/approval', async ({ request }) => {
    await record(request);
    return HttpResponse.json(model({ id: 'm9', stage: 'EVALUATED', approved_by: devUserId(6) }));
  }),
  mswHttp.get('*/api/v1/aip/suggestions', () => HttpResponse.json(suggestions)),
  mswHttp.post('*/api/v1/aip/suggestions/:id/decision', async ({ request, params }) => {
    await record(request);
    const b = (await request.clone().json()) as { decision: string };
    suggestions = suggestions.map((s) => (s.id === params.id ? { ...s, status: b.decision as Suggestion['status'], decided_by: ENG } : s));
    return HttpResponse.json({ ok: true });
  }),
  mswHttp.get('*/api/v1/aip/audit', () => HttpResponse.json([
    { job_id: 'j1', created_at: '2026-10-19T00:00:00Z', service_code: 'CRACK_DETECT', model_name: 'crack', model_version: '1.0.0', adapter: 'crack',
      prompt_key: 'vision.crack.score', prompt_version: '1.0.0', input_sha256: 'a'.repeat(64), output_sha256: 'b'.repeat(64),
      params: { secretish: '(합성) 원문 파라미터' }, provenance: { prompt_hash: 'c'.repeat(64) }, latency_ms: 120, partition_name: 'aip.inference_audit_202610', project_id: 'p1' },
  ])),
);

describe('AIP 유틸', () => {
  it('승격 요청: SHADOW 는 QA, PRODUCTION 은 EXEC 승인 필요', () => {
    expect(promotionNeeds(model({ stage: 'EVALUATED' }))).toEqual([{ to: 'SHADOW', approver: 'QA', approved: false }]);
    expect(promotionNeeds(model({ stage: 'CANARY', exec_approved_by: 'x' }))).toEqual([{ to: 'PRODUCTION', approver: 'EXEC', approved: true }]);
    expect(promotionNeeds(model({ stage: 'PRODUCTION' }))).toEqual([]);
  });
  it('추론 감사는 해시 · 모델 · 지연만 남긴다(params · provenance 제거)', () => {
    const r = auditHashesOnly({ job_id: 'j', input_sha256: 'a', params: { x: 1 }, provenance: { y: 2 } }) as unknown as Record<string, unknown>;
    expect(r.input_sha256).toBe('a');
    expect('params' in r).toBe(false);
    expect('provenance' in r).toBe(false);
  });
});

describe('AI Job · 모델 화면', () => {
  it('Job 상태 칩 · 상태 필터 · 상세(이력) · 대기 Job 취소', async () => {
    const user = userEvent.setup();
    renderAs(ENG, ['ENG', 'EMPLOYEE'], <AipPage />);
    const grid = await screen.findByTestId('aip-jobs');
    expect(await within(grid).findByText('중단(DEAD)')).toHaveClass('danger');
    expect(within(grid).getByText('대기')).toHaveClass('info');
    await user.click(screen.getByRole('button', { name: '상태 대기' }));
    expect(within(screen.getByTestId('aip-jobs')).getAllByRole('row')).toHaveLength(1);
    await user.click(within(screen.getByTestId('aip-jobs')).getAllByRole('row')[0]);
    const dlg = await screen.findByRole('dialog');
    expect(await within(dlg).findByText('{"to":"QUEUED"}')).toBeInTheDocument();
    await user.click(within(dlg).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(toasts.success[0]).toContain('Job 취소'));
    expect(seen[0]).toMatchObject({ method: 'DELETE', url: '/api/v1/aip/jobs/j2' });
  });

  it('모델 레지스트리: 별칭 · 승격 요청 · QA 승인', async () => {
    const user = userEvent.setup();
    renderAs(devUserId(6), ['QA'], <AipPage />, { route: '/?tab=models' });
    const t = await screen.findByTestId('aip-models');
    expect(await within(t).findByText('production · 균열 탐지 100%')).toHaveClass('ok');
    const req = screen.getByTestId('aip-promotions');
    expect(req).toHaveTextContent('crack@1.1.0평가 완료ShadowQA대기');
    await user.click(within(t).getAllByRole('button', { name: 'QA 승인' })[1]);
    await waitFor(() => expect(seen[0]).toMatchObject({ url: '/api/v1/aip/models/m9/approval', body: { role: 'QA' } }));
  });

  it('제안: 결정 대기 → 수락, 결정됨 탭', async () => {
    suggestions = [sugg({}), sugg({ id: 's-bbbbbbbb2', status: 'ACCEPTED', decided_by: ENG })];
    const user = userEvent.setup();
    renderAs(ENG, ['ENG'], <AipPage />, { route: '/?tab=suggestions' });
    expect(await screen.findByRole('tab', { name: '결정 대기 (1)' })).toBeInTheDocument();
    await user.click(await within(screen.getByTestId('aip-suggestions')).findByRole('button', { name: '수락 s-aaaaaa' }));
    await waitFor(() => expect(seen[0]?.body).toEqual({ decision: 'ACCEPTED' }));
    expect(await screen.findByRole('tab', { name: '결정됨 (2)' })).toBeInTheDocument();
  });

  it('감사(AUDITOR): 해시만 표시', async () => {
    renderAs(devUserId(7), ['AUDITOR'], <AipPage />);
    const g = await screen.findByTestId('aip-audit');
    expect(await within(g).findByText('a'.repeat(64))).toBeInTheDocument();
    expect(g).not.toHaveTextContent('원문 파라미터');
    expect(g).not.toHaveTextContent('c'.repeat(64));
    expect(screen.queryByRole('tab', { name: 'Job' })).toBeNull();
  });
});
