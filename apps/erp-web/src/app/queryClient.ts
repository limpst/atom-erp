import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ProblemError } from '@/api/http';
import { toastError } from './errors';

export function createQueryClient() {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (e, q) => {
        if (q.meta?.silent) return;
        toastError(e);
      },
    }),
    mutationCache: new MutationCache({
      onError: (e, _v, _c, m) => {
        if (m.meta?.silent) return;
        toastError(e);
      },
    }),
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        refetchOnWindowFocus: false,
        // 4xx(권한 · 없음)는 재시도하지 않는다
        retry: (n, e) => !(e instanceof ProblemError && e.status >= 400 && e.status < 500) && n < 1,
      },
      mutations: { retry: false },
    },
  });
}

declare module '@tanstack/react-query' {
  interface Register {
    queryMeta: { silent?: boolean };
    mutationMeta: { silent?: boolean };
  }
}
