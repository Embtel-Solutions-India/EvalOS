import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { fetchBoard } from './boardApi'
import type { BoardData } from './boardRules'

/**
 * The board read, cached (Unit 70a): the production board and the four queue pages read the same
 * rows, so they share one entry per window and brand. A write anywhere refreshes it (the `api`
 * interceptor), and so does coming back to the tab. `load` re-reads now, for callers that wait.
 *
 * @returns `error` only while there is no data: a failed background re-read keeps the rows on screen
 */
export function useBoard(dueBefore: string | null, brandId: string | null) {
  const queryClient = useQueryClient()
  const query = useQuery<BoardData>({
    queryKey: ['board', dueBefore, brandId],
    queryFn: ({ signal }) => fetchBoard(dueBefore, brandId, signal),
  })
  // Joins a re-read the `api` interceptor already started after a write, rather than repeating it.
  const load = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['board'] }, { cancelRefetch: false }),
    [queryClient],
  )
  const error = query.data || !query.error ? null : query.error instanceof Error ? query.error.message : 'Could not load the board'
  return { data: query.data ?? null, error, load }
}
