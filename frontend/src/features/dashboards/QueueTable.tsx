import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import type { QueueRow } from './pmMetricsApi'
import { riskColor, riskLabel } from '../queues/queueRules'
import { ageText } from './pmOverviewRules'

/** A waiting queue, longest wait first. Rows open the existing case page; no new actions live here. */
export function QueueTable({
  title, note, rows, state, emptyNote, className,
}: { title: string; note: string; rows?: QueueRow[]; state: CardState; emptyNote: string; className?: string }) {
  const shown: CardState = state.kind === 'ok' && rows?.length === 0 ? { kind: 'empty', note: emptyNote } : state
  return (
    <Card title={title} note={note} state={shown} className={className}>
      <table className="w-full text-left text-sm">
        <thead>
          <tr style={{ color: 'var(--text-muted)' }}>
            <th className="py-1 font-normal">Case</th>
            <th className="py-1 font-normal">Owner</th>
            <th className="py-1 font-normal">Deadline</th>
            <th className="py-1 text-right font-normal">Waiting</th>
          </tr>
        </thead>
        <tbody>
          {rows?.map((row) => (
            <tr key={row.caseId}>
              <td className="py-1.5">
                <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
              </td>
              <td className="py-1.5">{row.ownerName ?? '—'}</td>
              <td className="py-1.5">
                {/* Status is text as well as colour: colour alone never carries it. */}
                {row.risk ? <span style={{ color: riskColor(row.risk) }}>{riskLabel(row.risk)}</span> : '—'}
                {row.deadline ? (
                  <span className="font-num ml-2 text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                    {new Date(row.deadline).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                  </span>
                ) : null}
              </td>
              <td className="font-num py-1.5 text-right tabular-nums">{ageText(row.waitingBusinessHours)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}
