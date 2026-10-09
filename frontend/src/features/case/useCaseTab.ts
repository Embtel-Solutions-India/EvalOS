import { useSearchParams } from 'react-router-dom'

export type CaseTab = 'overview' | 'documents' | 'draft' | 'expert' | 'timeline' | 'chat'

export const CASE_TABS: readonly { id: CaseTab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'documents', label: 'Documents' },
  { id: 'draft', label: 'Draft' },
  { id: 'expert', label: 'Expert' },
  { id: 'timeline', label: 'Timeline' },
  { id: 'chat', label: 'Chat' },
]

/** The open tab, kept in `?tab=` so a link or a notification can land on it. An unknown value is Overview. */
export function useCaseTab(): [CaseTab, (next: CaseTab) => void] {
  const [params, setParams] = useSearchParams()
  const asked = params.get('tab')
  const tab = CASE_TABS.some((t) => t.id === asked) ? (asked as CaseTab) : 'overview'
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
