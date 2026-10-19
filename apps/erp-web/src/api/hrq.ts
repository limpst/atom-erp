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
