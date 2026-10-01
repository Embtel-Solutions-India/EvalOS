import { useState } from 'react'
import { useBoard } from '../board/useBoard'
import { Link } from 'react-router-dom'
import type { BoardCard } from '../board/boardRules'
import AvailabilityBoard from '../experts/AvailabilityBoard'
import ExpertProfile from '../experts/ExpertProfile'
import { useFilters } from '../shell/filtersContext'
import RowActions from './RowActions'
import { awaitingExpert, expertSignOverdue, riskColor, riskLabel } from './queueRules'

/**
 * The Project Manager's expert assignment board: who is waiting for an expert, who is free to
 * take one, and whose signature has run past its budget.
 *
 * **Three questions on one screen because they are one decision.** `17-dashboards.md` asks for
 * cases waiting, expert availability, and overdue responses flagged red with a reassign prompt —
 * and a PM answering the third needs the second in front of them, or the prompt sends them to
 * another screen to find out who is free.
 *
 * **No new endpoint.** Both case lists are selections over `/api/cases/board` (see `queueRules`),
 * the availability half is Unit 11's shipped `AvailabilityBoard`, and the shortlist inside the
 * reassign dialog is Unit 12's. This screen is a composition, which is why it is a page and not a
 * service: a third read of the same rows would be a third scope predicate to keep in step.
 */
export default function ExpertAssignmentPage() {
  const { activeBrandId } = useFilters()
  // Unit 70a: the cached board read, shared with the board and the other queues.
  const { data, error, load } = useBoard(null, activeBrandId)
  // No `dueBefore`: this screen's question is "who has no expert", which a deadline window
  // would silently narrow — a case with no date at all would drop out of a list whose whole
  // purpose is that nobody is working it.
  const [openExpert, setOpenExpert] = useState<string | null>(null)



  const waiting = data ? awaitingExpert(data) : []
  const overdue = data ? expertSignOverdue(data) : []

  return (
    <section className="flex flex-col gap-6">
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Expert assignment</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {data ? `${waiting.length} waiting · ${overdue.length} overdue` : ''}
        </p>
      </header>

      {error && (
        <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}


      {data === null && !error && (
        <div className="h-40 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
      )}

      {data && (
        <>
          {/*
            Overdue first, above the larger list. It is the shorter of the two and the only one
            with a clock already run out — a queue ordered by volume would put the urgent table
            below the fold on the 1366px reference width.
          */}
          <Panel
            title="Responses overdue"
            // The threshold is named in words because the colour cannot carry it: this is the
            // 24-hour EXPERT_SIGN budget, on business hours, not 24 hours of wall clock.
            note="Past the 24-hour expert-sign budget. Mark the expert overdue to open a reassignment."
            empty="Every expert signature is inside its budget."
            rows={overdue}
            overdue
          />

          <Panel
            title="Waiting for an expert"
            note="In Expert Assignment, or thrown back by a decline or a timeout."
            empty="Every open case has an expert on it."
            rows={waiting}
          />

          <div>
            <h2 className="text-sm font-semibold tracking-tight">Expert availability</h2>
            <p className="mt-0.5 mb-3 text-xs" style={{ color: 'var(--text-muted)' }}>
              Who can take the cases above, with each expert&apos;s live load beside the claim.
            </p>
            <AvailabilityBoard onOpen={setOpenExpert} />
          </div>
        </>
      )}


      {openExpert && (
        // `mayWrite` is false and not derived: this screen is PM-only and a PM is never on
        // `ExpertController.ROSTER_WRITE`. A live Save here would be a 403 wearing a button.
        <ExpertProfile
          expertId={openExpert}
          mayWrite={false}
          onSaved={() => void load()}
          onClose={() => setOpenExpert(null)}
        />
      )}
    </section>
  )
}


function Panel({
  title,
  note,
  empty,
  rows,
  overdue = false,
}: {
  title: string
  note: string
  empty: string
  rows: BoardCard[]
  overdue?: boolean
}) {
  return (
    <div>
      <h2 className="text-sm font-semibold tracking-tight">
        {title}{' '}
        <span className="font-num tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {rows.length}
        </span>
      </h2>
      <p className="mt-0.5 mb-3 text-xs" style={{ color: 'var(--text-muted)' }}>
        {note}
      </p>

      {rows.length === 0 ?
        <p
          className="rounded-lg border p-6 text-sm"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-default)',
            color: 'var(--text-muted)',
          }}
        >
          {empty}
        </p>
      : <div
          className="scroll-slim overflow-x-auto rounded-lg border"
          style={{
            background: 'var(--bg-surface)',
            // The red is on the container, not on each cell: the brief asks for the overdue
            // *set* flagged, and a red rule per row reads as eight separate alarms.
            borderColor: overdue ? 'var(--status-red)' : 'var(--border-default)',
          }}
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <Th>Case</Th>
                <Th>Client</Th>
                <Th>Deadline</Th>
                <Th>State</Th>
                <Th> </Th>
              </tr>
            </thead>
            <tbody>
              {rows.map((card) => {
                return (
                  <tr key={card.id} style={{ borderBottom: '1px solid var(--border-default)' }}>
                    <td className="px-3 py-2">
                      <Link to={`/cases/${card.id}`} className="font-mono text-xs font-medium">
                        {card.caseCode}
                      </Link>
                    </td>
                    <td className="px-3 py-2">{card.clientName ?? 'Unnamed contact'}</td>
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
                        <span className="sr-only">{riskLabel(card.deadlineRisk)}</span>
                      </span>
                    </td>
                    <td
                      className="px-3 py-2"
                      style={{ color: overdue ? 'var(--status-red)' : 'var(--text-muted)' }}
                    >
                      {stateOf(card, overdue)}
                    </td>
                    <td className="px-3 py-2 text-right">
                      {/* Unit 67: every action this role has on this case, a first expert included. */}
                      <span className="inline-flex items-center gap-3">
                        <RowActions card={card} />
                        <Link
                          to={`/cases/${card.id}`}
                          className="text-sm font-medium"
                          style={{ color: 'var(--accent-primary)' }}
                        >
                          Open
                        </Link>
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      }
    </div>
  )
}

/** Words for what the row is, so the red border is never the only thing carrying the state. */
function stateOf(card: BoardCard, overdue: boolean): string {
  if (overdue) return 'Signature overdue'
  if (card.exceptionState === 'EXPERT_DECLINED_REMATCHING') return 'Expert gone — rematch'
  return 'No expert assigned'
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
