import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  CapacityBar,
  Card,
  ChartCard,
  KpiCard,
} from "../../components/ui/card";
import type { CardState } from "../../components/ui/card";
import { Donut } from "../../components/ui/widgets";
import { formatCount, formatMoney } from "../../lib/money";
import { rangeLabel, useFilters } from "../shell/filtersContext";
import {
  fetchOpportunityBoard,
  type OpportunityBoard,
} from "../opportunities/opportunityApi";
import { STALE_DAYS, staleDeals } from "./dealAge";
import {
  fetchExpertNetworkMetrics,
  fetchGmOverview,
  fetchPmMetrics,
  fetchRevenueMetrics,
  fetchTargets,
  type ExpertNetworkMetrics,
  type GmOverview,
  type GmTrend,
  type GmTrendPoint,
  type PmMetrics,
  type RevenueMetrics,
  type TargetAmount,
} from "./pmMetricsApi";
import { DeskTarget } from "./DeskTarget";
import RevenueGoalCard from "./RevenueGoalCard";
import { SERIES } from "./journeyWidgets";
import { TrendCard } from "./TrendCard";
import { emptyWhen, useMetrics } from "./useMetrics";

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
  const { activeBrandId, dateRange } = useFilters();

  const gm = useMetrics<GmOverview>(
    (signal) => fetchGmOverview(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
  );
  const revenue = useMetrics<RevenueMetrics>(
    (signal) => fetchRevenueMetrics(activeBrandId, signal),
    [activeBrandId],
  );
  const pm = useMetrics<PmMetrics>(
    (signal) => fetchPmMetrics(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
  );
  const roster = useMetrics<ExpertNetworkMetrics>(
    (signal) => fetchExpertNetworkMetrics(signal),
    [],
  );
  const board = useMetrics<OpportunityBoard>(
    (signal) => fetchOpportunityBoard(signal),
    [],
  );

  const data = gm.data;
  const period = rangeLabel(dateRange).toLowerCase();

  // A desk's monthly target is for a calendar month, so the column exists only while the range is one
  // (the overview names the month in `goalMonth`). Amounts only: progress is on the rows already.
  const targetMonth = data?.headline?.goalMonth ?? null;
  const targets = useMetrics<TargetAmount[]>(
    (signal) =>
      targetMonth ? fetchTargets(targetMonth, signal) : Promise.resolve([]),
    [targetMonth],
  );
  const targetOf = (memberId: string) =>
    targets.data?.find((t) => t.memberId === memberId)?.target ?? null;

  /**
   * The state for a tile fed by the GHL half of `/metrics/gm`.
   *
   * <p>A 200 carrying `pipelineUnavailable` is not a successful read of a zero — it is a failed
   * read the server chose not to fail the whole request over. Rendering it as `ok` would print
   * `$0` won for a month that may have gone well.
   */
  const pipelineState: CardState = data?.pipelineUnavailable
    ? { kind: "error", note: data.pipelineUnavailable, onRetry: gm.reload }
    : gm.state;

  const untouched = staleDeals(board.data);
  const openDeals = board.data?.totalDeals ?? null;

  const rankedServices = (data?.byService ?? [])
    .filter((row) => row.openCases > 0)
    .sort((a, b) => b.openCases - a.openCases);
  const serviceSlices = [
    ...rankedServices.slice(0, SERIES.length).map((row, i) => ({
      name: readable(row.serviceType),
      value: row.openCases,
      color: SERIES[i],
    })),
    {
      name: "Other",
      value: rankedServices
        .slice(SERIES.length)
        .reduce((n, row) => n + row.openCases, 0),
      color: "var(--text-muted)",
    },
  ].filter((slice) => slice.value > 0);

  // A 12-column grid sized by the question each card answers: the headline is 4 beside four 2s, a trend or table
  // is the full 12, a distribution is two 6s, an alert is one of six 2s.
  // Arrows, not lines: the change against the previous period of the same length, from the trend slices the
  // server already cut. Null (no arrow) when the previous period is partial or had nothing to compare to.
  const wonChange = trendChangePct(data?.trend, (p) => Number(p.won), (p) => Number(p.previousWon));
  const leadsChange = trendChangePct(data?.trend, (p) => p.leads, (p) => p.previousLeads);

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-xl font-semibold tracking-tight">
          The number — {period}
        </h1>
        <p
          className="font-num text-xs tabular-nums"
          style={{ color: "var(--text-muted)" }}
        >
          {activeBrandId ? "one brand" : "all brands"}
        </p>
      </header>

      {/* Figures and charts answer "how are we doing"; the two lists at the foot answer "what needs me" and
          "what is the bench". */}
      <div className="mt-4 grid grid-cols-12 gap-5">
        <div className="col-span-12 grid grid-cols-12 content-start gap-5">
          <RevenueGoalCard
            className="col-span-12 md:col-span-4"
            state={pipelineState}
            won={data?.headline ? data.headline.won : null}
            goal={data?.headline?.goal ?? null}
            pctToGoal={data?.headline?.pctToGoal ?? null}
            goalMonth={data?.headline?.goalMonth ?? null}
            change={wonChange}
            onSaved={gm.reload}
          />
          <div className="col-span-12 grid grid-cols-2 gap-5 md:col-span-8">
            <KpiCard
              title="Sales · new leads"
              state={pipelineState}
              value={data?.sales ? data.sales.newLeads : null}
              denominator={
                data?.sales
                  ? `worth ${formatMoney(Math.round(data.sales.newValue))}`
                  : undefined
              }
              delta={leadsChange === null ? undefined : { value: leadsChange, better: "up" }}
              to="/opportunities/board"
            />
            <KpiCard
              title="Sales · won"
              money
              state={pipelineState}
              value={data?.sales ? Math.round(data.sales.wonValue) : null}
              denominator={
                data?.sales
                  ? `${data.sales.won} deal${data.sales.won === 1 ? "" : "s"}`
                  : undefined
              }
              delta={
                data?.sales?.wonDeltaPct == null
                  ? undefined
                  : { value: data.sales.wonDeltaPct, better: "up" }
              }
            />
            <KpiCard
              title="Open deals"
              state={board.state}
              value={openDeals}
              denominator={
                board.data
                  ? `worth ${formatMoney(Math.round(board.data.totalValue))}`
                  : undefined
              }
              to="/opportunities/board"
            />
            <KpiCard
              title="Marketing · new leads"
              state={pipelineState}
              value={data?.marketing ? data.marketing.newLeads : null}
              denominator={
                data?.marketing
                  ? `worth ${formatMoney(Math.round(data.marketing.newValue))}`
                  : undefined
              }
            />
          </div>

          <div className="col-span-12 xl:col-span-7">
            <TrendCard
              trend={data?.trend ?? null}
              state={pipelineState}
              period={period}
            />
          </div>
          <Card
            className="col-span-12 xl:col-span-5"
            title="By desk"
            state={emptyWhen(
              pipelineState,
              (data?.desks.length ?? 0) === 0,
              "No salesperson has a GHL pipeline assigned. Assign one on the team screen.",
            )}
            /* Keyed on `team_member.ghl_pipeline_id`, not on GHL's `assignedTo`: EvalOS has no
             mapping from a GHL user to a team member, and the pipeline link is the one it owns. */
          >
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr style={{ color: "var(--text-muted)" }}>
                    <th className="pb-1 text-left text-xs font-medium uppercase">
                      Desk
                    </th>
                    <th
                      className="pb-1 text-right text-xs font-medium uppercase"
                      title="Deals created in the selected date range"
                    >
                      New
                    </th>
                    <th className="pb-1 text-right text-xs font-medium uppercase">
                      Won
                    </th>
                    <th className="pb-1 text-right text-xs font-medium uppercase">
                      Value
                    </th>
                    <th
                      className="pb-1 text-right text-xs font-medium uppercase"
                      title="Open on the desk's pipelines right now — what their board's header shows"
                    >
                      Open now
                    </th>
                    {targetMonth && (
                      <th
                        className="pb-1 text-right text-xs font-medium uppercase"
                        title="Sales: won value. Marketing: new leads. Click a cell to set the month's target."
                      >
                        Target
                      </th>
                    )}
                  </tr>
                </thead>
                <tbody>
                  {data?.desks.map((row) => (
                    <tr key={row.memberId}>
                      <td className="py-1">
                        <Link
                          to={`/dashboard/${row.role === "MARKETING" ? "marketing" : "sales"}?member=${row.memberId}`}
                          className="hover:underline"
                          style={{ color: "var(--accent-primary)" }}
                        >
                          {row.name}
                        </Link>
                        {row.role === "MARKETING" && (
                          <span
                            className="ml-2 text-xs"
                            style={{ color: "var(--text-muted)" }}
                          >
                            marketing
                          </span>
                        )}
                      </td>
                      <td className="font-num py-1 text-right tabular-nums">
                        {row.newLeads}
                      </td>
                      <td className="font-num py-1 text-right tabular-nums">
                        {row.won}
                      </td>
                      <td className="font-num py-1 text-right tabular-nums">
                        {formatMoney(Math.round(row.wonValue))}
                      </td>
                      <td className="font-num py-1 text-right tabular-nums">
                        {row.open}
                        <span
                          className="ml-1 text-xs"
                          style={{ color: "var(--text-muted)" }}
                        >
                          {formatMoney(Math.round(row.openValue))}
                        </span>
                      </td>
                      {targetMonth && (
                        <td className="py-1 text-right">
                          <DeskTarget
                            row={row}
                            target={targetOf(row.memberId)}
                            known={targets.state.kind === "ok"}
                            month={targetMonth}
                            onSaved={targets.reload}
                          />
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Label>Distribution</Label>
          <ChartCard
            className="col-span-12 md:col-span-6"
            title="Business by source"
            state={emptyWhen(
              pipelineState,
              (data?.bySource.length ?? 0) === 0,
              `No wins ${period}.`,
            )}
            note="The same sales wins, split by the source GHL holds on the opportunity."
          >
            <MoneyBars
              rows={(data?.bySource ?? []).map((row) => ({
                label: row.source,
                value: Math.round(row.value),
                detail: `${row.deals} deal${row.deals === 1 ? "" : "s"}`,
              }))}
            />
          </ChartCard>
          <ChartCard
            className="col-span-12 md:col-span-6"
            title="Business by service"
            state={emptyWhen(
              gm.state,
              (data?.byService.length ?? 0) === 0,
              "No cases yet.",
            )}
            note="Open and delivered case value by service. A case, not a deal — a different denominator from source."
          >
            <MoneyBars
              rows={(data?.byService ?? []).map((row) => ({
                label: readable(row.serviceType),
                value: Math.round(row.openValue + row.deliveredValue),
                detail: `${row.openCases} open · ${row.delivered} delivered`,
              }))}
            />
          </ChartCard>

          <Label>Production</Label>
          <KpiCard
            className="col-span-12 md:col-span-4"
            title="Open cases"
            state={gm.state}
            value={data ? data.evaluation.openCases : null}
            denominator={
              data
                ? `worth ${formatMoney(Math.round(data.evaluation.openValue))}`
                : undefined
            }
            to="/board"
          />
          <KpiCard
            className="col-span-12 md:col-span-4"
            title="Delivered"
            state={gm.state}
            value={data ? data.evaluation.delivered : null}
            denominator={
              data
                ? `worth ${formatMoney(Math.round(data.evaluation.deliveredValue))}`
                : undefined
            }
            to="/delivery"
          />
          <KpiCard
            className="col-span-12 md:col-span-4"
            title="Delivered on time"
            state={pm.state}
            value={pm.data?.onTime.ratePct ?? null}
            unit="%"
            denominator={
              pm.data ? `of ${pm.data.onTime.delivered} delivered` : undefined
            }
            delta={
              pm.data?.onTime.deltaPoints == null
                ? undefined
                : { value: pm.data.onTime.deltaPoints, better: "up", unit: " pts" }
            }
            tone={
              pm.data?.onTime.ratePct == null
                ? undefined
                : pm.data.onTime.ratePct >= 90
                  ? "good"
                  : pm.data.onTime.ratePct >= 75
                    ? "warn"
                    : "bad"
            }
          />
          <Card
            className="col-span-12 md:col-span-6"
            title="Open cases by service"
            state={emptyWhen(
              gm.state,
              serviceSlices.length === 0,
              "Nothing in the shop.",
            )}
          >
            <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
              <Donut
                slices={serviceSlices}
                centre={formatCount(data?.evaluation.openCases ?? 0)}
                caption="open"
                size={128}
              />
              <ul className="min-w-0 flex-1 space-y-1.5 text-sm">
                {serviceSlices.map((slice) => (
                  <li key={slice.name} className="flex items-center gap-2">
                    <span
                      className="h-2 w-2 shrink-0 rounded-full"
                      style={{ background: slice.color }}
                      aria-hidden
                    />
                    <span className="min-w-0 flex-1 truncate">
                      {slice.name}
                    </span>
                    <span className="font-num tabular-nums">
                      {formatCount(slice.value)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </Card>
          <Card
            className="col-span-12 md:col-span-6"
            title="Case manager workload"
            state={emptyWhen(
              pm.state,
              (pm.data?.workload.length ?? 0) === 0,
              "No case manager is carrying cases.",
            )}
          >
            {pm.data?.workload.map((cm) => (
              <CapacityBar
                key={cm.cmId}
                label={cm.name}
                used={cm.active}
                capacity={cm.capacity}
              />
            ))}
          </Card>
        </div>

        <aside className="col-span-12 grid grid-cols-1 items-start gap-5 md:grid-cols-2">
          {/* Late is past the promised date; `At risk` is the wider band — deliberately two rows, because one
              word answering two definitions is how a dashboard stops being believed. `No movement` is `bad`
              above zero rather than against a tolerance: one deal nobody has touched in a working week is
              already the problem. Leads with no source is amber, not red: a measurement failure, not an
              operational one. */}
          <Card title="Needs attention" state={{ kind: "ok" }}>
            <ul className="-mx-2 space-y-1">
              <AttentionRow
                label="Late"
                sub="past promised date"
                value={data ? data.evaluation.late : null}
                tone={
                  data === null
                    ? undefined
                    : data.evaluation.late > 0
                      ? "bad"
                      : "good"
                }
                to="/board?urgent=1"
              />
              <AttentionRow
                label="At risk"
                sub="late or in the red band"
                value={pm.data ? pm.data.atRiskNow : null}
                tone={
                  pm.data === null
                    ? undefined
                    : pm.data.atRiskNow > 0
                      ? "warn"
                      : "good"
                }
                to="/board?urgent=1"
              />
              <AttentionRow
                label={`No movement ${STALE_DAYS}d+`}
                sub="open deals, no activity"
                value={board.data ? untouched.length : null}
                tone={
                  board.data === null
                    ? undefined
                    : untouched.length > 0
                      ? "bad"
                      : "good"
                }
                to="/opportunities/board"
              />
              <AttentionRow
                label="Unassigned"
                sub="paid, no case manager"
                value={pm.data ? pm.data.unassigned : null}
                tone={
                  pm.data === null
                    ? undefined
                    : pm.data.unassigned > 0
                      ? "warn"
                      : "good"
                }
                to="/board"
              />
              <AttentionRow
                label="Coverage gaps"
                sub="fields under 5 available"
                value={
                  roster.data
                    ? roster.data.coverage.filter((row) => row.gap).length
                    : null
                }
                tone={
                  roster.data === null
                    ? undefined
                    : roster.data.coverage.some((row) => row.gap)
                      ? "bad"
                      : "good"
                }
                to="/experts"
              />
              <AttentionRow
                label="Leads with no source"
                sub="attribution to fix"
                value={data?.marketing?.noSourcePct ?? null}
                unit="%"
                tone={
                  data?.marketing?.noSourcePct == null
                    ? undefined
                    : data.marketing.noSourcePct >= 25
                      ? "warn"
                      : "good"
                }
              />
            </ul>
          </Card>

          {/* The money and the bench: neither is an alarm, both are the GM's to own. */}
          <Card title="Money and bench" state={{ kind: "ok" }}>
            <dl className="space-y-3 text-sm">
              <Fact
                label="Open liability"
                sub={
                  revenue.data
                    ? `${revenue.data.openCases} paid, not delivered`
                    : undefined
                }
                value={
                  revenue.data
                    ? formatMoney(Math.round(revenue.data.total.openLiability))
                    : "–"
                }
              />
              <Fact
                label="Refunded"
                value={
                  revenue.data
                    ? formatMoney(Math.round(revenue.data.total.refunded))
                    : "–"
                }
                to="/payouts"
              />
              <Fact
                label="Experts"
                sub={
                  roster.data
                    ? `${roster.data.roster.inactive + roster.data.roster.onLeave} inactive of ${roster.data.roster.total}`
                    : undefined
                }
                value={
                  roster.data
                    ? formatCount(
                        roster.data.roster.available +
                          roster.data.roster.atCapacity,
                      )
                    : "–"
                }
                to="/experts"
              />
              <Fact
                label="Onboarded"
                sub="agreements signed this month"
                value={
                  roster.data
                    ? `${roster.data.onboarding.thisMonth} / ${roster.data.onboarding.target}`
                    : "–"
                }
                to="/experts"
              />
              <Fact
                label="Offer acceptance"
                sub={
                  roster.data
                    ? `${roster.data.acceptance.resolved} offers resolved`
                    : undefined
                }
                value={
                  roster.data?.acceptance.ratePct == null
                    ? "–"
                    : `${roster.data.acceptance.ratePct}%`
                }
              />
            </dl>
          </Card>
        </aside>
      </div>
    </section>
  );
}

/** A quiet level label that spans the grid: the hierarchy is the order, not a louder heading. */
function Label({ children }: { children: string }) {
  return (
    <h2
      className="col-span-12 mt-2 text-xs font-semibold uppercase tracking-wider"
      style={{ color: "var(--text-muted)" }}
    >
      {children}
    </h2>
  );
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
  rows: readonly { label: string; value: number; detail: string }[];
}) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart
        data={[...rows]}
        layout="vertical"
        margin={{ top: 4, right: 12, bottom: 0, left: 0 }}
      >
        <CartesianGrid
          strokeDasharray="3 3"
          stroke="var(--border-default)"
          horizontal={false}
        />
        <XAxis
          type="number"
          tickFormatter={(value: number) => formatMoney(value)}
          tick={{ fontSize: 11, fill: "var(--text-muted)" }}
          tickLine={false}
          axisLine={false}
        />
        <YAxis
          type="category"
          dataKey="label"
          width={110}
          tick={{ fontSize: 11, fill: "var(--text-muted)" }}
          tickLine={false}
          axisLine={false}
        />
        <Tooltip
          cursor={{ fill: "var(--bg-raised)" }}
          contentStyle={{
            background: "var(--bg-surface)",
            border: "1px solid var(--border-default)",
            borderRadius: "var(--radius-md)",
            fontSize: "0.8125rem",
          }}
          // The exact amount on hover, with the count beside it: $14k over one deal and $14k over
          // twenty are not the same claim about a channel.
          formatter={(value, _name, item) =>
            [
              `${formatMoney(Number(value))} (${item?.payload?.detail ?? ""})`,
              "Value",
            ] as [string, string]
          }
        />
        <Bar
          dataKey="value"
          fill="var(--accent-primary)"
          radius={[0, 4, 4, 0]}
          maxBarSize={18}
        >
          {rows.map((row, i) => (
            <Cell
              key={row.label}
              fill={i < SERIES.length ? SERIES[i] : "var(--text-muted)"}
            />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}

/** `CREDENTIAL_EVALUATION` → `Credential evaluation`. Same helper the PM's chart uses. */
function readable(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll("_", " ");
}

/**
 * The change in a trend figure against the previous period of the same length, as a whole percentage.
 * Null — so no arrow — when the previous period is partial (`comparable` is false) or its total is zero:
 * growth from nothing is not a percentage.
 */
function trendChangePct(
  trend: GmTrend | null | undefined,
  now: (point: GmTrendPoint) => number,
  before: (point: GmTrendPoint) => number,
): number | null {
  if (!trend?.comparable || trend.points.length === 0) return null;
  const current = trend.points.reduce((sum, point) => sum + now(point), 0);
  const previous = trend.points.reduce((sum, point) => sum + before(point), 0);
  return previous > 0 ? Math.round(((current - previous) / previous) * 100) : null;
}

/** One row of the alert list: what it is, what it means, and the count as a status pill. */
function AttentionRow({
  label,
  sub,
  value,
  unit,
  tone,
  to,
}: {
  label: string;
  sub: string;
  value: number | null;
  unit?: string;
  tone?: "good" | "warn" | "bad";
  to?: string;
}) {
  const colour =
    tone === "bad"
      ? "red"
      : tone === "warn"
        ? "amber"
        : tone === "good"
          ? "green"
          : null;
  const row = (
    <>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium">{label}</span>
        <span
          className="block truncate text-xs"
          style={{ color: "var(--text-muted)" }}
        >
          {sub}
        </span>
      </span>
      <span
        className="font-num shrink-0 rounded-full px-3 py-1 text-sm font-semibold tabular-nums"
        style={
          colour
            ? {
                background: `var(--status-${colour}-bg)`,
                color: `var(--status-${colour})`,
              }
            : { background: "var(--bg-raised)", color: "var(--text-muted)" }
        }
      >
        {value === null ? "–" : `${formatCount(value)}${unit ?? ""}`}
      </span>
    </>
  );
  const cls = "flex items-center gap-3 rounded-xl px-2 py-2";
  return (
    <li>
      {to ? (
        <Link to={to} className={`${cls} hover:bg-(--bg-raised)`}>
          {row}
        </Link>
      ) : (
        <div className={cls}>{row}</div>
      )}
    </li>
  );
}

/** A label with a small explanation and its figure — the quiet list. */
function Fact({
  label,
  sub,
  value,
  to,
}: {
  label: string;
  sub?: string;
  value: string;
  to?: string;
}) {
  const body = (
    <>
      <dt className="min-w-0">
        <span className="block truncate font-medium">{label}</span>
        {sub && (
          <span
            className="block truncate text-xs"
            style={{ color: "var(--text-muted)" }}
          >
            {sub}
          </span>
        )}
      </dt>
      <dd className="font-num shrink-0 text-base font-semibold tabular-nums">
        {value}
      </dd>
    </>
  );
  return to ? (
    <Link to={to} className="flex items-center justify-between gap-3">
      {body}
    </Link>
  ) : (
    <div className="flex items-center justify-between gap-3">{body}</div>
  );
}
