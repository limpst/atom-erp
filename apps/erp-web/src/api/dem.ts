import { http } from './http';

/** DEM 해체 · 감리 (설계 10) — 프로젝트 → 업무 패키지 · 감리자 · 감리일지 · 도서 리비전 · 트랜스미털 · 검토의견 */
export interface WorkPackage {
  id: string; packageType: string; facilityId: string | null; status: string; locale: string; currency: string;
  supervisorGap: boolean;
}
export interface DemProject {
  projectId: string; buildingId: string | null; status: string; completedOn: string | null; version: number;
  workPackages: WorkPackage[];
}
export const PACKAGE_TYPE_LABEL: Record<string, string> = {
  DEM_PLAN: '해체계획', DEM_SAFETY: '해체 안전', DEM_TEMP: '가설', DEM_SUPERVISION: '해체 감리', STR_DESIGN: '구조 설계',
  STR_REVIEW: '구조 검토', STR_SUPERVISION: '구조 감리', VE: 'VE',
};
export const PROJECT_STATUS_LABEL: Record<string, string> = {
  DRAFT: '초안', PLANNING: '계획', REVIEWING: '검토', PERMIT_PENDING: '허가 대기', IN_PROGRESS: '공사 중',
  SUSPENDED: '중지', COMPLETING: '완료 신고', CLOSED: '종료',
};

export interface CredentialCheck {
  credentialId: string; typeCode: string; status: string; expiresOn: string | null; expiredEventReceived: boolean;
  valid: boolean;
}
export interface Assignment {
  id: string; workPackageId: string; employeeId: string; role: 'CHIEF' | 'ASSISTANT'; validFrom: string;
  validTo: string | null; status: 'ACTIVE' | 'RELEASED'; releaseReason: string | null; version: number;
  hrqCheckSnapshot: {
    checkedOn: string; requiredAnyOf: string[]; credentials: CredentialCheck[]; qualified: boolean; reasons: string[];
  } | null;
}
export interface SupervisorAlert {
  id: string; employeeId: string; kind: 'CREDENTIAL_EXPIRED' | 'EMPLOYEE_TERMINATED'; detail: unknown;
  createdAt: string; resolvedAt: string | null;
}
export interface DailyLogSummary {
  id: string; workPackageId: string; logDate: string; authorId: string; phaseCode: string; progressPct: number | null;
  workersCount: number | null; status: 'DRAFT' | 'SUBMITTED' | 'LOCKED'; locked: boolean; lateSync: boolean;
  lockedAt: string | null; contentSha256: string | null; amendments: number; pendingAmendments: number; version: number;
}
export interface DailyLogDetail extends Omit<DailyLogSummary, 'amendments' | 'locked' | 'pendingAmendments'> {
  remarks: string | null; weather: Record<string, unknown> | null; equipment: unknown[] | null;
  checklist: { id: string; seq: number; title: string; result: string; severity: string | null; note: string | null; correctiveOrderNo: string | null }[];
  amendments: { id: string; fieldPath: string; reason: string; status: 'APPLIED' | 'PENDING'; amendedAt: string }[];
}
export const PHASE_LABEL: Record<string, string> = {
  TEMPORARY: '가설', ASBESTOS_CHECK: '석면 확인', UPPER_DEMOLITION: '상부 해체', LOWER_DEMOLITION: '하부 해체',
  DEBRIS_OUT: '잔재 반출',
};

export type RevisionStatus = 'WIP' | 'INTERNAL_REVIEW' | 'APPROVED' | 'ISSUED' | 'SUPERSEDED';
export const REVISION_STATUS_LABEL: Record<RevisionStatus, string> = {
  WIP: '작성 중', INTERNAL_REVIEW: '내부 검토', APPROVED: '승인', ISSUED: '발행', SUPERSEDED: '대체됨',
};
export interface Revision {
  id: string; documentId: string; docNo: string; revCode: string; issuedRev: string | null; status: RevisionStatus;
  fileAttachmentId: string; changeSummary: string | null; approvedBy: string | null; transmittalId: string | null;
  issuedAt: string | null;
}
export interface DemDocument {
  id: string; workPackageId: string; docNo: string; title: string; discipline: string; isInput: boolean;
  revisions: Revision[]; unresolvedComments: number;
}
export interface Transmittal {
  id: string; transmittalNo: string; purpose: TransmittalPurpose; recipientName: string | null; issuedAt: string;
  revisions: { revisionId: string; docNo: string; revCode: string; issuedRev: string | null }[];
}
export type TransmittalPurpose = 'FOR_REVIEW' | 'FOR_APPROVAL' | 'FOR_CONSTRUCTION';
export const PURPOSE_LABEL: Record<TransmittalPurpose, string> = {
  FOR_REVIEW: '검토용', FOR_APPROVAL: '승인용', FOR_CONSTRUCTION: '시공용',
};
export type CommentStatus = 'OPEN' | 'RESPONDED' | 'ACCEPTED' | 'REJECTED' | 'CLOSED';
export const COMMENT_STATUS_LABEL: Record<CommentStatus, string> = {
  OPEN: '미결', RESPONDED: '회신', ACCEPTED: '수용', REJECTED: '재검토 요청', CLOSED: '종결',
};
/** 다음에 갈 수 있는 상태(서버 전이표와 같음) */
export const COMMENT_NEXT: Record<CommentStatus, CommentStatus[]> = {
  OPEN: ['RESPONDED', 'CLOSED'], RESPONDED: ['ACCEPTED', 'REJECTED'], REJECTED: ['RESPONDED'], ACCEPTED: ['CLOSED'],
  CLOSED: [],
};
export interface ReviewComment {
  id: string; revisionId: string; commentNo: string; source: string; severity: 'MINOR' | 'MAJOR' | 'CRITICAL';
  body: string; status: CommentStatus; unresolved: boolean; authorId: string;
  replies: { authorId: string; body: string; previousStatus: string; resultingStatus: string }[];
}

export const demApi = {
  projects: () => http.get<DemProject[]>('/dem/projects'),
  supervisors: (wp: string) =>
    http.get<{ assignments: Assignment[]; alerts: SupervisorAlert[] }>(`/dem/work-packages/${wp}/supervisors`),
  assign: (wp: string, body: { employeeId: string; role: 'CHIEF' | 'ASSISTANT'; validFrom: string; reason?: string }) =>
    http.post<Assignment>(`/dem/work-packages/${wp}/supervisors`, body),
  release: (id: string, releasedOn: string) => http.post<Assignment>(`/dem/supervisors/${id}:release`, { releasedOn }),
  dailyLogs: (wp: string) => http.get<DailyLogSummary[]>(`/dem/work-packages/${wp}/daily-logs`),
  dailyLog: (id: string) => http.get<DailyLogDetail>(`/dem/daily-logs/${id}`),
  documents: (wp: string) => http.get<DemDocument[]>(`/dem/work-packages/${wp}/documents`),
  transmittals: (wp: string) => http.get<Transmittal[]>(`/dem/work-packages/${wp}/transmittals`),
  issue: (body: { workPackageId: string; purpose: TransmittalPurpose; revisionIds: string[]; recipientName?: string }) =>
    http.post<Transmittal>('/dem/transmittals', body),
  comments: (revisionId: string) => http.get<ReviewComment[]>(`/dem/revisions/${revisionId}/comments`),
  addComment: (revisionId: string, body: { source: string; severity: string; body: string }) =>
    http.post<ReviewComment>(`/dem/revisions/${revisionId}/comments`, body),
  reply: (commentId: string, body: { body: string; resultingStatus: CommentStatus }) =>
    http.post<ReviewComment>(`/dem/comments/${commentId}/replies`, body),
  revisionCommand: (id: string, cmd: 'request-review' | 'approve' | 'reject') =>
    http.post<Revision>(`/dem/revisions/${id}:${cmd}`, {}),
};

/** 자격 경고: 만료(EXPIRED · 만료 이벤트 수신) 또는 30일 이내 만료 */
export function credentialWarning(c: Pick<CredentialCheck, 'status' | 'expiresOn' | 'expiredEventReceived'>, today: string) {
  if (c.status === 'EXPIRED' || c.expiredEventReceived || (c.expiresOn !== null && c.expiresOn < today)) {
    return { tone: 'danger' as const, label: '자격 만료' };
  }
  if (c.expiresOn) {
    const days = Math.round((Date.parse(c.expiresOn) - Date.parse(today)) / 86_400_000);
    if (days <= 30) return { tone: 'warn' as const, label: `자격 만료 임박(D-${days})` };
  }
  return null;
}

/** 시공용(FOR_CONSTRUCTION) 발행 가능 여부: 선택한 리비전들의 도서에 미결 의견이 없어야 한다(BR-DEM-009) */
export function constructionBlockers(docs: DemDocument[], revisionIds: string[]) {
  return docs.filter((d) => d.revisions.some((r) => revisionIds.includes(r.id)) && d.unresolvedComments > 0);
}
