/**
 * 공통 HTTP 클라이언트 (설계 00 §9.1 · §9.2).
 * - POST: Idempotency-Key 자동 부여(호출자가 같은 키를 넘기면 재시도 시 같은 응답 재생 → Idempotent-Replayed: true)
 * - PUT/PATCH: 마지막으로 받은 ETag 를 If-Match 로 자동 부여(없으면 서버가 428 CORE-428-002)
 * - 오류: application/problem+json → ProblemError(code = CORE-xxx-nnn)
 */

export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
  errors?: Array<Record<string, unknown>>;
  [k: string]: unknown;
}

export class ProblemError extends Error {
  readonly problem: ProblemDetail;
  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = 'ProblemError';
    this.problem = problem;
  }
  get status() {
    return this.problem.status;
  }
  get code() {
    return this.problem.code;
  }
}

export type AuthHeaderProvider = () => Promise<Record<string, string>> | Record<string, string>;

let authHeaders: AuthHeaderProvider = () => ({});
export function setAuthHeaderProvider(p: AuthHeaderProvider) {
  authHeaders = p;
}

/** 경로별 최신 ETag(정규화된 경로 → "v3"). GET/POST/PUT/PATCH 응답의 ETag 를 기억한다. */
const etags = new Map<string, string>();
export const etagStore = {
  get: (path: string) => etags.get(normalize(path)),
  set: (path: string, etag: string) => etags.set(normalize(path), etag),
  clear: () => etags.clear(),
};

function normalize(path: string) {
  return path.split('?')[0].replace(/\/+$/, '');
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  // 구형 환경 대비(테스트 등)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export type Query = Record<string, string | number | boolean | undefined | null>;

export interface RequestOptions {
  query?: Query;
  body?: unknown;
  /** 원문 본문(멀티파트 · YAML 등) */
  rawBody?: BodyInit;
  contentType?: string;
  /** POST 멱등 키를 고정(같은 사용자 동작 재시도). 생략하면 새로 만든다. */
  idempotencyKey?: string;
  /** PUT/PATCH If-Match 를 명시. 생략하면 etagStore 값(같은 경로 또는 etagFrom 경로)을 쓴다. */
  ifMatch?: string;
  /** If-Match 를 다른 리소스 경로의 ETag 에서 가져올 때 */
  etagFrom?: string;
  signal?: AbortSignal;
  accept?: string;
  /** 응답 본문을 Blob 으로 받는다(파일 다운로드 — CSV BOM · HWPX · DXF 바이트 그대로) */
  asBlob?: boolean;
}

export interface ApiResponse<T> {
  data: T;
  status: number;
  etag?: string;
  replayed: boolean;
  headers: Headers;
}

export const API_BASE = '/api/v1';

export function buildUrl(path: string, query?: Query) {
  const url = path.startsWith('/api/') ? path : `${API_BASE}${path}`;
  if (!query) return url;
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== '') qs.append(k, String(v));
  }
  const s = qs.toString();
  return s ? `${url}?${s}` : url;
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = {
    Accept: opts.accept ?? 'application/json, application/problem+json',
    ...(await authHeaders()),
  };
  let body: BodyInit | undefined;
  if (opts.rawBody !== undefined) {
    body = opts.rawBody;
    if (opts.contentType) headers['Content-Type'] = opts.contentType;
  } else if (opts.body !== undefined) {
    body = JSON.stringify(opts.body);
    headers['Content-Type'] = 'application/json';
  }
  const m = method.toUpperCase();
  if (m === 'POST') {
    headers['Idempotency-Key'] = opts.idempotencyKey ?? newIdempotencyKey();
  }
  if (m === 'PUT' || m === 'PATCH') {
    const tag = opts.ifMatch ?? etagStore.get(opts.etagFrom ?? path);
    if (tag) headers['If-Match'] = tag;
  } else if (opts.ifMatch) {
    // 상태 전이 명령(POST …:move · :approve-discount 등) 중 낙관적 잠금이 필요한 것은 명시적으로 넘긴다
    headers['If-Match'] = opts.ifMatch;
  }

  const url = buildUrl(path, opts.query);
  let res: Response;
  try {
    res = await fetch(url, { method: m, headers, body, signal: opts.signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ProblemError({
      status: 0,
      title: '서버에 연결할 수 없습니다',
      detail: `백엔드(${url})에 연결하지 못했습니다. 서버가 실행 중인지 확인하세요.`,
      code: 'NETWORK',
    });
  }

  const etag = res.headers.get('ETag') ?? undefined;
  if (etag && m !== 'DELETE') {
    etagStore.set(path, etag);
    const loc = res.headers.get('Location');
    if (loc) etagStore.set(loc.replace(/^.*\/api\/v1/, ''), etag);
  }

  const ct = res.headers.get('Content-Type') ?? '';
  if (!res.ok) {
    let problem: ProblemDetail = { status: res.status, title: res.statusText };
    if (ct.includes('json')) {
      try {
        problem = { status: res.status, ...(await res.json()) };
      } catch {
        /* 본문 없음 */
      }
    }
    throw new ProblemError(problem);
  }

  let data: unknown = undefined;
  if (res.status !== 204) {
    if (opts.asBlob) data = await res.blob();
    else if (ct.includes('json')) data = await res.json();
    else if (ct.startsWith('text/')) data = await res.text();
    else data = await res.blob();
  }
  return {
    data: data as T,
    status: res.status,
    etag,
    replayed: res.headers.get('Idempotent-Replayed') === 'true',
    headers: res.headers,
  };
}

export const http = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) =>
    request<T>('GET', path, { query, signal }).then((r) => r.data),
  getWithMeta: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'body'> = {}) =>
    request<T>('POST', path, { ...opts, body: body ?? {} }).then((r) => r.data),
  put: <T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'body'> = {}) =>
    request<T>('PUT', path, { ...opts, body }).then((r) => r.data),
  patch: <T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'body'> = {}) =>
    request<T>('PATCH', path, { ...opts, body }).then((r) => r.data),
  delete: <T>(path: string, opts: Omit<RequestOptions, 'body'> = {}) =>
    request<T>('DELETE', path, opts).then((r) => r.data),
};

/** 목록 공통 응답 PageResponse (설계 9.1) */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
