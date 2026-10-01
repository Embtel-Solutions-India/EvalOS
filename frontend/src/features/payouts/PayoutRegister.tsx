import { useQuery } from '@tanstack/react-query'
import { useCallback, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { formatPayout } from '../../lib/money'
import { useFilters } from '../shell/filtersContext'
import OfferLogPanel from './OfferLogPanel'
import { exportRegister, fetchOfferHistory, fetchRegister } from './registerApi'
import {
  REGISTER_STATUS_LABEL,
  day,
  downloadBlob,
  type LogEntry,
  type RegisterFilter,
  type RegisterRow,
  type RegisterStatus,
} from './registerRules'
import { StatusChip } from './registerUi'

/**
 * Every case's fee and whether it is done (Unit 65) — the register the ENM works from.
 *
 * **The filters live in the URL**, so a filtered view can be shared or bookmarked, and the Overview
 * tiles and the Experts screen link straight into a narrowed register. A row opens its log in a
 * side panel; a payout with no offer on record (from before the unit) has no log to open.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; rows: RegisterRow[] }
  | { status: 'failed'; message: string }

const STATUSES = Object.keys(REGISTER_STATUS_LABEL) as RegisterStatus[]

export default function PayoutRegister() {
  const [params, setParams] = useSearchParams()
  const { activeBrandId } = useFilters()
  const filter = useMemo<RegisterFilter>(
    () => ({
      status: (params.get('status') as RegisterStatus | null) ?? undefined,
      expertId: params.get('expertId') ?? undefined,
      from: params.get('from') ?? undefined,
      to: params.get('to') ?? undefined,
      q: params.get('q') ?? undefined,
    }),
    [params],
  )
  const [exporting, setExporting] = useState(false)
  const [exportFailure, setExportFailure] = useState<string | null>(null)
  const [open, setOpen] = useState<{ row: RegisterRow; entries: LogEntry[] | null; failure: string | null } | null>(null)

  // Unit 70a phase 2. A changed filter is a new key, so it shows the loading state rather than old rows.
  const query = useQuery({
    queryKey: ['payouts', 'register', filter, activeBrandId],
    queryFn: ({ signal }) => fetchRegister(filter, activeBrandId, signal),
  })
  const load = () => query.refetch()
  const state: LoadState = query.data
    ? { status: 'ready', rows: query.data }
    : query.isError
      ? { status: 'failed', message: query.error.message || 'Could not load the cases' }
      : { status: 'loading' }

  function setParam(key: keyof RegisterFilter, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  async function openLog(row: RegisterRow) {
    if (!row.offerId) return
    setOpen({ row, entries: null, failure: null })
    try {
      const entries = await fetchOfferHistory(row.offerId)
      setOpen({ row, entries, failure: null })
    } catch (error: unknown) {
      setOpen({ row, entries: [], failure: error instanceof Error ? error.message : 'Could not load the history' })
    }
  }

  async function exportCsv() {
    setExporting(true)
    setExportFailure(null)
    try {
      downloadBlob(await exportRegister(filter, activeBrandId), 'payout-cases.csv')
    } catch (error: unknown) {
      setExportFailure(error instanceof Error ? error.message : 'Could not export the cases')
    } finally {
      setExporting(false)
    }
  }

  const closeLog = useCallback(() => setOpen(null), [])
  const rows = state.status === 'ready' ? state.rows : []
  const filtered = Boolean(filter.status || filter.expertId || filter.from || filter.to || filter.q)

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Cases</h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            What each case pays its expert, and whether it has been paid
          </p>
        </div>
        <button type="button" className="btn" disabled={exporting || rows.length === 0} onClick={() => void exportCsv()}>
          {exporting ? 'Exporting…' : 'Export CSV'}
        </button>
      </header>

      <div className="flex flex-wrap items-end gap-3 text-sm">
        <label className="flex min-w-[14rem] flex-1 flex-col gap-1">
          <span style={{ color: 'var(--text-muted)' }}>Search</span>
          <input
            type="search"
            placeholder="Case code or expert"
            defaultValue={filter.q ?? ''}
            onChange={(e) => setParam('q', e.target.value)}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span style={{ color: 'var(--text-muted)' }}>Status</span>
          <select
            value={filter.status ?? ''}
            onChange={(e) => setParam('status', e.target.value)}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          >
            <option value="">Any status</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{REGISTER_STATUS_LABEL[s]}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span style={{ color: 'var(--text-muted)' }}>Offered from</span>
          <input
            type="date"
            value={filter.from ?? ''}
            onChange={(e) => setParam('from', e.target.value)}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span style={{ color: 'var(--text-muted)' }}>to</span>
          <input
            type="date"
            value={filter.to ?? ''}
            onChange={(e) => setParam('to', e.target.value)}
            className="rounded border px-2 py-1.5"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
          />
        </label>
        {filtered && (
          <button type="button" className="btn" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
            Clear filters
          </button>
        )}
      </div>

      {exportFailure && <p className="text-sm" style={{ color: 'var(--status-red)' }}>{exportFailure}</p>}

      <section
        className="rounded-lg border"
        style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
      >
        {state.status === 'loading' && (
          <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>Loading cases…</p>
        )}
        {state.status === 'failed' && (
          <div className="flex items-center gap-3 px-4 py-3 text-sm">
            <span style={{ color: 'var(--status-red)' }}>{state.message}</span>
            <button type="button" className="btn" onClick={() => void load()}>Try again</button>
          </div>
        )}
        {state.status === 'ready' && rows.length === 0 && (
          <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>
            {filtered ? 'No cases match these filters.' : 'No case has a fee yet. Fees are set when a case is offered to an expert.'}
          </p>
        )}
        {rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                  <th className="px-4 py-2 font-medium">Case</th>
                  <th className="px-4 py-2 font-medium">Expert</th>
                  <th className="px-4 py-2 text-right font-medium">Amount</th>
                  <th className="px-4 py-2 font-medium">Status</th>
                  <th className="px-4 py-2 font-medium">Fee set by</th>
                  <th className="px-4 py-2 font-medium">Due</th>
                  <th className="px-4 py-2 font-medium">Sent</th>
                  <th className="px-4 py-2 font-medium">Confirmed</th>
                  <th className="px-4 py-2 text-center font-medium">Done</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.offerId ?? row.payoutId ?? row.caseId}
                    className={`border-t ${row.offerId ? 'cursor-pointer hover:bg-[var(--bg-surface)]' : ''}`}
                    style={{ borderColor: 'var(--border-default)' }}
                    tabIndex={row.offerId ? 0 : undefined}
                    title={row.offerId ? 'Open this case’s history' : 'No offer on record — this payout predates case fees'}
                    onClick={() => void openLog(row)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void openLog(row)
                    }}
                  >
                    <td className="px-4 py-2">
                      <Link to={`/cases/${row.caseId}`} onClick={(e) => e.stopPropagation()} className="underline-offset-2 hover:underline">
                        {row.caseCode ?? 'Case'}
                      </Link>
                    </td>
                    <td className="px-4 py-2">{row.expertName ?? '—'}</td>
                    <td className="font-num px-4 py-2 text-right tabular-nums">
                      {row.amount === null || !row.currency ? (
                        <span style={{ color: 'var(--status-amber)' }}>not set</span>
                      ) : (
                        formatPayout(row.amount, row.currency)
                      )}
                    </td>
                    <td className="px-4 py-2"><StatusChip status={row.status} /></td>
                    <td className="px-4 py-2">
                      {row.feeSetByName ?? (row.feeSetAt ? 'Standard fee' : '—')}
                      {row.feeSetAt && (
                        <span className="block text-xs" style={{ color: 'var(--text-muted)' }}>{day(row.feeSetAt)}</span>
                      )}
                    </td>
                    <td className="font-num px-4 py-2 tabular-nums">{day(row.dueDate)}</td>
                    <td className="font-num px-4 py-2 tabular-nums">{day(row.sentAt)}</td>
                    <td className="font-num px-4 py-2 tabular-nums">{day(row.confirmedAt)}</td>
                    <td className="px-4 py-2 text-center">
                      {row.done ? (
                        <span aria-label="Done" style={{ color: 'var(--status-green)' }}>✓</span>
                      ) : (
                        <span aria-label="Not done" style={{ color: 'var(--text-muted)' }}>–</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {open && (
        <OfferLogPanel
          title={`${open.row.caseCode ?? 'Case'} — ${open.row.expertName ?? 'expert'}`}
          entries={open.entries}
          failure={open.failure}
          onClose={closeLog}
        />
      )}
    </div>
  )
}
