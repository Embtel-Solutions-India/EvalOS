import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/card'
import { useMetrics } from '../dashboards/useMetrics'
import { formatCount, formatMoney } from '../../lib/money'
import { fetchOpportunityBoard, refreshOpportunityBoard, type BoardColumn, type Deal } from './opportunityApi'

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
 */
export default function OpportunityBoardPage() {
  // **`reload` from the hook, not a counter in the deps.** Passing a counter as a dependency made
  // `useMetrics` clear `data` on every refresh, so adding a note or moving a card blanked the whole
  // board to a skeleton and lost the reader's scroll position — the exact behaviour the hook
  // documents itself as having been fixed to avoid by returning a separate `reload` that keeps the
  // last good data on screen while the new read is in flight.
  const { data, state, reload } = useMetrics((signal) => fetchOpportunityBoard(signal), [])
  const [syncing, setSyncing] = useState(false)

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

  return (
    <section className="space-y-4">
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">My pipeline</h1>
          <p className="text-sm text-slate-500">
            Opportunities in GoHighLevel. EvalOS shows them; GHL owns them.
          </p>
        </div>
        {data && (
          <div className="flex items-center gap-3 text-sm text-slate-500">
            <span>
              {formatCount(data.totalDeals)} open · {formatMoney(data.totalValue)}
            </span>
            {/*
              The sync time is shown rather than hidden, and it is the mirror's — not this
              render's. Unit 46 took the board off live GHL reads, so "when did we last hear from
              GHL" is the only freshness question that means anything, and the reader cannot answer
              it from the page. A null means the sync has never run: it says so instead of
              guessing "now".
            */}
            <span className="text-xs text-slate-400">
              {data.lastSyncedAt
                ? `synced ${new Date(data.lastSyncedAt).toLocaleTimeString()}`
                : 'never synced'}
            </span>
            <button
              type="button"
              onClick={syncNow}
              disabled={syncing}
              className="rounded border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50 disabled:opacity-50"
            >
              {syncing ? 'Syncing…' : 'Refresh'}
            </button>
          </div>
        )}
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

      <Card title="" state={state}>
        {data && data.columns.length === 0 ? (
          <p className="p-4 text-sm text-slate-500">
            {/*
              An empty board has two very different causes and the reader deserves both: no deals
              yet, or no pipeline assigned. The server fails closed on the second (a caller with
              no pipeline matches nothing), so "ask your GM" is the actionable half.
            */}
            No opportunities. If you expect some, check that a GM has assigned you a GHL pipeline —
            a member without one sees an empty board by design.
          </p>
        ) : (
          <div className="flex gap-3 overflow-x-auto p-1">
            {data?.columns.map((column) => (
              <StageColumn key={column.stageId} column={column} />
            ))}
          </div>
        )}
      </Card>
    </section>
  )
}

// The column carries no actions now: a card is a link, and everything you can DO to a deal lives
// on `DealPage`. The props that threaded stage lists and reload callbacks down two levels went
// with them.
function StageColumn({ column }: { column: BoardColumn }) {
  return (
    <div className="flex w-64 shrink-0 flex-col gap-2">
      <div className="flex items-baseline justify-between border-b border-slate-200 pb-1">
        <h2 className="text-sm font-medium text-slate-700">{column.stageName}</h2>
        <span className="text-xs text-slate-400">{column.deals.length}</span>
      </div>
      {column.deals.length > 0 && (
        <p className="text-xs text-slate-500">{formatMoney(column.total)}</p>
      )}
      {column.deals.map((deal) => (
        <DealCard key={deal.opportunityId} deal={deal} />
      ))}
    </div>
  )
}

function DealCard({ deal }: { deal: Deal }) {
  // **The card is a link now, not a disclosure** (2026-09-17). It used to expand in place, which
  // put the questionnaire, the notes and the stage actions inside a 16rem column between two other
  // cards. `DealPage` is the screen they belong on; the card's job is to get you there.
  return (
    <article className="rounded border border-slate-200 bg-white p-3 shadow-sm hover:border-slate-300">
      <Link
        to={`/opportunities/${deal.opportunityId}`}
        className="block truncate text-sm font-medium text-slate-900 hover:underline"
      >
        {deal.name ?? 'Untitled'}
      </Link>
      <p className="mt-1 text-xs text-slate-500">
        {/*
          `amount` is null when GHL holds no value, and that is shown as "no value" rather than
          as a zero: "nobody has priced it" and "it is worth nothing" are different facts, and
          the second one loses deals.
        */}
        {deal.amount === null ? 'No value set' : formatMoney(deal.amount)}
        {deal.status !== 'open' && <span className="ml-2 uppercase">{deal.status}</span>}
      </p>
    </article>
  )
}
