import { http } from './http';

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
  approveBudget: (projectId: string, rev: Pick<BudgetRevision, 'id' | 'version'>) =>
    http.post<BudgetRevision>(`/cst/projects/${projectId}/budget-revisions/${rev.id}:approve`, undefined, {
      ifMatch: `"v${rev.version}"`,
    }),
  profitability: (projectId: string) => http.get<Profitability>(`/cst/projects/${projectId}/profitability`),
  evm: (projectId: string, asOf?: string) => http.get<EvmView>(`/cst/projects/${projectId}/evm`, { asOf }),
  ledger: (projectId: string) => http.get<LedgerRow[]>(`/cst/projects/${projectId}/ledger`),
};
