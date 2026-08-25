import { Card, KpiCard } from '../../components/ui/card'
import { useFilters } from '../shell/filtersContext'
import { fetchCoordinatorMetrics, type CoordinatorMetrics } from './pmMetricsApi'
import { emptyWhen, useMetrics, warnWhen } from './useMetrics'

/**
 * The Project Coordinator's screen: who is waiting on us, and what went out.
 *
 * The largest tile is documents outstanding, because clearing that blocker is the job. Every tile
 * that names a population links to it.
 */
export default function CoordinatorDashboard() {
  const { activeBrandId } = useFilters()
  const { data, state } = useMetrics<CoordinatorMetrics>(
    (signal) => fetchCoordinatorMetrics(activeBrandId, signal),
    [activeBrandId],
  )

  return (
    <section>
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Client operations</h1>
      </header>

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Documents outstanding"
          wide
          state={warnWhen(state, (data?.documents.aging ?? 0) > 0)}
          to="/checklists"
          value={data?.documents.outstanding ?? null}
          denominator={
            data ? `${data.documents.aging} past the collection budget` : undefined
          }
          tone={data === null ? undefined : data.documents.aging > 0 ? 'warn' : 'good'}
          // Decision 6: the same 24-business-hour DOC_COLLECTION budget the board's SLA rail
          // uses. One clock, so this tile and that rail cannot disagree about a case.
          note="Aging is measured against the stage SLA, not a separate chase clock."
        />

        <KpiCard
          title="Median wait"
          state={state}
          value={data?.documents.medianWaitHours ?? null}
          unit="h"
          denominator="business hours, open cases"
          note="How long the clients still collecting have been waiting."
        />

        <KpiCard
          title="Delivered this week"
          state={state}
          value={data?.delivered.thisWeek ?? null}
          denominator={data ? `${data.delivered.today} today` : undefined}
          tone="good"
        />

        <KpiCard
          title="Ready to deliver"
          state={state}
          to="/delivery"
          value={data?.readyToDeliver ?? null}
          note="QC passed, waiting to go out."
        />

        <Card
          title="With the client"
          wide
          state={emptyWhen(state, data?.clientReview.awaiting === 0, 'No drafts are with a client.')}
          note="Drafts sent for review. Unopened means the portal link has never been used."
        >
          {data && (
            <dl className="grid grid-cols-3 gap-3">
              <Figure label="Awaiting" value={data.clientReview.awaiting} />
              <Figure
                label="Never opened"
                value={data.clientReview.unopened}
                tone={data.clientReview.unopened > 0 ? 'var(--status-amber)' : undefined}
              />
              <Figure
                label="Over 48h"
                value={data.clientReview.stale}
                tone={data.clientReview.stale > 0 ? 'var(--status-red)' : undefined}
              />
            </dl>
          )}
        </Card>

        <Card
          title="Review requests"
          // `google_review_requested` has no writer anywhere — Handoff C would set it and is
          // unbuilt. Zero here would read as "we asked nobody", which is a different claim.
          state={{ kind: 'unavailable', blockedBy: 'Unit 18' }}
        />
      </div>
    </section>
  )
}

function Figure({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div>
      <dt className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd
        className="font-num text-2xl font-semibold tabular-nums"
        style={{ color: tone ?? 'var(--text-primary)' }}
      >
        {value}
      </dd>
    </div>
  )
}
