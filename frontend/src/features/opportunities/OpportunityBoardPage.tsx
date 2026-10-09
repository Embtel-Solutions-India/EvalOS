import { memo, useDeferredValue, useMemo, useRef, useState, type DragEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { useMetrics } from '../dashboards/useMetrics'
import { formatCount, formatMoney } from '../../lib/money'
import StageColumn from '../board/StageColumn'
import { stageColor } from '../board/stageColors'
import { cardDate } from '../board/boardRules'
import { CalendarDays } from 'lucide-react'
import { isWonStage, moveDeal } from './boardMove'
import { openSummary } from './dealOpen'
import MyTarget from './MyTarget'
import WinNote from './WinNote'
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
/** How often a desk's open board re-reads the mirror. The sweep itself runs every 5 minutes. */
const BOARD_REFRESH_MS = 60_000

export default function OpportunityBoardPage() {
  // **`reload` from the hook, not a counter in the deps.** Passing a counter as a dependency made
  // `useMetrics` clear `data` on every refresh, so adding a note or moving a card blanked the whole
  // board to a skeleton and lost the reader's scroll position — the exact behaviour the hook
  // documents itself as having been fixed to avoid by returning a separate `reload` that keeps the
  // last good data on screen while the new read is in flight.
  const role = useMe().role
  // The sweeps rewrite the mirror every few minutes and tell no browser, so a desk's board re-reads once a
  // minute while visible. Not the GM's: theirs is every pipeline (thousands of cards) and a poll would be heavy.
  const { data, state, reload } = useMetrics((signal) => fetchOpportunityBoard(signal), [], {
    refreshEvery: role === 'GM' ? undefined : BOARD_REFRESH_MS,
  })
  const [syncing, setSyncing] = useState(false)
  // The server refuses a stage move to anyone else, and a drag that 403s is worse than none.
  // Unit 63: the ENM moves candidates on their hiring pipeline, through the same stage route.
  const canMove = role === 'SALES' || role === 'EXPERT_NETWORK_MANAGER'
  const hiring = role === 'EXPERT_NETWORK_MANAGER'

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

  // The header's total is the sum of the columns as drawn, so it can never disagree with them — it also
  // follows a drag, which the server's figure (read once) cannot.
  const openTotals = useMemo(
    () => (columns ? columns.reduce((t, c) => { const o = openSummary(c.deals); return { count: t.count + o.count, value: t.value + o.value } }, { count: 0, value: 0 }) : null),
    [columns],
  )

  const [moveError, setMoveError] = useState<string | null>(null)
  // D70: a drop on the Won column waits here for the production team's note.
  const [pendingWin, setPendingWin] = useState<{ id: string; from: string; to: string } | null>(null)
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
    } catch {
      // Even a failed or timed-out press may have synced some pipelines server-side; the reload
      // below shows whatever the mirror now holds, and the button stays amber if it is still behind.
    } finally {
      setSyncing(false)
      reload()
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
    if (!drag || !to || to === drag.from) return
    const target = columns?.find((column) => column.stageId === to)
    if (target && isWonStage(target.stageName)) setPendingWin({ id: drag.id, from: drag.from, to })
    else void move(drag.id, drag.from, to)
  }

  /**
   * Optimistic, and the server still decides. The card moves on the drop; the server's answer is
   * applied over it (D44 answers from the row it wrote, so normally that changes nothing); a
   * refusal — permission, a deal the mirror no longer holds, a timeout — puts the card back and
   * says why. The search and both scroll positions survive all of it, because no column remounts
   * and nothing refetches.
   */
  async function move(id: string, from: string, to: string, note?: string) {
    saving.current.add(id)
    setMoveError(null)
    setColumns((current) => current && moveDeal(current, id, to))
    try {
      const saved = await moveStage(id, to, note)
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

  const stale = !!data?.stale && data.syncConfigured

  return (
    <div className="flex min-h-0 flex-col gap-4">
      {pendingWin && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Win this deal"
          onKeyDown={(event) => event.key === 'Escape' && setPendingWin(null)}
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ background: 'rgb(0 0 0 / 0.4)' }}
        >
          <div
            className="w-full max-w-md rounded-[1.25rem] p-6"
            style={{ background: 'var(--bg-surface)', boxShadow: 'var(--shadow-pop)' }}
          >
            <h2 className="mb-3 text-base font-semibold">Win this deal</h2>
            <WinNote
              busy={false}
              onCancel={() => setPendingWin(null)}
              onConfirm={(note) => {
                const { id, from, to } = pendingWin
                setPendingWin(null)
                void move(id, from, to, note)
              }}
            />
          </div>
        </div>
      )}
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{hiring ? 'Hiring pipeline' : 'My pipeline'}</h1>
          <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
            {hiring
              ? 'Expert candidates in GoHighLevel. A move here reaches GHL, and a move in GHL shows here.'
              : 'Opportunities in GoHighLevel. EvalOS shows them; GHL owns them.'}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3 text-sm" style={{ color: 'var(--text-muted)' }}>
          {(role === 'SALES' || role === 'MARKETING') && <MyTarget />}
          {data && openTotals && (
            <span className="font-num tabular-nums">
              {formatCount(openTotals.count)} open · {formatMoney(openTotals.value)}
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
          {/* Behind on sync (past `board-stale-after`: new GHL deals are not arriving here) is
              shown on the button that fixes it — an amber dot and border, the reason on hover —
              rather than as a banner across the board. */}
          <button
            type="button"
            onClick={syncNow}
            disabled={syncing || !data}
            className="btn relative"
            style={stale ? { borderColor: 'var(--status-amber)', color: 'var(--status-amber)' } : undefined}
            title={
              stale
                ? `Sync delayed — last confirmed ${data?.lastSyncedAt ? new Date(data.lastSyncedAt).toLocaleTimeString() : 'never'}. New GHL deals may be missing. Click to refresh.`
                : undefined
            }
            aria-label={stale ? 'Refresh — sync delayed, new GoHighLevel deals may be missing' : undefined}
          >
            {stale && (
              <span
                aria-hidden="true"
                className="absolute -right-1 -top-1 h-2.5 w-2.5 rounded-full"
                style={{ background: 'var(--status-amber)' }}
              />
            )}
            {syncing ? 'Syncing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {/*
        Not configured at all is still a banner: the board is empty for that reason, and no button
        press fixes it. A merely delayed sync is marked on the Refresh button instead.
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
          className="rounded-2xl p-4"
          style={{ background: 'var(--status-red-bg)' }}
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
          {hiring ? (
            // An ENM holds exactly the hiring pipelines granted to them (D61): an Administrator grants one on the
            // Staff screen, and it has to be tagged Expert hiring and belong to the ENM's own brand. A new ENM
            // starts with none, and nothing else gives them one.
            <>
              No hiring pipeline is granted to you. An administrator grants one on the Staff screen; it has to be
              tagged <strong>Expert hiring</strong> and be in your own brand. Until then this screen stays empty by
              design.
            </>
          ) : (
            <>
              No opportunities. If you expect some, check that a GM has assigned you a GHL pipeline — a
              member without one sees an empty board by design.
            </>
          )}
        </p>
      ) : (
        <div
          className="scroll-slim flex gap-3 overflow-x-auto pb-2"
          // Taller columns than the production board's. `--board-column-max` reserves 20rem there
          // for a pool lane and an "Off the pipeline" row this screen does not have; the chrome
          // here is the 4.5rem top bar, the page heading, the column header with its value line and
          // the gutter — about 15rem. The variable is inherited, so overriding it on this strip
          // changes only this board.
          style={{ '--board-column-max': 'max(14rem, calc(100svh - 16rem))' } as React.CSSProperties}
          onDragStart={canMove ? onDragStart : undefined}
          onDragOver={canMove ? onDragOver : undefined}
          onDrop={canMove ? onDrop : undefined}
          onDragEnd={canMove ? endDrag : undefined}
        >
          {shown.map((column, index) => (
            <Column
              key={column.stageId}
              column={column}
              color={stageColor(index, column.stageName)}
              // Sales opens deals from the column they belong in; the form starts on this stage.
              addHref={role === 'SALES' ? `/opportunities/new?stage=${encodeURIComponent(column.stageId)}` : undefined}
              over={over === column.stageId}
              canMove={canMove}
            />
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
  return { ...column, deals, total: openSummary(deals).value }
}

// Memoised because a drop hands every untouched column back by identity (`moveDeal`) and the
// hover outline is a boolean, so a drag re-renders the columns it crosses and no others. The
// column carries no actions: everything beyond moving a deal lives on `DealPage`.
const Column = memo(function Column({
  column,
  color,
  addHref,
  over,
  canMove,
}: {
  column: BoardColumn
  color: string
  addHref?: string
  over: boolean
  canMove: boolean
}) {
  return (
    <div
      data-stage={column.stageId}
      className="shrink-0"
      style={{
        borderRadius: 'var(--radius-xl)',
        outline: over ? `2px solid ${color}` : undefined,
        outlineOffset: 2,
      }}
    >
      <StageColumn
        label={column.stageName}
        count={openSummary(column.deals).count}
        emptyText="No deals at this stage."
        color={color}
        addHref={addHref}
        // Always drawn, so every header is the same height and the value sits in the same place
        // across the strip; an empty stage reads "—" rather than a zero it has not earned.
        subtitle={
          <p className="font-num px-2 text-xs tabular-nums">
            <span style={{ color: 'var(--text-muted)' }}>Value </span>
            <span className="font-medium">{openSummary(column.deals).count > 0 ? formatMoney(openSummary(column.deals).value) : '—'}</span>
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
 * portal request, the notes and the actions belong, and the card's job is to get you there.
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
      className={`relative shrink-0 p-3 transition-shadow [contain-intrinsic-size:auto_7.5rem] [content-visibility:auto] hover:shadow-(--shadow-pop) ${
        canMove ? 'cursor-grab active:cursor-grabbing' : ''
      }`}
      style={{
        borderRadius: 'var(--radius-xl)',
        // The column's colour (`--stage`, from `StageColumn`), as on `CaseCard`.
        background:
          'linear-gradient(180deg, color-mix(in srgb, var(--stage) 20%, #ffffff), color-mix(in srgb, var(--stage) 7%, #ffffff))',
      }}
    >
      {/* Not draggable itself, so a drag picks up the card rather than the link's URL. */}
      <Link
        to={`/opportunities/${deal.opportunityId}`}
        draggable={false}
        aria-label={`Open ${deal.name ?? 'untitled deal'}`}
        className="absolute inset-0 rounded-[inherit] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--accent-primary)"
      />
      <div className="pointer-events-none relative flex items-center justify-between gap-2">
        <span className="min-w-0 truncate rounded-full bg-white/85 px-2.5 py-0.5 text-[11px] font-medium">
          {deal.source ?? 'Source not set'}
        </span>
        {deal.status !== 'open' && (
          <span className="shrink-0 text-[11px] font-medium capitalize" style={{ color: 'var(--card-text-muted)' }}>
            {deal.status}
          </span>
        )}
      </div>
      <p className="pointer-events-none relative mt-2 truncate text-[15px] leading-snug font-semibold">
        {deal.name ?? 'Untitled'}
      </p>
      <p className="pointer-events-none relative truncate text-xs" style={{ color: 'var(--card-text-muted)' }}>
        {deal.service ?? 'Service not set'}
      </p>
      <div
        className="pointer-events-none relative mt-2.5 flex items-center justify-between gap-2 border-t pt-2.5"
        style={{ borderColor: 'color-mix(in srgb, var(--stage) 18%, var(--border-default))' }}
      >
        <span className="font-num flex min-w-0 items-center gap-1.5 text-xs tabular-nums">
          <CalendarDays className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--card-text-muted)' }} aria-hidden />
          <span className="truncate">{deal.updatedAt ? `Updated ${cardDate(deal.updatedAt)}` : 'Not updated yet'}</span>
        </span>
        {/*
          `amount` is null when GHL holds no value, and that is shown as a dash rather than as a
          zero: "nobody has priced it" and "it is worth nothing" are different facts, and the second
          one loses deals.
        */}
        <span className="font-num shrink-0 rounded-md bg-white px-2 py-1 text-xs font-semibold tabular-nums shadow-(--shadow-card)">
          {deal.amount === null ? '—' : formatMoney(deal.amount)}
        </span>
      </div>
    </article>
  )
})
