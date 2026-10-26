import { http, ProblemError } from './http';
import type { Role } from '@/auth/auth';
import { demApi, demLogApi } from './dem';
import { rateApi, type Rate } from './admin';
import { fcdApi, type CaseListItem, type RuleSetRow } from './fcd';
import { aipApi, promotionNeeds, type ModelRow } from './aip';

/**
 * "내 할 일" 수신함 — 모듈별 목록을 브라우저에서 모은다(읽기 전용).
 * - 목록 API 가 이미 있으면 그대로 쓰고 화면에서 거른다(본인이 만든 것 제외 등).
 * - 승인 대기를 직접 찾을 목록이 없거나 "본인 제외" 를 서버만 알 수 있는 곳은 모듈마다 읽기 전용 수신함 API 를 쓴다
 *   (GET /sal/inbox/discount-approvals · /cst/inbox/budget-approvals · /hrq/inbox/timesheet-approvals · /qar/inbox/assignments
 *   — 서버가 "실제로 처리할 수 있는 항목만" 준다).
 * - 권한이 없는 출처(403)는 조용히 빈 목록으로 본다(서버가 막는 것이 기준).
 */
export interface InboxItem {
  key: string;
  module: 'SAL' | 'CST' | 'HRQ' | 'QAR' | 'FCD' | 'AIP' | 'DEM';
  kind: string;
  title: string;
  detail?: string;
  link: string;
  at?: string | null;
}
export interface InboxSource {
  id: string;
  label: string;
  module: InboxItem['module'];
  /** 이 역할 중 하나라도 있어야 부른다(없으면 서버가 403 — 부르지 않는다) */
  roles: Role[];
  fetch: (me: string, roles: Role[]) => Promise<InboxItem[]>;
}

interface DiscountRow {
  estimateId: string; estimateNo: string; versionNo: number; opportunityId: string; oppNo: string; opportunityTitle: string;
  discountRate: number; discountLimit: number | null; offerAmount: number; createdBy: string; requestedAt: string | null;
}
interface BudgetRow {
  revisionId: string; projectId: string; revNo: number; status: string; bacKrw: number; contractAmountKrw: number;
  expectedMarginPct: number; createdBy: string; requestedAt: string | null; version: number;
}
interface TimesheetRow {
  employeeId: string; empNo: string; employeeName: string; projectId: string; isoWeek: string; entries: number;
  hours: number; firstDate: string; submittedAt: string | null;
}
interface AssignmentRow {
  assignmentId: string; stepCode: string; dueAt: string | null; assignedAt: string; reviewId: string; reportId: string;
  projectId: string; reportType: string; status: string; roundNo: number; revNo: number;
}

const pct = (r: number) => `${Math.round(r * 1000) / 10}%`;
const STEP_LABEL: Record<string, string> = { QA_REVIEW: 'QA 검토', PEER_REVIEW: '동료 검토', FINAL_APPROVAL: '최종 승인' };

export const INBOX_SOURCES: InboxSource[] = [
  {
    id: 'sal-discount', label: '견적 할인 승인', module: 'SAL', roles: ['EXEC'],
    fetch: async () => (await http.get<DiscountRow[]>('/sal/inbox/discount-approvals')).map((r) => ({
      key: `sal:${r.estimateId}`, module: 'SAL', kind: '할인 승인',
      title: `${r.estimateNo} v${r.versionNo} — 할인 ${pct(r.discountRate)} 승인`,
      detail: `${r.opportunityTitle} · 한도 ${r.discountLimit == null ? '—' : pct(r.discountLimit)}`,
      link: `/sal/estimates?opp=${r.opportunityId}`, at: r.requestedAt,
    })),
  },
  {
    id: 'cst-budget', label: '실행예산 결재', module: 'CST', roles: ['EXEC', 'ADMIN', 'FIN'],
    fetch: async () => (await http.get<BudgetRow[]>('/cst/inbox/budget-approvals')).map((r) => ({
      key: `cst:${r.revisionId}`, module: 'CST', kind: '예산 결재',
      title: `실행예산 Rev.${r.revNo} 결재 (${r.status})`,
      detail: `BAC ${r.bacKrw.toLocaleString('ko-KR')}원 · 예상 이익률 ${r.expectedMarginPct}%`,
      link: `/projects/${r.projectId}`, at: r.requestedAt,
    })),
  },
  {
    id: 'cst-rate', label: '표준 노임 단가 승인', module: 'CST', roles: ['EXEC'],
    fetch: async (me) => (await rateApi.list()).filter((r: Rate) => r.status === 'DRAFT' && r.createdBy !== me).map((r) => ({
      key: `rate:${r.id}`, module: 'CST', kind: '요율 승인',
      title: `${r.rateKind} ${r.keyCode} ${r.value.toLocaleString('ko-KR')} 승인`,
      detail: `${r.validFrom} ~ ${r.validTo ?? ''}`, link: '/admin/labor-rates', at: r.createdAt,
    })),
  },
  {
    id: 'hrq-timesheet', label: '타임시트 승인', module: 'HRQ', roles: ['PM'],
    fetch: async () => (await http.get<TimesheetRow[]>('/hrq/inbox/timesheet-approvals')).map((r) => ({
      key: `hrq:${r.employeeId}:${r.projectId}:${r.isoWeek}`, module: 'HRQ', kind: '타임시트 승인',
      title: `${r.empNo} ${r.employeeName} — ${r.isoWeek} ${r.entries}건 · ${Number(r.hours)}시간`,
      detail: `프로젝트 ${r.projectId.slice(0, 8)} (내가 책임자)`,
      link: `/hrq/timesheets?emp=${r.employeeId}&week=${r.isoWeek}`, at: r.submittedAt,
    })),
  },
  {
    id: 'qar-assignment', label: 'QAR 검토 배정', module: 'QAR', roles: ['QA', 'PM', 'EXEC', 'ENG'],
    fetch: async () => (await http.get<AssignmentRow[]>('/qar/inbox/assignments')).map((r) => ({
      key: `qar:${r.assignmentId}`, module: 'QAR', kind: STEP_LABEL[r.stepCode] ?? r.stepCode,
      title: `${r.reportType} 보고서 ${STEP_LABEL[r.stepCode] ?? r.stepCode} (라운드 ${r.roundNo} · Rev.${r.revNo})`,
      detail: r.dueAt ? `기한 ${r.dueAt.slice(0, 16).replace('T', ' ')}` : undefined, link: `/reports/${r.reportId}`, at: r.assignedAt,
    })),
  },
  {
    id: 'fcd-ruleset', label: '탐지 룰셋 승인(4-eyes)', module: 'FCD', roles: ['AUDITOR', 'EXEC'],
    fetch: async (me) => (await fcdApi.ruleSets())
      .filter((r: RuleSetRow) => (r.status === 'DRAFT' || r.status === 'PENDING_APPROVAL') && r.authored_by !== me)
      .map((r) => ({
        key: `fcd-rs:${r.version}`, module: 'FCD', kind: '룰셋 승인', title: `룰셋 ${r.version} 승인`,
        detail: `${r.effective_from} 시행`, link: '/fcd/rulesets',
      })),
  },
  {
    id: 'fcd-case', label: '법인카드 건', module: 'FCD', roles: ['AUDITOR', 'EMPLOYEE', 'ENG'],
    fetch: async (me, roles) => (await fcdApi.cases()).filter((c: CaseListItem) =>
      (roles.includes('AUDITOR') && (c.status === 'SUBMITTED' || c.status === 'ESCALATED'))
      || (c.subject_employee_id === me && (c.status === 'EXPLANATION_REQUESTED' || c.status === 'SUPPLEMENT_REQUESTED')))
      .map((c) => ({
        key: `fcd-case:${c.id}`, module: 'FCD',
        kind: c.subject_employee_id === me ? '소명 제출' : '판정',
        title: `${c.case_no} ${c.subject_employee_id === me ? '소명 요청' : '판정 대기'}`,
        detail: `기한 ${c.due_on}`, link: '/fcd/cases',
      })),
  },
  {
    id: 'aip-promotion', label: 'AI 모델 승격 승인', module: 'AIP', roles: ['QA', 'EXEC'],
    fetch: async (_me, roles) => (await aipApi.models()).flatMap((m: ModelRow) => promotionNeeds(m)
      .filter((n) => n.approver && !n.approved && roles.includes(n.approver))
      .map((n) => ({
        key: `aip:${m.id}:${n.to}`, module: 'AIP' as const, kind: '승격 승인',
        title: `${m.name} ${m.version} → ${n.to} 승인(${n.approver})`, link: '/aip?tab=models',
      }))),
  },
  {
    id: 'dem', label: '감리 정정 · 리비전', module: 'DEM', roles: ['PM'],
    fetch: async (me) => {
      const out: InboxItem[] = [];
      for (const p of await demApi.projects()) {
        for (const wp of p.workPackages) {
          const [logs, docs] = await Promise.all([
            wp.packageType.endsWith('SUPERVISION') ? demApi.dailyLogs(wp.id).catch(() => []) : Promise.resolve([]),
            demApi.documents(wp.id).catch(() => []),
          ]);
          for (const l of logs.filter((x) => x.pendingAmendments > 0)) {
            const d = await demLogApi.log(l.id).catch(() => null);
            // 요청자 본인은 승인할 수 없다(DEM-03b) — 다른 사람이 낸 대기 정정만
            const pending = (d?.amendments ?? []).filter((a) => a.status === 'PENDING' && a.amendedBy !== me);
            if (pending.length) {
              out.push({
                key: `dem-log:${l.id}`, module: 'DEM', kind: '정정 요청', title: `감리일지 ${l.logDate} 정정 요청 ${pending.length}건`,
                detail: pending[0].reason, link: `/dem/logs?wp=${wp.id}&log=${l.id}`, at: pending[0].amendedAt,
              });
            }
          }
          for (const doc of docs) {
            for (const r of doc.revisions.filter((x) => x.status === 'INTERNAL_REVIEW')) {
              out.push({
                key: `dem-rev:${r.id}`, module: 'DEM', kind: '리비전 승인', title: `${doc.docNo} Rev.${r.revCode} 내부 검토 승인`,
                detail: doc.title, link: '/dem',
              });
            }
          }
        }
      }
      return out;
    },
  },
];

/** 403 · 404(권한 없음 · 아직 없는 API)는 빈 목록, 그 밖의 오류는 그대로 던진다 */
export async function safeFetch(src: InboxSource, me: string, roles: Role[]): Promise<InboxItem[]> {
  try {
    return await src.fetch(me, roles);
  } catch (e) {
    if (e instanceof ProblemError && (e.status === 403 || e.status === 404)) return [];
    throw e;
  }
}

export function sourcesFor(roles: Role[]) {
  return INBOX_SOURCES.filter((s) => s.roles.some((r) => roles.includes(r)));
}
