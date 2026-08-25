import { AlertTriangle } from 'lucide-react'
import { Card, KpiCard } from '../../components/ui/card'
import { fetchExpertNetworkMetrics, type ExpertNetworkMetrics } from './pmMetricsApi'
import { emptyWhen, useMetrics, warnWhen } from './useMetrics'

/**
 * The Expert Network Manager's screen: is there capacity, and where is the bench thin.
 *
 * **Nothing here names a client or a case.** That is the supply-side axis `architecture.md` draws,
 * and it is enforced on the server — this screen simply has no field to render one from.
 */
export default function ExpertNetworkDashboard() {
  const { data, state } = useMetrics<ExpertNetworkMetrics>(
    (signal) => fetchExpertNetworkMetrics(signal),
    [],
  )

  const gaps = data?.coverage.filter((row) => row.gap) ?? []

  return (
    <section>
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Expert network</h1>
      </header>

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Available experts"
          wide
          state={warnWhen(state, (data?.roster.available ?? 1) === 0)}
          to="/experts"
          value={data?.roster.available ?? null}
          denominator={
            data
              ? `${data.roster.atCapacity} at capacity · ${data.roster.onLeave} on leave · ${data.roster.total} on the roster`
              : undefined
          }
          tone={data === null ? undefined : data.roster.available > 0 ? 'good' : 'bad'}
          note="Availability is what decides whether a case can be staffed at all."
        />

        <KpiCard
          title="Onboarded this month"
          state={state}
          value={data?.onboarding.thisMonth ?? null}
          denominator={data ? `of ${data.onboarding.target} target` : undefined}
          tone={
            data === null
              ? undefined
              : data.onboarding.thisMonth >= data.onboarding.target
                ? 'good'
                : 'warn'
          }
        />

        <KpiCard
          title="Acceptance rate"
          state={
            state.kind === 'ok' && data?.acceptance.ratePct === null
              ? { kind: 'empty', note: 'No offer has resolved yet.' }
              : state
          }
          value={data?.acceptance.ratePct ?? null}
          unit="%"
          denominator={data ? `of ${data.acceptance.resolved} resolved offers` : undefined}
          // Imported from ExpertMatchService's own expression, never re-derived — two definitions
          // is how this tile and a shortlist come to disagree about the same person.
          note="Accepted over accepted, declined and timed out."
        />

        <KpiCard
          title="Cases in flight"
          state={state}
          value={data?.activeCases ?? null}
          note="Across the whole roster. Derived from cases, never from the dead counter column."
        />

        <Card
          title="Availability board"
          wide
          state={emptyWhen(state, data?.coverage.length === 0, 'No expert has claimed a primary field yet.')}
          note="Primary fields only — a secondary tag is not cover you can staff from."
        >
          <div className="scroll-slim max-h-64 overflow-y-auto">
            <table className="w-full text-sm">
              <thead>
                <tr style={{ color: 'var(--text-muted)' }}>
                  <th className="pb-1 text-left text-xs font-medium uppercase">Field</th>
                  <th className="pb-1 text-right text-xs font-medium uppercase">Available</th>
                  <th className="pb-1 text-right text-xs font-medium uppercase">At capacity</th>
                  <th className="pb-1 text-right text-xs font-medium uppercase">Inactive</th>
                </tr>
              </thead>
              <tbody>
                {data?.coverage.map((row) => (
                  <tr key={row.field} style={{ borderTop: '1px solid var(--border-default)' }}>
                    <td className="py-1.5">
                      <span className="inline-flex items-center gap-1.5">
                        {row.gap && (
                          <AlertTriangle
                            className="h-3.5 w-3.5 shrink-0"
                            style={{ color: 'var(--status-red)' }}
                            aria-label="Coverage gap"
                          />
                        )}
                        {readable(row.field)}
                      </span>
                    </td>
                    {/* Only `available` is coloured: at-capacity and inactive are context for why a
                        field is thin, not the number the gap rule is about. */}
                    <td
                      className="font-num py-1.5 text-right tabular-nums"
                      style={{ color: row.gap ? 'var(--status-red)' : 'var(--status-green)' }}
                    >
                      {row.available}
                    </td>
                    <td className="font-num py-1.5 text-right tabular-nums" style={{ color: 'var(--text-muted)' }}>
                      {row.atCapacity}
                    </td>
                    <td className="font-num py-1.5 text-right tabular-nums" style={{ color: 'var(--text-muted)' }}>
                      {row.inactive}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        <Card
          title="Low quality scores"
          state={emptyWhen(state, data?.lowQuality.length === 0, 'No expert scores below 6.')}
          note="Current scores, not a trend — quality_score is unversioned, so there is no history to plot."
        >
          <ul className="max-h-40 space-y-1 overflow-y-auto text-sm">
            {data?.lowQuality.map((row) => (
              <li key={row.expertId} className="flex justify-between gap-2">
                <span className="truncate">{row.name}</span>
                <span className="font-num tabular-nums" style={{ color: 'var(--status-amber)' }}>
                  {row.qualityScore}
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card
          title="Coverage gaps"
          state={emptyWhen(state, gaps.length === 0, 'Every field has five or more available experts.')}
          note="Fewer than five available. A field with one is a single point of failure."
        >
          <ul className="space-y-1 text-sm">
            {gaps.map((row) => (
              <li key={row.field} className="flex justify-between gap-2">
                <span className="truncate">{readable(row.field)}</span>
                <span className="font-num tabular-nums" style={{ color: 'var(--status-red)' }}>
                  {row.available} available
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card
          title="Declining two or more"
          state={emptyWhen(state, data?.declining.length === 0, 'No expert has declined repeatedly.')}
          note="From the offer ledger, not from a flag somebody set."
        >
          <ul className="space-y-1 text-sm">
            {data?.declining.map((row) => (
              <li key={row.expertId} className="flex justify-between gap-2">
                <span className="truncate">{row.name}</span>
                <span className="font-num tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {row.declines} declines
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card title="Response time" state={{ kind: 'unavailable', blockedBy: 'Unit 15' }} />
        <Card title="Payments" state={{ kind: 'unavailable', blockedBy: 'Unit 16' }} />
      </div>
    </section>
  )
}

function readable(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
