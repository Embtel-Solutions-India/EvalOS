import { Link } from 'react-router-dom'
import { Card, KpiCard } from '../../components/ui/card'
import { formatMoney } from '../../lib/money'
import { fetchOpportunityBoard, type OpportunityBoard } from '../opportunities/opportunityApi'
import { countUndated, daysSince, STALE_DAYS, staleDeals } from './dealAge'
import { emptyWhen, useMetrics } from './useMetrics'

/**
 * The Sales and Marketing landing screen: what is in the pipeline, and what nobody has touched.
 *
 * <p><strong>This reverses `RoleDashboard`'s "their board is their dashboard" decision, and the
 * reason it was right to reverse is narrow.</strong> That argument held that a tile page in front
 * of one screen is a summary of that screen — true, and it stays true for anything this file
 * could compute by counting cards. What the board genuinely cannot answer is
 * <em>"which of these is dying"</em>: a Kanban shows deals grouped by stage, in GHL's order, with
 * no notion of age. A salesperson scanning six columns cannot see that four deals have had no
 * activity in three weeks. That is the one question worth a second screen, and it is the whole
 * justification for this one.
 *
 * <p><strong>Deliberately not a scoreboard.</strong> No win rate, no leaderboard, no revenue
 * booked. Sales needs queues — the deals that need a call today — not a performance summary, and
 * a per-person ranking is a management decision rather than a UI one.
 *
 * <p><strong>One endpoint.</strong> Everything here is derived from `GET /api/opportunities/board`,
 * which the desk already calls. No new backend, and no second read to keep consistent with the
 * first.
 *
 * <p><strong>What is missing, and why it is absent rather than a greyed-out tile.</strong> Today's
 * meetings, upcoming meetings and overdue follow-ups belong on this screen and are not here:
 * EvalOS <em>writes</em> both to GHL (`SalesDeskService.followUp`, `bookMeeting`) and has no route
 * that reads either back. Drawing four empty tiles that blame a missing endpoint would spend the
 * `unavailable` state on a promise nobody has scheduled — the tombstone pattern this codebase has
 * had to clean up once already. They arrive when the read does.
 */
export default function PipelineDashboard({ audience }: { audience: 'sales' | 'marketing' }) {
  const { data, state } = useMetrics<OpportunityBoard>(
    (signal) => fetchOpportunityBoard(signal),
    [audience],
  )

  const copy = COPY[audience]
  const stale = data?.stale ?? false

  // Sorted by GHL's own position so the summary reads in the same order as the board it links to.
  const columns = [...(data?.columns ?? [])].sort((a, b) => a.position - b.position)
  const untouched = staleDeals(data)

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">{copy.title}</h1>
        {data && (
          <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {stale ? 'showing a cached read' : 'read just now'}
          </p>
        )}
      </header>

      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Open deals"
          state={state}
          value={data ? data.totalDeals : null}
          to="/opportunities/board"
          note={copy.openNote}
        />

        <KpiCard
          title="Value in pipeline"
          money
          state={state}
          value={data ? Math.round(data.totalValue) : null}
          to="/opportunities/board"
          note="Sum of every open deal's amount, as GHL holds it."
        />

        {/* The tile this screen exists for. `bad` above zero rather than a threshold: one deal
            nobody has touched in three weeks is already the problem, and a tolerance would only
            decide how many of them are acceptable. */}
        <KpiCard
          title={`Untouched ${STALE_DAYS}d+`}
          state={state}
          value={data ? untouched.length : null}
          tone={data === null ? undefined : untouched.length > 0 ? 'bad' : 'good'}
          to="/opportunities/board"
          note={copy.untouchedNote}
        />

        <KpiCard
          title="Age unknown"
          state={state}
          value={data ? countUndated(data) : null}
          tone={data === null ? undefined : countUndated(data) > 0 ? 'warn' : undefined}
          note="Deals GHL sent no last-modified date for — they cannot be aged."
        />
      </div>

      <div className="mt-4 grid gap-4 xl:grid-cols-2">
        <Card
          title="By stage"
          state={emptyWhen(state, columns.length === 0, copy.emptyBoard)}
          to="/opportunities/board"
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <th className="pb-1 text-left text-xs font-medium uppercase">Stage</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Deals</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Value</th>
              </tr>
            </thead>
            <tbody>
              {columns.map((column) => (
                <tr key={column.stageId}>
                  <td className="py-1">{column.stageName}</td>
                  <td className="font-num py-1 text-right tabular-nums">{column.deals.length}</td>
                  <td className="font-num py-1 text-right tabular-nums">
                    {formatMoney(Math.round(column.total))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        {/* Oldest first: the list is a work queue, so it is ordered by how badly each row needs
            the call rather than by stage or name. */}
        <Card
          title={`Nobody has touched these`}
          state={emptyWhen(state, untouched.length === 0, copy.emptyUntouched)}
        >
          <ul className="divide-y" style={{ borderColor: 'var(--border-subtle)' }}>
            {untouched.slice(0, 8).map((deal) => (
              <li key={deal.opportunityId} className="flex items-baseline justify-between gap-3 py-1.5">
                <Link to="/opportunities/board" className="truncate text-sm hover:underline">
                  {deal.name ?? 'Unnamed deal'}
                </Link>
                <span
                  className="font-num shrink-0 text-xs tabular-nums"
                  style={{ color: 'var(--status-red)' }}
                >
                  {daysSince(deal.updatedAt)}d
                </span>
              </li>
            ))}
          </ul>
          {untouched.length > 8 && (
            <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
              and {untouched.length - 8} more
            </p>
          )}
        </Card>
      </div>
    </section>
  )
}

const COPY = {
  sales: {
    title: 'My pipeline',
    openNote: 'Deals on your pipeline in GHL.',
    untouchedNote: 'No activity in GHL for a working week — the deals quietly dying.',
    emptyBoard: 'No deals on your pipeline. If you expect some, check your pipeline assignment.',
    emptyUntouched: 'Every open deal has had activity this week.',
  },
  marketing: {
    title: 'My leads',
    openNote: 'Leads on your pipeline in GHL.',
    untouchedNote: 'No activity in GHL for a working week — leads nobody has called back.',
    emptyBoard: 'No leads on your pipeline. If you expect some, check your pipeline assignment.',
    emptyUntouched: 'Every open lead has had activity this week.',
  },
} as const
