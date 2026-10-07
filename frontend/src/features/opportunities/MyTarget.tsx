import { useMetrics } from '../dashboards/useMetrics'
import { fetchMyTarget, type MyTarget as Target } from '../dashboards/pmMetricsApi'
import { progressPct } from '../dashboards/targetProgress'
import { formatMoney } from '../../lib/money'

/**
 * The signed-in Sales or Marketing member's own monthly target and progress (D75), beside the totals
 * they already see. Read-only: only the GM sets it. Draws nothing for a role with no target, and nothing
 * while loading or if the read fails: a target line is not worth an error card on someone's board.
 */
export default function MyTarget() {
  const mine = useMetrics<Target | null>((signal) => fetchMyTarget(signal), [])
  const target = mine.data
  if (!target) return null

  const money = target.kind === 'WON_VALUE'
  const show = (n: number) => (money ? formatMoney(Math.round(n)) : String(Math.round(n)))
  const pct = progressPct(target.progress, target.target)

  return (
    <span className="font-num tabular-nums" title={money ? 'Won value this month against your target' : 'New leads this month against your target'}>
      {target.target === null ? (
        'No target set for this month'
      ) : (
        <>
          Target {show(target.target)} · {show(target.progress)} so far{pct !== null && ` (${pct}%)`}
        </>
      )}
    </span>
  )
}
