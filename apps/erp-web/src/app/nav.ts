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
    ],
  },
  {
    id: 'G1',
    label: 'G1 현장·비전 AI',
    items: [
      { label: '현장조사', module: 'FLD' },
      { label: 'AI Job · 모델', module: 'AIP' },
    ],
  },
  {
    id: 'G2',
    label: 'G2 진단 보고·표준',
    items: [
      { label: '시설물', module: 'FAC' },
      { label: '보고서 · QAR 검토', module: 'RPT·QAR', path: '/reports' },
    ],
  },
  {
    id: 'G3',
    label: 'G3 재무·회계·감사',
    items: [
      { label: '법인카드 검토 큐', module: 'FCD', path: '/fcd/cases' },
      { label: '원가 기간 · 요율', module: 'CST' },
    ],
  },
  {
    id: 'G4',
    label: 'G4 영업·견적·고객',
    items: [
      { label: '영업기회 · 견적', module: 'SAL', path: '/sal/estimates' },
      { label: '고객 포털', module: 'PTL' },
    ],
  },
  {
    id: 'G5',
    label: 'G5 인력·자원',
    items: [
      { label: '타임시트', module: 'HRQ', path: '/hrq/timesheets' },
      { label: '장비', module: 'EQP' },
    ],
  },
  {
    id: 'G6',
    label: 'G6 감리·해체',
    items: [{ label: '해체 · 감리', module: 'DEM' }],
  },
];
