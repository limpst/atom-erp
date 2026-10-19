import { http, newIdempotencyKey } from './http';

/** HRQ 인력·자격 — HrqController DTO */
export type WorkType = 'FIELD' | 'OFFICE' | 'REPORT' | 'TRAVEL' | 'REVIEW' | 'LABELING';
export const WORK_TYPE_LABEL: Record<WorkType, string> = {
  FIELD: '현장', OFFICE: '내근', REPORT: '보고서', TRAVEL: '출장 이동', REVIEW: '검토', LABELING: '라벨링',
};
export type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'LOCKED' | 'SUPERSEDED';
export const TS_STATUS_LABEL: Record<TimesheetStatus, string> = {
  DRAFT: '임시저장', SUBMITTED: '제출', APPROVED: '승인', LOCKED: '마감', SUPERSEDED: '정정됨',
};

export interface EmployeeRow {
  id: string; empNo: string; name: string; field: string; employmentType: string;
  status: 'ACTIVE' | 'LEAVE' | 'RESIGN_PLANNED' | 'RESIGNED'; registeredField: boolean; hiredOn: string;
  resignPlannedOn: string | null; resignedOn: string | null; version: number;
}
export interface EntryRequest { projectId: string; workDate: string; workType: WorkType; hours: number }
export interface WeekRequest { entries: EntryRequest[]; submit: boolean }
export interface TimesheetRow {
  id: string; employeeId: string; projectId: string; workDate: string; workType: WorkType; hours: number;
  status: TimesheetStatus; timesheetKey: string; revisionNo: number; supersedesId: string | null;
  correctionReason: string | null; postingPeriod: string;
}
export interface CorrectRequest { hours: number; workType?: WorkType; reason: string; adjustment?: boolean }
export interface CloseResult {
  yearMonth: string; rows: number; totalMandays: number; lockedEntries: number;
  mandays: { projectId: string; employeeId: string; grade: string; workType: string; mandays: number }[];
}

export type CredentialStatus = 'PENDING' | 'VALID' | 'EXPIRING' | 'EXPIRED' | 'REJECTED' | 'SUPERSEDED' | 'REVOKED';
export const CRED_STATUS_LABEL: Record<CredentialStatus, string> = {
  PENDING: '승인 대기', VALID: '유효', EXPIRING: '만료 임박', EXPIRED: '만료', REJECTED: '반려', SUPERSEDED: '대체됨', REVOKED: '취소',
};
/** GET /hrq/credentials — JDBC 맵(snake_case) */
export interface CredentialRow {
  id: string; employee_id: string; emp_no: string; employee_name: string; type_code: string; type_name: string;
  has_expiry: boolean; cert_no: string | null; acquired_on: string; expires_on: string | null; status: CredentialStatus;
  reject_reason: string | null; approved_by: string | null; approved_at: string | null; created_at: string;
}
export type Grade = 'JUNIOR' | 'INTER' | 'SENIOR' | 'MASTER';
export const GRADE_LABEL: Record<Grade, string> = { JUNIOR: '초급', INTER: '중급', SENIOR: '고급', MASTER: '특급' };
export interface ComplianceOutcome {
  rule: { id: string; field: string; minGrade: Grade; minCount: number; requiredCredential: string | null; legalReference: string };
  assignedEmpNos: string[]; shortage: number;
}
export interface ComplianceField { field: string; satisfied: boolean; outcomes: ComplianceOutcome[] }
export interface ComplianceEvaluation { date: string; allSatisfied: boolean; fields: ComplianceField[] }
export interface ComplianceForecast {
  from: string; to: string; firstShortfall: string | null; shortage: Record<string, number>; series: ComplianceEvaluation[];
}
export interface MonthCloseRow {
  year_month: string; status: 'OPEN' | 'CLOSED' | 'REOPENED'; total_mandays: number | null; closed_at: string | null;
  closed_by: string | null; reopened_at: string | null; reopened_by: string | null; reopen_reason: string | null;
}

/** 등록요건 미달 인원 합계(분야 · 등급 전체) */
export function totalShortage(e: ComplianceEvaluation): number {
  return e.fields.reduce((a, f) => a + f.outcomes.reduce((b, o) => b + o.shortage, 0), 0);
}
export function totalRequired(e: ComplianceEvaluation): number {
  return e.fields.reduce((a, f) => a + f.outcomes.reduce((b, o) => b + o.rule.minCount, 0), 0);
}

/** 자격 만료 칩 판정 — 오늘 기준 남은 일수(만료일 없음 = 무기한) */
export function credentialChip(c: Pick<CredentialRow, 'status' | 'expires_on'>, today: string): {
  label: string; tone: 'ok' | 'warn' | 'danger' | 'info' | undefined; daysLeft: number | null;
} {
  const days = c.expires_on ? Math.round((Date.parse(c.expires_on) - Date.parse(today)) / 86400000) : null;
  if (c.status === 'PENDING') return { label: '승인 대기', tone: 'info', daysLeft: days };
  if (c.status === 'REJECTED' || c.status === 'REVOKED' || c.status === 'SUPERSEDED') {
    return { label: CRED_STATUS_LABEL[c.status], tone: undefined, daysLeft: days };
  }
  if (days === null) return { label: '유효 (무기한)', tone: 'ok', daysLeft: null };
  if (days < 0 || c.status === 'EXPIRED') return { label: `만료 D+${Math.abs(days)}`, tone: 'danger', daysLeft: days };
  if (days <= 90) return { label: `만료 임박 D-${days}`, tone: 'warn', daysLeft: days };
  return { label: `유효 D-${days}`, tone: 'ok', daysLeft: days };
}

export const hrqApi = {
  employees: () => http.get<EmployeeRow[]>('/hrq/employees'),
  getWeek: (empId: string, isoWeek: string) => http.get<TimesheetRow[]>(`/hrq/timesheets/${empId}/weeks/${isoWeek}`),
  /** PUT 주간 — 열린(DRAFT/SUBMITTED) 항목 전체 교체. HRQ 는 ETag 를 쓰지 않는다. */
  putWeek: (empId: string, isoWeek: string, body: WeekRequest) =>
    http.put<TimesheetRow[]>(`/hrq/timesheets/${empId}/weeks/${isoWeek}`, body),
  approve: (entryIds: string[]) => http.post<{ approved: number }>('/hrq/timesheets/approvals', { entryIds }),
  /** 정정은 Idempotency-Key 필수(서버 428) — 같은 동작 재시도 시 같은 키를 넘긴다 */
  correct: (id: string, body: CorrectRequest, idempotencyKey = newIdempotencyKey()) =>
    http.post<TimesheetRow>(`/hrq/timesheets/${id}:correct`, body, { idempotencyKey }),
  revisions: (timesheetKey: string) => http.get<TimesheetRow[]>(`/hrq/timesheets/keys/${timesheetKey}/revisions`),
  closeMonth: (yyyyMM: string) => http.post<CloseResult>(`/hrq/month-close/${yyyyMM}`, {}),
  reopenMonth: (yyyyMM: string, reason: string) =>
    http.post<MonthCloseRow>(`/hrq/month-close/${yyyyMM}:reopen`, { reason }),
  monthCloses: () => http.get<MonthCloseRow[]>('/hrq/month-close'),
  credentials: (status?: CredentialStatus) => http.get<CredentialRow[]>('/hrq/credentials', { status }),
  approveCredential: (id: string) => http.post(`/hrq/credentials/${id}:approve`),
  rejectCredential: (id: string, reason: string) => http.post(`/hrq/credentials/${id}:reject`, { reason }),
  compliance: (date?: string) => http.get<ComplianceEvaluation>('/hrq/compliance', { date }),
  forecast: (from?: string, to?: string) => http.get<ComplianceForecast>('/hrq/compliance:forecast', { from, to }),
};

/** ISO 주차 유틸 — '2026-W42' ↔ 월요일 */
export function isoWeekOf(d: Date): string {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  const week = Math.ceil(((t.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
  return `${t.getUTCFullYear()}-W${String(week).padStart(2, '0')}`;
}
export function mondayOf(isoWeek: string): Date {
  const [y, w] = isoWeek.split('-W').map(Number);
  const jan4 = new Date(Date.UTC(y, 0, 4));
  const day = jan4.getUTCDay() || 7;
  const mon = new Date(jan4);
  mon.setUTCDate(jan4.getUTCDate() - day + 1 + (w - 1) * 7);
  return mon;
}
export function weekDates(isoWeek: string): string[] {
  const mon = mondayOf(isoWeek);
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(mon);
    d.setUTCDate(mon.getUTCDate() + i);
    return d.toISOString().slice(0, 10);
  });
}
export function shiftWeek(isoWeek: string, delta: number): string {
  const mon = mondayOf(isoWeek);
  mon.setUTCDate(mon.getUTCDate() + delta * 7);
  return isoWeekOf(new Date(mon.getUTCFullYear(), mon.getUTCMonth(), mon.getUTCDate()));
}
