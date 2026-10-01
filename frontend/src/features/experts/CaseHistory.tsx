import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { fetchCaseHistory, retakeCase } from './expertApi'
import { WORK_STATUS_LABEL } from './expertRules'

/**
 * Every case this expert was offered and where the work stands (Unit 63): accepted, submitted,
 * delivered, rejected. A rejected case still waiting for a rematch can be offered to them again
 * (D62) by whoever may reassign — `mayRetake` is that gate, the server's is the real one.
 */
export default function CaseHistory({ expertId, mayRetake }: { expertId: string; mayRetake: boolean }) {
  const [actionFailure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const query = useQuery({
    queryKey: ['experts', expertId, 'cases'],
    queryFn: ({ signal }) => fetchCaseHistory(expertId, signal),
  })
  const rows = query.data ?? null
  const failure = actionFailure ?? (query.isError && !rows ? query.error.message || 'Could not load the cases' : null)

  async function retake(caseId: string) {
    setBusy(caseId)
    setFailure(null)
    try {
      // The POST's interceptor re-reads this list (Unit 70a).
      await retakeCase(caseId)
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : 'Could not offer the case again')
    } finally {
      setBusy(null)
    }
  }

  if (failure) return <p className="text-sm" role="alert" style={{ color: 'var(--status-red)' }}>{failure}</p>
  if (!rows) return <p className="text-sm text-(--text-muted)">Loading…</p>
  if (rows.length === 0) return <p className="text-sm text-(--text-muted)">Never offered a case.</p>

  const counts = rows.reduce<Record<string, number>>((acc, row) => {
    acc[row.status] = (acc[row.status] ?? 0) + 1
    return acc
  }, {})

  return (
    <div className="space-y-3">
      <p className="text-xs text-(--text-muted)">
        {(['ACCEPTED', 'SUBMITTED', 'DELIVERED', 'REJECTED'] as const)
          .map((status) => `${counts[status] ?? 0} ${WORK_STATUS_LABEL[status].toLowerCase()}`)
          .join(' · ')}
      </p>
      <ul className="divide-y divide-(--border-default) text-sm">
        {rows.map((row) => (
          <li key={`${row.caseId}-${row.offeredAt}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
            <Link to={`/cases/${row.caseId}`} className="font-num font-medium" style={{ color: 'var(--accent-primary)' }}>
              {row.caseCode ?? row.caseId.slice(0, 8)}
            </Link>
            <span className="chip">{WORK_STATUS_LABEL[row.status]}</span>
            <span className="font-num text-xs text-(--text-muted)">offered {row.offeredAt.slice(0, 10)}</span>
            {row.declineReason && <span className="text-xs text-(--text-muted)">“{row.declineReason}”</span>}
            {mayRetake && row.retakeEligible && (
              <button
                type="button"
                className="btn ml-auto"
                disabled={busy !== null}
                onClick={() => void retake(row.caseId)}
              >
                {busy === row.caseId ? 'Offering…' : 'Offer again'}
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
