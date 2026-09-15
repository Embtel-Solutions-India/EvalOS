import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, ChartCard, KpiCard } from '../../components/ui/card'
import type { CardState } from '../../components/ui/card'
import { formatMoney } from '../../lib/money'
import { rangeLabel, useFilters } from '../shell/filtersContext'
import { fetchOpportunityBoard, type OpportunityBoard } from '../opportunities/opportunityApi'
import { STALE_DAYS, staleDeals } from './dealAge'
import {
  fetchExpertNetworkMetrics,
  fetchGmOverview,
  fetchPmMetrics,
  fetchRevenueMetrics,
  type ExpertNetworkMetrics,
  type GmOverview,
  type PmMetrics,
  type RevenueMetrics,
} from './pmMetricsApi'
import { emptyWhen, useMetrics } from './useMetrics'

/**
 * The GM's monthly overview — the business on one screen, department by department.
 *
 * <p><strong>Five reads, and four of them already existed.</strong> `/metrics/gm` is the only new
 * endpoint; `/metrics/revenue`, `/metrics/pm`, `/metrics/expert-network` and
 * `/opportunities/board` are routes this role could already call, and every figure taken from
 * them is taken rather than recomputed. A second copy of "at risk" or "onboarded this month"
 * living here is a second number to keep in step with the first, and they always drift.
 *
 * <p><strong>Each read carries its own state, deliberately.</strong> GHL being down must not
 * blank the evaluation department, and an empty expert roster must not hide the money. The one
 * read that spans both worlds — `/metrics/gm` — returns its production half regardless and names
 * the reason its pipeline half is missing, which this screen prints on exactly the tiles that
 * reason invalidates.
 *
 * <p><strong>The Brand Manager does not land here</strong>, and that is not an oversight. The
 * pipeline half reads one GHL location EvalOS cannot attribute to a brand — invariant 1's stated
 * exception, licensed only while the reader is the one cross-brand role. A Brand Manager would be
 * shown figures neither they nor the server can tell are theirs. They keep `RevenueDashboard`,
 * which is brand-scoped and true.
 *
 * <p><strong>What the business asked for and is not here</strong> is recorded in
 * `context/specs/51-gm-dashboard.md` rather than drawn as an empty tile: email-campaign sends and
 * replies (needs an `emails/stats.readonly` token this deployment's PIT does not carry), social
 * reach (GHL publishes posts and reports no analytics at all), the BDE table, and the expert
 * recruitment funnel (Unit 50). A tile that names a metric it cannot compute is the
 * header-contradicting-the-instrument failure this project has already had to clean up once.
 */
export default function GmDashboard() {
  const { activeBrandId, dateRange } = useFilters()

  const gm = useMetrics<GmOverview>(
    (signal) => fetchGmOverview(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
  )
  const revenue = useMetrics<RevenueMetrics>(
    (signal) => fetchRevenueMetrics(activeBrandId, signal),
    [activeBrandId],
  )
  const pm = useMetrics<PmMetrics>(
    (signal) => fetchPmMetrics(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
  )
  const roster = useMetrics<ExpertNetworkMetrics>((signal) => fetchExpertNetworkMetrics(signal), [])
  const board = useMetrics<OpportunityBoard>((signal) => fetchOpportunityBoard(signal), [])

  const data = gm.data
  const period = rangeLabel(dateRange).toLowerCase()

  /**
   * The state for a tile fed by the GHL half of `/metrics/gm`.
   *
   * <p>A 200 carrying `pipelineUnavailable` is not a successful read of a zero — it is a failed
   * read the server chose not to fail the whole request over. Rendering it as `ok` would print
   * `$0` won for a month that may have gone well.
   */
  const pipelineState: CardState = data?.pipelineUnavailable
    ? { kind: 'error', note: data.pipelineUnavailable, onRetry: gm.reload }
    : gm.state

  const untouched = staleDeals(board.data)
  const openDeals = board.data?.totalDeals ?? null

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">The number — {period}</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {activeBrandId ? 'one brand' : 'all brands'}
        </p>
      </header>

      {/* 1 — THE NUMBER. Won money, and the two ways it breaks down. */}
      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Business won"
          wide
          money
          state={pipelineState}
          value={data?.headline ? Math.round(data.headline.won) : null}
          /* The goal is only ever shown over a calendar month — the server returns null for it on
             any other window rather than dividing by a denominator that does not apply. */
          denominator={
            data?.headline?.goal
              ? `${formatMoney(Math.round(data.headline.goal))} goal · ${data.headline.pctToGoal}% there`
              : 'No monthly goal set — SALES_MONTHLY_GOAL'
          }
          tone={
            data?.headline?.pctToGoal == null
              ? undefined
              : data.headline.pctToGoal >= 100
                ? 'good'
                : data.headline.pctToGoal >= 70
                  ? 'warn'
                  : 'bad'
          }
          note="Opportunities GHL marked won in this period, across every desk of the selling brand."
        />

        <ChartCard
          title="Business by source"
          wide
          state={emptyWhen(pipelineState, (data?.bySource.length ?? 0) === 0, `No wins ${period}.`)}
          note="The same won money, split by the source GHL holds on the opportunity."
        >
          <MoneyBars
            rows={(data?.bySource ?? []).map((row) => ({
              label: row.source,
              value: Math.round(row.value),
              detail: `${row.deals} deal${row.deals === 1 ? '' : 's'}`,
            }))}
          />
        </ChartCard>

        <ChartCard
          title="Business by service"
          wide
          state={emptyWhen(gm.state, (data?.byService.length ?? 0) === 0, 'No cases yet.')}
          /* Stated on the tile because the two charts above and here do NOT sum to the same
             total and never will: a source lives on a GHL opportunity, a service lives on an
             EvalOS case, and a case exists only after a deal is won. */
          note="Open and delivered case value by service. A case, not a deal — these two charts have different denominators."
        >
          <MoneyBars
            rows={(data?.byService ?? []).map((row) => ({
              label: readable(row.serviceType),
              value: Math.round(row.openValue + row.deliveredValue),
              detail: `${row.openCases} open · ${row.delivered} delivered`,
            }))}
          />
        </ChartCard>
      </div>

      {/* 2 — SALES */}
      <Heading>Sales</Heading>
      <div className="mt-3 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="New leads"
          state={pipelineState}
          value={data?.sales ? data.sales.newLeads : null}
          denominator={
            data?.sales ? `worth ${formatMoney(Math.round(data.sales.newValue))}` : undefined
          }
          to="/opportunities/board"
          note={`Opportunities opened on a sales desk ${period}.`}
        />
        <KpiCard
          title="Won"
          money
          state={pipelineState}
          value={data?.sales ? Math.round(data.sales.wonValue) : null}
          denominator={
            data?.sales
              ? `${data.sales.won} deal${data.sales.won === 1 ? '' : 's'}`
              : undefined
          }
          delta={
            data?.sales?.wonDeltaPct == null
              ? undefined
              : { value: data.sales.wonDeltaPct, better: 'up' }
          }
          note="Against the previous period of the same length."
        />
        <KpiCard
          title="Open deals"
          state={board.state}
          value={openDeals}
          denominator={
            board.data ? `worth ${formatMoney(Math.round(board.data.totalValue))}` : undefined
          }
          to="/opportunities/board"
          note="Live from GHL, every desk of the selling brand."
        />
        {/* The PDF's "open, no movement". `bad` above zero rather than against a tolerance: one
            deal nobody has touched in a working week is already the problem, and a threshold
            would only decide how many of them are acceptable. */}
        <KpiCard
          title={`No movement ${STALE_DAYS}d+`}
          state={board.state}
          value={board.data ? untouched.length : null}
          tone={board.data === null ? undefined : untouched.length > 0 ? 'bad' : 'good'}
          denominator={
            board.data
              ? `worth ${formatMoney(Math.round(untouched.reduce((sum, deal) => sum + (deal.amount ?? 0), 0)))}`
              : undefined
          }
          to="/opportunities/board"
          note="Open deals with no activity in GHL for a working week."
        />

        <Card
          title="By desk"
          wide
          state={emptyWhen(
            pipelineState,
            (data?.desks.length ?? 0) === 0,
            'No salesperson has a GHL pipeline assigned. Assign one on the team screen.',
          )}
          /* Keyed on `team_member.ghl_pipeline_id`, not on GHL's `assignedTo`: EvalOS has no
             mapping from a GHL user to a team member, and the pipeline link is the one it owns. */
          note="One row per desk — the pipeline that person works, as Unit 36 assigned it."
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <th className="pb-1 text-left text-xs font-medium uppercase">Desk</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">New</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Won</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Value</th>
              </tr>
            </thead>
            <tbody>
              {data?.desks.map((row) => (
                <tr key={row.memberId}>
                  <td className="py-1">
                    {row.name}
                    {row.role === 'MARKETING' && (
                      <span className="ml-2 text-xs" style={{ color: 'var(--text-muted)' }}>
                        marketing
                      </span>
                    )}
                  </td>
                  <td className="font-num py-1 text-right tabular-nums">{row.newLeads}</td>
                  <td className="font-num py-1 text-right tabular-nums">{row.won}</td>
                  <td className="font-num py-1 text-right tabular-nums">
                    {formatMoney(Math.round(row.wonValue))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>

      {/* 3 — MARKETING */}
      <Heading>Marketing</Heading>
      <div className="mt-3 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="New leads"
          state={pipelineState}
          value={data?.marketing ? data.marketing.newLeads : null}
          denominator={
            data?.marketing ? `worth ${formatMoney(Math.round(data.marketing.newValue))}` : undefined
          }
          note={`Opportunities opened on a marketing desk ${period}.`}
        />
        {/* The PDF's "leads with no source — needs fixing". Amber rather than red: unattributed
            leads are a measurement failure, not an operational one, and painting it red would put
            it beside "3 cases are late" as if they cost the same. */}
        <KpiCard
          title="Leads with no source"
          state={pipelineState}
          value={data?.marketing?.noSourcePct ?? null}
          unit="%"
          tone={
            data?.marketing?.noSourcePct == null
              ? undefined
              : data.marketing.noSourcePct >= 25
                ? 'warn'
                : 'good'
          }
          note="Share of this period's opportunities GHL holds no source for — attribution that needs fixing at the form, not here."
        />
      </div>

      {/* 4 — EXPERT MANAGEMENT */}
      <Heading>Expert network</Heading>
      <div className="mt-3 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Experts"
          state={roster.state}
          value={roster.data ? roster.data.roster.available + roster.data.roster.atCapacity : null}
          denominator={
            roster.data
              ? `${roster.data.roster.inactive + roster.data.roster.onLeave} inactive of ${roster.data.roster.total}`
              : undefined
          }
          to="/experts"
          note="Available and at capacity — the bench that can actually take work."
        />
        <KpiCard
          title="Onboarded"
          state={roster.state}
          value={roster.data ? roster.data.onboarding.thisMonth : null}
          denominator={roster.data ? `of ${roster.data.onboarding.target} this month` : undefined}
          tone={
            roster.data === null
              ? undefined
              : roster.data.onboarding.thisMonth >= roster.data.onboarding.target
                ? 'good'
                : 'warn'
          }
          to="/experts"
          note="Experts whose agreement was signed this calendar month."
        />
        <KpiCard
          title="Offer acceptance"
          state={roster.state}
          value={roster.data?.acceptance.ratePct ?? null}
          unit="%"
          denominator={roster.data ? `${roster.data.acceptance.resolved} offers resolved` : undefined}
          tone={
            roster.data?.acceptance.ratePct == null
              ? undefined
              : roster.data.acceptance.ratePct >= 70
                ? 'good'
                : 'warn'
          }
          note="How often the roster says yes."
        />
        <KpiCard
          title="Coverage gaps"
          state={roster.state}
          value={roster.data ? roster.data.coverage.filter((row) => row.gap).length : null}
          tone={
            roster.data === null
              ? undefined
              : roster.data.coverage.some((row) => row.gap)
                ? 'bad'
                : 'good'
          }
          to="/experts"
          note="Fields with fewer than five available experts."
        />
      </div>

      {/* 5 — EVALUATION DEPARTMENT */}
      <Heading>Evaluation department</Heading>
      <div className="mt-3 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Open cases"
          state={gm.state}
          value={data ? data.evaluation.openCases : null}
          denominator={
            data ? `worth ${formatMoney(Math.round(data.evaluation.openValue))}` : undefined
          }
          to="/board"
          note="Paid work in the shop — not yet delivered, not closed."
        />
        <KpiCard
          title="Delivered"
          state={gm.state}
          value={data ? data.evaluation.delivered : null}
          denominator={
            data ? `worth ${formatMoney(Math.round(data.evaluation.deliveredValue))}` : undefined
          }
          to="/delivery"
          note={`Signed and sent ${period}.`}
        />
        {/* Late is past the promised date. `atRiskNow` beside it is the wider band — deliberately
            two tiles, because one word answering two definitions is how a dashboard stops being
            believed. */}
        <KpiCard
          title="Late"
          state={gm.state}
          value={data ? data.evaluation.late : null}
          tone={data === null ? undefined : data.evaluation.late > 0 ? 'bad' : 'good'}
          to="/board?urgent=1"
          note="Open cases already past their promised date."
        />
        <KpiCard
          title="At risk"
          state={pm.state}
          value={pm.data ? pm.data.atRiskNow : null}
          denominator={
            pm.data && pm.data.onTime.ratePct !== null
              ? `${pm.data.onTime.ratePct}% delivered on time`
              : undefined
          }
          tone={pm.data === null ? undefined : pm.data.atRiskNow > 0 ? 'warn' : 'good'}
          to="/board?urgent=1"
          note="Late, or inside the red band against the stage SLA."
        />

        <Card
          title="Open cases by service"
          wide
          state={emptyWhen(gm.state, (data?.byService.length ?? 0) === 0, 'Nothing in the shop.')}
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <th className="pb-1 text-left text-xs font-medium uppercase">Service</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Open</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Worth</th>
                <th className="pb-1 text-right text-xs font-medium uppercase">Delivered</th>
              </tr>
            </thead>
            <tbody>
              {data?.byService.map((row) => (
                <tr key={row.serviceType}>
                  <td className="py-1">{readable(row.serviceType)}</td>
                  <td className="font-num py-1 text-right tabular-nums">{row.openCases}</td>
                  <td className="font-num py-1 text-right tabular-nums">
                    {formatMoney(Math.round(row.openValue))}
                  </td>
                  <td className="font-num py-1 text-right tabular-nums">{row.delivered}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        {/* The two money figures the PDF has no tile for and the GM still owns. Collected and
            recognised are not repeated here — "business won" and "delivered, worth" above answer
            the same two questions on this screen's own window. */}
        <KpiCard
          title="Open liability"
          state={revenue.state}
          money
          value={revenue.data ? Math.round(revenue.data.total.openLiability) : null}
          denominator={
            revenue.data ? `${revenue.data.openCases} paid and not yet delivered` : undefined
          }
          tone={revenue.data === null ? undefined : revenue.data.openCases > 0 ? 'warn' : 'good'}
          note="Money taken for work not yet delivered — the refund exposure."
        />
        <KpiCard
          title="Refunded"
          state={revenue.state}
          money
          value={revenue.data ? Math.round(revenue.data.total.refunded) : null}
          tone={revenue.data && revenue.data.total.refunded > 0 ? 'bad' : undefined}
          to="/payouts"
          note="Shown beside the others and counted inside none of them."
        />
      </div>
    </section>
  )
}

function Heading({ children }: { children: string }) {
  return <h2 className="mt-8 text-lg font-semibold tracking-tight">{children}</h2>
}

/**
 * A horizontal money bar chart — the PDF's two breakdowns.
 *
 * **Horizontal, because the labels are words.** "Credential evaluation" and "Application
 * Form--" do not fit under a vertical bar at this width, and a rotated axis label is a chart
 * asking to be read sideways. The rows are also already sorted by value, which a horizontal
 * layout reads top-down.
 */
function MoneyBars({
  rows,
}: {
  rows: readonly { label: string; value: number; detail: string }[]
}) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={[...rows]} layout="vertical" margin={{ top: 4, right: 12, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border-default)" horizontal={false} />
        <XAxis
          type="number"
          tickFormatter={(value: number) => formatMoney(value)}
          tick={{ fontSize: 11, fill: 'var(--text-muted)' }}
          tickLine={false}
          axisLine={false}
        />
        <YAxis
          type="category"
          dataKey="label"
          width={110}
          tick={{ fontSize: 11, fill: 'var(--text-muted)' }}
          tickLine={false}
          axisLine={false}
        />
        <Tooltip
          cursor={{ fill: 'var(--bg-raised)' }}
          contentStyle={{
            background: 'var(--bg-surface)',
            border: '1px solid var(--border-default)',
            borderRadius: 'var(--radius-md)',
            fontSize: '0.8125rem',
          }}
          // The exact amount on hover, with the count beside it: $14k over one deal and $14k over
          // twenty are not the same claim about a channel.
          formatter={(value, _name, item) =>
            [`${formatMoney(Number(value))} (${item?.payload?.detail ?? ''})`, 'Value'] as [
              string,
              string,
            ]
          }
        />
        <Bar dataKey="value" fill="var(--accent)" radius={[0, 4, 4, 0]} maxBarSize={18} />
      </BarChart>
    </ResponsiveContainer>
  )
}

/** `CREDENTIAL_EVALUATION` → `Credential evaluation`. Same helper the PM's chart uses. */
function readable(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
