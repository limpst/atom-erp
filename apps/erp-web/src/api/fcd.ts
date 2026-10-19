import { http, request } from './http';

/** FCD 법인카드 — 응답은 JDBC 맵이라 snake_case 키 */
export type RiskGrade = 'HIGH' | 'EXPLAIN' | 'WATCH' | 'NORMAL';
export const GRADE_LABEL: Record<RiskGrade, string> = {
  HIGH: '고위험', EXPLAIN: '소명 필요', WATCH: '관찰', NORMAL: '정상',
};
export type CaseStatus =
  | 'OPEN' | 'EXPLANATION_REQUESTED' | 'OVERDUE' | 'SUBMITTED' | 'SUPPLEMENT_REQUESTED' | 'ESCALATED'
  | 'RESOLVED_LEGIT' | 'RESOLVED_MISUSE' | 'RECOVERY' | 'CLOSED';
export const CASE_STATUS_LABEL: Record<CaseStatus, string> = {
  OPEN: '열림', EXPLANATION_REQUESTED: '소명 요청', OVERDUE: '기한 경과', SUBMITTED: '소명 제출',
  SUPPLEMENT_REQUESTED: '보완 요청', ESCALATED: '상향', RESOLVED_LEGIT: '판정: 정당', RESOLVED_MISUSE: '판정: 부정',
  RECOVERY: '환수', CLOSED: '종결',
};
export type CaseLabel = 'LEGIT' | 'FALSE_POSITIVE' | 'MINOR_VIOLATION' | 'MISUSE';
export const LABEL_LABEL: Record<CaseLabel, string> = {
  LEGIT: '정당 사용', FALSE_POSITIVE: '오탐', MINOR_VIOLATION: '경미 위반', MISUSE: '부정 사용',
};
export interface CaseListItem {
  id: string; case_no: string; status: CaseStatus; grade: RiskGrade; score: number;
  subject_employee_id: string | null; due_on: string; audit_sample: boolean; score_dropped: boolean;
}
export interface CaseDetail extends CaseListItem {
  opened_reason: string; opened_at: string; suspension_candidate: boolean; label: CaseLabel | null;
  resolution: string | null; resolved_by: string | null; resolved_at: string | null; txnIds: string[];
  history: { from_status: CaseStatus | null; event: string; to_status: CaseStatus; actor_id: string; note: string | null; at: string }[];
}
export interface Txn {
  id: string; masked_no: string; employee_id: string | null; approval_no: string | null; approved_at: string;
  amount_krw: number; cancelled_amount_krw: number; status: string; merchant_name: string;
  risk_score: number | null; risk_grade: RiskGrade | null;
}
export interface RiskScoreRow {
  scored_at: string; trigger: string; rule_set_version: string; model_version: string | null; score: number;
  grade: RiskGrade; hits: string; mitigations: string; reasons: string; l3_status: string;
}
export interface RuleHit { code: string; layer: string; kind: string; s: number; severity: string; message: string; legal_basis?: string }
export interface ImportBatch {
  id: string; channel: string; filename: string | null; status: string; profile_code: string | null;
  rows_total: number; rows_error: number; counts: Record<string, number>; warnings: string[];
  detected_encoding: string | null;
}
export interface ReviewRunResult {
  period: string; policy: string; nTxn: number; budget: number | null; selected: string[]; casesOpened: string[];
}

export interface ImportBatchRow {
  id: string; channel: string; filename: string | null; status: string; file_kind: string | null;
  detected_encoding: string | null; rows_total: number; rows_error: number; counts: string; profile_code: string | null;
  uploaded_at: string; committed_at: string | null; rolled_back_at: string | null;
}
export const BATCH_STATUS_LABEL: Record<string, string> = {
  UPLOADED: '업로드', PARSED: '파싱', NEEDS_MAPPING: '매핑 필요', VALIDATED: '검증 완료(확정 전)', COMMITTED: '확정', DISCARDED: '폐기',
  ROLLED_BACK: '롤백', PARTIALLY_ROLLED_BACK: '부분 롤백', FAILED: '실패',
};
export interface DedupCandidate {
  id: string; source_id: string; txn_id: string; new_txn_id: string; score: number; features: string;
  decision: string | null; created_at: string;
}
export interface RuleSetRow {
  version: string; name: string; effective_from: string; effective_to: string | null;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'RETIRED' | string; authored_by: string; approved_by: string | null;
  sha256: string; source: string;
}
export interface CardRow {
  id: string; card_company: string; masked_no: string; alias: string | null; card_type: string;
  holder_employee_id: string | null; dept: string | null; limit_per_txn: number; limit_daily: number;
  limit_monthly: number; status: string; deactivation_flag: boolean; deactivation_reason: string | null;
}
export const CARD_TYPE_LABEL: Record<string, string> = {
  PERSONAL_ASSIGNED: '개인 지정', SHARED: '공용(대여)',
};

/** 화면 표시용 카드 번호 — 서버 마스킹 값만 쓰고, 혹시 숫자가 10자리 이상 이어지면 가운데를 다시 가린다(방어) */
export function safeMasked(masked: string | null | undefined): string {
  if (!masked) return '—';
  return masked.replace(/\d{10,}/g, (d) => `${d.slice(0, 6)}${'*'.repeat(d.length - 10)}${d.slice(-4)}`);
}

export const fcdApi = {
  cases: () => http.get<CaseListItem[]>('/fcd/cases'),
  case: (id: string) => http.get<CaseDetail>(`/fcd/cases/${id}`),
  transactions: (from?: string, to?: string) => http.get<Txn[]>('/fcd/transactions', { from, to }),
  risk: (txnId: string) => http.get<RiskScoreRow[]>(`/fcd/transactions/${txnId}/risk`),
  requestExplanation: (id: string) =>
    http.post<{ to: string; title: string; body: string; caseNo: string; due: string }>(
      `/fcd/cases/${id}:request-explanation`,
    ),
  explain: (id: string, body: { purpose: string; text: string; attendees?: unknown }) =>
    http.post<{ caseNo: string; status: CaseStatus }>(`/fcd/cases/${id}/explanations`, body),
  requestSupplement: (id: string, note?: string) => http.post(`/fcd/cases/${id}:request-supplement`, { note }),
  escalate: (id: string) => http.post(`/fcd/cases/${id}:escalate`),
  resolve: (id: string, body: { label: CaseLabel; reason: string; recoveryAmount?: number }) =>
    http.post<{ caseNo: string; status: CaseStatus; label: CaseLabel }>(`/fcd/cases/${id}:resolve`, body),
  runReviewQueue: (ym: string, policy: 'MONTHLY_BUDGET' | 'THRESHOLD') =>
    http.post<ReviewRunResult>(`/fcd/review-queue/${ym}:run`, { policy }),
  uploadStatement: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return request<ImportBatch>('POST', '/fcd/imports', { rawBody: fd }).then((r) => r.data);
  },
  commitImport: (id: string) => http.post<ImportBatch>(`/fcd/imports/${id}:commit`),
  discardImport: (id: string) => http.post<ImportBatch>(`/fcd/imports/${id}:discard`),
  imports: () => http.get<ImportBatchRow[]>('/fcd/imports'),
  importBatch: (id: string) => http.get<ImportBatch & Record<string, unknown>>(`/fcd/imports/${id}`),
  dedupCandidates: () => http.get<DedupCandidate[]>('/fcd/dedup-candidates'),
  decideDedup: (id: string, decision: 'MERGE' | 'SEPARATE') =>
    http.post<{ id: string; decision: string }>(`/fcd/dedup-candidates/${id}:decide`, { decision }),
  ruleSets: () => http.get<RuleSetRow[]>('/fcd/rule-sets'),
  draftRuleSet: (yaml: string) =>
    request<{ version: string; status: string; rules: number }>('POST', '/fcd/rule-sets', {
      rawBody: yaml, contentType: 'application/yaml',
    }).then((r) => r.data),
  approveRuleSet: (version: string, name?: string) =>
    http.post<{ version: string; name: string; status: string }>(
      `/fcd/rule-sets/${encodeURIComponent(version)}:approve${name ? `?name=${encodeURIComponent(name)}` : ''}`,
    ),
  cards: () => http.get<CardRow[]>('/fcd/cards'),
};

export function parseHits(row: RiskScoreRow): RuleHit[] {
  try {
    return JSON.parse(row.hits) as RuleHit[];
  } catch {
    return [];
  }
}
