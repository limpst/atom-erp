import { request, http } from './http';

/** PTL 고객 포털 (설계 11) — CUSTOMER 전용. 테넌트(고객사)는 서버가 X-Client-Org-Id / 토큰 cid 로 정한다(RLS). */
export interface PortalDue {
  buildingId: string; buildingName: string; inspectionType: string; dueDate: string; dDay: number;
  severity: 'OVERDUE' | 'RED' | 'YELLOW' | 'GREEN'; facStatus: string;
}
export interface PortalBuilding {
  id: string; name: string; dongName: string; roadAddress: string | null; structure: string | null;
  grossFloorArea: number | null; floorsAbove: number | null; floorsBelow: number | null; approvalDate: string | null;
  status: string; currentGrade: string | null; gradePerformedOn: string | null; nextInspection?: PortalDue;
}
export interface PortalReport {
  id: string; reportId: string; revisionId: string; revNo: number; facilityId: string; reportType: string; title: string;
  inspectionDate: string; safetyGrade: string | null; pageCount: number | null; deliveredAt: string;
  status: 'PUBLISHED' | 'SUPERSEDED' | 'REVOKED'; downloadable: boolean; banner: string | null;
  supersededByRevisionId: string | null; supersededReason: string | null; supersededAt: string | null;
}
export interface ServiceRequest {
  id: string; kind: ServiceRequestKind; facilityId: string | null; serviceType: string; reasonCode: string | null;
  description: string; status: string; slaDueAt: string | null; createdAt: string;
}
export type ServiceRequestKind = 'QUOTE' | 'EMERGENCY' | 'REINSPECTION' | 'VERIFY_REPAIR';
export const KIND_LABEL: Record<ServiceRequestKind, string> = {
  QUOTE: '견적 요청', EMERGENCY: '긴급 점검', REINSPECTION: '재점검', VERIFY_REPAIR: '보수 확인',
};
export const REASON_LABEL: Record<string, string> = {
  EARTHQUAKE: '지진', FIRE: '화재', CRACK_GROWTH: '균열 진행', LEAK: '누수', OTHER: '기타',
};
export interface Notification {
  id: string; kind: string; category: string; title: string; body: string; refType: string | null; refId: string | null;
  createdAt: string; readAt: string | null;
}
export const SEVERITY_TONE: Record<PortalDue['severity'], 'danger' | 'warn' | 'ok'> = {
  OVERDUE: 'danger', RED: 'danger', YELLOW: 'warn', GREEN: 'ok',
};

export interface Download { blob: Blob; downloadId: string | null; sha256: string | null; filename: string }

export const ptlApi = {
  me: () => http.get<{ customerId: string; actorId: string }>('/ptl/me'),
  buildings: () => http.get<PortalBuilding[]>('/ptl/buildings'),
  schedule: () => http.get<PortalDue[]>('/ptl/inspection-schedule'),
  reports: () => http.get<PortalReport[]>('/ptl/reports'),
  report: (id: string) => http.get<PortalReport>(`/ptl/reports/${id}`),
  /** 워터마크 PDF(매 다운로드마다 다른 download-id · 해시) */
  download: async (id: string): Promise<Download> => {
    const r = await request<Blob>('POST', `/ptl/reports/${id}:download`, { accept: 'application/pdf, application/problem+json' });
    const cd = r.headers.get('Content-Disposition') ?? '';
    const filename = /filename="([^"]+)"/.exec(cd)?.[1] ?? `report-${id}.pdf`;
    return { blob: r.data, downloadId: r.headers.get('X-Download-Id'), sha256: r.headers.get('X-Content-SHA256'), filename };
  },
  serviceRequests: () => http.get<ServiceRequest[]>('/ptl/service-requests'),
  submitRequest: (body: { kind: ServiceRequestKind; facilityId?: string; reasonCode?: string; description: string; contactName?: string }, idempotencyKey?: string) =>
    http.post<ServiceRequest>('/ptl/service-requests', body, { idempotencyKey }),
  notifications: (unread?: boolean) => http.get<Notification[]>('/ptl/notifications', { unread }),
  markRead: (id: string) => http.post<void>(`/ptl/notifications/${id}:read`, {}),
};

/** 브라우저 저장(a[download]) */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
