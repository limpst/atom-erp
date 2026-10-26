import { useQueries } from '@tanstack/react-query';
import { safeFetch, sourcesFor, type InboxItem } from '@/api/inbox';
import { useAuth } from '@/auth/auth';

/** 현재 사용자의 "내 할 일" — 출처별 쿼리(30초 캐시). 헤더 배지와 수신함 화면이 같은 캐시를 쓴다 */
export function useInbox() {
  const { session } = useAuth();
  const roles = session?.roles ?? [];
  const me = session?.actorId ?? '';
  const sources = session ? sourcesFor(roles) : [];
  const results = useQueries({
    queries: sources.map((s) => ({
      queryKey: ['inbox', me, roles.join(','), s.id],
      queryFn: () => safeFetch(s, me, roles),
      staleTime: 30_000,
      meta: { silent: true },
    })),
  });
  const items: InboxItem[] = results.flatMap((r) => r.data ?? []);
  return {
    sources: sources.map((s, i) => ({ source: s, query: results[i] })),
    items,
    count: items.length,
    loading: results.some((r) => r.isLoading),
  };
}
