import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'

import { formatPayout } from '../../lib/money'
import { rangeLabel, useFilters } from '../shell/filtersContext'
import PayoutSummary from './PayoutSummary'
import { fetchOverview } from './registerApi'
import { STATUS_TONE, day, type Overview, type RegisterRow, type RegisterStatus, type Tile } from './registerRules'

/**
 * Where the money stands (Unit 65): the module's front page.
 *
 * The four stages are drawn as the pipeline they are — promised, owed, sent, confirmed — one bar
 * split by amount with the stages in order beneath it, rather than four unrelated cards. The period
 * and brand are the shell's own filter bar, as on every dashboard. One
 * pipeline per currency: a GM across brands is never shown USD added to INR. Below: what needs
 * the ENM today, then the Unit 63 period summary and its exports, unchanged.
 */

const STAGES: { key: keyof Pick<Overview, 'committed' | 'pending' | 'processing' | 'paid'>; status: RegisterStatus; label: string; note: string }[] = [
  { key: 'committed', status: 'ACCEPTED', label: 'Committed', note: 'accepted, not yet delivered' },
  { key: 'pending', status: 'PENDING', label: 'Pending', note: 'delivered, not yet sent' },
  { key: 'processing', status: 'PROCESSING', label: 'Processing', note: 'sent, awaiting the expert' },
  { key: 'paid', status: 'PAID', label: 'Paid', note: 'confirmed by the expert' },
]

export default function PayoutsOverview() {
  // The shell's period and brand switcher — one filter for the whole app, not a second one here.
  const { dateRange, activeBrandId } = useFilters()
  // Unit 70a phase 2: re-read on focus, after any write, and on a live `case.changed`.
  const query = useQuery({
    queryKey: ['payouts', 'overview', dateRange, activeBrandId],
    queryFn: ({ signal }) => fetchOverview(dateRange, activeBrandId, signal),
  })
  const overviews = query.data ?? null
  const failure = query.isError && !overviews ? query.error.message || 'Could not load the overview' : null

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Payouts</h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            Fees on cases offered {rangeLabel(dateRange).toLowerCase()}, from promised to confirmed
          </p>
        </div>
      </header>

      {failure && <p className="text-sm" style={{ color: 'var(--status-red)' }}>{failure}</p>}
      {!failure && !overviews && <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>}
      {overviews?.length === 0 && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          No case offered in this period has a fee yet. Try a longer period.
        </p>
      )}

      {overviews?.map((o) => <Pipeline key={o.currency} overview={o} showCurrency={overviews.length > 1} />)}

      {overviews && overviews.length > 0 && <Attention overviews={overviews} />}

      <PayoutSummary />
    </div>
  )
}

function Pipeline({ overview, showCurrency }: { overview: Overview; showCurrency: boolean }) {
  const total = STAGES.reduce((sum, s) => sum + overview[s.key].amount, 0)
  const money = (value: number) => formatPayout(value, overview.currency)
  return (
    <section
      aria-label={`Money pipeline${showCurrency ? ` in ${overview.currency}` : ''}`}
      className="rounded-lg border p-5"
      style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
    >
      <div className="mb-4 flex items-baseline justify-between gap-3">
        <h2 className="text-sm font-semibold">{showCurrency ? `In ${overview.currency}` : 'Money pipeline'}</h2>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {money(total)} across {STAGES.reduce((n, s) => n + overview[s.key].count, 0)} cases
        </p>
      </div>

      {/* The proportion bar: one segment per stage, sized by amount. Decorative — the numbers below say it. */}
      <div aria-hidden className="flex h-3 w-full overflow-hidden rounded-full" style={{ background: 'var(--bg-surface)' }}>
        {total > 0 &&
          STAGES.map((s) =>
            overview[s.key].amount > 0 ? (
              <span
                key={s.key}
                style={{ width: `${(overview[s.key].amount / total) * 100}%`, background: STATUS_TONE[s.status] }}
              />
            ) : null,
          )}
      </div>

      <ol className="mt-4 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {STAGES.map((s, i) => (
          <li key={s.key} className="relative">
            <StageLink tile={overview[s.key]} status={s.status} label={s.label} note={s.note} money={money} />
            {i < STAGES.length - 1 && (
              <span
                aria-hidden
                className="absolute top-2 -right-3 hidden text-sm lg:block"
                style={{ color: 'var(--text-muted)' }}
              >
                ›
              </span>
            )}
          </li>
        ))}
      </ol>
    </section>
  )
}

function StageLink({ tile, status, label, note, money }: {
  tile: Tile
  status: RegisterStatus
  label: string
  note: string
  money: (value: number) => string
}) {
  return (
    <Link to={`/payouts/cases?status=${status}`} className="group block rounded-md p-2 -m-2 hover:bg-[var(--bg-surface)]">
      <span className="flex items-center gap-1.5 text-sm font-medium">
        <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: STATUS_TONE[status] }} />
        {label}
      </span>
      <span className="font-num mt-1 block text-2xl font-semibold tabular-nums tracking-tight">{money(tile.amount)}</span>
      <span className="block text-xs" style={{ color: 'var(--text-muted)' }}>
        {tile.count} {tile.count === 1 ? 'case' : 'cases'}, {note}
      </span>
    </Link>
  )
}

function Attention({ overviews }: { overviews: Overview[] }) {
  const rows: RegisterRow[] = overviews.flatMap((o) => o.attention)
  return (
    <section className="rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
      <header className="border-b px-4 py-3" style={{ borderColor: 'var(--border-default)' }}>
        <h2 className="text-sm font-semibold">Needs attention</h2>
      </header>
      {rows.length === 0 ? (
        <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing is overdue, and every transfer older than a week has been confirmed.
        </p>
      ) : (
        <ul>
          {rows.map((row) => (
            <li
              key={row.offerId ?? row.payoutId ?? row.caseId}
              className="flex flex-wrap items-baseline justify-between gap-2 border-t px-4 py-2 text-sm first:border-t-0"
              style={{ borderColor: 'var(--border-default)' }}
            >
              <span>
                <Link to={`/cases/${row.caseId}`} className="font-medium hover:underline">{row.caseCode ?? 'Case'}</Link>
                <span style={{ color: 'var(--text-muted)' }}> for {row.expertName ?? 'an expert'}</span>
              </span>
              <span className="flex items-baseline gap-3">
                <span className="font-num tabular-nums">
                  {row.amount !== null && row.currency ? formatPayout(row.amount, row.currency) : 'no amount'}
                </span>
                <span className="text-xs" style={{ color: row.status === 'PENDING' ? 'var(--status-red)' : 'var(--status-amber)' }}>
                  {row.status === 'PENDING'
                    ? `Overdue since ${day(row.dueDate)}`
                    : `Sent ${day(row.sentAt)}, not confirmed after 7 days`}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
