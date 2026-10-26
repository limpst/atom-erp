import type { Role } from '@/auth/auth';

/** 사이드바 메뉴 — ATOM-LIB-001 §3 도메인 그룹 G1~G6 + 공통 */
export interface NavItem {
  label: string;
  module: string;
  path?: string; // 없으면 준비 중(비활성)
  roles?: Role[]; // 없으면 모두에게 표시
}
export interface NavGroup {
  id: string;
  label: string;
  items: NavItem[];
}

export const NAV: NavGroup[] = [
  {
    id: 'COMMON',
    label: '공통',
    items: [
      { label: '대시보드', module: 'HOME', path: '/' },
      { label: '프로젝트', module: 'CORE·CST', path: '/projects' },
      { label: '관리 › 영업팀장 그룹', module: 'SAL', path: '/admin/sales-group', roles: ['ADMIN'] },
      { label: '관리 › QA 검토자', module: 'QAR', path: '/admin/qa-reviewers', roles: ['ADMIN'] },
      { label: '관리 › 표준 노임 단가', module: 'CST', path: '/admin/labor-rates', roles: ['ADMIN', 'EXEC', 'FIN', 'SYSADMIN'] },
    ],
  },
  {
    id: 'G1',
    label: 'G1 현장·비전 AI',
    items: [
      { label: '현장조사 (관리)', module: 'FLD', path: '/fld', roles: ['PM', 'ENG', 'QA', 'EXEC', 'AUDITOR', 'ADMIN'] },
      { label: 'AI Job · 모델 · 제안', module: 'AIP', path: '/aip', roles: ['EXEC', 'PM', 'ENG', 'QA', 'SYSADMIN', 'AUDITOR'] },
    ],
  },
  {
    id: 'G2',
    label: 'G2 진단 보고·표준',
    items: [
      { label: '시설물', module: 'FAC', path: '/fac/buildings' },
      { label: '점검 도래', module: 'FAC', path: '/fac/dues', roles: ['EXEC', 'SALES', 'PM'] },
      { label: '보고서 · QAR 검토', module: 'RPT·QAR', path: '/reports' },
    ],
  },
  {
    id: 'G3',
    label: 'G3 재무·회계·감사',
    items: [
      { label: '법인카드 검토 큐', module: 'FCD', path: '/fcd/cases' },
      { label: '법인카드 가져오기 · 중복', module: 'FCD', path: '/fcd/imports', roles: ['FIN', 'ADMIN', 'AUDITOR'] },
      { label: '탐지 룰셋 (4-eyes)', module: 'FCD', path: '/fcd/rulesets', roles: ['FIN', 'AUDITOR', 'EXEC'] },
      { label: '법인카드 목록', module: 'FCD', path: '/fcd/cards', roles: ['FIN', 'ADMIN', 'EXEC', 'AUDITOR'] },
      { label: '원가 회계기간 · 대사', module: 'CST', path: '/cst/periods', roles: ['EXEC', 'ADMIN', 'FIN', 'AUDITOR', 'SYSADMIN'] },
      { label: '원가 원장 · EVM', module: 'CST', path: '/cst/ledger', roles: ['EXEC', 'PM', 'ADMIN', 'FIN', 'AUDITOR'] },
    ],
  },
  {
    id: 'G4',
    label: 'G4 영업·견적·고객',
    items: [
      { label: '영업기회 · 견적', module: 'SAL', path: '/sal/estimates' },
      { label: '고객 포털(고객 전용 /portal)', module: 'PTL' },
    ],
  },
  {
    id: 'G5',
    label: 'G5 인력·자원',
    items: [
      { label: '타임시트', module: 'HRQ', path: '/hrq/timesheets' },
      { label: '인력 · 자격', module: 'HRQ', path: '/hrq/people', roles: ['EXEC', 'PM', 'QA', 'ADMIN', 'SYSADMIN', 'AUDITOR', 'ENG'] },
      { label: '등록요건 · 예측', module: 'HRQ', path: '/hrq/compliance', roles: ['EXEC', 'SALES', 'PM', 'QA', 'ADMIN', 'SYSADMIN'] },
      { label: 'HRQ 월 마감', module: 'HRQ', path: '/hrq/month-close', roles: ['EXEC', 'PM', 'ADMIN', 'SYSADMIN'] },
      { label: '장비 · 교정', module: 'EQP', path: '/eqp/equipment' },
      { label: '장비 예약 · 반출', module: 'EQP', path: '/eqp/reservations', roles: ['PM', 'ENG', 'ADMIN', 'EXEC'] },
    ],
  },
  {
    id: 'G6',
    label: 'G6 감리·해체',
    items: [{ label: '해체 · 감리', module: 'DEM', path: '/dem', roles: ['EXEC', 'SALES', 'PM', 'ENG', 'QA', 'ADMIN'] }],
  },
];
