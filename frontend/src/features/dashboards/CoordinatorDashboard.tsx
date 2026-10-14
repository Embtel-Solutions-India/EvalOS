import { Card, KpiCard } from '../../components/ui/card'
import { useFilters } from '../shell/filtersContext'
import { DocumentsOwed } from './DocumentsOwed'
import {
  fetchCoordinatorMetrics,
  fetchCoordinatorWork,
  type CoordinatorMetrics,
  type CoordinatorWork,
} from './pmMetricsApi'
import { StageFunnel } from './StageFunnel'
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
    { key: 'CoordinatorDashboard:0' },
  )

  // A separate load, so a failed /work cannot blank the tiles below it or the reverse.
  const { data: work, state: workState } = useMetrics<CoordinatorWork>(
    (signal) => fetchCoordinatorWork(activeBrandId, signal),
    [activeBrandId],
    { key: 'CoordinatorDashboard:1' },
  )

  return (
    <section>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Documents awaiting verification"
          state={workState}
          value={work?.documents.awaitingVerification ?? null}
        />
        <KpiCard
          title="Missing or incorrect documents"
          state={workState}
          value={work?.documents.blockerItems ?? null}
          tone={work === null ? undefined : work.documents.blockerItems > 0 ? 'bad' : 'good'}
        />
        <KpiCard
          title="Unsent checklists"
          state={workState}
          value={work?.documents.unsentCases ?? null}
          tone={work === null ? undefined : work.documents.unsentCases > 0 ? 'warn' : 'good'}
        />
        <KpiCard
          title="Blocked cases"
          state={workState}
          value={work?.blocked ?? null}
        />
      </div>

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Cases collecting documents"
          wide
          state={warnWhen(state, (data?.documents.aging ?? 0) > 0)}
          to="/checklists"
          value={data?.documents.outstanding ?? null}
          tone={data === null ? undefined : data.documents.aging > 0 ? 'warn' : 'good'}
          // Decision 6: the same 24-business-hour DOC_COLLECTION budget the board's SLA chip
          // uses. One clock, so this tile and that rail cannot disagree about a case.
          note="Aging is measured against the stage SLA, not a separate chase clock."
        />

        <KpiCard
          title="Median wait"
          // The server answers 0 for an empty set; an unmeasured figure must not read as a 0h wait.
          state={emptyWhen(state, data?.documents.outstanding === 0, 'No client is collecting documents.')}
          value={data?.documents.medianWaitHours ?? null}
          unit="h"
          note="How long the clients still collecting have been waiting."
        />

        <KpiCard
          title="Delivered this week"
          state={state}
          value={data?.delivered.thisWeek ?? null}
          tone="good"
        />

        <KpiCard
          title="Ready to deliver"
          state={state}
          to="/delivery"
          value={data?.readyToDeliver ?? null}
          note="QC passed, waiting to go out."
        />

        {/* One "With the client" and one "Ready to deliver" (2026-10-14): these summaries, not the case tables
            that repeated them — the Delivery queue lists the cases, and the counts here say what a table could not
            (never opened, over 48h). */}
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

        <DocumentsOwed rows={work?.documents.owed} state={workState} />
        <StageFunnel
          stages={work?.stages}
          state={workState}
          title="Your stages"
          className="xl:col-span-2"
          note="Open cases in the four stages you hold. Age is the median business hours in the stage."
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
