import { expect, type APIResponse, type Page } from '@playwright/test';

/** 4차 E2E 공통 — dev 헤더 인증으로 API 를 직접 부른다(화면을 우회한 서버 거부 확인 · 준비 단계) */
export const uid = (n: number) => `00000000-0000-7000-8000-${n.toString(16).padStart(12, '0')}`;
export const U = {
  EXEC: { id: uid(1), roles: 'EXEC' },
  SALES: { id: uid(2), roles: 'SALES' },
  PM: { id: uid(4), roles: 'PM' },
  ENG: { id: uid(5), roles: 'ENG' },
  AUDITOR: { id: uid(7), roles: 'AUDITOR' },
  ADMIN: { id: uid(9), roles: 'ADMIN' },
  PM2: { id: uid(10), roles: 'PM' },
  SALES_EXEC: { id: uid(14), roles: 'SALES,EXEC' },
  PM_ENG_EXEC: { id: uid(15), roles: 'PM,ENG,EXEC' },
};
export type DevActor = { id: string; roles: string };

export async function api(page: Page, method: string, path: string, who: DevActor,
  opts: { body?: unknown; ifMatch?: string; raw?: string; contentType?: string } = {}): Promise<APIResponse> {
  const headers: Record<string, string> = { 'X-Actor-Id': who.id, 'X-Roles': who.roles, Accept: 'application/json' };
  if (method === 'POST') headers['Idempotency-Key'] = crypto.randomUUID();
  if (opts.ifMatch) headers['If-Match'] = opts.ifMatch;
  if (opts.raw !== undefined) headers['Content-Type'] = opts.contentType ?? 'text/plain';
  return page.request.fetch(`/api/v1${path}`, {
    method, headers, data: opts.raw !== undefined ? opts.raw : opts.body,
  });
}

export async function apiJson<T = any>(page: Page, method: string, path: string, who: DevActor, opts: Parameters<typeof api>[4] = {}): Promise<T> {
  const r = await api(page, method, path, who, opts);
  expect(r.status(), `${method} ${path} → ${await r.text()}`).toBeLessThan(300);
  return (await r.json()) as T;
}

export const etag = (version: number) => `"v${version}"`;

export function isoWeek(d = new Date()) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const y = t.getUTCFullYear();
  const w = Math.ceil(((t.getTime() - Date.UTC(y, 0, 1)) / 86_400_000 + 1) / 7);
  return `${y}-W${String(w).padStart(2, '0')}`;
}
export const todayLocal = (d = new Date()) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** 프로젝트 이름으로 id 찾기(EXEC 전체 조회) */
export async function projectIdByName(page: Page, name: RegExp) {
  const list = await apiJson<{ id: string; name: string }[]>(page, 'GET', '/core/projects', U.EXEC);
  const p = list.find((x) => name.test(x.name));
  expect(p, `프로젝트 ${name}`).toBeTruthy();
  return p!.id;
}
