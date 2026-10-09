import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { segmentPercents } from './cmWorkRules'
import type { OwedRow } from './pmMetricsApi'
import { ageText } from './pmOverviewRules'
import { emptyWhen } from './useMetrics'

/** Cases that still owe documents or an unsent checklist — the worst first. Item counts are checklist items. */
export function DocumentsOwed({ rows, state, className }: { rows?: OwedRow[]; state: CardState; className?: string }) {
  return (
    <Card
      title="Documents still owed"
      wide
      className={className}
      note="Checklist items per case, worst first. Chased shows the last chase sent; Unsent means a checklist item has not gone to the client yet."
      state={emptyWhen(state, rows?.length === 0, 'No case is waiting on documents or an unsent checklist.')}
    >
      <table className="w-full text-left text-sm">
        <thead>
          <tr style={{ color: 'var(--text-muted)' }}>
            <th className="py-1 font-normal">Case</th>
            <th className="py-1 font-normal">Case manager</th>
            <th className="py-1 font-normal">Items</th>
            <th className="py-1 text-right font-normal">In stage</th>
            <th className="py-1 text-right font-normal">Chased</th>
          </tr>
        </thead>
        <tbody>
          {rows?.map((row) => {
            const segments = segmentPercents(row)
            return (
              <tr key={row.caseId}>
                <td className="py-1.5">
                  <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
                  {row.unsent ? (
                    <span className="ml-2 text-xs" style={{ color: 'var(--status-amber)' }}>Unsent</span>
                  ) : null}
                </td>
                <td className="py-1.5">{row.cmName ?? '—'}</td>
                <td className="py-1.5">
                  <div className="flex h-2 w-28 overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
                    {segments.map((s) => (
                      <span key={s.key} title={`${s.label}: ${s.count}`} style={{ width: `${s.pct}%`, background: s.color }} />
                    ))}
                  </div>
                  <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
                    {`${row.approved} of ${row.total} approved${row.missing + row.incorrect > 0 ? ` · ${row.missing + row.incorrect} missing or incorrect` : ''}`}
                  </p>
                </td>
                <td className="font-num py-1.5 text-right tabular-nums">{ageText(row.waitingBusinessHours)}</td>
                <td className="font-num py-1.5 text-right tabular-nums">
                  {row.lastChasedAt ? new Date(row.lastChasedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : 'Never'}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </Card>
  )
}
