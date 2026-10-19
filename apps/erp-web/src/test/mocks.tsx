/**
 * 화면 시험 공통 도구(2차 화면 · Vitest + MSW).
 * - sonner 토스트 캡처, AG Grid 대신 단순 표(jsdom 은 레이아웃이 없어 가상 스크롤 행이 그려지지 않는다)
 * - 요청 기록(seen) · problem+json 응답 · 사용자별 렌더(renderAs)
 *
 * 사용: vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule)
 *       vi.mock('@/components/DataGrid', async () => (await import('@/test/mockModules')).dataGridModule)
 *       (모의 모듈은 앱 코드를 import 하지 않는 별도 파일 — 순환 import 교착 방지)
 */
import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { HttpResponse, type RequestHandler } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { createQueryClient } from '@/app/queryClient';
import { AuthProvider } from '@/auth/auth';
import { etagStore } from '@/api/http';

export { toasts, sonnerModule, dataGridModule } from './mockModules';
import { toasts } from './mockModules';

export interface Seen { method: string; url: string; search: string; headers: Record<string, string>; body: unknown }
export const seen: Seen[] = [];
export async function record(r: Request) {
  const text = await r.clone().text();
  const u = new URL(r.url);
  let body: unknown = undefined;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    body = text;
  }
  seen.push({ method: r.method, url: u.pathname, search: u.search, headers: Object.fromEntries(r.headers.entries()), body });
}
export const problem = (status: number, code: string, title: string, detail: string, extra: Record<string, unknown> = {}) =>
  HttpResponse.json({ type: 'about:blank', title, status, detail, code, traceId: 't-1', ...extra }, {
    status, headers: { 'Content-Type': 'application/problem+json' },
  });

/** MSW 서버 + 상대 경로 fetch 보정 + 매 시험 초기화 */
export function useMswServer(...handlers: RequestHandler[]) {
  const server = setupServer(...handlers);
  let origFetch: typeof fetch;
  beforeAll(() => {
    server.listen();
    origFetch = globalThis.fetch;
    globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) =>
      origFetch(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init)) as typeof fetch;
  });
  afterEach(() => {
    server.resetHandlers();
    seen.length = 0;
    toasts.success.length = 0;
    toasts.error.length = 0;
    toasts.warning.length = 0;
    etagStore.clear();
    localStorage.clear();
  });
  afterAll(() => {
    server.close();
    globalThis.fetch = origFetch;
  });
  return server;
}

function NavigatedTo() {
  const loc = useLocation();
  return <div data-testid="navigated">{loc.pathname}</div>;
}

/** dev 세션(헤더 인증)으로 렌더. path 를 주면 그 라우트 패턴에 route 로 들어간다 */
export function renderAs(
  actorId: string, roles: string[], page: ReactNode,
  opts: { route?: string; path?: string; clientOrgId?: string } = {},
) {
  localStorage.setItem('atom-erp.dev-session', JSON.stringify({ actorId, name: '합성', roles, clientOrgId: opts.clientOrgId }));
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={[opts.route ?? '/']}>
          <Routes>
            <Route path={opts.path ?? '/'} element={page} />
            <Route path="*" element={<NavigatedTo />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}
