import type { Role } from './auth';

/**
 * 개발 모드(local-noauth) 합성 사용자. ID 는 dev 서버 시드(DevServer · DevSeed)가 core.employee 에 같은 값으로 넣는다.
 * 실존 인물 아님(합성 데이터).
 */
export interface DevUser {
  id: string;
  name: string;
  roles: Role[];
  note?: string;
  /** CUSTOMER(고객 포털) 사용자의 고객사 이름 — 선택 시 core 고객 목록에서 ID 를 찾아 X-Client-Org-Id 로 보낸다 */
  clientName?: string;
}

const id = (n: number) => `00000000-0000-7000-8000-${n.toString(16).padStart(12, '0')}`;

export const DEV_USERS: DevUser[] = [
  { id: id(1), name: '김임원', roles: ['EXEC'], note: '할인 승인 · 계약 결재 · 예산 승인' },
  { id: id(2), name: '박영업', roles: ['SALES'], note: '견적 작성 · 발행(할인 5%)' },
  { id: id(3), name: '이영업팀장', roles: ['SALES'], note: '영업팀장(할인 10%) — 공통 › 관리 › 영업팀장 그룹' },
  { id: id(4), name: '최책임', roles: ['PM'], note: '프로젝트 책임 · 타임시트 승인' },
  { id: id(5), name: '정참여', roles: ['ENG', 'EMPLOYEE'], note: '타임시트 입력 · 카드 소명' },
  { id: id(6), name: '한품질', roles: ['QA'], note: 'QAR 검토' },
  { id: id(7), name: '오감사', roles: ['AUDITOR'], note: '법인카드 판정' },
  { id: id(8), name: '윤재무', roles: ['FIN'], note: '카드 명세서 · 검토 큐 · 정산' },
  { id: id(9), name: '서경영', roles: ['ADMIN'], note: '경영지원(전체 조회)' },
  { id: id(10), name: '문책임2', roles: ['PM'], note: '최종 승인자(직무 분리용 두 번째 PM)' },
  // 고객 포털(/portal) — 고객사 2곳(테넌트 A · B). 직원이 아니다(합성 담당자)
  { id: id(11), name: '(고객A) 한빛 담당', roles: ['CUSTOMER'], clientName: '(합성) 한빛자산관리', note: '고객 포털 — 한빛자산관리' },
  { id: id(12), name: '(고객B) 푸른 담당', roles: ['CUSTOMER'], clientName: '(합성) 푸른시설공단', note: '고객 포털 — 푸른시설공단' },
];

/**
 * dev 전용: 고객 사용자의 고객사 ID 를 이름으로 찾는다. 고객사 ID 는 시드가 API 로 만들 때 정해지므로
 * 경영지원(ADMIN) 헤더로 core 고객 목록을 한 번 읽는다(local-noauth 개발 서버에서만 쓰는 경로).
 */
export async function resolveClientOrgId(clientName: string): Promise<string | undefined> {
  const res = await fetch('/api/v1/core/clients', {
    headers: { Accept: 'application/json', 'X-Actor-Id': id(9), 'X-Roles': 'ADMIN' },
  });
  if (!res.ok) return undefined;
  const body = (await res.json()) as { id: string; name: string }[] | { content: { id: string; name: string }[] };
  const list = Array.isArray(body) ? body : body.content;
  return list.find((c) => c.name === clientName)?.id;
}

export const devUserId = id;
