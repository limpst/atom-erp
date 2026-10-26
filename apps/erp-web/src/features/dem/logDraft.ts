import { newIdempotencyKey } from '@/api/http';
import type { DailyLogInput } from '@/api/dem';

/**
 * 감리일지 오프라인 초안(이 기기 localStorage) — 키 = 업무 패키지 + 일자.
 * clientOpId 는 초안마다 1개로 고정해 재전송(네트워크 끊김 · 새로 고침 · 다시 열기)에도 같은 값을 쓴다
 * → 서버가 같은 응답을 재생(Idempotent-Replayed)하고 두 번 저장하지 않는다.
 * 전송에 성공하면 초안을 지운다(다음 수정은 새 clientOpId — 서버는 같은 clientOpId 에 예전 응답을 돌려주므로).
 * 서버가 4xx 로 거부한 요청은 sync_op 에 기록되지 않아(트랜잭션 롤백) 고친 뒤 같은 clientOpId 로 다시 보낼 수 있다.
 */
export type DraftState = 'DRAFT' | 'PENDING_SEND';
export interface LogDraft {
  wpId: string;
  date: string;
  clientOpId: string;
  body: Omit<DailyLogInput, 'clientOpId'>;
  state: DraftState;
  savedAt: string;
  attempts: number;
  lastError?: string;
}

const PREFIX = 'atom-erp.dem-log-draft.';
export const draftKey = (wpId: string, date: string) => `${PREFIX}${wpId}.${date}`;

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

export function loadDraft(wpId: string, date: string): LogDraft | null {
  const raw = storage()?.getItem(draftKey(wpId, date));
  if (!raw) return null;
  try {
    return JSON.parse(raw) as LogDraft;
  } catch {
    return null;
  }
}

/** 초안 저장 — 기존 초안의 clientOpId 를 그대로 이어 쓴다(없을 때만 새로 만든다) */
export function saveDraft(wpId: string, date: string, body: LogDraft['body'], state: DraftState, patch: Partial<LogDraft> = {}): LogDraft {
  const prev = loadDraft(wpId, date);
  const d: LogDraft = {
    wpId, date, body, state,
    clientOpId: prev?.clientOpId ?? newIdempotencyKey(),
    savedAt: new Date().toISOString(),
    attempts: prev?.attempts ?? 0,
    lastError: prev?.lastError,
    ...patch,
  };
  storage()?.setItem(draftKey(wpId, date), JSON.stringify(d));
  return d;
}

export function updateDraft(d: LogDraft, patch: Partial<LogDraft>): LogDraft {
  const n = { ...d, ...patch };
  storage()?.setItem(draftKey(d.wpId, d.date), JSON.stringify(n));
  return n;
}

export function clearDraft(wpId: string, date: string) {
  storage()?.removeItem(draftKey(wpId, date));
}

/** 이 기기에 남은 모든 초안(전송 대기 먼저) */
export function listDrafts(): LogDraft[] {
  const s = storage();
  if (!s) return [];
  const out: LogDraft[] = [];
  for (let i = 0; i < s.length; i++) {
    const k = s.key(i);
    if (k?.startsWith(PREFIX)) {
      try {
        out.push(JSON.parse(s.getItem(k) ?? '') as LogDraft);
      } catch {
        /* 깨진 항목 무시 */
      }
    }
  }
  return out.sort((a, b) => (a.state === b.state ? a.savedAt.localeCompare(b.savedAt) : a.state === 'PENDING_SEND' ? -1 : 1));
}

/** 서버에 보낼 본문(초안의 고정 clientOpId 포함) */
export const toRequest = (d: LogDraft): DailyLogInput => ({ ...d.body, clientOpId: d.clientOpId });
