import { useState } from 'react'
import { Card } from '../../components/ui/card'
import { useMe } from '../../lib/authContext'
import { useMetrics } from '../dashboards/useMetrics'
import { formatCount, formatMoney } from '../../lib/money'
import DealActions from './DealActions'
import DealNotes from './DealNotes'
import NewLeadForm from './NewLeadForm'
import { fetchOpportunityBoard, type BoardColumn, type Deal } from './opportunityApi'

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
  const [reloads, setReloads] = useState(0)
  const { data, state } = useMetrics((signal) => fetchOpportunityBoard(signal), [reloads])
  const role = useMe().role
  const isMarketing = role === 'MARKETING'
  const reload = () => setReloads((n) => n + 1)
  // Every stage on the board, so a salesperson can move a deal to any of them. Taken from the
  // board itself rather than fetched separately: it is the same pipeline, already loaded.
  const stages = (data?.columns ?? []).map((column) => ({
    stageId: column.stageId,
    stageName: column.stageName,
  }))

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
          <p className="text-sm text-slate-500">
            {formatCount(data.totalDeals)} open · {formatMoney(data.totalValue)}
            {/*
              The age is shown rather than hidden. This screen is served from a short-lived copy,
              and a reader who cannot tell how old it is has to trust it blindly — the same
              reasoning the GM's funnel screens carry their own timestamp for.
            */}
            <span className="ml-2 text-xs text-slate-400">
              read {new Date(data.readAt).toLocaleTimeString()}
              {data.stale && ' · refreshing'}
            </span>
          </p>
        )}
      </header>

      {/*
        Opening a lead refetches the board rather than inserting the new card locally: the
        opportunity now lives in GHL, and the only honest confirmation it landed is reading it
        back. A locally inserted card would show an outcome the server has not agreed to.
      */}
      {isMarketing && <NewLeadForm onOpened={reload} />}

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
              <StageColumn
                key={column.stageId}
                column={column}
                isSales={role === 'SALES'}
                stages={stages}
                onChanged={reload}
              />
            ))}
          </div>
        )}
      </Card>
    </section>
  )
}

function StageColumn({
  column,
  isSales,
  stages,
  onChanged,
}: {
  column: BoardColumn
  isSales: boolean
  stages: readonly { stageId: string; stageName: string }[]
  onChanged: () => void
}) {
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
        <DealCard
          key={deal.opportunityId}
          deal={deal}
          isSales={isSales}
          stages={stages}
          onChanged={onChanged}
        />
      ))}
    </div>
  )
}

function DealCard({
  deal,
  isSales,
  stages,
  onChanged,
}: {
  deal: Deal
  isSales: boolean
  stages: readonly { stageId: string; stageName: string }[]
  onChanged: () => void
}) {
  // Notes are loaded per card, and only when a card is opened. Eagerly fetching a stream for
  // every deal on the board would be one request per card against a shared 100-per-10-seconds
  // budget, to show text nobody has asked to read yet.
  const [open, setOpen] = useState(false)

  return (
    <article className="rounded border border-slate-200 bg-white p-3 shadow-sm">
      <button
        type="button"
        onClick={() => setOpen((shown) => !shown)}
        className="w-full text-left"
        aria-expanded={open}
      >
        <p className="truncate text-sm font-medium text-slate-900">{deal.name ?? 'Untitled'}</p>
      </button>
      <p className="mt-1 text-xs text-slate-500">
        {/*
          `amount` is null when GHL holds no value, and that is shown as "no value" rather than
          as a zero: "nobody has priced it" and "it is worth nothing" are different facts, and
          the second one loses deals.
        */}
        {deal.amount === null ? 'No value set' : formatMoney(deal.amount)}
        {deal.status !== 'open' && <span className="ml-2 uppercase">{deal.status}</span>}
      </p>
      {open && isSales && (
        <DealActions
          opportunityId={deal.opportunityId}
          contactId={deal.contactId}
          stages={stages}
          onChanged={onChanged}
        />
      )}
      {open && <DealNotes opportunityId={deal.opportunityId} />}
    </article>
  )
}
