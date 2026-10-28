import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { formatPayout } from '../../lib/money'
import { useFilters } from '../shell/filtersContext'
import { exportRegister, fetchRegister } from './registerApi'
import { REGISTER_STATUS_LABEL, downloadBlob, type RegisterFilter, type RegisterStatus } from './registerRules'
import { StatusChip } from './registerUi'

/**
 * Every payout as one table, ready to export (2026-10-28) — opened from the Export button on Payouts.
 *
 * The table is the register's own read and the CSV is the register's own export with the same filter, so
 * what is on screen is exactly what downloads. Print gives a PDF through the browser's own dialog.
 */
export default function PayoutExport() {
  const { activeBrandId } = useFilters()
  const [status, setStatus] = useState<RegisterStatus | ''>('')
  const filter: RegisterFilter = status ? { status } : {}
  const rows = useQuery({
    queryKey: ['payouts', 'export', activeBrandId, status],
    queryFn: ({ signal }) => fetchRegister(filter, activeBrandId, signal),
  })
  const [exporting, setExporting] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  async function exportCsv() {
    setExporting(true)
    setFailure(null)
    try {
      downloadBlob(await exportRegister(filter, activeBrandId), 'payouts.csv')
    } catch (error) {
      setFailure(error instanceof Error ? error.message : 'Could not export the payouts')
    } finally {
      setExporting(false)
    }
  }

  const data = rows.data ?? []

  return (
    <div className="flex flex-col gap-5">
      <Link to="/payouts" className="inline-flex items-center gap-1 text-sm hover:underline print:hidden" style={{ color: 'var(--text-muted)' }}>
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
        Payouts
      </Link>

      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Export payouts</h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            Every case's fee and where its payment stands{rows.data ? ` · ${data.length} rows` : ''}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 print:hidden">
          <select
            className="field"
            aria-label="Status"
            value={status}
            onChange={(e) => setStatus(e.target.value as RegisterStatus | '')}
          >
            <option value="">All statuses</option>
            {(Object.keys(REGISTER_STATUS_LABEL) as RegisterStatus[]).map((key) => (
              <option key={key} value={key}>
                {REGISTER_STATUS_LABEL[key]}
              </option>
            ))}
          </select>
          <button type="button" className="btn" disabled={data.length === 0} onClick={() => window.print()}>
            Print / PDF
          </button>
          <button type="button" className="btn btn-primary" disabled={exporting || data.length === 0} onClick={() => void exportCsv()}>
            {exporting ? 'Exporting…' : 'Export CSV'}
          </button>
        </div>
      </header>

      {failure && (
        <p role="alert" className="text-sm" style={{ color: 'var(--status-red)' }}>
          {failure}
        </p>
      )}
      {rows.isError && (
        <p role="alert" className="text-sm" style={{ color: 'var(--status-red)' }}>
          {rows.error.message || 'Could not load the payouts'}
        </p>
      )}
      {rows.isPending && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Loading…
        </p>
      )}
      {rows.isSuccess && data.length === 0 && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          No payout matches.
        </p>
      )}

      {data.length > 0 && (
        <div className="overflow-x-auto rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}>
          <table className="tbl">
            <thead>
              <tr>
                <th>Case</th>
                <th>Expert</th>
                <th className="num">Fee</th>
                <th>Status</th>
                <th>Offered</th>
                <th>Due</th>
                <th>Sent</th>
                <th>Confirmed</th>
              </tr>
            </thead>
            <tbody>
              {data.map((row) => (
                <tr key={row.offerId ?? row.payoutId ?? row.caseId}>
                  <td className="font-mono text-xs">
                    <Link to={`/cases/${row.caseId}`}>{row.caseCode ?? '—'}</Link>
                  </td>
                  <td>{row.expertName ?? '—'}</td>
                  <td className="num font-num">
                    {row.amount == null || !row.currency ? '—' : formatPayout(row.amount, row.currency)}
                  </td>
                  <td>
                    <StatusChip status={row.status} />
                  </td>
                  <td className="whitespace-nowrap">{day(row.offeredAt)}</td>
                  <td className="whitespace-nowrap">{day(row.dueDate)}</td>
                  <td className="whitespace-nowrap">{day(row.sentAt)}</td>
                  <td className="whitespace-nowrap">{day(row.confirmedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}
