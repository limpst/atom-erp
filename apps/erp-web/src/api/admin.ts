import { http } from './http';

/**
 * 공통 › 관리 — 영업팀장 그룹(SAL, V0036) · QA 검토자 프로필(QAR, V0037) · 기준 요율(CST, V0038).
 * 수정 · 상태 전이는 응답 version 으로 If-Match 를 명시한다(목록 GET 은 행별 ETag 가 없다).
 */
const ifMatch = (version: number) => ({ ifMatch: `"v${version}"` });

// ------------------------------------------------------------------ SAL 영업팀장 그룹
export type SalesGroupCode = 'SALES_LEAD';
export const SALES_GROUP_LABEL: Record<SalesGroupCode, string> = { SALES_LEAD: '영업팀장(할인 한도 10%)' };
export interface SalesGroupMember {
  employeeId: string; groupCode: SalesGroupCode; active: boolean; note: string | null; createdAt: string;
  createdBy: string | null; updatedAt: string | null; updatedBy: string | null; version: number;
}

export const salesGroupApi = {
  list: () => http.get<SalesGroupMember[]>('/sal/sales-group-members'),
  add: (body: { employeeId: string; groupCode: SalesGroupCode; note?: string }) =>
    http.post<SalesGroupMember>('/sal/sales-group-members', body),
  update: (m: Pick<SalesGroupMember, 'employeeId' | 'groupCode' | 'version'>, body: { active?: boolean; note?: string }) =>
    http.patch<SalesGroupMember>(`/sal/sales-group-members/${m.employeeId}/${m.groupCode}`, body, ifMatch(m.version)),
};

// ------------------------------------------------------------------ QAR 검토자 프로필
export interface ReviewerProfile {
  employeeId: string; stepCode: 'QA_REVIEW'; active: boolean; maxOpen: number; expertise: Record<string, number>;
  qualifications: string[]; note: string | null; openAssignments: number; createdAt: string; updatedAt: string | null;
  updatedBy: string | null; version: number;
}
export interface ReviewerProfileInput {
  maxOpen?: number; expertise?: Record<string, number>; qualifications?: string[]; note?: string; active?: boolean;
}

export const reviewerApi = {
  list: () => http.get<ReviewerProfile[]>('/qar/reviewer-profiles'),
  create: (body: ReviewerProfileInput & { employeeId: string }) => http.post<ReviewerProfile>('/qar/reviewer-profiles', body),
  update: (p: Pick<ReviewerProfile, 'employeeId' | 'version'>, body: ReviewerProfileInput) =>
    http.patch<ReviewerProfile>(`/qar/reviewer-profiles/${p.employeeId}`, body, ifMatch(p.version)),
};

/** "RT_PSD=3, RT_PRC=1" ↔ {RT_PSD: 3, RT_PRC: 1} */
export function parseExpertise(text: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const part of text.split(/[,\n]/)) {
    const t = part.trim();
    if (!t) continue;
    const [k, v] = t.split(/[=:]/).map((x) => x.trim());
    const n = Number(v);
    if (!/^RT_[A-Z_]{2,20}$/.test(k ?? '') || !Number.isInteger(n) || n < 0) {
      throw new Error(`전문성 형식 오류: "${t}" — 예: RT_PSD=3`);
    }
    out[k] = n;
  }
  return out;
}
export const formatExpertise = (e: Record<string, number>) =>
  Object.entries(e)
    .map(([k, v]) => `${k}=${v}`)
    .join(', ');

// ------------------------------------------------------------------ CST 기준 요율
export type RateKind = 'STD_LABOR' | 'OVERHEAD_PCT' | 'TECHFEE_PCT' | 'EQUIP_DAILY';
export type RateStatus = 'DRAFT' | 'APPROVED' | 'REJECTED';
export const RATE_STATUS_LABEL: Record<RateStatus, string> = { DRAFT: '승인 대기', APPROVED: '승인', REJECTED: '반려' };
export const LABOR_GRADES = ['JUNIOR', 'INTER', 'SENIOR', 'MASTER'] as const;
export const GRADE_LABEL: Record<string, string> = { JUNIOR: '초급', INTER: '중급', SENIOR: '고급', MASTER: '특급' };
export interface Rate {
  id: string; rateKind: RateKind; keyCode: string; value: number; validFrom: string; validTo: string | null;
  sourceRef: string; version: number; status: RateStatus; createdBy: string | null; createdAt: string | null;
  approvedBy: string | null; approvedAt: string | null; rejectReason: string | null; retiredAt: string | null;
}
export interface RateInput { rateKind: RateKind; keyCode: string; value: number; validFrom: string; validTo?: string | null; sourceRef: string }

export const rateApi = {
  list: (kind?: RateKind) => http.get<Rate[]>('/cst/rates', { kind }),
  register: (body: RateInput) => http.post<Rate>('/cst/rates', body),
  edit: (r: Pick<Rate, 'id' | 'version'>, body: Partial<Omit<RateInput, 'rateKind' | 'keyCode'>>) =>
    http.patch<Rate>(`/cst/rates/${r.id}`, body, ifMatch(r.version)),
  approve: (r: Pick<Rate, 'id' | 'version'>) => http.post<Rate>(`/cst/rates/${r.id}:approve`, {}, ifMatch(r.version)),
  reject: (r: Pick<Rate, 'id' | 'version'>, reason: string) =>
    http.post<Rate>(`/cst/rates/${r.id}:reject`, { reason }, ifMatch(r.version)),
  retire: (r: Pick<Rate, 'id' | 'version'>, validTo: string) =>
    http.post<Rate>(`/cst/rates/${r.id}:retire`, { validTo }, ifMatch(r.version)),
};

/** 오늘(로컬) 기준 유효 여부 — [validFrom, validTo) */
export function rateInEffect(r: Pick<Rate, 'status' | 'validFrom' | 'validTo'>, on: string): boolean {
  return r.status === 'APPROVED' && r.validFrom <= on && (r.validTo === null || on < r.validTo);
}
