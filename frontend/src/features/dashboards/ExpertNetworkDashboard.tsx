import { Card, KpiCard } from '../../components/ui/card'
import { formatPayout } from '../../lib/money'
import { fetchOpportunityBoard } from '../opportunities/opportunityApi'
import { fetchSummary } from '../payouts/payoutApi'
import {
  fetchExpertNetworkMetrics,
  fetchExpertNetworkWork,
  type ExpertNetworkMetrics,
  type ExpertNetworkWork,
} from './pmMetricsApi'
import { countUndated, daysSince, STALE_DAYS, staleDeals } from './dealAge'
import { AvailabilityBars, HBars, PayoutBar } from './NetworkCharts'
import { emptyWhen, useMetrics, warnWhen } from './useMetrics'

/**
 * The Expert Network Manager's screen: is there capacity, and where is the bench thin.
 *
 * **Nothing here names a client or a case** (hiring candidates are named, and are not clients). That is the supply-side axis `architecture.md` draws,
 * and it is enforced on the server — this screen simply has no field to render one from.
 */
export default function ExpertNetworkDashboard() {
  const { data, state } = useMetrics<ExpertNetworkMetrics>(
    (signal) => fetchExpertNetworkMetrics(signal),
    [],
    { key: 'ExpertNetworkDashboard:0' },
  )

  // Spec 82: the offer ledger by outcome, and the oldest unanswered offers.
  const { data: work, state: workState } = useMetrics<ExpertNetworkWork>(
    (signal) => fetchExpertNetworkWork(signal),
    [],
    { key: 'ExpertNetworkDashboard:1' },
  )
  const funnel = work
    ? [
        { label: 'Waiting for an answer', value: work.funnel.open, color: 'var(--accent-primary)' },
        { label: 'Accepted', value: work.funnel.accepted, color: 'var(--status-green)' },
        { label: 'Declined', value: work.funnel.declined, color: 'var(--status-red)' },
        { label: 'Timed out', value: work.funnel.timedOut, color: 'var(--status-amber)' },
        { label: 'Superseded', value: work.funnel.superseded, color: 'var(--text-muted)' },
      ]
    : []

  const gaps = data?.coverage.filter((row) => row.gap) ?? []
  // Unit 63: the hiring pipeline by stage, and this month's payouts — both existing reads.
  const { data: hiring, state: hiringState } = useMetrics((signal) => fetchOpportunityBoard(signal), [], { key: 'ExpertNetworkDashboard:2' })
  const { data: months, state: payState } = useMetrics((signal) => fetchSummary('MONTH', signal), [], { key: 'ExpertNetworkDashboard:3' })
  const stale = staleDeals(hiring)
  const undated = countUndated(hiring)
  const thisMonth = months?.filter((row) => row.periodStart === months[0]?.periodStart) ?? []

  return (
    <section>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
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
          title="Offer turnaround"
          state={
            state.kind === 'ok' && data?.turnaround.medianHours === null
              ? { kind: 'empty', note: 'No offer has resolved yet.' }
              : state
          }
          value={data?.turnaround.medianHours ?? null}
          unit="h"
          denominator={data ? `median of ${data.turnaround.resolved} resolved offers` : undefined}
          // Derived from expert_case_offer's own timestamps (G9). The stored
          // `avg_response_hours` column was never written by any path and is deliberately not
          // revived — a column nothing fills is a figure that reads as zero.
          note="Median, not mean: one expert answering after a fortnight would move a mean."
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
          className="xl:row-span-2"
          state={emptyWhen(state, data?.coverage.length === 0, 'No expert has claimed a primary field yet.')}
          note="Primary fields only — a secondary tag is not cover you can staff from. Red = fewer than five available."
        >
          <div className="scroll-slim max-h-80 overflow-y-auto">
            <AvailabilityBars
              rows={(data?.coverage ?? []).map((row) => ({
                label: readable(row.field),
                available: row.available,
                atCapacity: row.atCapacity,
                inactive: row.inactive,
                gap: row.gap,
              }))}
            />
          </div>
        </Card>

        <Card
          title="Hiring pipeline"
          wide
          to="/hiring"
          state={emptyWhen(hiringState, (hiring?.totalDeals ?? 0) === 0, 'No candidates on the hiring pipeline')}
        >
          <HBars
            rows={(hiring?.columns ?? []).map((column) => ({ label: column.stageName, value: column.deals.length }))}
          />
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

        <Card
          title="Offer funnel"
          state={emptyWhen(
            workState,
            work !== null && funnel.every((row) => row.value === 0),
            'No offer has been made yet.',
          )}
          note="Offers, not cases: a case rematched twice is three offers."
        >
          <HBars rows={funnel} />
        </Card>

        <Card
          title="Oldest unanswered offers"
          wide
          state={emptyWhen(workState, work?.oldestOpen.length === 0, 'No offer is waiting for an answer.')}
          note={
            work && work.openOffers > work.oldestOpen.length
              ? `Showing ${work.oldestOpen.length} of ${work.openOffers}, longest wait first.`
              : 'Longest wait first, in business hours. Offers have no timeout to measure against.'
          }
        >
          <ul className="scroll-slim max-h-64 space-y-1 overflow-y-auto text-sm">
            {work?.oldestOpen.map((row, index) => (
              <li key={`${row.expertId}-${index}`} className="flex items-center gap-3">
                <span className="w-40 shrink-0 truncate">{row.expertName}</span>
                <span className="h-2.5 flex-1 overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
                  <span
                    className="block h-full rounded-md"
                    style={{
                      width: `${Math.max(2, (row.waitingHours / Math.max(1, work?.oldestOpen[0]?.waitingHours ?? 1)) * 100)}%`,
                      background: 'var(--status-amber)',
                    }}
                  />
                </span>
                <span className="font-num w-12 text-right tabular-nums" style={{ color: 'var(--text-muted)' }}>
                  {row.waitingHours} h
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card
          title="Candidates gone quiet"
          to="/hiring"
          state={emptyWhen(
            hiringState,
            hiring !== null && stale.length === 0,
            `No open candidate has been untouched for ${STALE_DAYS} days.`,
          )}
          note={`No change in GHL for ${STALE_DAYS}+ days, oldest first. That is last touched, not time in stage.${
            undated > 0 ? ` ${undated} with no date are not counted.` : ''
          }`}
        >
          <ul className="scroll-slim max-h-40 space-y-1 overflow-y-auto text-sm">
            {stale.slice(0, 10).map((deal) => (
              <li key={deal.opportunityId} className="flex justify-between gap-2">
                <span className="truncate">{deal.name ?? 'Unnamed candidate'}</span>
                <span className="font-num tabular-nums" style={{ color: 'var(--status-amber)' }}>
                  {daysSince(deal.updatedAt)} d
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card
          title="Payouts this month"
          note="Processing = recorded, waiting for the expert to confirm"
          to="/payouts#summary"
          state={emptyWhen(payState, thisMonth.length === 0, 'No payouts yet')}
        >
          <ul className="space-y-1 text-sm">
            {thisMonth.map((row) => (
              <li key={row.currency} className="space-y-0.5">
                <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{row.currency}</p>
                <PayoutBar pending={row.pending} processing={row.processing} paid={row.paid} />
                <div className="flex justify-between gap-2">
                  <span>Pending</span>
                  <span className="font-num tabular-nums">{formatPayout(row.pending, row.currency)}</span>
                </div>
                <div className="flex justify-between gap-2">
                  <span>Processing</span>
                  <span className="font-num tabular-nums">{formatPayout(row.processing, row.currency)}</span>
                </div>
                <div className="flex justify-between gap-2">
                  <span>Paid</span>
                  <span className="font-num tabular-nums">{formatPayout(row.paid, row.currency)}</span>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      </div>
    </section>
  )
}

function readable(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
