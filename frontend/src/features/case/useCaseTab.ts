import { useSearchParams } from 'react-router-dom'

export type CaseTab = 'documents' | 'drafts' | 'expert' | 'conversation' | 'notes' | 'timeline'

// Six tabs, one per kind of work (2026-10-30, the business; was Work and Overview). Documents first,
// and the default: it is where every case starts.
export const CASE_TABS: readonly { id: CaseTab; label: string }[] = [
  { id: 'documents', label: 'Documents & checklist' },
  { id: 'drafts', label: 'Drafts' },
  { id: 'expert', label: 'Expert & offer' },
  { id: 'conversation', label: 'Conversation' },
  { id: 'notes', label: 'Notes' },
  { id: 'timeline', label: 'Timeline' },
]

/** The open tab, kept in `?tab=` so a link or a notification can land on it. An unknown value is Documents. */
export function useCaseTab(): [CaseTab, (next: CaseTab) => void] {
  const [params, setParams] = useSearchParams()
  const asked = params.get('tab')
  const tab = CASE_TABS.some((t) => t.id === asked) ? (asked as CaseTab) : 'documents'
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
