import { useState } from 'react'

export type ViewMode = 'cards' | 'list'

/**
 * A page's card/list choice, remembered per page in this browser. Storage can be blocked (private
 * window, cleared site data), so every read and write is guarded and the page works without it.
 */
export function useViewMode(page: string, initial: ViewMode = 'cards'): [ViewMode, (next: ViewMode) => void] {
  const key = `view:${page}`
  const [mode, setMode] = useState<ViewMode>(() => {
    try {
      const saved = localStorage.getItem(key)
      return saved === 'cards' || saved === 'list' ? saved : initial
    } catch {
      return initial
    }
  })
  return [
    mode,
    (next) => {
      setMode(next)
      try {
        localStorage.setItem(key, next)
      } catch {
        // Not remembered; the choice still applies for this visit.
      }
    },
  ]
}
