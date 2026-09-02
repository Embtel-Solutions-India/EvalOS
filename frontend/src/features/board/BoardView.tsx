import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  DEADLINE_WINDOWS,
  DEFAULT_DEADLINE_WINDOW,
  type DeadlineWindow,
} from './deadlineWindow'
import { useLocation } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { useFilters } from '../shell/filtersContext'
import { itemFor } from '../shell/navigation'
import CaseCard from './CaseCard'
import PoolLane from './PoolLane'
import QuickActionDialog from './QuickActionDialog'
import StageColumn from './StageColumn'
import { fetchBoard, performAction } from './boardApi'
import {
  EXCEPTION_LANES,
  actionsFor,
  allInsideSla,
  columnsFor,
  dueBeforeFor,
  slaMix,
  type BoardCard,
  type BoardData,
  type QuickAction,
  type ServiceType,
  cardsInColumn,
} from './boardRules'

/**
 * The production board. Mounted at `/board` for the roles that operate it and at
 * `/my-cases` for a Case Manager, whose docket is the same board seen through their own
 * assignment — the server decides which cases that is, so it is one screen, not two.
 *
 * The shell's brand switcher and date filter are read here rather than duplicated: this is
 * the first unit that actually sends them anywhere, which is what Unit 07 held them for.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; data: BoardData }
  | { status: 'failed'; message: string }

/**
 * Who gets the pool lane.
 *
 * **The GM left it in Unit 23**, when the front door for incoming work became the PM inbox.
 * The board is the screen the GM *watches*; the pool is a queue somebody *works*, and putting
 * one on the other is what made an oversight screen also a to-do list.
 *
 * **The PM is not here either, and for a different reason — they have a better version of it.**
 * A pooled case now appears in their inbox under *Unassigned*, where taking it and staffing it
 * are one flow. A lane duplicating that would be the `/cases`-beside-`/board` split this repo
 * has deleted twice. (The PM *can* read pooled cases as of Unit 23 — `CaseRepository.SCOPE`
 * sets `unteamedVisible` — so this is a choice about screens, no longer a scope limit.)
 */
const SEES_POOL = ['BRAND_MANAGER'] as const

export default function BoardView() {
  const me = useMe()
  const { pathname } = useLocation()
  // **No `dateRange`.** The shell's period is backwards-looking and this screen's filter is
  // forwards — see `deadlineWindow.ts`. The board owns its own, so a dashboard period can no
  // longer silently widen the board's deadline horizon.
  const { activeBrandId } = useFilters()

  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [pending, setPending] = useState<{ card: BoardCard; action: QuickAction } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  // Board-local filters, held here rather than in the shell: they are this screen's, and
  // no other screen would read them.
  const [ownerFilter, setOwnerFilter] = useState<'all' | 'mine'>('all')
  const [serviceFilter, setServiceFilter] = useState<ServiceType | 'all'>('all')
  const [urgentOnly, setUrgentOnly] = useState(false)
  // Local state like its three neighbours above, not a URL parameter: every other filter on this
  // screen is held here, and one linkable filter beside three that are not is the inconsistency
  // somebody has to explain later.
  const [deadlineWindow, setDeadlineWindow] = useState<DeadlineWindow>(DEFAULT_DEADLINE_WINDOW)

  const dueBefore = dueBeforeFor(deadlineWindow)

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState({ status: 'ready', data: await fetchBoard(dueBefore, activeBrandId, signal) })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load the board',
        })
      }
    },
    [dueBefore, activeBrandId],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  /**
   * Runs a transition, then re-reads the board.
   *
   * Deliberately not an optimistic move: the server decides the target stage from its own
   * transition table, so guessing it here means guessing where the card lands and being
   * wrong on every guard (unpaid, checklist incomplete, wrong exception state). A refused
   * action shows its reason above the pool and nothing moves.
   *
   * One error, not a map keyed by case: the pool is the only thing on this screen that acts
   * now that the cards are read-only, and it can only be doing one assignment at a time.
   */
  const run = useCallback(
    async (card: BoardCard, action: QuickAction, values: Record<string, string>) => {
      setPending(null)
      setActionError(null)
      try {
        await performAction(card.id, action, values)
        await load()
      } catch (error: unknown) {
        setActionError(error instanceof Error ? error.message : 'That action was refused')
      }
    },
    [load],
  )

  const onAction = useCallback(
    (card: BoardCard, action: QuickAction) => {
      // Only actions that collect something need the dialog; the rest fire directly.
      if (action.fields?.length) setPending({ card, action })
      else void run(card, action, {})
    },
    [run],
  )

  /** The viewer holds this case in one of its three assignable slots. */
  const isMine = useCallback(
    (card: BoardCard) =>
      card.assignedPm === me.id || card.assignedCm === me.id || card.assignedCoordinator === me.id,
    [me.id],
  )

  const visible = useCallback(
    (cards: readonly BoardCard[]) =>
      cards.filter((card) => {
        if (serviceFilter !== 'all' && card.serviceType !== serviceFilter) return false
        if (urgentOnly && card.slaStatus !== 'AT_RISK' && card.slaStatus !== 'OVERDUE') return false
        if (ownerFilter === 'mine') return isMine(card)
        return true
      }),
    [isMine, ownerFilter, serviceFilter, urgentOnly],
  )

  const pool = useMemo(() => {
    if (state.status !== 'ready') return []
    // Lanes as well as columns: the server puts a case holding an exception state in its lane
    // *instead of* its stage column, and an unassigned case awaiting client documents is
    // exactly the kind that gets held. Reading only `stages` understated the one number this
    // lane exists to give.
    return [...Object.values(state.data.stages), ...Object.values(state.data.exceptions)]
      .flat()
      .filter((card) => card.poolStatus === 'IN_POOL')
  }, [state])

  if (state.status === 'loading') {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading the board…
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

  const board = state.data
  const columns = columnsFor(me.role).map((column) => ({
    ...column,
    // A column can hold two stages (Unit 31), so its cards are the union.
    cards: visible(cardsInColumn(board, column.stages)),
  }))
  const lanes = EXCEPTION_LANES.map((lane) => ({
    ...lane,
    cards: visible(board.exceptions[lane.state] ?? []),
  }))

  // The headline counts only what is drawn — the role's own columns plus the lanes — so the
  // number always matches what the reader can count on screen.
  const inView = [...columns, ...lanes].flatMap((group) => group.cards)
  const mix = slaMix(inView)

  return (
    <div className="flex min-h-0 flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2">
        <div>
          {/*
            The owner half is drawn only when the filter is actually narrowing. It used to read
            "everyone" whenever the filter was off, which is false for a Case Manager: the server
            has already scoped their board to their own assignments, so "everyone" *is* them. An
            eyebrow that overstates the scope of what you are looking at is worse than a shorter
            one, and the cards carry a "Yours" badge regardless.
          */}
          <p
            className="text-[11px] font-semibold tracking-[0.08em] uppercase"
            style={{ color: 'var(--text-muted)' }}
          >
            {me.role === 'GM' ? (activeBrandId ? 'One brand' : 'All brands') : 'Your brand'}
            {ownerFilter === 'mine' && ' · assigned to you'}
          </p>
          {/* Titled from the nav table, so `/my-cases` is headed "My cases" rather than telling a
              Case Manager they are looking at a screen whose name is in somebody else's nav. */}
          <h1 className="text-2xl font-semibold tracking-tight">
            {itemFor(pathname)?.label ?? 'Production board'}
          </h1>
          {/* The thesis of the screen: not how many cases exist, but how many are in trouble. */}
          <p className="font-num mt-0.5 flex flex-wrap items-baseline gap-x-2 text-sm tabular-nums">
            <span style={{ color: 'var(--text-muted)' }}>
              {inView.length} {inView.length === 1 ? 'case' : 'cases'} in view
            </span>
            {mix.overdue > 0 && (
              <>
                <Dot />
                <span className="font-semibold" style={{ color: 'var(--status-red)' }}>
                  {mix.overdue} overdue
                </span>
              </>
            )}
            {mix.atRisk > 0 && (
              <>
                <Dot />
                <span className="font-semibold" style={{ color: 'var(--status-amber)' }}>
                  {mix.atRisk} at risk
                </span>
              </>
            )}
            {/*
              "All inside SLA" requires a clock on every case — not merely no red and no amber.
              `slaMix` keeps `unknown` a separate band precisely because a case with no clock
              running (closed, or holding an exception state) is not a healthy one, and the rail
              above draws it grey rather than green. A headline that called those cases green
              would contradict the instrument directly underneath it, on the same data.
            */}
            {allInsideSla(mix) && (
              <>
                <Dot />
                <span className="font-semibold" style={{ color: 'var(--status-green)' }}>
                  all inside SLA
                </span>
              </>
            )}
            {mix.unknown > 0 && (
              <>
                <Dot />
                <span style={{ color: 'var(--text-muted)' }}>{mix.unknown} with no clock running</span>
              </>
            )}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* First in the row because it is the only one of these that refetches: the other three
              filter cards already in hand, this one changes what the server is asked for. */}
          <Select
            label="Due within"
            value={deadlineWindow}
            onChange={(value) => setDeadlineWindow(value as DeadlineWindow)}
            options={DEADLINE_WINDOWS.map((option) => ({ value: option.value, label: option.label }))}
          />
          <Select
            label="Owner"
            value={ownerFilter}
            onChange={(value) => setOwnerFilter(value as 'all' | 'mine')}
            options={[
              { value: 'all', label: 'Anyone' },
              { value: 'mine', label: 'Assigned to me' },
            ]}
          />
          <Select
            label="Service"
            value={serviceFilter}
            onChange={(value) => setServiceFilter(value as ServiceType | 'all')}
            options={[
              { value: 'all', label: 'All services' },
              { value: 'CREDENTIAL_EVALUATION', label: 'Credential evaluation' },
              { value: 'EXPERT_OPINION_LETTER', label: 'Expert opinion letter' },
              { value: 'PERM', label: 'PERM' },
              { value: 'RFE_RESPONSE', label: 'RFE response' },
              { value: 'TRANSLATION', label: 'Translation' },
            ]}
          />
          <label
            className="flex h-9 cursor-pointer items-center gap-2 rounded-xl border px-3 text-sm"
            style={{
              borderColor: urgentOnly ? 'var(--status-amber)' : 'var(--border-default)',
              background: urgentOnly ? 'var(--status-amber-bg)' : 'var(--bg-surface)',
            }}
          >
            <input type="checkbox" checked={urgentOnly} onChange={(e) => setUrgentOnly(e.target.checked)} />
            At risk or overdue
          </label>
        </div>
      </header>

      {actionError && (
        <p
          className="px-1 text-sm font-medium"
          role="alert"
          style={{ color: 'var(--status-red)' }}
        >
          {actionError} — nothing was changed.
        </p>
      )}

      {(SEES_POOL as readonly string[]).includes(me.role) && (
        <PoolLane
          cards={visible(pool)}
          onAssign={(card) => {
            const assign = actionsFor(card, me.role).find((action) => action.path === 'assign-pm')
            if (assign) onAction(card, assign)
          }}
        />
      )}

      <div className="scroll-slim flex gap-3 overflow-x-auto pb-2">
        {columns.map(({ stages, label, access, step, cards }) => (
          <StageColumn
            key={stages.join('+')}
            label={label}
            step={step}
            count={cards.length}
            mix={slaMix(cards)}
            readOnly={access === 'status'}
          >
            {cards.map((card) => (
              <CaseCard key={card.id} card={card} mine={isMine(card)} />
            ))}
          </StageColumn>
        ))}
      </div>

      {/* Closed by default now that the board fits one screen: an open lane row pushed the
          stage columns up out of view on a laptop, which is the opposite of the point. */}
      <details className="pt-1">
        <summary className="cursor-pointer text-base font-semibold tracking-tight">
          Off the pipeline
          <span className="font-num ml-2 font-normal tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {lanes.reduce((total, lane) => total + lane.cards.length, 0)} held
          </span>
        </summary>
        <div className="scroll-slim mt-3 flex gap-3 overflow-x-auto pb-2">
          {lanes.map(({ state: lane, label, cards }) => (
            <StageColumn key={lane} label={label} count={cards.length} mix={slaMix(cards)} tone="lane">
              {cards.map((card) => (
                <CaseCard key={card.id} card={card} mine={isMine(card)} />
              ))}
            </StageColumn>
          ))}
        </div>
      </details>

      {pending && (
        <QuickActionDialog
          action={pending.action}
          caseId={pending.card.id}
          caseCode={pending.card.caseCode}
          onCancel={() => setPending(null)}
          onConfirm={(values) => void run(pending.card, pending.action, values)}
        />
      )}
    </div>
  )
}

function Dot() {
  return (
    <span aria-hidden style={{ color: 'var(--border-default)' }}>
      ·
    </span>
  )
}

function Select({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  options: readonly { value: string; label: string }[]
}) {
  return (
    <label className="flex items-center gap-1.5 text-sm">
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="h-9 rounded-xl px-3 text-sm"
        style={{
          background: 'var(--bg-surface)',
          border: '1px solid var(--border-default)',
          boxShadow: 'var(--shadow-card)',
        }}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}
