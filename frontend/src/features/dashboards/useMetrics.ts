import { useCallback, useEffect, useState } from 'react'
import type { CardState } from '../../components/ui/card'

/**
 * Load one dashboard's figures and turn the result into the card system's state.
 *
 * Written once because four dashboards were about to repeat the same twenty lines: abort on
 * unmount, clear the previous payload so a stale number never sits under a new filter, and map
 * the outcome onto `loading` / `error` / `ok`.
 *
 * **The reset on re-fetch is the part that matters.** Leaving the old data in place while a new
 * request is in flight shows last month's figures under this month's header — the tile looks
 * live and is not.
 */
export function useMetrics<T>(
  load: (signal: AbortSignal) => Promise<T>,
  deps: readonly unknown[],
): { data: T | null; state: CardState; reload: () => void } {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  /**
   * Bumped to force a refetch after a mutation. A counter rather than exposing `setData`, because
   * the only honest confirmation that a write landed is the next read — patching local state would
   * show the user an outcome the server has not agreed to.
   */
  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setData(null)
    setError(null)
    load(controller.signal)
      .then(setData)
      .catch((cause: Error) => {
        if (!controller.signal.aborted) setError(cause.message)
      })
    return () => controller.abort()
    // `load` is deliberately not in the dependency list: callers pass an inline closure, which is
    // a new function identity every render, so including it would re-fetch forever. The `deps`
    // the caller names are the real inputs.
    //
    // No lint-suppression comment here — this project lints with oxlint, and a directive naming
    // an eslint rule that is not enabled reads as "a linter objects to this" when none does.
  }, [...deps, reloads])

  const state: CardState = error
    ? { kind: 'error', note: error }
    : data === null
      ? { kind: 'loading' }
      : { kind: 'ok' }

  return { data, state, reload: useCallback(() => setReloads((n) => n + 1), []) }
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

/** `warning` on a live figure that should be zero, once loaded. */
export function warnWhen(state: CardState, condition: boolean): CardState {
  return state.kind === 'ok' && condition ? { kind: 'warning' } : state
}
