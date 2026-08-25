import { useEffect, useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, CapacityBar, ChartCard, KpiCard } from '../../components/ui/card'
import type { CardState } from '../../components/ui/card'
import { useFilters } from '../shell/filtersContext'
import { fetchPmMetrics, type PmMetrics } from './pmMetricsApi'

/**
 * The Project Manager's production command centre.
 *
 * Every KPI that names a population links to that population: clicking "at risk" opens the
 * inbox already filtered, rather than leaving the reader to rebuild the filter that produced
 * the number.
 */
export default function PmDashboard() {
  const { dateRange, activeBrandId } = useFilters()
  const [metrics, setMetrics] = useState<PmMetrics | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setMetrics(null)
    setError(null)
    fetchPmMetrics(dateRange, activeBrandId, controller.signal)
      .then(setMetrics)
      .catch((cause: Error) => {
        if (controller.signal.aborted) return
        setError(cause.message)
      })
    return () => controller.abort()
  }, [dateRange, activeBrandId])

  /** One state for every tile, so a failed load cannot leave half the board showing stale zeroes. */
  const base: CardState = error
    ? { kind: 'error', note: error }
    : metrics === null
      ? { kind: 'loading' }
      : { kind: 'ok' }

  const onTime = metrics?.onTime
  const onTimeState: CardState =
    base.kind === 'ok' && onTime?.ratePct === null
      ? { kind: 'empty', note: 'Nothing was delivered in this period.' }
      : base

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Production</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {dateRange}
        </p>
      </header>

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Delivered on time"
          wide
          state={onTimeState}
          to="/inbox?view=delivered"
          value={onTime?.ratePct ?? null}
          unit="%"
          // Thresholds, not taste: 90% and above is healthy, below 75% is a production problem.
          // Stated here rather than inside the card so the card stays generic and this tile's
          // bar is visible to whoever reads the dashboard.
          tone={
            onTime?.ratePct === null || onTime?.ratePct === undefined
              ? undefined
              : onTime.ratePct >= 90
                ? 'good'
                : onTime.ratePct >= 75
                  ? 'warn'
                  : 'bad'
          }
          // The denominator is required, not decoration: "100%" of two cases must not read
          // like "100%" of two hundred.
          denominator={onTime ? `of ${onTime.delivered} delivered` : undefined}
          delta={
            onTime?.deltaPoints === null || onTime?.deltaPoints === undefined
              ? undefined
              : { value: onTime.deltaPoints, better: 'up' }
          }
          note="Delivered on or before the date the client was promised."
        />

        <KpiCard
          title="At risk right now"
          state={base}
          to="/inbox?view=at-risk"
          value={metrics?.atRiskNow ?? null}
          // Any case inside 48 business hours of its deadline is worth a colour; zero is the
          // only healthy reading, so there is no amber band here.
          tone={metrics === null ? undefined : metrics.atRiskNow > 0 ? 'bad' : 'good'}
          // Says plainly that the header's period does not apply here. A tile reading "right
          // now" while silently obeying a year filter is the header contradicting the instrument.
          note="Deadline inside 48 business hours. Live — the date filter does not apply."
        />

        <KpiCard
          title="Unassigned"
          state={
            base.kind === 'ok' && (metrics?.unassigned ?? 0) > 0 ? { kind: 'warning' } : base
          }
          to="/inbox?view=unassigned"
          value={metrics?.unassigned ?? null}
          // The desired state is zero, so anything above it is amber rather than red: an
          // unassigned case is work waiting, not work failing.
          tone={metrics === null ? undefined : metrics.unassigned > 0 ? 'warn' : 'good'}
          note="Arrived from sales, no Case Manager yet. This should be zero."
        />

        <ChartCard
          title="Completion by service type"
          wide
          state={
            base.kind === 'ok' && metrics!.completionByService.length === 0
              ? { kind: 'empty', note: 'No cases were delivered in this period.' }
              : base
          }
          note="Median business hours from intake to delivery."
        >
          {metrics && (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={metrics.completionByService} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border-default)" vertical={false} />
                <XAxis
                  dataKey="serviceType"
                  tickFormatter={readable}
                  tick={{ fontSize: 11, fill: 'var(--text-muted)' }}
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis tick={{ fontSize: 11, fill: 'var(--text-muted)' }} tickLine={false} axisLine={false} />
                <Tooltip
                  cursor={{ fill: 'var(--bg-raised)' }}
                  contentStyle={{
                    background: 'var(--bg-surface)',
                    border: '1px solid var(--border-default)',
                    borderRadius: 'var(--radius-md)',
                    fontSize: '0.8125rem',
                  }}
                  // The brief's rule: hovering a bar gives the exact value, not an estimate off
                  // the axis. The case count rides along because a median over two cases and one
                  // over fifty are not the same claim.
                  formatter={(value, _name, item) =>
                    [`${value} business hours (${item?.payload?.delivered ?? 0} cases)`, 'Median'] as [
                      string,
                      string,
                    ]
                  }
                  labelFormatter={(label) => readable(String(label))}
                />
                <Bar dataKey="medianBusinessHours" fill="var(--chart-1)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <Card
          title="Case manager workload"
          wide
          state={
            base.kind === 'ok' && metrics!.workload.length === 0
              ? { kind: 'empty', note: 'No Case Managers on this brand yet.' }
              : base
          }
          note="Green under 70% of capacity, amber to 90%, red above."
        >
          <div>
            {metrics?.workload.map((row) => (
              <CapacityBar key={row.cmId} label={row.name} used={row.active} capacity={row.capacity} />
            ))}
          </div>
        </Card>

        <Card
          title="Draft revision rate"
          state={
            base.kind === 'ok' && metrics!.revisionRateByCm.length === 0
              ? { kind: 'empty', note: 'No drafts have been submitted yet.' }
              : base
          }
          note="Share of cases needing more than one draft. A coaching signal, not a score."
        >
          <ul className="space-y-1.5">
            {metrics?.revisionRateByCm.map((row) => (
              <li key={row.cmId} className="flex items-baseline justify-between gap-2 text-sm">
                <span className="truncate">{row.name}</span>
                <span className="font-num tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {row.ratePct}% of {row.cases}
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card
          title="Expert response time"
          // Honest rather than zero: the signing events this measures do not exist yet.
          state={{ kind: 'unavailable', blockedBy: 'Unit 15' }}
        />
      </div>
    </section>
  )
}

function readable(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
