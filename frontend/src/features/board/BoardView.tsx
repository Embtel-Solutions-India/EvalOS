import { useCallback, useEffect, useMemo, useState } from 'react'
import { useMe } from '../../lib/authContext'
import { useFilters } from '../shell/filtersContext'
import CaseCard from './CaseCard'
import PoolLane from './PoolLane'
import QuickActionDialog from './QuickActionDialog'
import StageColumn from './StageColumn'
import { fetchBoard, performAction } from './boardApi'
import {
  EXCEPTION_LANES,
  actionsFor,
  columnsFor,
  dueBeforeFor,
  type BoardCard,
  type BoardData,
  type QuickAction,
  type ServiceType,
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
 * The spec names the PM here too, but a PM cannot have a pool: `assign-pm` is what stamps
 * `team_id`, so before it runs the case has no team and a PM's TEAM scope never matches it —
 * and `assign-pm` is gated to GM / Brand Manager anyway. Listing them produced a lane that
 * was always empty and an "Assign PM" button that silently did nothing when clicked. The
 * pool is the commercial roles' queue.
 */
const SEES_POOL = ['GM', 'BRAND_MANAGER'] as const

export default function BoardView() {
  const me = useMe()
  const { activeBrandId, dateRange } = useFilters()

  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [pending, setPending] = useState<{ card: BoardCard; action: QuickAction } | null>(null)
  const [busyCaseId, setBusyCaseId] = useState<string | null>(null)
  const [cardErrors, setCardErrors] = useState<Record<string, string>>({})

  // Board-local filters, held here rather than in the shell: they are this screen's, and
  // no other screen would read them.
  const [ownerFilter, setOwnerFilter] = useState<'all' | 'mine'>('all')
  const [serviceFilter, setServiceFilter] = useState<ServiceType | 'all'>('all')
  const [urgentOnly, setUrgentOnly] = useState(false)

  const dueBefore = dueBeforeFor(dateRange)

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
   * action shows its reason on the card and nothing moves.
   */
  const run = useCallback(
    async (card: BoardCard, action: QuickAction, values: Record<string, string>) => {
      setPending(null)
      setBusyCaseId(card.id)
      setCardErrors((previous) => {
        const { [card.id]: _removed, ...rest } = previous
        return rest
      })
      try {
        await performAction(card.id, action, values)
        await load()
      } catch (error: unknown) {
        setCardErrors((previous) => ({
          ...previous,
          [card.id]: error instanceof Error ? error.message : 'That action was refused',
        }))
      } finally {
        setBusyCaseId(null)
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

  const visible = useCallback(
    (cards: readonly BoardCard[]) =>
      cards.filter((card) => {
        if (serviceFilter !== 'all' && card.serviceType !== serviceFilter) return false
        if (urgentOnly && card.slaStatus !== 'AT_RISK' && card.slaStatus !== 'OVERDUE') return false
        if (ownerFilter === 'mine') {
          return (
            card.assignedPm === me.id || card.assignedCm === me.id || card.assignedCoordinator === me.id
          )
        }
        return true
      }),
    [me.id, ownerFilter, serviceFilter, urgentOnly],
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
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {state.message}
        </p>
        <button
          type="button"
          onClick={() => void load()}
          className="mt-2 rounded-md px-3 py-1.5 text-sm font-medium text-white"
          style={{ background: 'var(--accent-primary)' }}
        >
          Try again
        </button>
      </div>
    )
  }

  const board = state.data

  return (
    <div className="flex min-h-0 flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Production board</h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            {me.role === 'GM'
              ? activeBrandId
                ? 'One brand, every case in your scope.'
                : 'All brands, every case in your scope.'
              : 'Every case in your scope.'}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
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
          <label className="flex items-center gap-1.5 text-sm">
            <input type="checkbox" checked={urgentOnly} onChange={(e) => setUrgentOnly(e.target.checked)} />
            At risk / overdue only
          </label>
        </div>
      </header>

      {(SEES_POOL as readonly string[]).includes(me.role) && (
        <PoolLane
          cards={visible(pool)}
          onAssign={(card) => {
            const assign = actionsFor(card, me.role).find((action) => action.path === 'assign-pm')
            if (assign) onAction(card, assign)
          }}
        />
      )}

      <div className="flex gap-3 overflow-x-auto pb-2">
        {columnsFor(me.role).map(({ stage, label, access }) => {
          const cards = visible(board.stages[stage] ?? [])
          return (
            <StageColumn key={stage} label={label} count={cards.length} readOnly={access === 'status'}>
              {cards.map((card) => (
                <CaseCard
                  key={card.id}
                  card={card}
                  actions={actionsFor(card, me.role)}
                  busy={busyCaseId === card.id}
                  error={cardErrors[card.id] ?? null}
                  onAction={(action) => onAction(card, action)}
                />
              ))}
            </StageColumn>
          )
        })}
      </div>

      <details open>
        <summary className="cursor-pointer text-sm font-semibold tracking-tight">Exception lanes</summary>
        <div className="mt-2 flex gap-3 overflow-x-auto pb-2">
          {EXCEPTION_LANES.map(({ state: lane, label }) => {
            const cards = visible(board.exceptions[lane] ?? [])
            return (
              <StageColumn key={lane} label={label} count={cards.length}>
                {cards.map((card) => (
                  <CaseCard
                    key={card.id}
                    card={card}
                    actions={actionsFor(card, me.role)}
                    busy={busyCaseId === card.id}
                    error={cardErrors[card.id] ?? null}
                    onAction={(action) => onAction(card, action)}
                  />
                ))}
              </StageColumn>
            )
          })}
        </div>
      </details>

      {pending && (
        <QuickActionDialog
          action={pending.action}
          caseCode={pending.card.caseCode}
          onCancel={() => setPending(null)}
          onConfirm={(values) => void run(pending.card, pending.action, values)}
        />
      )}
    </div>
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
        className="rounded-md border px-2 py-1 text-sm"
        style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
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
