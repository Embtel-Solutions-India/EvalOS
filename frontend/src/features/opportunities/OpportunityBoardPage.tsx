import { memo, useDeferredValue, useMemo, useRef, useState, type DragEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { useMetrics } from '../dashboards/useMetrics'
import { formatCount, formatMoney } from '../../lib/money'
import StageColumn from '../board/StageColumn'
import { moveDeal } from './boardMove'
import {
  fetchOpportunityBoard,
  moveStage,
  refreshOpportunityBoard,
  type BoardColumn,
  type Deal,
} from './opportunityApi'

/**
 * The sales and marketing desk: the opportunities standing in the pipeline you own.
 *
 * **One screen for both roles, because it is one question.** A salesperson and a marketer each
 * ask "what is on my desk, and where has it got to" — the answer is the same shape over the same
 * data, and the only difference is whose pipeline it is. The server decides that from the
 * caller's own token; nothing here can name a pipeline, and nothing here should ever be able to.
 *
 * **Unit 39 made it a desk for Marketing and Unit 40 for Sales.** A lead can be opened here,
 * every card carries its note stream, and a salesperson can move a deal between stages, close
 * it, and set a follow-up. Each role sees only its own actions — the server refuses the others,
 * and a button that 403s is worse than no button.
 *
 * **Only `MARKETING` sees the new-lead form.** Sales works the same opportunities and shares the
 * note table, but *opening* a lead is a marketing act — the server refuses the route to anyone
 * else, and showing a button that 403s is worse than showing none.
 *
 * **Not the production board.** That one draws EvalOS *cases*, which begin at payment
 * (invariant 8) — by which point the deal has left this screen. Two boards over two things, and
 * `boardRules.ts` gives Sales and Marketing `none` on every production stage for that reason.
 * It does share that board's layout: the same `StageColumn`, the same fixed-width strip scrolling
 * sideways, each column scrolling on its own under a pinned header.
 *
 * **A salesperson moves a deal by dragging it** (2026-09-23). The production board refuses drag
 * because most case transitions need a field a drop cannot supply (spec 22); a stage move needs
 * only the stage, so here the drop *is* the whole action. It is native HTML5 drag-and-drop with
 * the handlers on the strip rather than on each card, and it touches React state only when the
 * column under the pointer changes — never per pointer move. The drop moves the card at once,
 * sends one `PUT`, and puts the card back if the server refuses; nothing refetches.
 */
export default function OpportunityBoardPage() {
  // **`reload` from the hook, not a counter in the deps.** Passing a counter as a dependency made
  // `useMetrics` clear `data` on every refresh, so adding a note or moving a card blanked the whole
  // board to a skeleton and lost the reader's scroll position — the exact behaviour the hook
  // documents itself as having been fixed to avoid by returning a separate `reload` that keeps the
  // last good data on screen while the new read is in flight.
  const { data, state, reload } = useMetrics((signal) => fetchOpportunityBoard(signal), [])
  const [syncing, setSyncing] = useState(false)
  // The server refuses a stage move to anyone else, and a drag that 403s is worse than none.
  const canMove = useMe().role === 'SALES'

  // The columns as drawn: the last read, plus any moves made since. A new read replaces them —
  // adjusted during render rather than in an effect, so no frame shows the old board.
  const [columns, setColumns] = useState(data?.columns ?? null)
  const [read, setRead] = useState(data)
  if (read !== data) {
    setRead(data)
    setColumns(data?.columns ?? null)
  }

  // Deferred, not debounced: filtering a few hundred names is cheap, and React keeps the input
  // responsive by rendering the filtered board at lower priority. An unchanged column is passed
  // through by identity, so its cards do not re-render either.
  const [query, setQuery] = useState('')
  const needle = useDeferredValue(query.trim().toLowerCase())
  const shown = useMemo(
    () => (needle && columns ? columns.map((column) => narrow(column, needle)) : columns),
    [columns, needle],
  )

  const [moveError, setMoveError] = useState<string | null>(null)
  const [over, setOver] = useState<string | null>(null)
  // Refs, not state: none of this is drawn, and writing it must not render anything mid-drag.
  const dragging = useRef<{ id: string; from: string; card: HTMLElement } | null>(null)
  const overRef = useRef<string | null>(null)
  // A deal with a move in flight cannot be picked up again, so a rollback can never undo a later
  // move made while the first was still being saved.
  const saving = useRef(new Set<string>())

  /**
   * The Refresh button: reconcile the mirror, then redraw.
   *
   * **`reload` alone would not do it.** A plain reload — and an F5, and every other refetch on this
   * page — reads the EvalOS mirror and nothing else, which is the whole design: it shows new GHL
   * leads only once the sweep has brought them in. This button is the way to ask for that sweep on
   * demand, and it still ends at the mirror.
   */
  const syncNow = async () => {
    setSyncing(true)
    try {
      await refreshOpportunityBoard()
      reload()
    } finally {
      setSyncing(false)
    }
  }

  const hover = (stageId: string | null) => {
    if (overRef.current === stageId) return
    overRef.current = stageId
    setOver(stageId)
  }

  const endDrag = () => {
    if (dragging.current) dragging.current.card.style.opacity = ''
    dragging.current = null
    hover(null)
  }

  const onDragStart = (event: DragEvent) => {
    const card = (event.target as HTMLElement).closest<HTMLElement>('[data-deal]')
    const from = card?.closest<HTMLElement>('[data-stage]')?.dataset.stage
    const id = card?.dataset.deal
    if (!card || !from || !id || saving.current.has(id)) {
      event.preventDefault()
      return
    }
    dragging.current = { id, from, card }
    event.dataTransfer.effectAllowed = 'move'
    // Firefox starts no drag without data.
    event.dataTransfer.setData('text/plain', id)
    // Straight onto the element: dimming the source card is not worth a render.
    card.style.opacity = '0.4'
  }

  // Fires every few milliseconds while dragging. The lookup is cheap and nothing renders unless
  // the column under the pointer actually changed.
  const onDragOver = (event: DragEvent) => {
    if (!dragging.current) return
    const stage = (event.target as HTMLElement).closest<HTMLElement>('[data-stage]')?.dataset.stage
    if (!stage) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
    hover(stage)
  }

  const onDrop = (event: DragEvent) => {
    event.preventDefault()
    const drag = dragging.current
    const to = (event.target as HTMLElement).closest<HTMLElement>('[data-stage]')?.dataset.stage
    endDrag()
    if (drag && to && to !== drag.from) void move(drag.id, drag.from, to)
  }

  /**
   * Optimistic, and the server still decides. The card moves on the drop; the server's answer is
   * applied over it (D44 answers from the row it wrote, so normally that changes nothing); a
   * refusal — permission, a deal the mirror no longer holds, a timeout — puts the card back and
   * says why. The search and both scroll positions survive all of it, because no column remounts
   * and nothing refetches.
   */
  async function move(id: string, from: string, to: string) {
    saving.current.add(id)
    setMoveError(null)
    setColumns((current) => current && moveDeal(current, id, to))
    try {
      const saved = await moveStage(id, to)
      setColumns(
        (current) =>
          current &&
          moveDeal(current, id, saved.stageId, {
            name: saved.name,
            status: saved.status,
            amount: saved.monetaryValue,
          }),
      )
    } catch (failure) {
      setColumns((current) => current && moveDeal(current, id, from))
      setMoveError(failure instanceof Error ? failure.message : 'That move was refused')
    } finally {
      saving.current.delete(id)
    }
  }

  return (
    <div className="flex min-h-0 flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">My pipeline</h1>
          <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
            Opportunities in GoHighLevel. EvalOS shows them; GHL owns them.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3 text-sm" style={{ color: 'var(--text-muted)' }}>
          {data && (
            <span className="font-num tabular-nums">
              {formatCount(data.totalDeals)} open · {formatMoney(data.totalValue)}
            </span>
          )}
          {/*
            The sync time is shown rather than hidden, and it is the mirror's — not this
            render's. Unit 46 took the board off live GHL reads, so "when did we last hear from
            GHL" is the only freshness question that means anything, and the reader cannot answer
            it from the page. A null means the sync has never run: it says so instead of
            guessing "now".
          */}
          {data && (
            <span className="text-xs">
              {data.lastSyncedAt
                ? `synced ${new Date(data.lastSyncedAt).toLocaleTimeString()}`
                : 'never synced'}
            </span>
          )}
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Find a deal"
            aria-label="Find a deal by name"
            className="field h-9 w-48"
          />
          <button type="button" onClick={syncNow} disabled={syncing || !data} className="btn">
            {syncing ? 'Syncing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {/*
        The banner, not a tooltip. Past `board-stale-after` the sweep has missed three passes, which
        means new GHL leads are NOT arriving on this screen — that is a working assumption a
        salesperson would otherwise make wrongly all morning. Saying it plainly beats a stamp they
        have to interpret.
      */}
      {data && !data.syncConfigured && (
        <p
          className="rounded border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-900"
          role="status"
        >
          <strong>GHL sync is not configured.</strong> `evalos.ghl.sales-brand` is blank on the
          server, so nothing mirrors pipelines, deals or calendars — the board is empty for that
          reason and not because the sync is behind. Set <code>GHL_SALES_BRAND_ID</code> to the
          brand that owns the GHL location, then run the PIPELINE_MIRROR and MIRROR_DELTA jobs.
        </p>
      )}

      {data?.stale && data.syncConfigured && (
        <p
          className="rounded border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
          role="status"
        >
          <strong>Sync delayed.</strong>{' '}
          {data.lastSyncedAt
            ? `The mirror was last confirmed against GoHighLevel at ${new Date(
                data.lastSyncedAt,
              ).toLocaleTimeString()}. Deals created in GHL since then are not on this board yet.`
            : 'This board has never been synced with GoHighLevel, so deals created there are not on it yet.'}{' '}
          Press Refresh, or ask a GM to check the MIRROR_DELTA sweep.
        </p>
      )}

      {/* The two "open something" actions moved to the sidebar on 2026-09-17. They were here as a
          button and an inline form, which put the thing a desk opens the app to do behind first
          loading the board. `NewDealPage` and `NewLeadPage` are one click from anywhere. */}

      {moveError && (
        <p className="px-1 text-sm font-medium" role="alert" style={{ color: 'var(--status-red)' }}>
          {moveError} — the deal is back where it was.
        </p>
      )}

      {state.kind === 'error' ? (
        <div
          className="rounded-lg border p-4"
          style={{ background: 'var(--status-red-bg)', borderColor: 'var(--border-default)' }}
        >
          <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
            {state.note}
          </p>
          <button type="button" onClick={reload} className="btn mt-3">
            Try again
          </button>
        </div>
      ) : !shown ? (
        // The header is already on screen; these hold the strip's place until the stages arrive.
        <div className="flex gap-3 overflow-hidden pb-2" aria-busy="true">
          {[0, 1, 2, 3].map((n) => (
            <div
              key={n}
              className="h-64 w-60 shrink-0 animate-pulse"
              style={{ background: 'var(--bg-raised)', borderRadius: 'var(--radius-lg)' }}
            />
          ))}
        </div>
      ) : shown.length === 0 ? (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          {/*
            An empty board has two very different causes and the reader deserves both: no deals
            yet, or no pipeline assigned. The server fails closed on the second (a caller with
            no pipeline matches nothing), so "ask your GM" is the actionable half.
          */}
          No opportunities. If you expect some, check that a GM has assigned you a GHL pipeline —
          a member without one sees an empty board by design.
        </p>
      ) : (
        <div
          className="scroll-slim flex gap-3 overflow-x-auto pb-2"
          // Taller columns than the production board's. `--board-column-max` reserves 20rem there
          // for a pool lane and an "Off the pipeline" row this screen does not have; the chrome
          // here is the 4.5rem top bar, the page heading, the column header with its value line and
          // the gutter — about 15rem. The variable is inherited, so overriding it on this strip
          // changes only this board. A stale-sync banner, when shown, costs one short page scroll.
          style={{ '--board-column-max': 'max(14rem, calc(100svh - 16rem))' } as React.CSSProperties}
          onDragStart={canMove ? onDragStart : undefined}
          onDragOver={canMove ? onDragOver : undefined}
          onDrop={canMove ? onDrop : undefined}
          onDragEnd={canMove ? endDrag : undefined}
        >
          {shown.map((column) => (
            <Column key={column.stageId} column={column} over={over === column.stageId} canMove={canMove} />
          ))}
        </div>
      )}
    </div>
  )
}

/** The column with only the deals whose name matches — the same object when nothing is hidden. */
function narrow(column: BoardColumn, needle: string): BoardColumn {
  const deals = column.deals.filter((deal) => deal.name?.toLowerCase().includes(needle))
  if (deals.length === column.deals.length) return column
  return { ...column, deals, total: deals.reduce((total, deal) => total + (deal.amount ?? 0), 0) }
}

// Memoised because a drop hands every untouched column back by identity (`moveDeal`) and the
// hover outline is a boolean, so a drag re-renders the columns it crosses and no others. The
// column carries no actions: everything beyond moving a deal lives on `DealPage`.
const Column = memo(function Column({
  column,
  over,
  canMove,
}: {
  column: BoardColumn
  over: boolean
  canMove: boolean
}) {
  return (
    <div
      data-stage={column.stageId}
      className="shrink-0"
      style={{
        borderRadius: 'var(--radius-lg)',
        outline: over ? '2px solid var(--accent-primary)' : undefined,
        outlineOffset: 2,
      }}
    >
      <StageColumn
        label={column.stageName}
        count={column.deals.length}
        emptyText="No deals at this stage."
        banded
        // Always drawn, so every header is the same height and the value sits in the same place
        // across the strip; an empty stage reads "—" rather than a zero it has not earned.
        subtitle={
          <p className="font-num -mt-1 px-3 pb-2 text-xs tabular-nums">
            <span style={{ color: 'var(--text-muted)' }}>Value </span>
            <span className="font-medium">{column.deals.length > 0 ? formatMoney(column.total) : '—'}</span>
          </p>
        }
      >
        {column.deals.map((deal) => (
          <DealCard key={deal.opportunityId} deal={deal} canMove={canMove} />
        ))}
      </StageColumn>
    </div>
  )
})

/**
 * One deal. **The card is a link, not a disclosure** (2026-09-17): `DealPage` is where the
 * questionnaire, the notes and the actions belong, and the card's job is to get you there.
 * Styled after `CaseCard` — the whole card is the link, and everything over it ignores the
 * pointer.
 *
 * `content-visibility: auto` lets the browser skip layout and paint for cards scrolled out of
 * their column, which keeps a stage of several hundred deals cheap without a virtualiser — and a
 * virtualiser would unmount the very card being dragged once it scrolled out of view.
 */
const DealCard = memo(function DealCard({ deal, canMove }: { deal: Deal; canMove: boolean }) {
  return (
    <article
      data-deal={deal.opportunityId}
      draggable={canMove}
      className={`relative shrink-0 bg-(--bg-base) p-2.5 [contain-intrinsic-size:auto_4.75rem] [content-visibility:auto] hover:bg-(--bg-surface) ${
        canMove ? 'cursor-grab active:cursor-grabbing' : ''
      }`}
      style={{ borderRadius: 'var(--radius-lg)' }}
    >
      {/* Not draggable itself, so a drag picks up the card rather than the link's URL. */}
      <Link
        to={`/opportunities/${deal.opportunityId}`}
        draggable={false}
        aria-label={`Open ${deal.name ?? 'untitled deal'}`}
        className="absolute inset-0 rounded-[inherit] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--accent-primary)"
      />
      <p className="pointer-events-none relative truncate text-[14px] leading-snug font-semibold">
        {deal.name ?? 'Untitled'}
      </p>
      {/* As `CaseCard` draws a case's service: small caps under the name, and said when absent. */}
      <p
        className="pointer-events-none relative truncate text-[10px] font-medium tracking-[0.06em] uppercase"
        style={{ color: 'var(--text-muted)' }}
      >
        {deal.service ?? 'service not set'}
      </p>
      {/* Labels inline, as on `CaseCard`'s due line: one row, every figure named. */}
      <p className="font-num pointer-events-none relative mt-1 flex items-baseline gap-3 text-[11px] tabular-nums">
        <span className="shrink-0">
          <span style={{ color: 'var(--text-muted)' }}>Value </span>
          {/*
            `amount` is null when GHL holds no value, and that is shown as a dash rather than as
            a zero: "nobody has priced it" and "it is worth nothing" are different facts, and
            the second one loses deals.
          */}
          <span className="font-medium">{deal.amount === null ? '—' : formatMoney(deal.amount)}</span>
        </span>
        <span className="min-w-0 truncate">
          <span style={{ color: 'var(--text-muted)' }}>Source </span>
          <span className="font-medium">{deal.source ?? '—'}</span>
        </span>
        {deal.status !== 'open' && (
          <span className="ml-auto shrink-0 uppercase" style={{ color: 'var(--text-muted)' }}>
            {deal.status}
          </span>
        )}
      </p>
    </article>
  )
})
