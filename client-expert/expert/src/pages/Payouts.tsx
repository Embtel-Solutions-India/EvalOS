import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, BadgeCheck, ChartColumn, FileText, Hourglass, Info, Wallet } from 'lucide-react'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { statusOf } from '@shared/services/apiClient'
import { formatDateShort } from '@shared/utils/formatters'
import { Donut, MonthBars } from '@/components/charts'
import { RangeMenu } from '@/components/RangeMenu'
import { Panel, Stat } from '@/components/Tiles'
import { COLORS, inRange, lastMonths, money, payoutDate, payoutSums, perMonth, primaryCurrency, RANGES, rangeLabel, rangeOf, type RangeKey } from '@/lib/dashboard'
import { expertFailureMessage, PAYOUT_STATUS } from '@/lib/expertCase'
import { confirmPayment, listPayouts } from '@/services/expertPortalService'

const TREND = [
  { key: '6', label: 'Last 6 months' },
  { key: '12', label: 'Last 12 months' },
] as const

const PREVIEW_ROWS = 5

/**
 * What the Expert Network Manager has recorded for this expert (spec 62). The ENM records payments
 * by hand on the staff screens (D59); a recorded transfer stays **Processing** until the expert
 * confirms receipt here (Unit 63), which is the one write on this page.
 *
 * The date range narrows the tiles and the status ring (a payout is dated when it was sent, else
 * when it falls due). **The table is never narrowed**: a transfer waiting for "Confirm received"
 * must not disappear because it was sent outside the chosen range.
 */
export default function Payouts() {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [rangeKey, setRangeKey] = useState<RangeKey>('this-month')
  const [trend, setTrend] = useState<'6' | '12'>('6')
  const [allRows, setAllRows] = useState(false)
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'payouts'],
    queryFn: listPayouts,
    retry: false,
  })

  async function confirm(paymentId: string) {
    setConfirming(paymentId)
    setFailure(null)
    try {
      queryClient.setQueryData(['expert-portal', 'payouts'], await confirmPayment(paymentId))
    } catch (failed) {
      setFailure(expertFailureMessage(statusOf(failed)))
    } finally {
      setConfirming(null)
    }
  }

  const range = useMemo(() => rangeOf(rangeKey), [rangeKey])
  const view = useMemo(() => {
    const rows = data ?? []
    const currency = primaryCurrency(rows)
    const sums = payoutSums(rows.filter((r) => inRange(payoutDate(r), range)), currency)
    const months = lastMonths(Number(trend))
    const sorted = [...rows].sort((a, b) => (payoutDate(b) ?? '').localeCompare(payoutDate(a) ?? ''))
    return {
      currency,
      sums,
      months,
      perMonth: perMonth(rows.filter((r) => r.currency === currency && r.status !== 'VOIDED'), months, payoutDate, (r) => r.amount),
      sorted,
      otherCurrencies: [...new Set(rows.map((r) => r.currency).filter((c) => c !== currency))],
    }
  }, [data, range, trend])

  const pct = (n: number) => (view.sums.total.count === 0 ? 0 : Math.round((n / view.sums.total.count) * 100))
  const cases = (n: number) => `${n} case${n === 1 ? '' : 's'}`
  const shown = allRows ? view.sorted : view.sorted.slice(0, PREVIEW_ROWS)

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Payouts</h1>
          <p className="mt-1 text-sm text-muted-foreground">Your earnings at a glance. Confirm payments when you receive them.</p>
        </div>
        <RangeMenu<RangeKey> calendar ariaLabel="Date range" value={rangeKey} options={RANGES} label={rangeLabel(range)} onChange={setRangeKey} />
      </header>
      {failure && <p role="alert" className="text-sm text-destructive">{failure}</p>}

      {isLoading && <ListSkeleton />}
      {isError && <ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} />}
      {data?.length === 0 && (
        <EmptyState icon={Wallet} title="No payouts yet" description="A payout appears here once a signed letter is recorded for payment. Questions go to the Expert Network Manager." />
      )}

      {data && data.length > 0 && (
        <>
          <div className="grid gap-4 md:grid-cols-3">
            <Stat
              tone="green"
              icon={<Wallet className="h-5 w-5" />}
              title={`Total earnings (${view.currency})`}
              value={money(view.sums.total.amount, view.currency)}
              foot={
                <span className="flex items-center gap-1" title="Paid, processing and pending together, in this period">
                  {cases(view.sums.total.count)} <Info className="h-3 w-3" aria-label="Paid, processing and pending together, in this period" />
                </span>
              }
            />
            <Stat tone="orange" icon={<Hourglass className="h-5 w-5" />} title={`Pending (${view.currency})`} value={money(view.sums.PENDING.amount, view.currency)} foot={cases(view.sums.PENDING.count)} />
            <Stat tone="blue" icon={<BadgeCheck className="h-5 w-5" />} title={`Paid (${view.currency})`} value={money(view.sums.PAID.amount, view.currency)} foot={cases(view.sums.PAID.count)} />
          </div>

          <div className="grid gap-4 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
            <Panel
              title="Earnings trend"
              icon={<ChartColumn className="h-4 w-4" />}
              action={<RangeMenu<'6' | '12'> ariaLabel="Trend length" value={trend} options={[...TREND]} onChange={setTrend} />}
            >
              <MonthBars
                axis
                caption={`Earnings per month in ${view.currency}`}
                labels={view.months.map((m) => m.label)}
                values={view.perMonth}
                color={COLORS.active}
                format={(v) => money(v, view.currency).replace(/\.00$/, '')}
              />
            </Panel>

            <Panel title="Case status" icon={<FileText className="h-4 w-4" />}>
              <Donut
                caption="Payouts by status in this period"
                center={
                  <>
                    <span className="text-2xl font-semibold tabular-nums text-foreground">{view.sums.total.count}</span>
                    <span className="text-xs text-muted-foreground">Total cases</span>
                  </>
                }
                slices={[
                  { label: 'Paid', value: view.sums.PAID.count, color: COLORS.done, detail: `${view.sums.PAID.count} (${pct(view.sums.PAID.count)}%)` },
                  { label: 'Pending', value: view.sums.PENDING.count, color: COLORS.waiting, detail: `${view.sums.PENDING.count} (${pct(view.sums.PENDING.count)}%)` },
                  { label: 'Processing', value: view.sums.PROCESSING.count, color: COLORS.neutral, detail: `${view.sums.PROCESSING.count} (${pct(view.sums.PROCESSING.count)}%)` },
                ]}
              />
            </Panel>
          </div>

          <Card className="overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4">
              <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-muted text-muted-foreground">
                  <FileText className="h-4 w-4" />
                </span>
                Recent payouts
              </h2>
              {view.sorted.length > PREVIEW_ROWS && (
                <Button variant="outline" size="sm" onClick={() => setAllRows(!allRows)}>
                  {allRows ? 'Show fewer' : `View all (${view.sorted.length})`} <ArrowRight className="ml-1 h-3.5 w-3.5" />
                </Button>
              )}
            </div>
            <div className="hidden grid-cols-[minmax(0,1fr)_8rem_8rem_9rem_10rem] gap-4 border-y bg-muted/50 px-5 py-2 text-xs font-medium text-muted-foreground sm:grid">
              <span>Case ID</span>
              <span>Amount</span>
              <span>Status</span>
              <span>Sent on</span>
              <span>Action</span>
            </div>
            <ul className="divide-y">
              {shown.map((row, i) => {
                const status = PAYOUT_STATUS[row.status]
                return (
                  <li
                    key={`${row.caseReference}-${i}`}
                    className="grid grid-cols-2 gap-x-4 gap-y-1 px-5 py-3 text-sm sm:grid-cols-[minmax(0,1fr)_8rem_8rem_9rem_10rem] sm:items-center"
                  >
                    <span className="font-medium text-foreground">{row.caseReference ?? 'Case'}</span>
                    <span className="text-right tabular-nums text-foreground sm:text-left">{money(row.amount, row.currency)}</span>
                    <span><Badge variant={status.variant}>{status.label}</Badge></span>
                    <span className="text-right text-muted-foreground sm:text-left">{row.settledOn ? formatDateShort(row.settledOn) : '—'}</span>
                    <span className="col-span-2 sm:col-span-1">
                      {/* One transfer may settle several cases; confirming it confirms all of them. */}
                      {row.status === 'PAID' && row.paymentId ? (
                        <Button size="sm" variant="destructive" disabled={confirming !== null} onClick={() => void confirm(row.paymentId!)}>
                          {confirming === row.paymentId ? 'Confirming…' : 'Confirm received'}
                        </Button>
                      ) : (
                        <span className="hidden text-muted-foreground sm:inline">—</span>
                      )}
                    </span>
                  </li>
                )
              })}
            </ul>
          </Card>
          {view.otherCurrencies.length > 0 && (
            <p className="text-xs text-muted-foreground">
              Figures above are in {view.currency}. Payouts in {view.otherCurrencies.join(', ')} are listed in the table and not added in — currencies do not add up.
            </p>
          )}
        </>
      )}
    </div>
  )
}
