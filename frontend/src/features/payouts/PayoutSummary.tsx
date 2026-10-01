import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'

import { formatPayout } from '../../lib/money'
import { exportPayoutRows, fetchSummary, type ReportPeriod } from './payoutApi'
import { summaryCsv } from './payoutRules'

/**
 * The ENM's payout reports (Unit 63): weekly, monthly or yearly, Pending / Processing (recorded,
 * waiting for the expert) / Paid (the expert confirmed), by due date — the same week the batch pays
 * it in. Two exports: the totals shown, and every row behind them.
 */
export default function PayoutSummary() {
  const [period, setPeriod] = useState<ReportPeriod>('MONTH')
  const [exporting, setExporting] = useState(false)
  const [exportFailure, setFailure] = useState<string | null>(null)
  const query = useQuery({
    queryKey: ['payouts', 'summary', period],
    queryFn: ({ signal }) => fetchSummary(period, signal),
  })
  const rows = query.data ?? null
  const failure = exportFailure ?? (query.isError && !rows ? query.error.message || 'Could not load the summary' : null)

  function save(blob: Blob, name: string) {
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = name
    link.click()
    URL.revokeObjectURL(url)
  }

  async function exportRows() {
    setExporting(true)
    setFailure(null)
    try {
      save(await exportPayoutRows(period), `payout-rows-by-${period.toLowerCase()}.csv`)
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : 'Could not export the rows')
    } finally {
      setExporting(false)
    }
  }

  return (
    <section
      id="summary"
      className="rounded-lg border"
      style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
    >
      <header
        className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3"
        style={{ borderColor: 'var(--border-default)' }}
      >
        <h2 className="text-sm font-semibold">Summary</h2>
        <div className="flex items-center gap-2 text-sm">
          {(['WEEK', 'MONTH', 'YEAR'] as const).map((p) => (
            <button
              key={p}
              type="button"
              className={`btn ${period === p ? 'chip-accent' : ''}`}
              aria-pressed={period === p}
              onClick={() => setPeriod(p)}
            >
              {PERIOD_LABEL[p]}
            </button>
          ))}
          <button
            type="button"
            className="btn"
            disabled={!rows?.length}
            onClick={() => rows && save(new Blob([summaryCsv(rows)], { type: 'text/csv' }), `payout-totals-by-${period.toLowerCase()}.csv`)}
          >
            Export totals
          </button>
          <button type="button" className="btn" disabled={!rows?.length || exporting} onClick={() => void exportRows()}>
            {exporting ? 'Exporting…' : 'Export rows'}
          </button>
        </div>
      </header>

      {failure && <p className="px-4 py-3 text-sm" style={{ color: 'var(--status-red)' }}>{failure}</p>}
      {!failure && !rows && <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>}
      {rows?.length === 0 && (
        <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>No payouts yet.</p>
      )}
      {rows && rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                <th className="px-4 py-2 font-medium">{period === 'WEEK' ? 'Week of' : period === 'MONTH' ? 'Month' : 'Year'}</th>
                <th className="px-4 py-2 text-right font-medium">Pending</th>
                <th className="px-4 py-2 text-right font-medium">Processing</th>
                <th className="px-4 py-2 text-right font-medium">Paid</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.periodStart}-${row.currency}`} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                  <td className="font-num px-4 py-2">{periodLabel(period, row.periodStart)}</td>
                  <Cell count={row.pendingCount} amount={row.pending} currency={row.currency} />
                  <Cell count={row.processingCount} amount={row.processing} currency={row.currency} />
                  <Cell count={row.paidCount} amount={row.paid} currency={row.currency} />
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

const PERIOD_LABEL: Record<ReportPeriod, string> = { WEEK: 'Weekly', MONTH: 'Monthly', YEAR: 'Yearly' }

function periodLabel(period: ReportPeriod, start: string): string {
  return period === 'WEEK' ? start : period === 'MONTH' ? start.slice(0, 7) : start.slice(0, 4)
}

function Cell({ count, amount, currency }: { count: number; amount: number; currency: string }) {
  return (
    <td className="font-num px-4 py-2 text-right tabular-nums">
      {formatPayout(amount, currency)}
      <span className="ml-1 text-xs" style={{ color: 'var(--text-muted)' }}>({count})</span>
    </td>
  )
}
