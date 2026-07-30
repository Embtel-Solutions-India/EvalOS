import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { useFilters } from '../shell/filtersContext'
import CaseChecklist from './CaseChecklist'
import { fetchChecklistBoard } from './checklistApi'
import {
  AGING_TOKEN,
  agingBand,
  agingHours,
  agingLabel,
  completionPercent,
  splitByChase,
  type ChecklistCard,
} from './checklistRules'

/**
 * The Coordinator's stage: every case still waiting on the client, longest wait first, with
 * the pending-docs queue lifted to the top.
 *
 * A list rather than a Kanban, because there is only one column — these cases are all in
 * Document Collection, and what varies between them is how complete and how old they are.
 * Both of those are numbers, and numbers read better in rows than in cards.
 *
 * The screen is deliberately not a second production board: it does not offer the eight
 * transitions a card offers, only the two that belong to this stage. Everything else about a
 * case is one click away on its detail page.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; cards: ChecklistCard[] }
  | { status: 'failed'; message: string }

export default function ChecklistBoard() {
  const me = useMe()
  const { activeBrandId } = useFilters()
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [openCaseId, setOpenCaseId] = useState<string | null>(null)

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState({ status: 'ready', cards: await fetchChecklistBoard(activeBrandId, signal) })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load the checklist board',
        })
      }
    },
    [activeBrandId],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  /** A completed case leaves this stage, so it has to leave this board too. */
  const onCaseLeftTheStage = useCallback(() => {
    setOpenCaseId(null)
    void load()
  }, [load])

  /**
   * A chase retires the case from the pending-docs queue, which is the whole point of the
   * 24-hour condition in `needsChase` — so the one card is patched with the server's timestamp
   * rather than the board being re-read. Patched, not reloaded: a reload would re-sort every
   * row underneath the Coordinator mid-triage.
   */
  const onChased = useCallback((caseId: string, lastChasedAt: string | null) => {
    setState((current) =>
      current.status === 'ready'
        ? {
            ...current,
            cards: current.cards.map((card) =>
              card.id === caseId ? { ...card, lastChasedAt } : card,
            ),
          }
        : current,
    )
  }, [])

  if (state.status === 'loading') {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading the checklist board…
      </p>
    )
  }

  if (state.status === 'failed') {
    return (
      <div
        className="rounded-lg border p-4"
        style={{ background: 'var(--status-red-bg)', borderColor: 'var(--border-default)' }}
      >
        <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
          {state.message}
        </p>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing was changed. Try the read again — if it keeps failing, your session may have expired.
        </p>
        <button
          type="button"
          onClick={() => void load()}
          className="mt-3 rounded-md px-3 py-1.5 text-sm font-medium text-white"
          style={{ background: 'var(--accent-primary)' }}
        >
          Try again
        </button>
      </div>
    )
  }

  const { chase, rest } = splitByChase(state.cards)
  const outstanding = state.cards.filter((card) => !card.checklistSatisfied).length
  // Not "ready for the PM": docs-complete also wants the case paid and staffed, and this count
  // includes unpaid cases — which the row two lines down chips as "Unpaid" for that exact
  // reason. The header states what it can actually see.
  const documentsIn = state.cards.length - outstanding

  return (
    <div className="flex flex-col gap-5">
      <header>
        <p
          className="text-[11px] font-semibold tracking-[0.08em] uppercase"
          style={{ color: 'var(--text-muted)' }}
        >
          {me.role === 'GM' ? (activeBrandId ? 'One brand' : 'All brands') : 'Your brand'} · document collection
        </p>
        <h1 className="mt-1 text-xl font-semibold tracking-tight">Doc checklists</h1>
        {/* The thesis: not how many cases are collecting, but how many are stuck. */}
        <p className="font-num mt-1 flex flex-wrap items-baseline gap-x-2 text-sm tabular-nums">
          <span style={{ color: 'var(--text-muted)' }}>
            {state.cards.length} {state.cards.length === 1 ? 'case' : 'cases'} collecting documents
          </span>
          {chase.length > 0 && (
            <>
              <Dot />
              <span className="font-semibold" style={{ color: 'var(--status-amber)' }}>
                {chase.length} due a chase
              </span>
            </>
          )}
          {documentsIn > 0 && (
            <>
              <Dot />
              <span className="font-semibold" style={{ color: 'var(--status-green)' }}>
                {documentsIn} with all documents in
              </span>
            </>
          )}
        </p>
      </header>

      {state.cards.length === 0 && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing is waiting on a client right now.
        </p>
      )}

      {/*
        The pending-docs queue. Split rather than re-sorted, so each half keeps the server's
        longest-wait-first order and a case cannot jump the queue by being sorted twice on two
        different keys.
      */}
      {chase.length > 0 && (
        <Section
          heading="Due a chase"
          hint="Waiting more than 24 hours with nothing sent in the last 24."
          cards={chase}
          openCaseId={openCaseId}
          onToggle={setOpenCaseId}
          onChased={onChased}
          onCaseLeftTheStage={onCaseLeftTheStage}
        />
      )}
      {rest.length > 0 && (
        <Section
          heading={chase.length > 0 ? 'Everything else' : 'Collecting'}
          cards={rest}
          openCaseId={openCaseId}
          onToggle={setOpenCaseId}
          onChased={onChased}
          onCaseLeftTheStage={onCaseLeftTheStage}
        />
      )}
    </div>
  )
}

function Section({
  heading,
  hint,
  cards,
  openCaseId,
  onToggle,
  onChased,
  onCaseLeftTheStage,
}: {
  heading: string
  hint?: string
  cards: readonly ChecklistCard[]
  openCaseId: string | null
  onToggle: (caseId: string | null) => void
  onChased: (caseId: string, lastChasedAt: string | null) => void
  onCaseLeftTheStage: () => void
}) {
  return (
    <section>
      <h2 className="text-sm font-semibold tracking-tight">
        {heading}
        <span className="font-num ml-2 font-normal tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {cards.length}
        </span>
      </h2>
      {hint && (
        <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
          {hint}
        </p>
      )}
      <ul className="mt-2 flex flex-col gap-2">
        {cards.map((card) => (
          <Row
            key={card.id}
            card={card}
            open={openCaseId === card.id}
            onToggle={() => onToggle(openCaseId === card.id ? null : card.id)}
            onChased={onChased}
            onCaseLeftTheStage={onCaseLeftTheStage}
          />
        ))}
      </ul>
    </section>
  )
}

function Row({
  card,
  open,
  onToggle,
  onChased,
  onCaseLeftTheStage,
}: {
  card: ChecklistCard
  open: boolean
  onToggle: () => void
  onChased: (caseId: string, lastChasedAt: string | null) => void
  onCaseLeftTheStage: () => void
}) {
  const hours = agingHours(card.stageEnteredAt)
  const band = agingBand(hours)
  const percent = completionPercent(card.complete, card.total)
  const panelId = `checklist-panel-${card.id}`

  return (
    <li
      className="rounded-lg border"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 p-3">
        <div className="min-w-44 flex-1">
          {/* A real link, so middle-click and "open in new tab" work — the same reasoning as
              the board's cards. */}
          <Link
            to={`/cases/${card.id}`}
            className="font-mono text-[11px] underline-offset-2 hover:underline"
            style={{ color: 'var(--text-muted)' }}
          >
            {card.caseCode}
          </Link>
          <p className="text-sm leading-snug font-semibold">{card.clientName ?? 'Unnamed contact'}</p>
          <p
            className="mt-0.5 text-[10px] font-medium tracking-[0.06em] uppercase"
            style={{ color: 'var(--text-muted)' }}
          >
            {card.serviceType?.replaceAll('_', ' ') ?? 'service not set'}
          </p>
        </div>

        {/* Completeness as a bar and a fraction: the bar is scannable down a column, the
            fraction is what somebody reads out. */}
        <div className="w-32">
          <div
            className="h-1.5 overflow-hidden rounded-md"
            style={{ background: 'var(--bg-raised)' }}
            role="img"
            aria-label={`${card.complete} of ${card.total} documents in`}
          >
            <div
              className="h-full"
              style={{
                width: `${percent}%`,
                background: card.checklistSatisfied ? 'var(--status-green)' : 'var(--accent-primary)',
              }}
            />
          </div>
          <p className="font-num mt-1 text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {card.complete} / {card.total} documents
          </p>
        </div>

        <span
          className="font-num rounded-md px-1.5 py-0.5 text-[11px] font-semibold tabular-nums"
          style={{ color: AGING_TOKEN[band].fg, background: AGING_TOKEN[band].bg }}
          title="Wall-clock time in Document Collection"
        >
          {agingLabel(hours)} waiting
        </span>

        {/* Unpaid is worth showing here even though it does not block collecting: it is what
            docs-complete will refuse on, so seeing it now beats discovering it on the click. */}
        {!card.paid && <Chip>Unpaid</Chip>}
        {card.exceptionState !== 'NONE' && <Chip>{card.exceptionState.replaceAll('_', ' ').toLowerCase()}</Chip>}
        {card.checklistSatisfied && <Chip tone="green">All documents in</Chip>}

        <button
          type="button"
          onClick={onToggle}
          aria-expanded={open}
          aria-controls={panelId}
          className="rounded-md bg-(--bg-raised) px-2.5 py-1.5 text-sm font-medium text-(--accent-primary) transition-colors hover:bg-(--accent-primary) hover:text-white"
        >
          {open ? 'Hide checklist' : 'Open checklist'}
        </button>
      </div>

      {/* Mounted only when open, so the board makes one request per case the user asks for
          rather than one per row on load. */}
      {open && (
        <div id={panelId} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
          <CaseChecklist
            caseId={card.id}
            onChased={(lastChasedAt) => onChased(card.id, lastChasedAt)}
            onCaseLeftTheStage={onCaseLeftTheStage}
          />
        </div>
      )}
    </li>
  )
}

function Chip({ children, tone }: { children: React.ReactNode; tone?: 'green' }) {
  return (
    <span
      className="rounded-md px-1.5 py-0.5 text-[11px] font-medium"
      style={
        tone === 'green'
          ? { color: 'var(--status-green)', background: 'var(--status-green-bg)' }
          : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
      }
    >
      {children}
    </span>
  )
}

function Dot() {
  return (
    <span aria-hidden style={{ color: 'var(--border-default)' }}>
      ·
    </span>
  )
}
