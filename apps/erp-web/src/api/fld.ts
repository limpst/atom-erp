import { http, newIdempotencyKey } from './http';

/** FLD 현장조사 (설계 01) — 관리자(데스크톱) 화면: 회차 · 구역 잠금 · 동기화 충돌 · 사진 검증 */
export interface Zone {
  id: string; roundId: string; zoneName: string; assigneeId: string; floorNodeId: string | null;
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'LOCKED'; defectCount: number;
}
export interface Round {
  id: string; projectId: string; buildingId: string; roundNo: number; surveyType: string; evidenceMode: boolean;
  status: 'IN_PROGRESS' | 'COMPLETED'; completedAt: string | null; lastSeq: number; zones: Zone[];
}
export const SURVEY_TYPE_LABEL: Record<string, string> = {
  REGULAR: '정기점검', PRECISE_INSPECTION: '정밀점검', PRECISE_DIAGNOSIS: '정밀진단', ADJACENT_PRE: '인접 사전조사',
  ADJACENT_POST: '인접 사후조사', DEMOLITION: '해체',
};
export const ZONE_STATUS_LABEL: Record<Zone['status'], string> = {
  NOT_STARTED: '시작 전', IN_PROGRESS: '조사 중', LOCKED: '잠금',
};
export interface LockIssue {
  kind: 'REQUIRED_MISSING' | 'OPEN_CONFLICT' | 'AI_SUGGESTION_PENDING' | 'PHOTO_REJECTED';
  defectId: string; defectCode: string; message: string; conflictId?: string; photoId?: string;
}
export const LOCK_ISSUE_LABEL: Record<LockIssue['kind'], string> = {
  REQUIRED_MISSING: '필수 측정 누락', OPEN_CONFLICT: '미해결 충돌', AI_SUGGESTION_PENDING: 'AI 제안 미결정',
  PHOTO_REJECTED: '사진 검증 실패',
};
export interface LockCheck { zoneId: string; ready: boolean; issues: LockIssue[] }

export type Resolution = 'KEEP_SERVER' | 'KEEP_CLIENT' | 'MERGE';
export const RESOLUTION_LABEL: Record<Resolution, string> = {
  KEEP_SERVER: '서버 값 유지', KEEP_CLIENT: '기기 값 채택', MERGE: '직접 값 입력(병합)',
};
export interface Conflict {
  id: string; roundId: string; zoneId: string; defectId: string; field: string;
  strategy: 'MANUAL' | 'GEOM_5MIN' | 'REMOVE_WINS'; localEventType: string; localValue: unknown;
  localPayload: Record<string, unknown>; localHlc: string; localDeviceId: string; localActorId: string;
  serverSeq: number; serverValue: unknown; serverHlc: string; serverDeviceId: string; status: 'OPEN' | 'RESOLVED';
  resolution: Resolution | null; resolvedValue: unknown; resolvedBy: string | null; resolvedAt: string | null;
}
export interface Photo {
  photoId: string; defectId: string | null; defectCode: string | null; kind: string;
  uploadState: 'PENDING' | 'UPLOADING' | 'VERIFIED' | 'REJECTED'; deviceSha256: string; serverSha256: string | null;
  attachmentId: string | null; rejectReason: string | null; rejectCount: number; sizeBytes: number | null;
  verifiedAt: string | null; invalidated: boolean;
}
export const UPLOAD_STATE: Record<Photo['uploadState'], { label: string; tone: 'ok' | 'warn' | 'danger' | 'info' }> = {
  PENDING: { label: '업로드 대기', tone: 'warn' },
  UPLOADING: { label: '업로드 중', tone: 'info' },
  VERIFIED: { label: '해시 검증 완료', tone: 'ok' },
  REJECTED: { label: '검증 실패(재업로드)', tone: 'danger' },
};
export interface PhotoVerify {
  photoId: string; uploadState: string; deviceSha256: string; attachmentId: string | null; serverSha256: string | null;
  result: 'MATCH' | 'MISMATCH' | 'NOT_UPLOADED';
}

/** 웹 관리자 화면의 고정 장치 ID(충돌 해결 시 서버가 기기 op 를 다시 쓴다) — 합성 */
export const ADMIN_DEVICE_ID = '00000000-0000-7000-8000-00000000eeb0';

export const fldApi = {
  rounds: (projectId: string) => http.get<Round[]>('/fld/rounds', { projectId }),
  lockCheck: (zoneId: string) => http.get<LockCheck>(`/fld/zones/${zoneId}/lock-check`),
  lock: (zoneId: string) => http.post<Zone>(`/fld/zones/${zoneId}/lock`, {}),
  conflicts: (roundId: string) => http.get<Conflict[]>('/fld/sync/conflicts', { roundId }),
  photos: (roundId: string) => http.get<Photo[]>(`/fld/rounds/${roundId}/photos`),
  verify: (photoId: string) => http.get<PhotoVerify>(`/fld/photos/${photoId}/verify`),
  /** opId 는 대화상자마다 1개 — 재시도하면 서버가 replayed 로 응답 */
  resolve: (conflictId: string, resolution: Resolution, opId: string, merged?: Record<string, unknown>) =>
    http.post<Conflict>(`/fld/sync/conflicts/${conflictId}/resolve`, resolution === 'KEEP_SERVER'
      ? { resolution }
      : { resolution, opId, deviceId: ADMIN_DEVICE_ID, merged }),
};
export const newOpId = newIdempotencyKey;

/** 해결 방식 선택지: REMOVE_WINS(삭제 우선) 충돌은 서버 값 유지만 가능 */
export function allowedResolutions(c: Pick<Conflict, 'strategy'>): Resolution[] {
  return c.strategy === 'REMOVE_WINS' ? ['KEEP_SERVER'] : ['KEEP_SERVER', 'KEEP_CLIENT', 'MERGE'];
}
export const shortHash = (h: string | null | undefined) => (h ? `${h.slice(0, 10)}…` : '—');
