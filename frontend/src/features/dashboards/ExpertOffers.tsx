import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { emptyWhen } from './useMetrics'
import type { OfferRow } from './pmMetricsApi'
import { feeText, OFFER_LABEL } from './cmWorkRules'
import { ageText } from './pmOverviewRules'

const TONE = {
  OFFERED: 'var(--text-primary)',
  ACCEPTED: 'var(--status-green)',
  DECLINED: 'var(--status-red)',
  TIMED_OUT: 'var(--status-amber)',
  SUPERSEDED: 'var(--text-muted)',
} as const

/** The latest expert offer on each of my cases — status, who, the fee, and how long it has been out. */
export function ExpertOffers({ rows, state, className }: { rows?: OfferRow[]; state: CardState; className?: string }) {
  return (
    <Card
      title="Expert offers"
      wide
      className={className}
      note="Latest offer per case, waiting ones first. Out for is business hours since offered."
      state={emptyWhen(state, rows?.length === 0, 'No expert has been offered a case of yours yet.')}
    >
      <table className="w-full text-left text-sm">
        <thead>
          <tr style={{ color: 'var(--text-muted)' }}>
            <th className="py-1 font-normal">Case</th>
            <th className="py-1 font-normal">Expert</th>
            <th className="py-1 font-normal">Status</th>
            <th className="py-1 text-right font-normal">Fee</th>
            <th className="py-1 text-right font-normal">Out for</th>
          </tr>
        </thead>
        <tbody>
          {rows?.map((row) => (
            <tr key={row.caseId}>
              <td className="py-1.5">
                <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
              </td>
              <td className="py-1.5">{row.expertName ?? '—'}</td>
              <td className="py-1.5" style={{ color: TONE[row.outcome] }} title={row.declineReason ?? undefined}>
                {OFFER_LABEL[row.outcome]}
              </td>
              <td className="font-num py-1.5 text-right tabular-nums">{feeText(row.fee, row.currency)}</td>
              <td className="font-num py-1.5 text-right tabular-nums">{ageText(row.ageBusinessHours)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}
