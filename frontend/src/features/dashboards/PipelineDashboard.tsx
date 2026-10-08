import { ArrowLeft } from 'lucide-react'
import { useState, type CSSProperties } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Card } from '../../components/ui/card'
import { useMe } from '../../lib/authContext'
import { fetchOpportunityBoard, type OpportunityBoard } from '../opportunities/opportunityApi'
import { countUndated, daysSince, STALE_DAYS, staleDeals } from './dealAge'
import { fetchJourney, type Audience } from './journeyApi'
import { DeskCard, JourneyChart, LeadsCard, SourceCard, StageCard, TargetCard } from './journeyWidgets'
import { emptyWhen, useMetrics } from './useMetrics'

/**
 * The Sales and Marketing dashboard — one component, one data scope.
 *
 * <p><strong>The question order is the layout order.</strong> How are we doing against the month
 * (target, leads), where did the leads come from over the year (journey, sources), where are they now
 * (stages), and who is behind it (team). Nothing is shown twice: the source list is also the source
 * filter, and the desk list is also the drill-down.
 *
 * <p><strong>Drill-down is a scope, not a screen.</strong> `?member=` narrows the same read to one
 * desk and the same widgets redraw. Only the GM is offered it; the server refuses anyone else, so
 * hiding the picker is a courtesy rather than the control (`PipelineJourneyService`).
 *
 * <p><strong>Filters are the ones the data can serve</strong>: year and source. This month's target
 * is fixed to the business month; a quarter or custom range would need a different read.
 *
 * <p>The earlier "untouched deals" queue survives for a desk's own view, below the analytics: it is
 * the one question a Kanban cannot answer (`dealAge`), and it reads the caller's board.
 */
export default function PipelineDashboard({ audience }: { audience: Audience }) {
  const isGm = useMe().role === 'GM'
  const [params, setParams] = useSearchParams()
  const thisYear = new Date().getFullYear()
  const year = Number(params.get('year')) || thisYear
  const source = params.get('source')
  const memberId = isGm ? params.get('member') : null
  const [metric, setMetric] = useState<'leads' | 'value'>('leads')
  const [compare, setCompare] = useState(false)

  const set = (key: string, value: string | null) =>
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      if (value) next.set(key, value)
      else next.delete(key)
      return next
    }, { replace: true })

  const { data, state, reload } = useMetrics(
    (signal) => fetchJourney({ year, audience, memberId, source }, signal),
    [year, audience, memberId, source],
    { refreshEvery: 60_000 },
  )
  const cardState = state.kind === 'error' ? { ...state, onRetry: reload } : state

  const copy = COPY[audience]
  const person = data?.member?.name ?? null
  const currentMonth = year === thisYear ? new Date().getMonth() + 1 : null
  const years = [thisYear, thisYear - 1, thisYear - 2]

  return (
    <section>
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div>
          {isGm && memberId && (
            <button
              type="button"
              onClick={() => set('member', null)}
              className="mb-1 inline-flex items-center gap-1 text-sm font-medium"
              style={{ color: 'var(--accent-primary)' }}
            >
              <ArrowLeft className="h-4 w-4" aria-hidden /> All {copy.team}
            </button>
          )}
          <h1 className="text-2xl font-semibold tracking-tight">
            {copy.title}
            {isGm && memberId && (
              <span style={{ color: 'var(--text-muted)' }}> / {person ?? '…'}</span>
            )}
          </h1>
          <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
            {isGm ? (memberId ? `${person ?? 'This desk'}'s own pipeline.` : copy.teamNote) : copy.ownNote}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Dashboard filters">
          <label className="sr-only" htmlFor="journey-year">Year</label>
          <select id="journey-year" value={year} onChange={(e) => set('year', e.target.value === String(thisYear) ? null : e.target.value)} className={SELECT} style={SELECT_STYLE}>
            {years.map((y) => <option key={y} value={y}>{y === thisYear ? `${y} · this year` : y}</option>)}
          </select>
          <label className="sr-only" htmlFor="journey-source">Lead source</label>
          <select id="journey-source" value={source ?? ''} onChange={(e) => set('source', e.target.value || null)} className={SELECT} style={SELECT_STYLE}>
            <option value="">All sources</option>
            {(data?.sources ?? []).map((s) => <option key={s.source} value={s.source}>{s.source}</option>)}
            {source && !(data?.sources ?? []).some((s) => s.source.toLowerCase() === source.toLowerCase()) && <option value={source}>{source}</option>}
          </select>
        </div>
      </header>

      <div className="mt-5 grid gap-4 lg:grid-cols-5">
        <div className="min-w-0 lg:col-span-3">
          <TargetCard data={data} state={cardState} audience={audience} canSetTarget={isGm && !memberId} />
        </div>
        <div className="min-w-0 lg:col-span-2">
          <LeadsCard data={data} state={cardState} source={source} />
        </div>
      </div>

      <div className="mt-4 grid gap-4 xl:grid-cols-3">
        <div className="min-w-0 xl:col-span-2">
          <JourneyChart
            data={data}
            state={cardState}
            metric={metric}
            compare={compare}
            thisMonth={currentMonth}
            controls={
              <>
                <Segmented value={metric} onChange={setMetric} options={[['leads', 'Leads'], ['value', 'Value']]} label="Chart measure" />
                <button
                  type="button"
                  onClick={() => setCompare((on) => !on)}
                  aria-pressed={compare && metric === 'leads'}
                  disabled={metric === 'value'}
                  title={metric === 'value' ? 'Source comparison is by lead count' : undefined}
                  className="rounded-lg border px-2.5 py-1 text-xs font-medium disabled:opacity-50"
                  style={{ borderColor: 'var(--border-default)', background: compare && metric === 'leads' ? 'var(--accent-soft)' : 'var(--bg-surface)' }}
                >
                  Compare sources
                </button>
              </>
            }
          />
        </div>
        <SourceCard data={data} state={cardState} selected={source} onSelect={(s) => set('source', s)} colored={compare && metric === 'leads'} />
      </div>

      <div className="mt-4 grid gap-4 xl:grid-cols-2">
        <div className={`min-w-0 ${isGm && memberId ? 'xl:col-span-2' : ''}`}>
          <StageCard data={data} state={cardState} to={isGm ? undefined : '/opportunities/board'} />
        </div>
        {isGm && !memberId ? (
          <DeskCard desks={data?.desks ?? []} state={cardState} audience={audience} onOpen={(id) => set('member', id)} />
        ) : (
          !isGm && <UntouchedDeals audience={audience} />
        )}
      </div>
    </section>
  )
}

const SELECT = 'rounded-lg border px-3 py-1.5 text-sm font-medium'
const SELECT_STYLE: CSSProperties = { borderColor: 'var(--border-default)', background: 'var(--bg-surface)', color: 'var(--text-primary)' }

function Segmented<T extends string>({ value, onChange, options, label }: { value: T; onChange: (value: T) => void; options: [T, string][]; label: string }) {
  return (
    <div className="inline-flex rounded-lg border p-0.5" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }} role="group" aria-label={label}>
      {options.map(([key, text]) => (
        <button
          key={key}
          type="button"
          onClick={() => onChange(key)}
          aria-pressed={value === key}
          className="rounded-md px-2.5 py-0.5 text-xs font-medium"
          style={value === key ? { background: 'var(--accent-primary)', color: '#fff' } : { color: 'var(--text-muted)' }}
        >
          {text}
        </button>
      ))}
    </div>
  )
}

/**
 * What nobody has touched — the one question the board's Kanban cannot answer, and so the one thing
 * from the old screen this one keeps. Reads the caller's own board; hence not shown to the GM, whose
 * board is every desk's.
 */
function UntouchedDeals({ audience }: { audience: Audience }) {
  const { data, state } = useMetrics<OpportunityBoard>((signal) => fetchOpportunityBoard(signal), [audience], { refreshEvery: 60_000 })
  const untouched = staleDeals(data)
  const undated = data ? countUndated(data) : 0
  const copy = COPY[audience]

  return (
    <Card
      title={`Needs a call · no activity in ${STALE_DAYS}+ days`}
      state={emptyWhen(state, untouched.length === 0, copy.emptyUntouched)}
    >
      <ul className="divide-y" style={{ borderColor: 'var(--border-subtle)' }}>
        {untouched.slice(0, 8).map((deal) => (
          <li key={deal.opportunityId} className="flex items-baseline justify-between gap-3 py-1.5">
            <Link to="/opportunities/board" className="truncate text-sm hover:underline">{deal.name ?? 'Unnamed deal'}</Link>
            <span className="font-num shrink-0 text-xs tabular-nums" style={{ color: 'var(--status-red)' }}>{daysSince(deal.updatedAt)}d</span>
          </li>
        ))}
      </ul>
      {untouched.length > 8 && (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>and {untouched.length - 8} more</p>
      )}
      {undated > 0 && (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>{undated} deals have no last-modified date from GHL, so they cannot be aged.</p>
      )}
      {data?.stale && (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-amber)' }}>The mirror has not confirmed with GHL recently; ages may be behind.</p>
      )}
    </Card>
  )
}

const COPY = {
  sales: {
    title: 'Sales',
    team: 'salespeople',
    teamNote: 'The whole sales team. Select a salesperson to see the same dashboard for them.',
    ownNote: 'Your pipeline in GHL.',
    emptyUntouched: 'Every open deal has had activity this week.',
  },
  marketing: {
    title: 'Marketing',
    team: 'marketers',
    teamNote: 'The whole marketing team. Select a member to see the same dashboard for them.',
    ownNote: 'Your leads pipeline in GHL.',
    emptyUntouched: 'Every open lead has had activity this week.',
  },
} as const
