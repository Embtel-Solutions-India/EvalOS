import { Card, KpiCard } from '../../components/ui/card'
import { useFilters } from '../shell/filtersContext'
import { fetchRevenueMetrics, type RevenueMetrics } from './pmMetricsApi'
import { emptyWhen, useMetrics } from './useMetrics'

/**
 * The GM's and Brand Manager's money screen.
 *
 * Largest tile is open liability, not collected: money taken for work not yet delivered is the
 * figure that carries risk, and invariant 5 is what makes it meaningful.
 *
 * The GM sees a per-brand breakdown and the Brand Manager does not — theirs would be the same
 * number printed twice.
 */
export default function RevenueDashboard() {
  const { activeBrandId } = useFilters()
  const { data, state } = useMetrics<RevenueMetrics>(
    (signal) => fetchRevenueMetrics(activeBrandId, signal),
    [activeBrandId],
  )

  const total = data?.total

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Money in vs delivered</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {activeBrandId ? 'one brand' : 'all brands'}
        </p>
      </header>

      {/* If the three ever stop adding up the screen says so rather than showing them anyway.
          Three numbers that quietly disagree are worse than an error. */}
      {total && !total.reconciles && (
        <p
          className="mt-4 rounded-lg border p-3 text-sm"
          style={{
            background: 'var(--status-red-bg)',
            borderColor: 'var(--status-red)',
            color: 'var(--status-red)',
          }}
        >
          Collected does not equal recognised plus open liability. These figures are inconsistent —
          do not report from them until it is investigated.
        </p>
      )}

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Open liability"
          wide
          money
          state={state}
          value={total ? round(total.openLiability) : null}
          denominator={data ? `${data.openCases} cases paid and not yet delivered` : undefined}
          tone={data === null ? undefined : data.openCases > 0 ? 'warn' : 'good'}
          note="Money taken for work not yet delivered — the refund exposure."
        />

        <KpiCard title="Collected" money state={state} value={total ? round(total.collected) : null} />
        <KpiCard
          title="Recognised"
          money
          state={state}
          value={total ? round(total.recognized) : null}
          tone="good"
          note="Paid and delivered — invariant 5."
        />
        <KpiCard
          title="Refunded"
          money
          state={state}
          value={total ? round(total.refunded) : null}
          tone={total && total.refunded > 0 ? 'bad' : undefined}
          note="Shown beside the others and counted inside none of them."
        />

        <Card
          title="By brand"
          wide
          state={emptyWhen(state, (data?.perBrand.length ?? 0) === 0, 'One brand in scope.')}
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <th className="pb-1 text-left text-xs font-medium uppercase">Brand</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Open</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Recognised</th>
              </tr>
            </thead>
            <tbody>
              {data?.perBrand.map((row) => (
                <tr key={row.brandId}>
                  <td className="py-1">{row.name}</td>
                  <td className="font-num py-1 text-right tabular-nums">{round(row.money.openLiability)}</td>
                  <td className="font-num py-1 text-right tabular-nums">{round(row.money.recognized)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <Card title="Money out" state={{ kind: 'unavailable', blockedBy: 'Unit 16' }} />
      </div>
    </section>
  )
}

/** Whole units. These are headline figures, and the cents add noise without adding meaning. */
function round(value: number): number {
  return Math.round(value)
}
