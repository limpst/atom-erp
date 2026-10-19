import { http } from './http';

/** RPT 보고서 엔진 + QAR 품질 검토 */
export type ReportType =
  | 'RT_REG' | 'RT_PRC' | 'RT_PSD' | 'RT_EMG' | 'RT_BLD' | 'RT_SEI' | 'RT_ADJ_PRE' | 'RT_ADJ_POST' | 'RT_DEM';
export const REPORT_TYPE_LABEL: Record<ReportType, string> = {
  RT_REG: '정기안전점검', RT_PRC: '정밀안전점검', RT_PSD: '정밀안전진단', RT_EMG: '긴급안전점검', RT_BLD: '건축물 점검',
  RT_SEI: '내진성능평가', RT_ADJ_PRE: '인접 사전조사', RT_ADJ_POST: '인접 사후조사', RT_DEM: '해체 감리',
};
export type RevisionStatus =
  | 'DRAFT' | 'SNAPSHOT_FROZEN' | 'COMPOSING' | 'IN_REVIEW' | 'APPROVED' | 'SIGNING' | 'PUBLISHED' | 'DELIVERED'
  | 'SUPERSEDED';
export const REV_STEPS: { key: RevisionStatus; label: string }[] = [
  { key: 'DRAFT', label: '초안' },
  { key: 'SNAPSHOT_FROZEN', label: '스냅샷 동결' },
  { key: 'COMPOSING', label: '작성' },
  { key: 'IN_REVIEW', label: 'QAR 검토' },
  { key: 'APPROVED', label: '승인' },
  { key: 'PUBLISHED', label: '발행' },
  { key: 'DELIVERED', label: '납품' },
];
export type SafetyGrade = 'A' | 'B' | 'C' | 'D' | 'E';

export interface RevisionRef {
  id: string; revNo: number; status: RevisionStatus; version: number; publishedAt: string | null;
  pdfSha256: string | null; supersededBy: string | null; deliveredAt: string | null;
}
export interface Report {
  id: string; projectId: string; facilityId: string; reportType: ReportType; title: string; locale: string;
  facilityClass: string; inspectionDate: string; status: RevisionStatus; currentRev: number;
  templateVersion: { id: string; semver: string; effectiveFrom: string }; version: number;
  revisions: RevisionRef[]; currentRevisionId: string;
}
export interface CreateReportRequest {
  projectId: string; facilityId: string; reportType: ReportType; title: string; inspectionDate: string;
  locale?: 'ko'; facilityClass?: string; dueDate?: string;
}
export interface Revision {
  id: string; reportId: string; revNo: number; status: RevisionStatus; snapshotId: string | null;
  changeNote: string | null; contentHash: string | null; pdfSha256: string | null; hwpxSha256: string | null;
  pageCount: number | null; publishedAt: string | null; deliveredAt: string | null; supersededBy: string | null;
  version: number;
  grade: { computedGrade: SafetyGrade; finalGrade: SafetyGrade | null; weightedScore: number } | null;
}
export interface Issue { ruleCode: string; severity: 'BLOCKING' | 'WARNING'; message: string }
export interface FreezeRequest {
  facilityName?: string;
  buildings: { code: string; name?: string; buildingId?: string; grossAreaM2: number }[];
  memberGroups: { buildingCode: string; floorCode: string; memberType: string; memberCount: number }[];
  defects?: {
    id: string; buildingCode: string; floorCode: string; memberType?: string; memberId?: string; defectType: string;
    widthMm?: number; lengthM?: number; areaM2?: number; polyline?: number[][]; note?: string;
  }[];
}
export interface FreezeResult {
  reportId: string; revisionId: string; snapshotId: string; sha256: string; defectCount: number;
  blockingCount: number; status: RevisionStatus; issues: Issue[];
}
export interface Grade {
  revisionId: string; weightedScore: number; computedGrade: SafetyGrade; finalGrade: SafetyGrade | null;
  overridden: boolean; overrideReason: string | null; resultSha256: string; decidedBy: string | null;
  members: { buildingCode: string; floorCode: string; memberType: string; count: number; rating: string; score: number }[];
}
export interface Evidence {
  indicator: string; value: number; unit: string; rating: string; threshold: number | null; defectIds: string[];
}
export interface GradeDetail extends Grade {
  ruleSet: Record<string, string>;
  members: (Grade['members'][number] & { weight: number; evidence: Evidence[] })[];
  scopeTree: {
    floorScores: { buildingCode: string; floorCode: string; score: number }[];
    buildingScores: { buildingCode: string; score: number }[];
    appliedFloorRules: unknown[];
  } | null;
}
export interface Block {
  blockId: string; kind: 'PARAGRAPH' | 'TABLE' | 'IMAGE' | 'HEADING' | string; text: string | null;
  data: { rows?: string[][]; columns?: string[]; objectKey?: string; bound?: string[] } | null; locked: boolean;
  textHash: string; source: 'AUTO' | 'HUMAN' | string;
}
export interface Section { sectionKey: string; title: string; orderNo: number; source: string; version: number; blocks: Block[] }
export interface AnchorRect { page: number; x: number; y: number; w: number; h: number }
export interface Anchor { blockId: string; sectionKey: string; kind: string; page: number; rects: AnchorRect[]; textHash: string }
export interface AnchorsDoc { revisionId: string; source: 'ESTIMATED' | string; unit: string; pageCount: number; sha256: string; anchors: Anchor[] }
export const INDICATOR_LABEL: Record<string, string> = {
  crack_width_mm: '균열 최대폭', spall_m2_per_member: '박리 · 박락 면적(부재당)', rebar_exposure_ratio: '철근 노출 비율',
};

/** 등급 트리: 동 → 층 → 부재(근거) — scopeTree 점수 + member_rating 근거를 합친다 */
export interface GradeTreeNode {
  key: string; label: string; score: number | null; rating?: string; children: GradeTreeNode[]; evidence?: Evidence[];
  weight?: number; count?: number;
}
export function gradeTree(g: GradeDetail): GradeTreeNode[] {
  const floorScore = new Map((g.scopeTree?.floorScores ?? []).map((f) => [`${f.buildingCode}/${f.floorCode}`, f.score]));
  const bScore = new Map((g.scopeTree?.buildingScores ?? []).map((b) => [b.buildingCode, b.score]));
  const buildings = new Map<string, GradeTreeNode>();
  for (const m of g.members) {
    let b = buildings.get(m.buildingCode);
    if (!b) {
      b = { key: m.buildingCode, label: `동 ${m.buildingCode}`, score: bScore.get(m.buildingCode) ?? null, children: [] };
      buildings.set(m.buildingCode, b);
    }
    const fk = `${m.buildingCode}/${m.floorCode}`;
    let f = b.children.find((c) => c.key === fk);
    if (!f) {
      f = { key: fk, label: `층 ${m.floorCode}`, score: floorScore.get(fk) ?? null, children: [] };
      b.children.push(f);
    }
    f.children.push({
      key: `${fk}/${m.memberType}`, label: m.memberType, score: Number(m.score), rating: m.rating, children: [],
      evidence: m.evidence, weight: Number(m.weight), count: m.count,
    });
  }
  return [...buildings.values()];
}

export interface RenderResult { jobId: string; status: string; converter: string; pdfSha256: string; pageCount: number }

export type ReviewStatus =
  | 'SELF_CHECK' | 'SUBMITTED' | 'AI_REVIEWING' | 'QA_REVIEWING' | 'PENDING_APPROVAL' | 'REJECTED' | 'APPROVED';
export const REVIEW_STEPS: { key: ReviewStatus; label: string }[] = [
  { key: 'SELF_CHECK', label: '자체검토' },
  { key: 'SUBMITTED', label: '제출' },
  { key: 'AI_REVIEWING', label: 'AI 검토' },
  { key: 'QA_REVIEWING', label: 'QA 검토' },
  { key: 'PENDING_APPROVAL', label: '책임기술자 승인' },
  { key: 'APPROVED', label: '승인' },
];
export interface Assignment {
  id: string; step: string; assigneeId: string; assignedBy: string; dueAt: string | null;
  completedAt: string | null; decision: string | null;
}
export interface Round {
  id: string; roundNo: number; revisionId: string; revNo: number; aiStatus: string; aiSkipReason: string | null;
  aiBadge: string | null; outcome: string | null; rejectSummary: string | null; assignments: Assignment[];
}
export interface Review {
  id: string; reportId: string; projectId: string; reportType: string; workflow: string; status: ReviewStatus;
  currentRound: number; approvedAt: string | null; approvedBy: string | null; version: number; rounds: Round[];
  round: Round;
}
export interface AutoCheckRun {
  runId: string; roundId: string; ruleVersion: string; blocking: number; warning: number; info: number;
  results: { id: string; ruleId: string; severity: 'BLOCKING' | 'WARNING' | 'INFO'; message: string }[];
}
export interface ChecklistItem {
  itemId: string; itemNo: string; category: string; question: string; criticality: string;
  stage: 'SELF' | 'QA' | 'BOTH';
  self?: { answer: 'PASS' | 'FAIL' | 'NA'; autoPrefilled: boolean };
  qa?: { answer: 'PASS' | 'FAIL' | 'NA'; autoPrefilled: boolean };
}
export interface Checklist { roundId: string; template: string; items: ChecklistItem[]; missingSelf: string[]; missingQa: string[] }

const v = (n: number) => `"v${n}"`;

export const rptApi = {
  reports: (projectId: string) => http.get<Report[]>('/rpt/reports', { projectId }),
  report: (id: string) => http.get<Report>(`/rpt/reports/${id}`),
  create: (body: CreateReportRequest) => http.post<Report>('/rpt/reports', body),
  freeze: (id: string, body: FreezeRequest) => http.post<FreezeResult>(`/rpt/reports/${id}/snapshots`, body),
  revision: (revId: string) => http.get<Revision>(`/rpt/revisions/${revId}`),
  compose: (revId: string) => http.post<Grade & { status: RevisionStatus }>(`/rpt/revisions/${revId}/compose`),
  grade: (revId: string) => http.get<Grade>(`/rpt/revisions/${revId}/grade`),
  gradeDetail: (revId: string) => http.get<GradeDetail>(`/rpt/revisions/${revId}/grade`),
  sections: (revId: string) => http.get<Section[]>(`/rpt/revisions/${revId}/sections`),
  anchors: (revId: string) => http.get<AnchorsDoc>(`/rpt/revisions/${revId}/anchors`),
  hwpxPath: (revId: string) => `/rpt/revisions/${revId}/files/report.hwpx`,
  dxfPath: (revId: string, building: string, floor: string) =>
    `/rpt/revisions/${revId}/drawings/${encodeURIComponent(building)}/${encodeURIComponent(floor)}.dxf`,
  /** PUT — If-Match 는 GET /revisions/{revId} 의 ETag 를 http 클라이언트가 자동으로 붙인다 */
  decideGrade: (revId: string, body: { finalGrade: SafetyGrade; reason?: string; evidenceDefectIds?: string[] }) =>
    http.put<Grade>(`/rpt/revisions/${revId}/grade/decision`, body, { etagFrom: `/rpt/revisions/${revId}` }),
  render: (revId: string) => http.post<RenderResult>(`/rpt/revisions/${revId}/render`),
  reviewRequest: (revId: string) => http.post<Revision>(`/rpt/revisions/${revId}/review-request`),
  publish: (revId: string) => http.post<Revision>(`/rpt/revisions/${revId}/publish`),
  deliver: (revId: string, recipientName: string) =>
    http.post<Revision>(`/rpt/revisions/${revId}/delivery-confirmation`, { recipientName }),
};

export const qarApi = {
  create: (revisionId: string) => http.post<Review>('/qar/reviews', { revisionId }),
  review: (id: string) => http.get<Review>(`/qar/reviews/${id}`),
  autocheck: (id: string) => http.post<AutoCheckRun>(`/qar/reviews/${id}/autocheck-runs`),
  checklist: (roundId: string) => http.get<Checklist>(`/qar/rounds/${roundId}/checklist`),
  answer: (roundId: string, itemId: string, stage: 'SELF' | 'QA', answer: 'PASS' | 'FAIL' | 'NA') =>
    http.put<Checklist>(`/qar/rounds/${roundId}/checklist/${itemId}`, { stage, answer }),
  submit: (r: Pick<Review, 'id' | 'version'>) => http.post<Review>(`/qar/reviews/${r.id}/submit`, {}, { ifMatch: v(r.version) }),
  runAiJobs: () => http.post<{ processed: number; done: number; skipped: number; timedOut: number }>('/qar/ai-jobs:run'),
  assign: (roundId: string, assigneeId: string) =>
    http.post<Assignment>(`/qar/rounds/${roundId}/assignments`, { stepCode: 'QA_REVIEW', assigneeId }),
  decide: (r: Pick<Review, 'version'>, roundId: string, decision: 'PASS' | 'REJECT', rejectSummary?: string) =>
    http.post<Review>(`/qar/rounds/${roundId}/decision`, { decision, rejectSummary }, { ifMatch: v(r.version) }),
  approve: (r: Pick<Review, 'id' | 'version'>) =>
    http.post<Review>(`/qar/reviews/${r.id}/approve`, {}, { ifMatch: v(r.version) }),
};

export const qarLookup = {
  byReport: (reportId: string) => http.get<Review[]>('/qar/reviews', { reportId }),
};

/** 합성 스냅샷(가짜 결함) — RptFlow 시험과 같은 모양. 균열 0.25mm → 대략 C 등급 */
export function syntheticFreeze(): FreezeRequest {
  return {
    facilityName: '(합성) 한빛타워',
    buildings: [{ code: 'B01', name: '본관', grossAreaM2: 5200.5 }],
    memberGroups: [
      { buildingCode: 'B01', floorCode: '1F', memberType: 'COLUMN', memberCount: 10 },
      { buildingCode: 'B01', floorCode: '2F', memberType: 'COLUMN', memberCount: 10 },
    ],
    defects: [
      { id: 'D-0001', buildingCode: 'B01', floorCode: '1F', memberType: 'COLUMN', memberId: '1F-COL-01', defectType: 'CRACK', widthMm: 0.25, lengthM: 1.2, polyline: [[1000, 2000], [1400, 2600]] },
      { id: 'D-0002', buildingCode: 'B01', floorCode: '2F', memberType: 'COLUMN', memberId: '2F-COL-03', defectType: 'CRACK', widthMm: 0.15, lengthM: 0.8, polyline: [[500, 900], [800, 1300]] },
    ],
  };
}
