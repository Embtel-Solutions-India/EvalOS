import { Link } from 'react-router-dom'
import CaseCard from '../board/CaseCard'
import { cardDate, type BoardCard, type BoardData, type SlaStatus } from '../board/boardRules'
import { stageColor } from '../board/stageColors'
import { useBoard } from '../board/useBoard'
import { STAGE_ORDER, STAGE_SHORT } from '../case/caseProgress'
import { useFilters } from '../shell/filtersContext'
import ViewToggle from './ViewToggle'
import { useViewMode } from './useViewMode'

const SLA: Record<SlaStatus, { label: string; color: string }> = {
  ON_TRACK: { label: 'On track', color: 'var(--status-green)' },
  AT_RISK: { label: 'At risk', color: 'var(--status-amber)' },
  OVERDUE: { label: 'Overdue', color: 'var(--status-red)' },
}

/**
 * The Case Manager's own cases, as cards or as a list. The stage board is the Production board
 * entry; the server narrows both to the caller's assignments, so this is the same cases read two
 * ways rather than a second scope.
 */
export default function MyCasesPage() {
  const { activeBrandId } = useFilters()
  const { data, error } = useBoard(null, activeBrandId)
  const [mode, setMode] = useViewMode('my-cases')

  const rows = data ? allCases(data) : []

  return (
    <section>
      <header className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">My cases</h1>
          <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {data ? `${rows.length} ${rows.length === 1 ? 'case' : 'cases'}, soonest deadline first` : ''}
          </p>
        </div>
        <ViewToggle mode={mode} onChange={setMode} />
      </header>

      {error && (
        <p className="mt-4 text-sm font-medium" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
      {data === null && !error && (
        <div className="mt-4 h-40 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
      )}
      {data && rows.length === 0 && (
        <p
          className="mt-4 rounded-lg border p-6 text-sm"
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)', color: 'var(--text-muted)' }}
        >
          You have no open cases.
        </p>
      )}

      {data && rows.length > 0 && mode === 'cards' && (
        <ul className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {rows.map((card) => (
            <li key={card.id} style={{ '--stage': stageColor(STAGE_ORDER.indexOf(card.currentStage)) } as React.CSSProperties}>
              <CaseCard card={card} mine />
            </li>
          ))}
        </ul>
      )}

      {data && rows.length > 0 && mode === 'list' && (
        <div
          className="mt-4 overflow-x-auto rounded-lg border"
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        >
          <table className="w-full text-left text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <th className="px-3 py-2 font-normal">Case</th>
                <th className="px-3 py-2 font-normal">Client</th>
                <th className="px-3 py-2 font-normal">Service</th>
                <th className="px-3 py-2 font-normal">Stage</th>
                <th className="px-3 py-2 text-right font-normal">Deadline</th>
                <th className="px-3 py-2 font-normal">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((card) => (
                <tr key={card.id} style={{ borderTop: '1px solid var(--border-default)' }}>
                  <td className="px-3 py-2">
                    <Link to={`/cases/${card.id}`} className="font-mono text-xs font-medium">
                      {card.caseCode}
                    </Link>
                  </td>
                  <td className="px-3 py-2">{card.clientName ?? 'Unnamed contact'}</td>
                  <td className="px-3 py-2">{readable(card.serviceType)}</td>
                  <td className="px-3 py-2">{STAGE_SHORT[card.currentStage]}</td>
                  <td className="font-num px-3 py-2 text-right tabular-nums">
                    {card.deadline ? cardDate(card.deadline) : '—'}
                  </td>
                  <td className="px-3 py-2">
                    {card.slaStatus ? (
                      <span className="font-medium" style={{ color: SLA[card.slaStatus].color }}>
                        {SLA[card.slaStatus].label}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/** Every case the board returned, held ones included, soonest deadline first and undated last. */
function allCases(data: BoardData): BoardCard[] {
  const seen = new Map<string, BoardCard>()
  for (const card of [...Object.values(data.stages).flat(), ...Object.values(data.exceptions).flat()]) {
    seen.set(card.id, card)
  }
  return [...seen.values()].sort((a, b) => {
    if (a.deadline === b.deadline) return 0
    if (a.deadline === null) return 1
    if (b.deadline === null) return -1
    return a.deadline.localeCompare(b.deadline)
  })
}

function readable(value: string | null): string {
  if (!value) return '—'
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
