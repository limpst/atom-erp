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
};

export function parseHits(row: RiskScoreRow): RuleHit[] {
  try {
    return JSON.parse(row.hits) as RuleHit[];
  } catch {
    return [];
  }
}
