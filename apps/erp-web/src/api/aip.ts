import { http } from './http';

/** AIP AI 플랫폼 — Job · 모델 레지스트리 · 제안(HITL) · 추론 감사(해시만) */
export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'DEAD' | 'PREEMPTED' | 'CANCELLED';
export const JOB_STATUS: Record<JobStatus, { label: string; tone?: 'ok' | 'warn' | 'danger' | 'info' }> = {
  QUEUED: { label: '대기', tone: 'info' },
  RUNNING: { label: '실행 중', tone: 'info' },
  SUCCEEDED: { label: '성공', tone: 'ok' },
  FAILED: { label: '실패(재시도 예정)', tone: 'warn' },
  DEAD: { label: '중단(DEAD)', tone: 'danger' },
  PREEMPTED: { label: '선점됨', tone: 'warn' },
  CANCELLED: { label: '취소' },
};
export const SERVICE_LABEL: Record<string, string> = {
  CRACK_DETECT: '균열 탐지', CRACK_MEASURE: '균열 측정', REPORT_DRAFT: '보고서 초안', REPORT_REVIEW: 'QAR 검토',
  KB_SEARCH: '지식 검색',
};
export interface Job {
  id: string; serviceCode: string; mode: 'ASYNC' | 'SYNC'; priority: number; status: JobStatus; projectId: string | null;
  attempts: number; maxAttempts: number; progressPct: number; routeAlias: string | null; modelVersionId: string | null;
  inputSha256: string; deadlineAt: string | null; cancelRequested: boolean; result: unknown; error: { code?: string; message?: string } | null;
  queuedAt: string; startedAt: string | null; finishedAt: string | null; requestedBy?: string; requesterModule?: string | null;
}
export type ModelStage = 'TRAINED' | 'EVALUATED' | 'REJECTED' | 'SHADOW' | 'CANARY' | 'PRODUCTION' | 'ROLLED_BACK' | 'ARCHIVED';
export const STAGE_LABEL: Record<ModelStage, string> = {
  TRAINED: '학습 완료', EVALUATED: '평가 완료', REJECTED: '게이트 탈락', SHADOW: 'Shadow', CANARY: 'Canary',
  PRODUCTION: 'Production', ROLLED_BACK: '롤백', ARCHIVED: '보관',
};
/** 설계 A6.3 단계 전이 — 서버 ModelStage.NEXT 와 같다 */
export const NEXT_STAGES: Record<ModelStage, ModelStage[]> = {
  TRAINED: ['EVALUATED'], EVALUATED: ['REJECTED', 'SHADOW'], SHADOW: ['CANARY', 'ROLLED_BACK'],
  CANARY: ['PRODUCTION', 'ROLLED_BACK'], PRODUCTION: ['ARCHIVED'], REJECTED: [], ROLLED_BACK: [], ARCHIVED: [],
};
/** 승격 승인 요건: SHADOW 진입 = QA, PRODUCTION 진입 = EXEC */
export const REQUIRED_APPROVER: Partial<Record<ModelStage, 'QA' | 'EXEC'>> = { SHADOW: 'QA', PRODUCTION: 'EXEC' };
export interface Deployment { serviceCode: string; alias: 'production' | 'canary' | 'shadow'; trafficPct: number }
export interface ModelRow {
  id: string; name: string; version: string; base_model: string | null; stage: ModelStage; artifact_uri: string;
  approved_by: string | null; exec_approved_by: string | null; deployments: Deployment[] | null;
}

/** 승격 요청(대기) — 다음 단계로 가려면 필요한 승인이 무엇이고 채워졌는지 */
export function promotionNeeds(m: ModelRow): { to: ModelStage; approver?: 'QA' | 'EXEC'; approved: boolean }[] {
  return NEXT_STAGES[m.stage]
    .filter((t) => t !== 'REJECTED' && t !== 'ROLLED_BACK' && t !== 'ARCHIVED')
    .map((to) => {
      const approver = REQUIRED_APPROVER[to];
      const approved = !approver || (approver === 'QA' ? !!m.approved_by : !!m.exec_approved_by);
      return { to, approver, approved };
    });
}

export type SuggestionStatus = 'SUGGESTED' | 'ACCEPTED' | 'MODIFIED' | 'REJECTED' | 'EXPIRED';
export const SUGG_STATUS: Record<SuggestionStatus, { label: string; tone?: 'ok' | 'warn' | 'danger' | 'info' }> = {
  SUGGESTED: { label: '결정 대기', tone: 'info' }, ACCEPTED: { label: '수락', tone: 'ok' }, MODIFIED: { label: '수정 수락', tone: 'ok' },
  REJECTED: { label: '거절', tone: 'danger' }, EXPIRED: { label: '만료' },
};
export interface Suggestion {
  id: string; job_id: string; project_id: string | null; photo_attachment_id: string; defect_type: string;
  crack_pattern: string | null; width_max_mm: number | null; width_mean_mm: number | null; width_uncert_mm: number | null;
  length_m: number | null; mm_per_px: number | null; scale_source: string | null; confidence: number;
  description: string | null; flags: string[]; status: SuggestionStatus; created_at: string;
  decision: string | null; reason_code: string | null; decided_by: string | null; decided_at: string | null;
}
export const REJECT_REASONS: Record<string, string> = {
  FALSE_POSITIVE: '오탐(균열 아님)', WRONG_TYPE: '결함 유형 다름', WIDTH_OFF: '폭 수치 틀림', GEOMETRY_OFF: '위치 · 형상 틀림',
};
/** 추론 감사 — 화면에는 해시 · 모델 · 지연만(파라미터 · 출처 원문은 표시하지 않음) */
export interface AuditRow {
  job_id: string; created_at: string; service_code: string; model_name: string | null; model_version: string | null;
  adapter: string | null; prompt_key: string | null; prompt_version: string | null; input_sha256: string;
  output_sha256: string | null; latency_ms: number | null; partition_name: string; project_id: string | null;
}
export function auditHashesOnly<T extends Record<string, unknown>>(row: T): AuditRow {
  const keep: (keyof AuditRow)[] = [
    'job_id', 'created_at', 'service_code', 'model_name', 'model_version', 'adapter', 'prompt_key', 'prompt_version',
    'input_sha256', 'output_sha256', 'latency_ms', 'partition_name', 'project_id',
  ];
  return Object.fromEntries(keep.map((k) => [k, row[k] ?? null])) as unknown as AuditRow;
}

export const aipApi = {
  jobs: (status?: JobStatus, serviceCode?: string) => http.get<Job[]>('/aip/jobs', { status, serviceCode }),
  job: (id: string) => http.get<Job>(`/aip/jobs/${id}`),
  events: (id: string) => http.get<{ id: number; at: string; kind: string; payload: unknown }[]>(`/aip/jobs/${id}/events`),
  cancel: (id: string) => http.delete<Job>(`/aip/jobs/${id}`),
  models: () => http.get<ModelRow[]>('/aip/models'),
  approve: (id: string, role: 'QA' | 'EXEC') => http.post<ModelRow>(`/aip/models/${id}/approval`, { role }),
  promote: (id: string, targetStage: ModelStage, serviceCode?: string, trafficPct?: number) =>
    http.post<ModelRow & { alias: string | null; trafficPct: number }>(`/aip/models/${id}/promote`, { targetStage, serviceCode, trafficPct }),
  suggestions: (status?: SuggestionStatus) => http.get<Suggestion[]>('/aip/suggestions', { status }),
  decide: (id: string, decision: 'ACCEPTED' | 'REJECTED', reasonCode?: string) =>
    http.post<Suggestion>(`/aip/suggestions/${id}/decision`, { decision, reasonCode }),
  audit: (from?: string, to?: string) =>
    http.get<Record<string, unknown>[]>('/aip/audit', { from, to }).then((rows) => rows.map(auditHashesOnly)),
};
