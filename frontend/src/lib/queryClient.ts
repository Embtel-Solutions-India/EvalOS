import { QueryClient } from '@tanstack/react-query'

/**
 * The staff app's one query cache (Unit 70a).
 *
 * `staleTime: 0` + `refetchOnWindowFocus`: a staff screen re-reads whenever the tab comes back, which
 * is how somebody else's change reaches it until spec 70's push exists. A re-read keeps the current
 * data on screen; screens show their loading state only when they have none.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 0, refetchOnWindowFocus: true, retry: 1 },
  },
})

/** Every key that describes a case or a list of them. A new screen names its key with one of these. */
export const CASE_KEYS = ['case', 'board', 'checklists', 'notifications', 'draft-review', 'pm-notes']

/**
 * Whether a finished request should refresh the case-shaped queries: any successful write, except
 * chat (typing, read marks and presence post constantly and change no case data) and sign-in.
 */
export function refreshesCases(method: string | undefined, url: string | undefined): boolean {
  const m = (method ?? 'get').toLowerCase()
  if (m === 'get' || m === 'head' || m === 'options') return false
  const path = url ?? ''
  return !path.startsWith('/chat/') && !path.startsWith('/auth/')
}

/**
 * **The one place a write refreshes the screens.** Called by the `api` interceptor after every
 * successful request, so no action has to know which panels it affects. Only queries on screen
 * re-read; the rest are marked stale and re-read when next shown.
 */
export function afterRequest(method: string | undefined, url: string | undefined, client = queryClient): void {
  if (!refreshesCases(method, url)) return
  void client.invalidateQueries({ predicate: (query) => CASE_KEYS.includes(String(query.queryKey[0])) })
}
