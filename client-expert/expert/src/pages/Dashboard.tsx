import { useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PushCard } from '@evalos/chat'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowRight, ArrowUp, ChevronRight, CircleCheck, Clock, Coins, FileText, History, Hourglass, Wallet } from 'lucide-react'
import { Card } from '@shared/components/ui/card'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { statusOf } from '@shared/services/apiClient'
import { formatDate } from '@shared/utils/formatters'
import { cn } from '@shared/utils/cn'
import { Donut, MonthBars } from '@/components/charts'
import { Panel, Stat } from '@/components/Tiles'
import { RangeMenu } from '@/components/RangeMenu'
import {
  COLORS,
  caseCounts,
  deltaPct,
  greeting,
  inRange,
  lastMonths,
  money,
  payoutDate,
  payoutSums,
  perMonth,
  previousRange,
  primaryCurrency,
  RANGES,
  rangeLabel,
  rangeOf,
  recentActivity,
  type Activity,
  type RangeKey,
} from '@/lib/dashboard'
import { expertFailureMessage } from '@/lib/expertCase'
import { getMe, listCases, listPayouts } from '@/services/expertPortalService'

/**
 * The expert's overview: their cases and their money on one screen. Everything comes from the two
 * lists the portal already reads (and `/expert/me` for the greeting); the date range narrows the
 * dated figures — earnings, letters signed, activity — while the case counts are where things stand
 * now. See `lib/dashboard.ts` for every definition.
 */
export default function Dashboard() {
  const [rangeKey, setRangeKey] = useState<RangeKey>('this-month')
  const me = useQuery({ queryKey: ['expert-portal', 'me'], queryFn: getMe, retry: false, staleTime: Infinity })
  // Same keys as the sidebar and the other pages, so this is no extra fetch.
  const cases = useQuery({ queryKey: ['expert-portal', 'cases'], queryFn: listCases, retry: false })
  const payouts = useQuery({ queryKey: ['expert-portal', 'payouts'], queryFn: listPayouts, retry: false })
  const [allActivity, setAllActivity] = useState(false)

  const range = useMemo(() => rangeOf(rangeKey), [rangeKey])
  const previous = previousRange(range)
  const firstName = me.data?.name?.replace(/^(Dr|Prof)\.?\s+/i, '').split(/\s+/)[0]

  const loading = cases.isLoading || payouts.isLoading
  const failed = cases.error ?? payouts.error

  const view = useMemo(() => {
    const caseRows = cases.data ?? []
    const payoutRows = payouts.data ?? []
    const currency = primaryCurrency(payoutRows)
    const inWindow = payoutRows.filter((r) => inRange(payoutDate(r), range))
    const sums = payoutSums(inWindow, currency)
    const before = previous ? payoutSums(payoutRows.filter((r) => inRange(payoutDate(r), previous)), currency) : null
    const signedNow = caseRows.filter((c) => inRange(c.signedAt, range)).length
    const signedBefore = previous ? caseRows.filter((c) => inRange(c.signedAt, previous)).length : null
    const months = lastMonths(6)
    return {
      counts: caseCounts(caseRows),
      currency,
      otherCurrencies: new Set(payoutRows.map((r) => r.currency).filter((c) => c !== currency)).size,
      sums,
      earningsDelta: before ? deltaPct(sums.total.amount, before.total.amount) : null,
      signedDelta: signedBefore === null ? null : signedNow - signedBefore,
      months,
      signedPerMonth: perMonth(caseRows, months, (c) => c.signedAt, () => 1),
      earnedPerMonth: perMonth(payoutRows.filter((r) => r.currency === currency && r.status !== 'VOIDED'), months, payoutDate, (r) => r.amount),
      activity: recentActivity(caseRows, payoutRows).filter((a) => inRange(a.at, range)),
    }
  }, [cases.data, payouts.data, range, previous])

  const since = rangeKey === 'this-month' ? 'from last month' : 'from the period before'
  const activity = allActivity ? view.activity : view.activity.slice(0, 5)

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            {greeting()}
            {firstName ? `, ${firstName}` : ''}!
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">Here's a quick overview of your cases and payouts.</p>
        </div>
        <RangeMenu<RangeKey> calendar ariaLabel="Date range" value={rangeKey} options={RANGES} label={rangeLabel(range)} onChange={setRangeKey} />
      </header>

      {/* The opt-in for new-case and message alerts while the portal is closed (also on Messages). */}
      <PushCard workerUrl="/sw.js" />

      {loading && <ListSkeleton />}
      {failed && !loading && <ErrorState description={expertFailureMessage(statusOf(failed))} onRetry={() => void Promise.all([cases.refetch(), payouts.refetch()])} />}

      {!loading && !failed && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <Stat
              to="/cases"
              tone="violet"
              icon={<FileText className="h-5 w-5" />}
              title="Total cases"
              value={String(view.counts.total)}
              foot={
                <>
                  <Dot color={COLORS.done} /> {view.counts.COMPLETED} completed · <Dot color={COLORS.active} /> {view.counts.IN_PROGRESS} in progress
                </>
              }
            />
            <Stat
              to="/cases"
              tone="green"
              icon={<CircleCheck className="h-5 w-5" />}
              title="Completed cases"
              value={String(view.counts.COMPLETED)}
              foot={<Delta value={view.signedDelta} suffix={`signed ${since}`} count />}
            />
            <Stat
              to="/cases"
              tone="blue"
              icon={<Clock className="h-5 w-5" />}
              title="In progress"
              value={String(view.counts.IN_PROGRESS)}
              foot={view.counts.NOT_STARTED > 0 ? `${view.counts.NOT_STARTED} new offer${view.counts.NOT_STARTED === 1 ? '' : 's'} waiting` : 'Accepted, not yet signed'}
            />
            <Stat
              to="/payouts"
              tone="orange"
              icon={<Coins className="h-5 w-5" />}
              title={`Total earnings (${view.currency})`}
              value={money(view.sums.total.amount, view.currency)}
              foot={<Delta value={view.earningsDelta} suffix={since} />}
            />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title="Cases overview" icon={<FileText className="h-4 w-4" />} to="/cases">
              <Donut
                caption="Cases by state"
                center={
                  <>
                    <span className="text-2xl font-semibold tabular-nums text-foreground">{view.counts.total}</span>
                    <span className="text-xs text-muted-foreground">Total cases</span>
                  </>
                }
                slices={[
                  { label: 'Completed', value: view.counts.COMPLETED, color: COLORS.done },
                  { label: 'In progress', value: view.counts.IN_PROGRESS, color: COLORS.active },
                  { label: 'Not started', value: view.counts.NOT_STARTED, color: COLORS.neutral },
                ]}
              />
              <p className="mb-1 mt-5 text-xs font-medium text-muted-foreground">Letters signed per month</p>
              <MonthBars
                caption="Letters signed per month, last six months"
                labels={view.months.map((m) => m.label)}
                values={view.signedPerMonth}
                color={COLORS.active}
                format={(v) => `${v} signed`}
              />
            </Panel>

            <Panel title="Payouts overview" icon={<Wallet className="h-4 w-4" />} to="/payouts">
              <Donut
                caption={`Payouts ${rangeLabel(range)}`}
                center={
                  <>
                    <span className="text-xl font-semibold tabular-nums text-foreground">{money(view.sums.total.amount, view.currency)}</span>
                    <span className="text-xs text-muted-foreground">Total earnings</span>
                  </>
                }
                slices={[
                  { label: 'Paid', value: view.sums.PAID.amount, color: COLORS.done, detail: money(view.sums.PAID.amount, view.currency) },
                  { label: 'Pending', value: view.sums.PENDING.amount, color: COLORS.waiting, detail: money(view.sums.PENDING.amount, view.currency) },
                  { label: 'Processing', value: view.sums.PROCESSING.amount, color: COLORS.neutral, detail: money(view.sums.PROCESSING.amount, view.currency) },
                ]}
              />
              <p className="mb-1 mt-5 text-xs font-medium text-muted-foreground">Earnings per month ({view.currency})</p>
              <MonthBars
                caption="Earnings per month, last six months"
                labels={view.months.map((m) => m.label)}
                values={view.earnedPerMonth}
                color={COLORS.waiting}
                format={(v) => money(v, view.currency)}
              />
              {view.otherCurrencies > 0 && (
                <p className="mt-2 text-xs text-muted-foreground">
                  Payouts in {view.otherCurrencies} other currenc{view.otherCurrencies === 1 ? 'y are' : 'ies are'} on the Payouts page.
                </p>
              )}
            </Panel>
          </div>

          <Card className="p-5">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
                <History className="h-4 w-4 text-muted-foreground" /> Recent activity
              </h2>
              {view.activity.length > 5 && (
                <button type="button" onClick={() => setAllActivity(!allActivity)} className="flex items-center gap-1 text-sm font-medium text-info hover:underline">
                  {allActivity ? 'Show fewer' : 'View all'} <ArrowRight className="h-3.5 w-3.5" />
                </button>
              )}
            </div>
            {activity.length === 0 ? (
              <p className="py-4 text-sm text-muted-foreground">Nothing happened in this period.</p>
            ) : (
              <ul className="divide-y">
                {activity.map((a) => (
                  <ActivityRow key={a.key} item={a} />
                ))}
              </ul>
            )}
          </Card>
        </>
      )}
    </div>
  )
}

function Dot({ color }: { color: string }) {
  return <span className="inline-block h-2 w-2 rounded-full" style={{ background: color }} aria-hidden="true" />
}

/** "↑ +2 signed from last month" / "↑ 12% from last month"; "No earlier period to compare" when there is none. */
function Delta({ value, suffix, count = false }: { value: number | null; suffix: string; count?: boolean }) {
  if (value === null) return <span>No earlier period to compare</span>
  if (value === 0) return <span>Same as the previous period</span>
  const up = value > 0
  const Arrow = up ? ArrowUp : ArrowDown
  return (
    <span className={cn('flex items-center gap-1', value === 0 ? '' : up ? 'text-success' : 'text-destructive')}>
      {value !== 0 && <Arrow className="h-3 w-3" />}
      {count ? `${up ? '+' : ''}${value}` : `${Math.abs(value)}%`} {suffix}
    </span>
  )
}

const ACTIVITY_ICON: Record<Activity['kind'], { icon: ReactNode; cls: string }> = {
  paid: { icon: <Wallet className="h-4 w-4" />, cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300' },
  processing: { icon: <Hourglass className="h-4 w-4" />, cls: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300' },
  pending: { icon: <Clock className="h-4 w-4" />, cls: 'bg-orange-100 text-orange-700 dark:bg-orange-950/50 dark:text-orange-300' },
  signed: { icon: <FileText className="h-4 w-4" />, cls: 'bg-sky-100 text-sky-700 dark:bg-sky-950/50 dark:text-sky-300' },
}

function ActivityRow({ item }: { item: Activity }) {
  const icon = ACTIVITY_ICON[item.kind]
  return (
    <li>
      <Link to={item.to} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 py-2.5 hover:bg-muted/50 sm:grid-cols-[auto_minmax(0,1fr)_8rem_8rem_auto]">
        <span className={cn('flex h-9 w-9 items-center justify-center rounded-full', icon.cls)}>{icon.icon}</span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold text-foreground">{item.reference}</span>
          <span className="block truncate text-xs text-muted-foreground">{item.label}</span>
        </span>
        <span className="hidden text-sm font-semibold tabular-nums text-foreground sm:block">{item.amount ? money(item.amount.value, item.amount.currency) : ''}</span>
        <span className="text-right text-xs text-muted-foreground sm:text-sm">{formatDate(item.at, 'short')}</span>
        <ChevronRight className="hidden h-4 w-4 text-muted-foreground sm:block" />
      </Link>
    </li>
  )
}
