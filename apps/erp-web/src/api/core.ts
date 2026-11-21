import { http, request } from './http';

/** CORE 프로젝트 · 고객 + CST 원가 · 수익성 · EVM */
export type ProjectStatus =
  | 'PLANNED' | 'IN_PROGRESS' | 'FIELD_DONE' | 'REPORTING' | 'IN_REVIEW' | 'DELIVERED' | 'CLOSED' | 'CANCELLED';
export const PROJECT_STATUS_LABEL: Record<ProjectStatus, string> = {
  PLANNED: '계획', IN_PROGRESS: '진행', FIELD_DONE: '현장 완료', REPORTING: '보고서 작성', IN_REVIEW: '검토',
  DELIVERED: '납품', CLOSED: '종료', CANCELLED: '취소',
};
export interface Project {
  id: string; projectNo: string; name: string; serviceType: string; clientId: string; status: ProjectStatus;
  startDate: string | null; endDate: string | null; contractAmount: number | null; currency: string;
  pmId: string | null; version: number;
}
export interface CreateProjectRequest {
  name: string; serviceType: string; clientId: string; startDate?: string | null; endDate?: string | null;
  contractAmount?: number | null;
}
export type RoleInProject = 'RESP' | 'PART' | 'QA' | 'SUPPORT';
export const ROLE_IN_PROJECT_LABEL: Record<RoleInProject, string> = {
  RESP: '책임', PART: '참여', QA: '품질', SUPPORT: '지원',
};
export interface ProjectMember {
  id: string; employeeId: string; empNo: string; roleInProject: RoleInProject; fromDate: string; toDate: string;
}
export type ClientType = 'PUBLIC' | 'PRIVATE' | 'APT' | 'OVERSEAS' | 'INDIVIDUAL';
export interface Client {
  id: string; clientNo: string; name: string; clientType: ClientType; bizRegNo: string | null;
  address: string | null; version: number;
}

export type CostElement =
  | 'LABOR' | 'TRAVEL' | 'LODGING' | 'VEHICLE' | 'EQUIP_USE' | 'MATERIAL_TEST' | 'DRONE' | 'SUBCONTRACT'
  | 'PRINTING' | 'OVERHEAD' | 'TECH_FEE' | 'OTHER';
export type BudgetStatus = 'DRAFT' | 'SUBMITTED' | 'REVIEWED' | 'APPROVED' | 'SUPERSEDED' | 'CLOSED';
export interface BudgetWbs {
  id: string; code: string; name: string; weightPct: number; milestoneEvent: string | null; budgetKrw: number;
}
export interface BudgetRevision {
  id: string; projectId: string; revNo: number; status: BudgetStatus; bacKrw: number; contractAmountKrw: number;
  expectedMarginPct: number; approvedBy: string | null; approvedAt: string | null; version: number; wbs: BudgetWbs[];
  /** CST-01 maker-checker — 작성 · 상신자는 같은 판을 결재할 수 없다 */
  createdBy?: string | null; submittedBy?: string | null; submittedAt?: string | null;
}
export interface Profitability {
  projectId: string; projectNo: string; basis: 'REAL' | 'STANDARD'; budgetRevNo: number | null;
  contractAmountKrw: number | null; bacKrw: number | null; acKrw: number;
  byElement: Partial<Record<CostElement, number>>; bySource: Partial<Record<string, number>>; mandays: number;
  marginKrw: number | null; marginPct: number | null;
}
export type AlertLevel = 'GREEN' | 'YELLOW' | 'RED';
export interface EvmView {
  projectId: string; asOf: string; acBasis: 'REAL' | 'STANDARD';
  result: {
    bac: number; pv: number; ev: number; ac: number; cv: number; sv: number; cpi: number; spi: number; eac: number;
    etc: number; vac: number; tcpi: number | null; eacMethod: string;
  };
  assessment: {
    level: AlertLevel;
    alerts: { code: string; level: AlertLevel; wbs: string | null; message: string; requiresComment: boolean }[];
  };
  budgetRevNo: number;
}
export interface LedgerRow {
  id: string; periodYm: string; postingDate: string; sourceType: string; sourceId: string; costElement: CostElement;
  employeeId: string | null; gradeCode: string | null; workType: string | null; hours: number | null;
  qty: number | null; amountStdKrw: number; amountKrw: number | null; amountKrwMasked: boolean;
  reversalOf: string | null; sourceRef: string | null; flags: string[];
}

export type PeriodStatus = 'OPEN' | 'CLOSING' | 'CLOSED' | 'REOPENED';
export const PERIOD_STATUS_LABEL: Record<PeriodStatus, string> = { OPEN: '열림', CLOSING: '마감 중', CLOSED: '마감', REOPENED: '재오픈' };
/** GET /cst/periods — JDBC 맵 */
export interface PeriodRow {
  period_ym: string; status: PeriodStatus; closed_at: string | null; closed_by: string | null; reopened_at: string | null;
  reopened_by: string | null; reopen_reason: string | null; hrq_closed_at: string | null;
  recon_status: 'MATCHED' | 'MISMATCH' | null; diff_mandays: number | null; hrq_total_mandays: number | null;
  cst_total_mandays: number | null; reconciled_at: string | null;
}
export interface PeriodView {
  periodYm: string; status?: PeriodStatus; closedAt?: string | null; hrqClosedAt?: string | null; reopenReason?: string | null;
  reconciliation: ReconciliationRow | null;
}
export interface ReconciliationRow {
  id: string; period_ym: string; source_event_id: string; hrq_total_mandays: number; cst_total_mandays: number;
  diff_mandays: number; status: 'MATCHED' | 'MISMATCH'; detail: string; created_at: string;
}
export interface ReconDetailRow {
  projectId?: string; employeeId?: string; grade?: string; workType?: string; hrq?: number; cst?: number; diff?: number;
  [k: string]: unknown;
}
export const CLOSE_BLOCKER_LABEL: Record<string, string> = {
  HRQ_MONTH_NOT_CLOSED: 'HRQ 월 마감 전(타임시트 인·일 확정 필요)',
  MANDAY_NOT_RECONCILED: 'HRQ↔CST 인·일 대사 결과 없음',
  MANDAY_RECONCILIATION_MISMATCH: 'HRQ↔CST 인·일 대사 불일치(MISMATCH)',
};
export interface CloseResult { periodYm: string; closed: boolean; blockers: string[] }
export interface EvmSnapshot {
  asOf: string; bac: number | null; pv: number | null; ev: number | null; acStd: number | null; sv: number | null;
  spi: number | null; cpiStd: number | null; ac: number | null; cv: number | null; cpi: number | null; eac: number | null;
  etc: number | null; vac: number | null; tcpi: number | null; acBasis: 'REAL' | 'STANDARD'; alertLevel: AlertLevel | null;
}

/** 원장 정정 짝 — REVERSAL 행(reversalOf = 원 행 id)과 그 원 행을 같은 묶음 번호로 */
export function reversalPairs(rows: Pick<LedgerRow, 'id' | 'reversalOf'>[]): Map<string, number> {
  const pair = new Map<string, number>();
  let n = 0;
  for (const r of rows) {
    if (r.reversalOf) {
      const k = pair.get(r.reversalOf) ?? ++n;
      pair.set(r.reversalOf, k);
      pair.set(r.id, k);
    }
  }
  return pair;
}

export type CorrectionRole = 'ORIGINAL' | 'REVERSAL' | 'REPLACEMENT';
export const CORRECTION_ROLE_LABEL: Record<CorrectionRole, string> = { ORIGINAL: '정정 전', REVERSAL: '역분개', REPLACEMENT: '정정 후' };
/**
 * 정정 묶음: 원 행 ↔ 역분개(reversalOf) 짝에, 같은 업무 멱등키(sourceRef = timesheetKey:새 리비전)로 들어온 새 행(정정 후)을 붙인다.
 */
export function correctionGroups(
  rows: Pick<LedgerRow, 'id' | 'reversalOf' | 'sourceType' | 'sourceRef'>[],
): Map<string, { pair: number; role: CorrectionRole }> {
  const pairs = reversalPairs(rows);
  const out = new Map<string, { pair: number; role: CorrectionRole }>();
  const refPair = new Map<string, number>();
  for (const r of rows) {
    const n = pairs.get(r.id);
    if (n === undefined) continue;
    if (r.reversalOf) {
      out.set(r.id, { pair: n, role: 'REVERSAL' });
      if (r.sourceRef) refPair.set(r.sourceRef, n);
    } else out.set(r.id, { pair: n, role: 'ORIGINAL' });
  }
  for (const r of rows) {
    if (!out.has(r.id) && r.sourceRef && r.sourceType !== 'REVERSAL' && refPair.has(r.sourceRef)) {
      out.set(r.id, { pair: refPair.get(r.sourceRef)!, role: 'REPLACEMENT' });
    }
  }
  return out;
}

export const coreApi = {
  projects: (status?: ProjectStatus) => http.get<Project[]>('/core/projects', { status }),
  project: (id: string) => http.get<Project>(`/core/projects/${id}`),
  createProject: (body: CreateProjectRequest) => http.post<Project>('/core/projects', body),
  members: (id: string) => http.get<ProjectMember[]>(`/core/projects/${id}/members`),
  assignMember: (id: string, body: Omit<ProjectMember, 'id' | 'empNo'>) =>
    http.post<ProjectMember>(`/core/projects/${id}/members`, body),
  clients: () => http.get<Client[]>('/core/clients'),
  createClient: (body: { name: string; clientType: ClientType; bizRegNo?: string; address?: string }) =>
    http.post<Client>('/core/clients', body),
};

export const cstApi = {
  budgets: (projectId: string) => http.get<BudgetRevision[]>(`/cst/projects/${projectId}/budget-revisions`),
  /** CST-01: DRAFT → SUBMITTED(상신, PM · ADMIN · FIN). 결재는 SUBMITTED 만 */
  submitBudget: (projectId: string, rev: Pick<BudgetRevision, 'id' | 'version'>) =>
    http.post<BudgetRevision>(`/cst/projects/${projectId}/budget-revisions/${rev.id}:submit`, undefined, {
      ifMatch: `"v${rev.version}"`,
    }),
  approveBudget: (projectId: string, rev: Pick<BudgetRevision, 'id' | 'version'>) =>
    http.post<BudgetRevision>(`/cst/projects/${projectId}/budget-revisions/${rev.id}:approve`, undefined, {
      ifMatch: `"v${rev.version}"`,
    }),
  profitability: (projectId: string) => http.get<Profitability>(`/cst/projects/${projectId}/profitability`),
  evm: (projectId: string, asOf?: string) => http.get<EvmView>(`/cst/projects/${projectId}/evm`, { asOf }),
  ledger: (projectId: string, from?: string, to?: string) =>
    http.get<LedgerRow[]>(`/cst/projects/${projectId}/ledger`, { from, to }),
  evmSnapshots: (projectId: string, from?: string, to?: string) =>
    http.get<EvmSnapshot[]>(`/cst/projects/${projectId}/evm/snapshots`, { from, to }),
  periods: () => http.get<PeriodRow[]>('/cst/periods'),
  period: (ym: string) => http.get<PeriodView>(`/cst/periods/${ym}`),
  reconciliations: (ym: string) => http.get<ReconciliationRow[]>(`/cst/periods/${ym}/reconciliations`),
  closePeriod: (ym: string) => http.post<CloseResult>(`/cst/periods/${ym}:close`),
  reopenPeriod: (ym: string, reason: string) =>
    http.post<{ periodYm: string; status: PeriodStatus }>(`/cst/periods/${ym}:reopen`, { reason }),
};

// ------------------------------------------------------------------ CST 청구 · 수금 · 통장 대사 (docs/CST_BILLING_COLLECTION_DESIGN.md §10)
export type AgingBucket = 'A_0_30' | 'B_31_60' | 'C_61_90' | 'D_91_180' | 'E_181_PLUS';
export const AGING_LABEL: Record<AgingBucket, string> = {
  A_0_30: 'A 0~30일', B_31_60: 'B 31~60일', C_61_90: 'C 61~90일', D_91_180: 'D 91~180일', E_181_PLUS: 'E 181일 이상',
};
export interface AgingRow { bucket: AgingBucket; invoiceCount: number; openKrw: number }
export interface OpenItem {
  invoiceId: string; invoiceNo: string; projectId: string; clientId: string; clientName: string; issueDate: string;
  supplyKrw: number; totalKrw: number; allocatedKrw: number; openKrw: number; ageDays: number; bucket: AgingBucket;
}
export interface MatchCandidate {
  invoiceId: string; invoiceNo: string; clientName: string; issueDate: string; openKrw: number; receiptId: string;
  valueDate: string; receiptKrw: number; remainingKrw: number; payerText: string; amountScore: number; nameScore: number;
  dateScore: number; score: number; autoCandidate: boolean;
}
export interface AllocationProposal {
  id: string; invoiceId: string; receiptId: string; amountKrw: number; matchScore: number;
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'; proposedBy: string; decidedBy: string | null; rejectReason: string | null;
  allocationId: string | null;
}
export interface PendingProposal {
  proposalId: string; invoiceId: string; invoiceNo: string; projectId: string; clientName: string; receiptId: string;
  valueDate: string; receiptKrw: number; amountKrw: number; matchScore: number; proposedBy: string; requestedAt: string | null;
}
/** 통장 명세 업로드 결과 — 숫자는 전부 건수 */
export interface StatementUploadResult {
  fileSha256: string; kind: string; encoding: string; headerRow: number; headers: string[]; rowsTotal: number;
  inflowLines: number; outflowLines: number; registered: number; replayed: number; skipped: number;
  balanceMismatchRows: number[]; warnings: string[]; linesStored: number; autoClassified: number; suggested: number; unclassified: number;
}
export interface BankLine {
  id: string; bankAccount: string; valueDate: string; direction: 'IN' | 'OUT'; amountKrw: number; memoText: string; statementKey: string;
  receiptId: string | null; suggestedAcct: string | null; ruleCode: string | null; confirmedAcct: string | null; confirmedBy: string | null;
}
export interface AcctSum { acct: string; direction: 'IN' | 'OUT'; lineCount: number; amountKrw: number }
export type DunningLevel = 'L0' | 'L1' | 'L2';
export type DunningStatus = 'DRAFT' | 'APPROVED' | 'SENT' | 'CANCELLED';
export const DUNNING_LEVEL_LABEL: Record<DunningLevel, string> = { L0: 'L0 알림(31일+)', L1: 'L1 담당자 안내(61일+)', L2: 'L2 공문(91일+, 임원 결재)' };
export interface DunningNotice {
  id: string; invoiceId: string; invoiceNo: string; projectId: string; clientName: string; level: DunningLevel; asOf: string; ageDays: number;
  openKrw: number; status: DunningStatus; bodyText: string; createdBy: string; createdAt: string; approvedBy: string | null; sentAt: string | null;
}
export interface DunningRunResult { asOf: string; created: number; cancelled: number; skippedOnHold: number }

export const cstBillingApi = {
  uploadStatement: (file: File, bankAccount: string) => {
    const fd = new FormData();
    fd.append('file', file);
    return request<StatementUploadResult>('POST', '/cst/receipts:upload', { rawBody: fd, query: { bankAccount } }).then((r) => r.data);
  },
  bankLines: (from: string, to: string, unclassifiedOnly: boolean, direction?: 'IN' | 'OUT') =>
    http.get<BankLine[]>('/cst/bank-lines', { from, to, unclassifiedOnly, direction }),
  bankLineSummary: (from: string, to: string) => http.get<AcctSum[]>('/cst/bank-lines/summary', { from, to }),
  bankLineAccounts: () => http.get<string[]>('/cst/bank-lines/accounts'),
  classifyLine: (id: string, acct: string, force = false, reason?: string) =>
    http.post<BankLine>(`/cst/bank-lines/${id}:classify`, { acct, force, reason }),
  dunningRun: (asOf: string) => http.post<DunningRunResult>('/cst/dunning:run', { asOf }),
  dunningNotices: (status?: DunningStatus) => http.get<DunningNotice[]>('/cst/dunning/notices', { status }),
  dunningApprove: (id: string) => http.post<{ id: string; status: string }>(`/cst/dunning/notices/${id}:approve`),
  dunningSent: (id: string, channel: string) => http.post<{ id: string; status: string }>(`/cst/dunning/notices/${id}:sent`, { channel }),
  dunningHold: (invoiceId: string, reason: string) => http.post<{ invoiceId: string; status: string }>(`/cst/dunning/invoices/${invoiceId}:hold`, { reason }),
  dunningRelease: (invoiceId: string, reason: string) => http.post<{ invoiceId: string; status: string }>(`/cst/dunning/invoices/${invoiceId}:release`, { reason }),
  aging: (asOf: string) => http.get<AgingRow[]>('/cst/receivables/aging', { asOf }),
  openItems: (asOf: string) => http.get<OpenItem[]>('/cst/receivables/open-items', { asOf }),
  candidates: (asOf: string, minScore = 60) => http.get<MatchCandidate[]>('/cst/allocations/candidates', { asOf, minScore }),
  propose: (invoiceId: string, receiptId: string, amountKrw: number) =>
    http.post<AllocationProposal>('/cst/allocations:propose', { invoiceId, receiptId, amountKrw }),
  pendingProposals: () => http.get<PendingProposal[]>('/cst/inbox/allocation-proposals'),
  confirmProposal: (id: string) => http.post<{ id: string; amountKrw: number }>(`/cst/allocation-proposals/${id}:confirm`),
  rejectProposal: (id: string, reason: string) => http.post<AllocationProposal>(`/cst/allocation-proposals/${id}:reject`, { reason }),
};
/** 지난달 말일 YYYY-MM-DD — 기준일 기본값(같은 기준일이면 언제 계산해도 같은 숫자) */
export function lastDayOfPrevMonth(now = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth(), 0);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
