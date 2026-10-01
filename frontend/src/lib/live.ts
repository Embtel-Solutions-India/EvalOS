import type { QueryClient } from '@tanstack/react-query'
import type { ChatClient, LiveSignal } from '@evalos/chat'
import { CASE_KEYS } from './queryClient'

/**
 * Unit 70 §4: somebody else's write reaches this screen. The backend sends a signal, never data;
 * this turns it into query invalidations, and TanStack Query re-reads in the background — the
 * current data stays on screen meanwhile.
 *
 * - `case.changed {caseId}` → that case's queries and every list of cases (not other cases' detail).
 * - `notifications.changed` → the bell.
 * - `reconnected` → every case-shaped query: whatever was missed while offline.
 *
 * Signals are gathered for 500 ms, so a transaction touching ten checklist items is one re-read.
 */
export function attachLive(chat: ChatClient, queries: QueryClient, waitMs = 500): () => void {
  let pending: LiveSignal[] = []
  let timer: ReturnType<typeof setTimeout> | null = null

  const flush = () => {
    timer = null
    const batch = pending
    pending = []
    void queries.invalidateQueries({ predicate: (query) => refreshes(batch, query.queryKey) })
  }

  const stop = chat.onLive((signal) => {
    pending.push(signal)
    timer ??= setTimeout(flush, waitMs)
  })
  return () => {
    stop()
    if (timer) clearTimeout(timer)
  }
}

/** Whether this query must re-read for these signals. Pure, so the rule is tested without a cache. */
export function refreshes(signals: readonly LiveSignal[], key: readonly unknown[]): boolean {
  const root = String(key[0])
  if (!CASE_KEYS.includes(root)) return false
  return signals.some((signal) => {
    if (signal.type === 'reconnected') return true
    if (signal.type === 'notifications.changed') return root === 'notifications'
    if (root === 'notifications') return false
    // One case's detail queries re-read only for that case; every list re-reads for any case.
    return root !== 'case' || key[1] === signal.caseId
  })
}
