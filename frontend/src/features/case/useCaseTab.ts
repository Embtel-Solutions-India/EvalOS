import { useSearchParams } from 'react-router-dom'

export type CaseTab = 'work' | 'overview'

export const CASE_TABS: readonly { id: CaseTab; label: string }[] = [
  { id: 'work', label: 'Work' },
  { id: 'overview', label: 'Overview' },
]

/** The open tab, kept in `?tab=` so a link or a notification can land on it. An unknown value is Work. */
export function useCaseTab(): [CaseTab, (next: CaseTab) => void] {
  const [params, setParams] = useSearchParams()
  const asked = params.get('tab')
  const tab = CASE_TABS.some((t) => t.id === asked) ? (asked as CaseTab) : 'work'
  return [
    tab,
    (next) =>
      setParams(
        (prev) => {
          const copy = new URLSearchParams(prev)
          copy.set('tab', next)
          return copy
        },
        { replace: true },
      ),
  ]
}
