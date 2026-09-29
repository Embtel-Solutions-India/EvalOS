import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { formatPayout } from '../../lib/money'
import { useFilters } from '../shell/filtersContext'
import { EXPERT_PAYOUTS_PATH } from '../shell/navigation'
import { fetchExpertTotals } from './registerApi'
import { day, type ExpertTotals } from './registerRules'

/**
 * Who is owed how much (Unit 65): one row per expert and currency, committed through paid.
 * Sortable by any money column; a row opens that expert's payout page, and "Cases" opens the
 * register narrowed to them.
 */

type SortKey = 'expertName' | 'committed' | 'pending' | 'processing' | 'paid' | 'oldestPendingDue'

const COLUMNS: { key: SortKey; label: string; money: boolean }[] = [
  { key: 'expertName', label: 'Expert', money: false },
  { key: 'committed', label: 'Committed', money: true },
  { key: 'pending', label: 'Pending', money: true },
  { key: 'processing', label: 'Processing', money: true },
  { key: 'paid', label: 'Paid', money: true },
  { key: 'oldestPendingDue', label: 'Oldest pending', money: false },
]

export default function ExpertBalances() {
  const navigate = useNavigate()
  const { activeBrandId } = useFilters()
  const [rows, setRows] = useState<ExpertTotals[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'pending', dir: -1 })

  useEffect(() => {
    const controller = new AbortController()
    setRows(null)
    setFailure(null)
    fetchExpertTotals(activeBrandId, controller.signal)
      .then(setRows)
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setFailure(error instanceof Error ? error.message : 'Could not load the experts')
      })
    return () => controller.abort()
  }, [activeBrandId])

  const sorted = useMemo(() => {
    if (!rows) return null
    return [...rows].sort((a, b) => {
      const x = a[sort.key] ?? ''
      const y = b[sort.key] ?? ''
      return (x < y ? -1 : x > y ? 1 : 0) * sort.dir
    })
  }, [rows, sort])

  function sortBy(key: SortKey) {
    setSort((s) => (s.key === key ? { key, dir: s.dir === 1 ? -1 : 1 } : { key, dir: key === 'expertName' ? 1 : -1 }))
  }

  return (
    <div className="flex flex-col gap-5">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Experts</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          What each expert has been promised, is owed, has been sent and has confirmed
        </p>
      </header>

      <section className="rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
        {failure && <p className="px-4 py-3 text-sm" style={{ color: 'var(--status-red)' }}>{failure}</p>}
        {!failure && !sorted && <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>}
        {sorted?.length === 0 && (
          <p className="px-4 py-3 text-sm" style={{ color: 'var(--text-muted)' }}>
            No expert has a priced case yet.
          </p>
        )}
        {sorted && sorted.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                  {COLUMNS.map((c) => (
                    <th
                      key={c.key}
                      className={`px-4 py-2 font-medium ${c.money ? 'text-right' : ''}`}
                      aria-sort={sort.key === c.key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}
                    >
                      <button type="button" className="hover:underline" onClick={() => sortBy(c.key)}>
                        {c.label}
                        {sort.key === c.key && <span aria-hidden> {sort.dir === 1 ? '↑' : '↓'}</span>}
                      </button>
                    </th>
                  ))}
                  <th className="px-4 py-2"><span className="sr-only">Links</span></th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((row) => {
                  const money = (value: number) => (row.currency ? formatPayout(value, row.currency) : String(value))
                  return (
                    <tr
                      key={`${row.expertId}-${row.currency}`}
                      className="cursor-pointer border-t hover:bg-[var(--bg-surface)]"
                      style={{ borderColor: 'var(--border-default)' }}
                      onClick={() => navigate(EXPERT_PAYOUTS_PATH.replace(':expertId', row.expertId))}
                    >
                      <td className="px-4 py-2 font-medium">{row.expertName ?? 'Unnamed expert'}</td>
                      <td className="font-num px-4 py-2 text-right tabular-nums">{money(row.committed)}</td>
                      <td
                        className="font-num px-4 py-2 text-right tabular-nums"
                        style={row.pending > 0 ? { color: 'var(--status-amber)' } : undefined}
                      >
                        {money(row.pending)}
                      </td>
                      <td className="font-num px-4 py-2 text-right tabular-nums">{money(row.processing)}</td>
                      <td className="font-num px-4 py-2 text-right tabular-nums">{money(row.paid)}</td>
                      <td className="font-num px-4 py-2 tabular-nums">{day(row.oldestPendingDue)}</td>
                      <td className="px-4 py-2 text-right">
                        <Link
                          to={`/payouts/cases?expertId=${row.expertId}`}
                          onClick={(e) => e.stopPropagation()}
                          className="text-xs underline"
                        >
                          Cases
                        </Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
