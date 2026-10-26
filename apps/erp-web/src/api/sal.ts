import { http, type Page } from './http';

/** SAL 수주·영업 — SalController DTO (record 컴포넌트 이름 그대로) */
export type OpportunitySource = 'G2B' | 'DUE_SOON' | 'INQUIRY' | 'REFERRAL' | 'RENEWAL' | 'PORTAL';
export type OpportunityStage =
  | 'LEAD' | 'QUALIFYING' | 'PROPOSING' | 'NEGOTIATING' | 'BIDDING' | 'BID_APPROVED'
  | 'SUBMITTED' | 'WON' | 'LOST' | 'DROPPED' | 'CONTRACTED';
export type LostReason = 'PRICE' | 'PERFORMANCE' | 'STAFF' | 'SCHEDULE' | 'COMPETITOR' | 'OTHER';

export const STAGE_LABEL: Record<OpportunityStage, string> = {
  LEAD: '리드', QUALIFYING: '검토', PROPOSING: '제안', NEGOTIATING: '협상', BIDDING: '입찰 준비',
  BID_APPROVED: '입찰 승인', SUBMITTED: '제출', WON: '수주', LOST: '실주', DROPPED: '중단', CONTRACTED: '계약',
};
/** OpportunityStage.NEXT (서버 전이 표와 동일) */
export const STAGE_NEXT: Record<OpportunityStage, OpportunityStage[]> = {
  LEAD: ['QUALIFYING', 'DROPPED'],
  QUALIFYING: ['PROPOSING', 'BIDDING', 'DROPPED'],
  BIDDING: ['BID_APPROVED', 'DROPPED'],
  BID_APPROVED: ['SUBMITTED'],
  SUBMITTED: ['WON', 'LOST'],
  PROPOSING: ['NEGOTIATING'],
  NEGOTIATING: ['PROPOSING', 'WON', 'LOST'],
  WON: ['CONTRACTED'],
  LOST: [], DROPPED: [], CONTRACTED: [],
};

export interface OpportunityRequest {
  source: OpportunitySource;
  clientId?: string | null;
  facilityId?: string | null;
  bidNoticeId?: string | null;
  serviceType: string;
  title: string;
  expectedAmount?: number | null;
  probability?: number | null;
  expectedClose?: string | null;
  ownerId?: string | null;
}
export interface Opportunity {
  id: string; oppNo: string; source: OpportunitySource; bidNoticeId: string | null; clientId: string | null;
  facilityId: string | null; dueInspectionType: string | null; dueDate: string | null; serviceType: string;
  title: string; stage: OpportunityStage; expectedAmount: number | null; probability: number | null;
  expectedClose: string | null; ownerId: string | null; lostReason: LostReason | null; criteriaChanged: boolean;
  version: number;
}
export interface MoveRequest { toStage: OpportunityStage; note?: string; lostReason?: LostReason; ownerId?: string }

export interface FacilityRequest {
  facilityClass: string; grossAreaM2: number; structure?: string; basementFloors?: number; floors?: number;
  completionYear?: number; inUse?: boolean;
}
export interface ExpenseRequest { item: string; quantity: number; unitPrice: number }
export interface OverrideRequest { workItem: string; adjFactor: number; reason: string }
export interface FeeCalcRequest {
  asOfDate: string; serviceType?: string; facility: FacilityRequest; overheadRate?: number; techFeeRate?: number;
  expenses?: ExpenseRequest[]; manDayOverrides?: OverrideRequest[]; discountRate?: number; validDays?: number;
}
export interface FeeLine {
  lineType: 'LABOR' | 'EXPENSE'; workItem: string; grade: 'SPECIAL' | 'ADVANCED' | 'INTERMEDIATE' | 'BEGINNER' | null;
  baseManDays: number | null; adjFactor: number | null; manDays: number | null; unitPrice: number;
  quantity: number | null; amount: number;
}
export interface FeeCalcResult {
  ruleSetId: string; ruleVersion: string; legalBasis: string; lines: FeeLine[]; directLabor: number;
  directExpense: number; overheadRate: number; overhead: number; techFeeRate: number; techFee: number;
  subtotal: number; vat: number; explanations: string[]; warnings: string[];
  ruleRanges: { overhead: { min: number; max: number }; techFee: { min: number; max: number } };
  verifiedBy: string | null; verifiedAt: string | null;
}
export type EstimateStatus =
  | 'DRAFT' | 'PENDING_APPROVAL' | 'ISSUED' | 'SENT' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED' | 'EXPIRED';
export const ESTIMATE_STATUS_LABEL: Record<EstimateStatus, string> = {
  DRAFT: '작성 중', PENDING_APPROVAL: '할인 승인 대기', ISSUED: '발행', SENT: '송부', ACCEPTED: '수락',
  REJECTED: '거절', SUPERSEDED: '대체됨', EXPIRED: '만료',
};
export interface Estimate {
  id: string; estimateNo: string; versionNo: number; opportunityId: string; serviceType: string; asOfDate: string;
  feeRuleSetId: string; ruleVersion: string; directLabor: number; directExpense: number; overheadRate: number;
  overhead: number; techFeeRate: number; techFee: number; subtotal: number; discountRate: number;
  offerAmount: number; vat: number; validUntil: string; status: EstimateStatus; discountLimit: number | null;
  approvedBy: string | null; documentId: string | null; version: number; lines: (FeeLine & { seq: number })[];
}
export interface EstimateVersionSummary {
  id: string; estimateNo: string; versionNo: number; status: EstimateStatus; subtotal: number;
  discountRate: number; offerAmount: number; asOfDate: string;
}
export interface DiscountDecision { decision: 'APPROVE' | 'REJECT'; comment?: string }

const etag = (v: number) => `"v${v}"`;

export const salApi = {
  listOpportunities: (q: { stage?: string; page?: number; size?: number } = {}) =>
    http.get<Page<Opportunity>>('/sal/opportunities', { page: 0, size: 100, ...q }),
  getOpportunity: (id: string) => http.get<Opportunity>(`/sal/opportunities/${id}`),
  createOpportunity: (body: OpportunityRequest) => http.post<Opportunity>('/sal/opportunities', body),
  /** If-Match 는 응답 body.version 으로 만든다(ETag 와 같은 값) */
  moveOpportunity: (o: Pick<Opportunity, 'id' | 'version'>, body: MoveRequest) =>
    http.post<Opportunity>(`/sal/opportunities/${o.id}:move`, body, { ifMatch: etag(o.version) }),
  calculateFee: (body: FeeCalcRequest) => http.post<FeeCalcResult>('/sal/fee-calculations', body),
  listEstimates: (oppId: string) => http.get<EstimateVersionSummary[]>(`/sal/opportunities/${oppId}/estimates`),
  createEstimate: (oppId: string, body: FeeCalcRequest) =>
    http.post<Estimate>(`/sal/opportunities/${oppId}/estimates`, body),
  getEstimate: (id: string) => http.get<Estimate>(`/sal/estimates/${id}`),
  issue: (id: string) => http.post<Estimate>(`/sal/estimates/${id}:issue`, {}),
  approveDiscount: (e: Pick<Estimate, 'id' | 'version'>, body: DiscountDecision) =>
    http.post<Estimate>(`/sal/estimates/${e.id}:approve-discount`, body, { ifMatch: etag(e.version) }),
  send: (id: string, body: { contacts?: string[]; message?: string } = {}) =>
    http.post<Estimate>(`/sal/estimates/${id}:send`, body),
  accept: (id: string, note?: string) => http.post<Estimate>(`/sal/estimates/${id}:accept`, { note }),
  reject: (id: string, note?: string) => http.post<Estimate>(`/sal/estimates/${id}:reject`, { note }),
};


// ------------------------------------------------------------------ 4차: 파이프라인 보드 · 공고 수집 · 적격심사

/** 보드 열 순서(진행 단계 → 종결 단계) */
export const BOARD_STAGES: OpportunityStage[] = [
  'LEAD', 'QUALIFYING', 'PROPOSING', 'NEGOTIATING', 'BIDDING', 'BID_APPROVED', 'SUBMITTED', 'WON', 'CONTRACTED', 'LOST', 'DROPPED',
];
export const LOST_REASON_LABEL: Record<LostReason, string> = {
  PRICE: '가격', PERFORMANCE: '실적', STAFF: '인력', SCHEDULE: '일정', COMPETITOR: '경쟁사', OTHER: '기타',
};
/** 화면에서 끌어 놓기 전에 전이표로 미리 판단(서버가 최종 판정 — 409 SAL-409-001) */
export function canMove(from: OpportunityStage, to: OpportunityStage) {
  return STAGE_NEXT[from].includes(to);
}

export type BidNoticeStatus = 'NEW' | 'WATCHING' | 'IGNORED' | 'CONVERTED' | 'SUPERSEDED' | 'CANCELLED';
export const NOTICE_STATUS_LABEL: Record<BidNoticeStatus, string> = {
  NEW: '신규', WATCHING: '관심', IGNORED: '제외', CONVERTED: '기회 전환', SUPERSEDED: '정정됨(이전 차수)', CANCELLED: '취소',
};
export interface BidNotice {
  id: string; bidNo: string; bidSeq: string; title: string; orgName: string | null; demandOrgName: string | null;
  estPrice: number | null; closeAt: string | null; openAt: string | null; noticeKind: string | null;
  status: BidNoticeStatus; fitScore: number | null; supersededBy: string | null; updatedAt: string;
}
export interface CollectLog {
  id: number; jobType: 'NOTICE' | 'BACKFILL' | string; rangeFrom: string; rangeTo: string; startedAt: string;
  finishedAt: string | null; success: boolean | null; fetched: number | null; upserted: number | null;
  inserted: number | null; error: string | null;
}
export interface CollectResult {
  logId: number; jobType: string; from: string; to: string; success: boolean; fetched: number; upserted: number;
  inserted: number; error: string | null;
}

export interface SimulationRequest {
  criteriaCode?: string; noticeDate?: string; estPrice?: number; serviceType?: string; bidRates: number[];
  engineersQualified?: number; engineersRequired?: number; creditGrade?: string; disabledStandardWorkplace: boolean;
}
export interface CapabilityItem { key: string; raw: number | string | boolean | null; ratio: number | null; score: number; max: number }
export interface SimulationScenario { bidRate: number; priceScore: number; total: number; pass: boolean; belowLowerLimit: boolean }
export interface SimulationResult {
  id: string; opportunityId: string; criteriaCode: string; passScore: number; lowerLimitRate: number;
  inputs: {
    noticeDate: string; estPrice: number; serviceType: string; similarYears: number; similarAmount: number;
    performanceRecords: { certNo: string; amountKrw: number; jointShare: number; endDate: string; creditedKrw: number }[];
  } & Record<string, unknown>;
  capability: { items: CapabilityItem[]; total: number };
  scenarios: SimulationScenario[];
  recommendedBidRate: number | null;
  notice: string | null;
}
export const CAPABILITY_LABEL: Record<string, string> = {
  SIMILAR_PERFORMANCE: '유사 용역 실적(5년)', ENGINEERS: '기술자 보유', CREDIT: '신용 등급', BONUS_DISABLED_STD: '가점 — 장애인 표준사업장',
};
export const CREDIT_GRADES = ['AAA', 'AA+', 'A0', 'BBB0', 'BB0'];

/** "0.877, 0.882" · "87.7 88.2" 처럼 적은 투찰률 목록 → 비율(0~1). 1 보다 큰 값은 % 로 본다 */
export function parseBidRates(text: string): number[] {
  return text
    .split(/[\s,]+/)
    .map((t) => t.trim())
    .filter(Boolean)
    .map(Number)
    .filter((n) => Number.isFinite(n) && n > 0)
    .map((n) => (n > 1 ? Math.round(n * 1000) / 100000 : n));
}

export const salBoardApi = {
  notices: (q: { status?: string; q?: string } = {}) => http.get<BidNotice[]>('/sal/bid-notices', { size: 200, ...q }),
  collectLogs: (limit = 10) => http.get<CollectLog[]>('/sal/collect-logs', { limit }),
  collectNow: () => http.post<CollectResult>('/sal/bid-notices:collect-now', {}),
  simulate: (oppId: string, body: SimulationRequest) => http.post<SimulationResult>(`/sal/opportunities/${oppId}/simulations`, body),
};
