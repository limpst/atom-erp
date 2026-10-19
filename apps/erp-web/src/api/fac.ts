import { http, type Page } from './http';

/** FAC 시설물 (설계 02) — 목록 · 등록(주소 조회 / 수기) · 상세(위치 트리 ltree · 등급 이력 · 도래) · 병합 */
export type BuildingStatus =
  | 'DRAFT' | 'VERIFIED' | 'CLASSIFIED' | 'ACTIVE' | 'RECLASSIFY_REQUIRED' | 'MERGED' | 'DEMOLISHED' | 'INACTIVE';
export const BUILDING_STATUS_LABEL: Record<BuildingStatus, string> = {
  DRAFT: '초안(수기)', VERIFIED: '대장 확인', CLASSIFIED: '분류 확정', ACTIVE: '운영', RECLASSIFY_REQUIRED: '재분류 필요',
  MERGED: '병합됨', DEMOLISHED: '해체', INACTIVE: '비활성',
};
export type DataSource = 'REGISTER' | 'MANUAL' | 'MIGRATION';
export const DATA_SOURCE_LABEL: Record<DataSource, string> = { REGISTER: '건축물대장', MANUAL: '수기 입력', MIGRATION: '이관' };

export interface Building {
  id: string; siteId: string; name: string; dongName: string; pnu: string | null; roadAddress: string | null;
  mainPurposeNm: string | null; structureNm: string | null; grossFloorArea: number | null; floorsAbove: number | null;
  floorsBelow: number | null; approvalDate: string | null; status: BuildingStatus; dataSource: DataSource;
  useRestrictionFlag: boolean; version: number;
}

export interface RegisterTitle {
  mgmBldrgstPk: string; buildingName: string; dongName: string; mainPurposeNm: string | null; structureNm: string | null;
  grossFloorArea: number | null; floorsAbove: number | null; floorsBelow: number | null; approvalDate: string | null;
}
export interface LookupView {
  address: { roadAddress: string; jibunAddress: string; admCd: string; pnu: { value: string }; buildingName: string | null } | null;
  register: { available: boolean; siteName: string | null; titles: RegisterTitle[]; failureReason: string | null } | null;
  failureReason: string | null;
}
export const LOOKUP_FAILURE_LABEL: Record<string, string> = {
  ADDRESS_NOT_FOUND: '주소를 찾지 못했습니다',
  NOT_CONFIGURED: '공공 API 미설정',
  API_ERROR: '공공 API 오류',
  TIMEOUT: '공공 API 시간 초과',
  HTTP_5XX: '공공 API 장애',
  QUOTA_EXCEEDED: '공공 API 호출 한도 초과',
};

export interface CreateBuildingRequest {
  lookupAddress?: string; mgmBldrgstPk?: string; ownerClientId?: string; name?: string; dongName?: string;
  roadAddress?: string; mainPurposeNm?: string; structureNm?: string; grossFloorArea?: number; floorsAbove?: number;
  floorsBelow?: number; approvalDate?: string; dataSource?: DataSource; force?: boolean; forceReason?: string;
}

export interface TreeNode {
  id: string; nodeType: 'FLOOR' | 'ZONE' | 'MEMBER'; memberType: string | null; code: string; name: string | null;
  floorLevel: number | null; gridRef: string | null; path: string; children: TreeNode[];
}
export interface GradeRow {
  id: string; buildingId: string; grade: 'A' | 'B' | 'C' | 'D' | 'E'; inspectionType: string; performedOn: string;
  reportId: string | null; performer: string; source: string; correctionOf: string | null; recordedBy: string | null;
}
export interface BuildingDue {
  id: string; inspectionType: string; baseDate: string | null; dueDate: string; status: string;
  calcTrace: Record<string, unknown> | null;
}
export interface DueItem {
  id: string; buildingId: string; buildingName: string; dongName: string; roadAddress: string | null;
  inspectionType: string; baseDate: string | null; dueDate: string; status: string; daysLeft: number;
}
export interface MergeReport {
  sourceId: string; targetId: string; nodes: number; drawings: number; grades: number; duesCancelled: number;
  repairs: number; parcels: number;
}
export type SitbeopClass = 'CLASS1' | 'CLASS2' | 'CLASS3' | 'NONE';

export const INSPECTION_TYPE_LABEL: Record<string, string> = {
  REGULAR: '정기안전점검', DETAILED: '정밀안전점검', PRECISION_DIAG: '정밀안전진단', EMERGENCY: '긴급안전점검',
  BMA_CHECK: '건축물관리 점검',
};
export const DUE_STATUS_LABEL: Record<string, string> = {
  SCHEDULED: '예정', APPROACHING_180: '180일 전', APPROACHING_90: '90일 전', APPROACHING_30: '30일 전',
  OVERDUE: '기한 경과', CONTRACTED: '계약됨',
};

export const facApi = {
  list: (q?: string) => http.get<Page<Building>>('/fac/buildings', { q, size: 200 }),
  get: (id: string) => http.get<Building>(`/fac/buildings/${id}`),
  lookup: (address: string) => http.get<LookupView>('/fac/register-lookup', { address }),
  register: (body: CreateBuildingRequest, idempotencyKey?: string) =>
    http.post<Building>('/fac/buildings', body, { idempotencyKey }),
  tree: (id: string) => http.get<TreeNode[]>(`/fac/buildings/${id}/nodes`),
  grades: (id: string) => http.get<GradeRow[]>(`/fac/buildings/${id}/grades`),
  dues: (id: string) => http.get<BuildingDue[]>(`/fac/buildings/${id}/dues`),
  dueList: (withinDays?: number) => http.get<DueItem[]>('/fac/dues', { withinDays }),
  classify: (id: string, sitbeopClass: SitbeopClass, note: string) =>
    http.put<Building>(`/fac/buildings/${id}/classification`, { sitbeopClass, bmaTarget: false, evidence: { note } }),
  merge: (sourceId: string, targetId: string, reason: string) =>
    http.post<MergeReport>(`/fac/buildings/${sourceId}/merge`, { targetId, reason }),
};

/** D-day 배지 문구 · 색: 경과(빨강) · 30일 이내(빨강) · 90일 이내(주황) · 그 외(초록) */
export function dDay(daysLeft: number): { label: string; tone: 'danger' | 'warn' | 'ok' } {
  if (daysLeft < 0) return { label: `D+${-daysLeft} 경과`, tone: 'danger' };
  const label = daysLeft === 0 ? 'D-Day' : `D-${daysLeft}`;
  if (daysLeft <= 30) return { label, tone: 'danger' };
  if (daysLeft <= 90) return { label, tone: 'warn' };
  return { label, tone: 'ok' };
}

/** 위치 트리 평탄화(들여쓰기 깊이 포함) — 표 렌더용 */
export function flattenTree(nodes: TreeNode[], depth = 0, out: { node: TreeNode; depth: number }[] = []) {
  for (const n of nodes) {
    out.push({ node: n, depth });
    flattenTree(n.children ?? [], depth + 1, out);
  }
  return out;
}
