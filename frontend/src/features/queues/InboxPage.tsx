import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchBoard } from '../board/boardApi'
import type { BoardCard, BoardData } from '../board/boardRules'
import { useFilters } from '../shell/filtersContext'
import { INBOX_VIEWS, inboxQueue, isInboxView, riskColor, riskLabel } from './queueRules'
import AssignPopover from './AssignPopover'

/**
 * The cases inbox: the queue a Project Manager *works*, as opposed to the board they *watch*.
 *
 * **This is the front door for incoming work.** A case created by Handoff A arrives paid and in
 * the pool, and it surfaces here under *Unassigned* — the PM takes it, then staffs the
 * coordinator and the case manager from the case. The GM's board no longer carries a pool lane
 * and the GM no longer carries this screen: one queue, worked by the person whose job it is.
 *
 * A PM can see a pooled case at all because `CaseRepository.SCOPE` marks unteamed rows visible
 * to the TEAM tier. Before that this preset filtered a set that was always empty here.
 *
 * **The deadline view is a preset here, not a route.** Overdue / today / this week / later are
 * questions about the same queue, and giving each a URL would be four screens that share every
 * column.
 */
export default function InboxPage() {
  const { dateRange, activeBrandId } = useFilters()
  const [params, setParams] = useSearchParams()
  const [data, setData] = useState<BoardData | null>(null)
  const [error, setError] = useState<string | null>(null)

  const raw = params.get('view')
  const view = isInboxView(raw) ? raw : 'all'

  const load = () => {
    const controller = new AbortController()
    setError(null)
    // No dueBefore: the presets below answer the date question, and passing the shell's filter
    // as well would silently intersect two date windows and hide work from "overdue".
    fetchBoard(null, activeBrandId, controller.signal)
      .then(setData)
      .catch((cause: Error) => {
        if (!controller.signal.aborted) setError(cause.message)
      })
    return () => controller.abort()
  }

  useEffect(load, [dateRange, activeBrandId])

  const rows = data ? inboxQueue(data, view) : []

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Cases inbox</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {data ? `${rows.length} of ${inboxQueue(data, 'all').length}` : ''}
        </p>
      </header>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {INBOX_VIEWS.map((entry) => {
          const active = entry.view === view
          return (
            <button
              key={entry.view}
              type="button"
              aria-pressed={active}
              onClick={() => setParams(entry.view === 'all' ? {} : { view: entry.view })}
              className="h-9 px-3 text-sm"
              style={{
                borderRadius: 'var(--radius-md)',
                background: active ? 'var(--accent-soft)' : 'var(--bg-surface)',
                border: `1px solid ${active ? 'var(--accent-primary)' : 'var(--border-default)'}`,
                color: active ? 'var(--accent-primary)' : 'var(--text-primary)',
                fontWeight: active ? 500 : 400,
              }}
            >
              {entry.label}
            </button>
          )
        })}
      </div>

      {error && (
        <p className="mt-4 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      {data === null && !error && (
        <div className="mt-4 h-40 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
      )}

      {data && rows.length === 0 && (
        <p
          className="mt-4 rounded-lg border p-6 text-sm"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-default)',
            color: 'var(--text-muted)',
          }}
        >
          {emptyCopy(view)}
        </p>
      )}

      {data && rows.length > 0 && (
        <div
          className="scroll-slim mt-4 overflow-x-auto rounded-lg border"
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <Th>Case</Th>
                <Th>Client</Th>
                <Th>Service</Th>
                <Th>Deadline</Th>
                <Th>Stage</Th>
                {/* Not "Case manager": the cell is whichever staffing step the row is up to. */}
                <Th>Assignment</Th>
                <Th> </Th>
              </tr>
            </thead>
            <tbody>
              {rows.map((card) => (
                <Row key={card.id} card={card} onAssigned={load} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/** Operational copy, never "No data" — an empty queue is a statement about how the work is going. */
function emptyCopy(view: string): string {
  switch (view) {
    case 'unassigned':
      return 'Nothing is waiting to be taken.'
    case 'at-risk':
      return 'No case is inside 48 business hours of its deadline.'
    case 'overdue':
      return 'Nothing has missed its promised date.'
    case 'today':
      return 'Nothing is due today.'
    case 'week':
      return 'Nothing is due in the next seven days.'
    case 'future':
      return 'Every open case is due within the week.'
    default:
      return 'There are no open cases.'
  }
}

function Th({ children }: { children: React.ReactNode }) {
  return (
    <th
      className="px-3 py-2 text-left text-xs font-medium tracking-wide uppercase"
      style={{ borderBottom: '1px solid var(--border-default)' }}
    >
      {children}
    </th>
  )
}

function Row({ card, onAssigned }: { card: BoardCard; onAssigned: () => void }) {
  return (
    <tr style={{ borderBottom: '1px solid var(--border-default)' }}>
      <td className="px-3 py-2">
        <Link to={`/cases/${card.id}`} className="font-mono text-xs font-medium">
          {card.caseCode}
        </Link>
      </td>
      <td className="px-3 py-2">{card.clientName ?? 'Unnamed contact'}</td>
      <td className="px-3 py-2" style={{ color: 'var(--text-muted)' }}>
        {readable(card.serviceType)}
      </td>
      <td className="px-3 py-2">
        <span className="inline-flex items-center gap-1.5">
          <span
            className="h-2 w-2 shrink-0 rounded-full"
            style={{ background: riskColor(card.deadlineRisk) }}
            aria-hidden
          />
          <span className="font-num tabular-nums">
            {card.deadline ? new Date(card.deadline).toLocaleDateString() : 'not set'}
          </span>
          {/* Colour is never the only signal; the band is spelled out for a screen reader. */}
          <span className="sr-only">{riskLabel(card.deadlineRisk)}</span>
        </span>
      </td>
      <td className="px-3 py-2" style={{ color: 'var(--text-muted)' }}>
        {readable(card.currentStage)}
      </td>
      <td className="px-3 py-2">
        <AssignPopover card={card} onAssigned={onAssigned} />
      </td>
      <td className="px-3 py-2 text-right">
        <Link to={`/cases/${card.id}`} className="text-sm font-medium" style={{ color: 'var(--accent-primary)' }}>
          Open
        </Link>
      </td>
    </tr>
  )
}

function readable(value: string | null): string {
  if (!value) return '—'
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
