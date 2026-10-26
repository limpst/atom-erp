import { http, type Page } from './http';

/** EQP 장비 (설계 05) — 목록 · 교정 현황 · 예약 · QR 반출/반납 */
export type EquipmentStatus =
  | 'REGISTERED' | 'AVAILABLE' | 'RESERVED' | 'CHECKED_OUT' | 'CAL_DUE' | 'IN_CALIBRATION' | 'BROKEN' | 'IN_REPAIR'
  | 'LOST' | 'DISPOSED';
export const EQUIPMENT_STATUS_LABEL: Record<EquipmentStatus, string> = {
  REGISTERED: '등록(교정 전)', AVAILABLE: '사용 가능', RESERVED: '예약됨', CHECKED_OUT: '반출 중', CAL_DUE: '교정 필요',
  IN_CALIBRATION: '교정 중', BROKEN: '고장', IN_REPAIR: '수리 중', LOST: '분실', DISPOSED: '폐기',
};

export interface Equipment {
  id: string; code: string; typeCode: string; typeName: string; parentId: string | null; model: string | null;
  manufacturer: string | null; serialNo: string; ownership: string; status: EquipmentStatus;
  homeLocation: string | null; dailyRate: number | null; version: number;
}

export type CalStatus = 'VALID' | 'DUE_SOON' | 'EXPIRED' | 'NONE' | 'FAILED' | 'NOT_REQUIRED';
export const CAL_STATUS: Record<CalStatus, { label: string; tone: 'ok' | 'warn' | 'danger' | 'info' | undefined }> = {
  VALID: { label: '교정 유효', tone: 'ok' },
  DUE_SOON: { label: '교정 임박', tone: 'warn' },
  EXPIRED: { label: '교정 만료', tone: 'danger' },
  FAILED: { label: '교정 불합격', tone: 'danger' },
  NONE: { label: '교정 이력 없음', tone: 'warn' },
  NOT_REQUIRED: { label: '교정 불필요', tone: 'info' },
};
export interface CalibrationStatus {
  equipmentId: string; code: string; typeCode: string; typeName: string; status: EquipmentStatus;
  calibrationRequired: boolean; nextDueOn: string | null; daysLeft: number | null; calStatus: CalStatus;
}

export type ReservationStatus =
  | 'REQUESTED' | 'CONFIRMED' | 'REJECTED' | 'IN_USE' | 'CANCELLED' | 'EXPIRED' | 'RETURNED' | 'OVERDUE';
export const RESERVATION_STATUS_LABEL: Record<ReservationStatus, string> = {
  REQUESTED: '승인 대기', CONFIRMED: '확정', REJECTED: '반려', IN_USE: '사용 중', CANCELLED: '취소', EXPIRED: '만료',
  RETURNED: '반납', OVERDUE: '반납 지연',
};
/** 서버 행(snake_case) */
export interface Reservation {
  id: string; equipment_id: string; project_id: string; from_date: string; to_date: string;
  status: ReservationStatus; conditional: boolean; version: number;
  /** EQP-01 신청자(본인 승인 금지 · 취소는 신청자 또는 승인 권한자) */
  requested_by?: string;
}
export interface Alternative { equipmentId: string; code: string; validUntil: string | null; score: number }

export interface CheckoutResult {
  checkoutId: string; equipmentId: string; projectId: string; reservationId: string | null; syncConflict: boolean;
  equipmentStatus: EquipmentStatus; result: 'OK' | 'REPLAYED' | 'CONFLICT_RECORDED';
  calibration?: { basis: string; validUntil: string | null; daysLeft: number | null };
}
export interface ReturnResult {
  checkoutId: string; equipmentId: string; equipmentStatus: EquipmentStatus; usageDays: number; returnedOn: string;
  faultId: string | null;
}
export interface ScanResult {
  equipmentId: string; code: string; typeCode: string; typeName: string; status: EquipmentStatus;
  activeCheckout: { id: string; project_id: string; out_by: string; out_at: string } | null; actions: string[];
}

export const eqpApi = {
  list: () => http.get<Page<Equipment>>('/eqp/equipment', { size: 500 }),
  calibrationStatus: (withinDays?: number) => http.get<CalibrationStatus[]>('/eqp/calibration-status', { withinDays }),
  reservations: (equipmentId?: string) => http.get<Reservation[]>('/eqp/reservations', { equipmentId }),
  reserve: (body: { equipmentId: string; projectId: string; from: string; to: string }) =>
    http.post<Reservation>('/eqp/reservations', body),
  approve: (id: string) => http.post<Reservation>(`/eqp/reservations/${id}:approve`, {}),
  cancel: (id: string) => http.post<Reservation>(`/eqp/reservations/${id}:cancel`, {}),
  issueLabel: (id: string) =>
    http.post<{ equipmentId: string; code: string; token: string; qrUrl: string }>(`/eqp/equipment/${id}/labels`, { rotate: false }),
  scan: (token: string) => http.get<ScanResult>(`/eqp/scan/${encodeURIComponent(token)}`),
  /** clientOpId 는 대화상자마다 1개(재시도 = 같은 작업 → REPLAYED) */
  checkout: (body: { clientOpId: string; qrToken: string; projectId: string; preCheck: Record<string, unknown> }) =>
    http.post<CheckoutResult>('/eqp/checkouts', body),
  return: (checkoutId: string, postCheck: { abnormal: boolean; symptom?: string }) =>
    http.post<ReturnResult>(`/eqp/checkouts/${checkoutId}/return`, { postCheck }),
};

/** QR 라벨 내용 → 토큰: URL(https://…/q/<token>) 이든 토큰 그대로든 받는다 */
export function parseQrToken(input: string): string {
  const t = input.trim();
  const m = t.match(/\/q\/([^/?#\s]+)/);
  return m ? decodeURIComponent(m[1]) : t;
}
export const QR_TOKEN_RE = /^eqp1\.[A-Za-z0-9-]+\.\d+\.[A-Za-z0-9_-]{16}$/;

/** [from, to] 두 기간(양 끝 포함)이 겹치는지 */
export function overlaps(aFrom: string, aTo: string, bFrom: string, bTo: string) {
  return aFrom <= bTo && bFrom <= aTo;
}
export const ACTIVE_RESERVATION: ReservationStatus[] = ['REQUESTED', 'CONFIRMED', 'IN_USE', 'OVERDUE'];
