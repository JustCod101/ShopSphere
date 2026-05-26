import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { handleApiError } from '@/utils/error-mapper';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
      mutations: { retry: 0 },
    },
    queryCache: new QueryCache({
      onError: (err, query) => {
        if (query.meta?.['silent']) return;
        handleApiError(err);
      },
    }),
    mutationCache: new MutationCache({
      onError: (err, _vars, _ctx, mutation) => {
        if (mutation.meta?.['silent']) return;
        handleApiError(err);
      },
    }),
  });
}
