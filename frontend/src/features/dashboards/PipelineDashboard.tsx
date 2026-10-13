import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PillButton, PillSelect, PillToggle } from '../../components/ui/widgets'
import { useMe } from '../../lib/authContext'
import { fetchJourney, type Audience } from './journeyApi'
import { DeskCard, JourneyChart, KpiRow, SourceCard, StageCard } from './journeyWidgets'
import { useMetrics } from './useMetrics'

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
 * <p>The "Needs a call" queue of untouched deals was removed on 2026-10-14 at the business's request.
 */
export default function PipelineDashboard({ audience }: { audience: Audience }) {
  const role = useMe().role
  // The GM and the read-only Administrator both see the whole team and may drill down; only the GM sets targets.
  const isGm = role === 'GM' || role === 'ADMIN'
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
    { key: 'PipelineDashboard:0', refreshEvery: 60_000 })
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
              <ArrowLeft className="h-4 w-4" aria-hidden /> All {copy.team} / {person ?? '…'}
            </button>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Dashboard filters">
          <PillSelect label="Year" value={String(year)} onChange={(e) => set('year', e.target.value === String(thisYear) ? null : e.target.value)}>
            {years.map((y) => <option key={y} value={y}>{y === thisYear ? `${y} · this year` : y}</option>)}
          </PillSelect>
          <PillSelect label="Lead source" value={source ?? ''} onChange={(e) => set('source', e.target.value || null)}>
            <option value="">All sources</option>
            {(data?.sources ?? []).map((s) => <option key={s.source} value={s.source}>{s.source}</option>)}
            {source && !(data?.sources ?? []).some((s) => s.source.toLowerCase() === source.toLowerCase()) && <option value={source}>{source}</option>}
          </PillSelect>
        </div>
      </header>

      {/* The Sketch's grid: a main column and a 255px rail. Questions run down the main column in the order
          a manager asks them; the rail answers "who" and "where from". */}
      <div className="mt-5 grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_16rem]">
        <div className="min-w-0 space-y-4">
          <KpiRow data={data} state={cardState} audience={audience} canSetTarget={role === 'GM' && !memberId} source={source} />
          {/* Where the pipeline stands right now, above the year's journey (2026-10-14). */}
          <StageCard data={data} state={cardState} to={isGm ? undefined : '/opportunities/board'} />
          <JourneyChart
            data={data}
            state={cardState}
            metric={metric}
            compare={compare}
            thisMonth={currentMonth}
            controls={
              <>
                <PillToggle value={metric} onChange={setMetric} options={[['leads', 'Leads'], ['value', 'Value']]} label="Chart measure" />
                <PillButton
                  onClick={() => setCompare((on) => !on)}
                  on={compare && metric === 'leads'}
                  disabled={metric === 'value'}
                  title={metric === 'value' ? 'Source comparison is by lead count' : undefined}
                >
                  Compare sources
                </PillButton>
              </>
            }
          />
        </div>

        <div className="min-w-0 space-y-4">
          {isGm && !memberId && (
            <DeskCard desks={data?.desks ?? []} state={cardState} audience={audience} onOpen={(id) => set('member', id)} />
          )}
          <SourceCard data={data} state={cardState} selected={source} onSelect={(s) => set('source', s)} colored={compare && metric === 'leads'} />
        </div>
      </div>
    </section>
  )
}

const COPY = {
  sales: {
    title: 'Sales',
    team: 'salespeople',
    teamNote: 'The whole sales team. Select a salesperson to see the same dashboard for them.',
    ownNote: 'Your pipeline in GHL.',
  },
  marketing: {
    title: 'Marketing',
    team: 'marketers',
    teamNote: 'The whole marketing team. Select a member to see the same dashboard for them.',
    ownNote: 'Your leads pipeline in GHL.',
  },
} as const
