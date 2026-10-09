import { useQuery } from '@tanstack/react-query'
import { useCallback, useId } from 'react'
import type { CardState } from '../../components/ui/card'

/**
 * Load one dashboard's figures and turn the result into the card system's state.
 *
 * Written once because four dashboards were about to repeat the same twenty lines: abort on
 * unmount, clear the previous payload so a stale number never sits under a new filter, and map
 * the outcome onto `loading` / `error` / `ok`.
 *
 * **Unit 70a phase 2: a TanStack query underneath**, so every dashboard, the diary and the boards
 * re-read when the tab comes back, like the rest of the staff app. The key is this call's own
 * `useId` plus the `deps` the caller names, so no two call sites share a cache entry and
 * `useMetrics(load, deps)` keeps its signature. The root `metrics` is deliberately not in
 * `CASE_KEYS`: dashboards are focus-only (spec 70 §1.8) — a live `case.changed` does not re-run
 * the GM's figures on every write in every brand.
 *
 * **A changed input clears the screen; a re-read of the same inputs does not.** A new `deps` is a
 * new key, which has no data yet, so last month's figures never sit under this month's header. A
 * focus re-read or `reload()` keeps the figures on screen until better ones arrive.
 *
 * **`key` names the request, so the cache outlives the screen.** Without it the key is this call's `useId`,
 * which is new on every mount: leaving a dashboard and coming back found nothing cached and showed the
 * loading cards until the server answered. With a stable `key` the last figures show at once and are
 * re-read in the background. The `key` plus `deps` must say everything that changes the response (the
 * brand, the range); the cache is cleared on sign-in and sign-out, so figures never cross users.
 *
 * **`refreshEvery` (ms) re-reads on a timer while the tab is visible** — for a screen over the mirror
 * only, whose rows the sweeps rewrite with nothing to tell the browser (D68 leaves mirrored screens
 * out of live updates). Never for one that reads GHL live, such as the GM overview: a poll there
 * would spend the shared 100-requests-per-10-seconds budget. A hidden tab does not poll.
 */
/** The timer half of a metrics query, apart from the hook so it can be tested without a DOM. */
export function refreshOptions(options?: { refreshEvery?: number }) {
  return { refetchInterval: options?.refreshEvery, refetchIntervalInBackground: false }
}

export function useMetrics<T>(
  load: (signal: AbortSignal) => Promise<T>,
  deps: readonly unknown[],
  options?: { refreshEvery?: number; key?: string },
): { data: T | null; state: CardState; reload: () => void } {
  const id = useId()
  const query = useQuery({ queryKey: ['metrics', options?.key ?? id, ...deps], queryFn: ({ signal }) => load(signal),
    ...refreshOptions(options),
  })
  const data = query.data ?? null

  // Error only while there is nothing to show. A re-read that fails over figures already on screen
  // must not replace them with an error card — the figures are still the last true answer, and
  // `readAt` is what tells the reader how old they are.
  const state: CardState = query.isError && data === null
    ? { kind: 'error', note: query.error.message }
    : data === null
      ? { kind: 'loading' }
      : { kind: 'ok' }

  const { refetch } = query
  return { data, state, reload: useCallback(() => void refetch(), [refetch]) }
}

/**
 * The `empty` state, but only once loading has finished.
 *
 * Guards the mistake every one of these dashboards could make: reporting "nothing to do" while
 * the request is still in flight. An empty queue is a claim about the operation, so it may only
 * be made about data that actually arrived.
 */
export function emptyWhen(state: CardState, isEmpty: boolean, note: string): CardState {
  return state.kind === 'ok' && isEmpty ? { kind: 'empty', note } : state
}

/** A plain react-query result as a card state: a skeleton while loading, the error once nothing is shown. */
export function stateOf(query: { data?: unknown; isError: boolean; error: Error | null }): CardState {
  if (query.data !== undefined) return { kind: 'ok' }
  if (query.isError) return { kind: 'error', note: query.error?.message ?? 'Could not load' }
  return { kind: 'loading' }
}

/** `warning` on a live figure that should be zero, once loaded. */
export function warnWhen(state: CardState, condition: boolean): CardState {
  return state.kind === 'ok' && condition ? { kind: 'warning' } : state
}
