import { useEffect, useState } from 'react'

import { formatPayout } from '../../lib/money'
import { fetchSummary } from './payoutApi'
import { summaryCsv, type SummaryRow } from './payoutRules'

/**
 * Weekly or monthly payouts by state (Unit 63): Pending, Processing (recorded, waiting for the
 * expert) and Paid (the expert confirmed), by the period the payout opened in — with a CSV of the
 * same rows for reports.
 */
export default function PayoutSummary() {
  const [period, setPeriod] = useState<'WEEK' | 'MONTH'>('MONTH')
  const [rows, setRows] = useState<SummaryRow[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setRows(null)
    setFailure(null)
    fetchSummary(period, controller.signal)
      .then(setRows)
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setFailure(error instanceof Error ? error.message : 'Could not load the summary')
      })
    return () => controller.abort()
  }, [period])

  function download() {
    if (!rows) return
    const url = URL.createObjectURL(new Blob([summaryCsv(rows)], { type: 'text/csv' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `payouts-by-${period.toLowerCase()}.csv`
    link.click()
    URL.revokeObjectURL(url)
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
          {(['WEEK', 'MONTH'] as const).map((p) => (
            <button
              key={p}
              type="button"
              className={`btn ${period === p ? 'chip-accent' : ''}`}
              aria-pressed={period === p}
              onClick={() => setPeriod(p)}
            >
              {p === 'WEEK' ? 'Weekly' : 'Monthly'}
            </button>
          ))}
          <button type="button" className="btn" disabled={!rows?.length} onClick={download}>
            Download CSV
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
                <th className="px-4 py-2 font-medium">{period === 'WEEK' ? 'Week of' : 'Month'}</th>
                <th className="px-4 py-2 text-right font-medium">Pending</th>
                <th className="px-4 py-2 text-right font-medium">Processing</th>
                <th className="px-4 py-2 text-right font-medium">Paid</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.periodStart}-${row.currency}`} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                  <td className="font-num px-4 py-2">{period === 'WEEK' ? row.periodStart : row.periodStart.slice(0, 7)}</td>
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

function Cell({ count, amount, currency }: { count: number; amount: number; currency: string }) {
  return (
    <td className="font-num px-4 py-2 text-right tabular-nums">
      {formatPayout(amount, currency)}
      <span className="ml-1 text-xs" style={{ color: 'var(--text-muted)' }}>({count})</span>
    </td>
  )
}
