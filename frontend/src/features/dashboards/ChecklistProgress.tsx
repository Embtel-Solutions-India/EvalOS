import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { emptyWhen } from './useMetrics'
import type { ChecklistCaseRow } from './pmMetricsApi'
import { segmentPercents } from './cmWorkRules'

/** One stacked bar per case: how much of its checklist is in, waiting for approval, missing or wrong. Read-only. */
export function ChecklistProgress({ cases, state, className }: { cases?: ChecklistCaseRow[]; state: CardState; className?: string }) {
  return (
    <Card
      title="Checklist progress"
      wide
      className={className}
      note="Checklist items per case, worst first. Missing and incorrect items hold a case up; the Coordinator sends and approves them."
      state={emptyWhen(state, cases?.length === 0, 'None of your open cases has a checklist yet.')}
    >
      <ul className="max-h-72 space-y-3 overflow-y-auto">
        {cases?.map((row) => {
          const segments = segmentPercents(row)
          return (
            <li key={row.caseId}>
              <div className="flex items-baseline justify-between gap-2 text-sm">
                <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
                <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {row.approved} of {row.total} items approved
                </span>
              </div>
              <div className="mt-1 flex h-2 w-full overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
                {segments.map((s) => (
                  <span key={s.key} title={`${s.label}: ${s.count}`} style={{ width: `${s.pct}%`, background: s.color }} />
                ))}
              </div>
              <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
                {segments.map((s) => `${s.count} ${s.label.toLowerCase()}`).join(' · ')}
              </p>
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
