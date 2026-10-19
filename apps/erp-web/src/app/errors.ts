import { toast } from 'sonner';
import { ProblemError } from '@/api/http';

/** problem+json → 토스트. 제목: [CORE-409-010] 제목, 본문: detail + 필드 오류 + traceId */
export function describeError(e: unknown): { title: string; description?: string } {
  if (e instanceof ProblemError) {
    const p = e.problem;
    const code = p.code ? `[${p.code}] ` : `[HTTP ${p.status}] `;
    const fieldErrors = (p.errors ?? [])
      .map((x) => [x.field ?? x.pointer ?? x.name, x.message ?? x.detail ?? x.reason].filter(Boolean).join(': '))
      .filter(Boolean);
    const lines = [p.detail && p.detail !== p.title ? p.detail : undefined, ...fieldErrors.slice(0, 5)];
    if (p.traceId) lines.push(`traceId ${p.traceId}`);
    return { title: code + (p.title ?? '요청 실패'), description: lines.filter(Boolean).join('\n') || undefined };
  }
  return { title: '오류', description: e instanceof Error ? e.message : String(e) };
}

export function toastError(e: unknown) {
  const d = describeError(e);
  toast.error(d.title, { description: d.description, duration: 8000 });
}
