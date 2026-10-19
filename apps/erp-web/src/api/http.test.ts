import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { http as mswHttp, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { etagStore, http, ProblemError, request, setAuthHeaderProvider } from './http';
import { hrqApi, isoWeekOf, mondayOf, shiftWeek, weekDates } from './hrq';
import { salApi } from './sal';
import { describeError } from '@/app/errors';

/** MSW 계약 목(mock) — 백엔드 DTO · 헤더 규약(Idempotency-Key · ETag/If-Match · problem+json) */
const seen: { method: string; url: string; headers: Record<string, string> }[] = [];
const record = (r: Request) => {
  seen.push({ method: r.method, url: new URL(r.url).pathname, headers: Object.fromEntries(r.headers.entries()) });
};

const server = setupServer(
  mswHttp.get('*/api/v1/core/clients/c1', ({ request: r }) => {
    record(r);
    return HttpResponse.json({ id: 'c1', name: '(합성) 고객', version: 3 }, { headers: { ETag: '"v3"' } });
  }),
  mswHttp.patch('*/api/v1/core/clients/c1', ({ request: r }) => {
    record(r);
    if (r.headers.get('If-Match') !== '"v3"') {
      return HttpResponse.json(
        { type: 'x', title: '버전 불일치', status: 412, detail: '다른 사용자가 먼저 수정했습니다', code: 'CORE-412-001', traceId: null },
        { status: 412, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return HttpResponse.json({ id: 'c1', version: 4 }, { headers: { ETag: '"v4"' } });
  }),
  mswHttp.post('*/api/v1/sal/estimates/:id', ({ request: r }) => {
    record(r);
    return HttpResponse.json({ id: 'e1', status: 'ISSUED', version: 2 });
  }),
  mswHttp.post('*/api/v1/sal/opportunities/:id', ({ request: r }) => {
    record(r);
    return HttpResponse.json({ id: 'o1', stage: 'QUALIFYING', version: 1 });
  }),
  mswHttp.post('*/api/v1/hrq/timesheets/:id', ({ request: r }) => {
    record(r);
    return HttpResponse.json({ id: 't2', revisionNo: 2 }, { status: 201 });
  }),
  mswHttp.post('*/api/v1/sal/fee-calculations', ({ request: r }) => {
    record(r);
    return HttpResponse.json(
      {
        type: 'https://erp.atom-eng.co.kr/problems/core-422-001', title: '입력값 검증 실패', status: 422,
        detail: '요청 본문 검증 실패', code: 'CORE-422-001', traceId: 'abc',
        errors: [{ field: 'asOfDate', reason: 'NotNull', message: '널이어서는 안됩니다' }],
      },
      { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
    );
  }),
);

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  server.resetHandlers();
  seen.length = 0;
  etagStore.clear();
});
afterAll(() => server.close());

// jsdom 에서 상대 URL fetch 를 위해 origin 고정
const origFetch = globalThis.fetch;
globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) =>
  origFetch(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init)) as typeof fetch;

setAuthHeaderProvider(() => ({ 'X-Actor-Id': '00000000-0000-7000-8000-000000000002', 'X-Roles': 'SALES' }));

describe('http client', () => {
  it('POST 에 Idempotency-Key 를 자동으로 붙이고 dev 헤더를 보낸다', async () => {
    await salApi.issue('e1');
    const h = seen[0].headers;
    expect(h['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(h['x-actor-id']).toBe('00000000-0000-7000-8000-000000000002');
    expect(h['x-roles']).toBe('SALES');
    expect(seen[0].url).toBe('/api/v1/sal/estimates/e1:issue');
  });

  it('같은 동작 재시도는 같은 Idempotency-Key 를 쓴다(정정)', async () => {
    await hrqApi.correct('t1', { hours: 6, reason: '합성' }, 'fixed-key');
    await hrqApi.correct('t1', { hours: 6, reason: '합성' }, 'fixed-key');
    expect(seen.map((s) => s.headers['idempotency-key'])).toEqual(['fixed-key', 'fixed-key']);
  });

  it('PATCH 는 마지막 GET 의 ETag 를 If-Match 로 자동 사용, 응답 ETag 로 갱신', async () => {
    await http.get('/core/clients/c1');
    await http.patch('/core/clients/c1', { name: 'x' });
    expect(seen[1].headers['if-match']).toBe('"v3"');
    expect(etagStore.get('/core/clients/c1')).toBe('"v4"');
  });

  it('상태 전이 POST(:move) 는 version 으로 If-Match 를 만든다', async () => {
    await salApi.moveOpportunity({ id: 'o1', version: 7 }, { toStage: 'QUALIFYING' });
    expect(seen[0].headers['if-match']).toBe('"v7"');
  });

  it('412 problem+json → ProblemError(code CORE-412-001)', async () => {
    etagStore.set('/core/clients/c1', '"v1"');
    const err = await request('PATCH', '/core/clients/c1', { body: {} }).catch((e) => e);
    expect(err).toBeInstanceOf(ProblemError);
    expect(err.code).toBe('CORE-412-001');
    expect(err.status).toBe(412);
  });

  it('검증 오류는 코드 · 필드 오류 · traceId 를 토스트 문구로 만든다', async () => {
    const err = await salApi.calculateFee({} as never).catch((e) => e);
    const d = describeError(err);
    expect(d.title).toBe('[CORE-422-001] 입력값 검증 실패');
    expect(d.description).toContain('asOfDate: 널이어서는 안됩니다');
    expect(d.description).toContain('traceId abc');
  });
});

describe('ISO week', () => {
  it('2026-10-19(월) 은 2026-W43', () => {
    expect(isoWeekOf(new Date(2026, 9, 19))).toBe('2026-W43');
    expect(mondayOf('2026-W43').toISOString().slice(0, 10)).toBe('2026-10-19');
    expect(weekDates('2026-W43')[6]).toBe('2026-10-25');
  });
  it('연도 경계', () => {
    expect(isoWeekOf(new Date(2027, 0, 1))).toBe('2026-W53');
    expect(shiftWeek('2026-W53', 1)).toBe('2027-W01');
  });
});
