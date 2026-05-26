import { useCallback, useMemo } from 'react';
import { getOrCreateRequestId, resetRequestId } from '@/utils/idempotent';

export function useIdempotentRequestId(key: string) {
  const requestId = useMemo(() => getOrCreateRequestId(key), [key]);
  const reset = useCallback(() => resetRequestId(key), [key]);
  return { requestId, reset };
}
