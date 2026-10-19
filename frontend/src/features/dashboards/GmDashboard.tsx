import { useState } from "react";
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
import { Avatar, Donut } from "../../components/ui/widgets";
import { ChevronRight } from "lucide-react";
import { fetchAssignable } from "../board/boardApi";
import type { PickerOption } from "../board/boardRules";
import type { Role } from "../../lib/session";
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
  type ExpertNetworkMetrics,
  type GmOverview,
  type GmTrend,
  type GmTrendPoint,
  type PmMetrics,
  type RevenueMetrics,
} from "./pmMetricsApi";
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
/** The roles the Team card lists, grouped into departments. */
const DEPARTMENTS: { name: string; roles: Role[]; dashboard?: "sales" | "marketing" }[] = [
  { name: "Sales", roles: ["SALES"], dashboard: "sales" },
  { name: "Marketing", roles: ["MARKETING"], dashboard: "marketing" },
  { name: "Production", roles: ["PROJECT_MANAGER", "PROJECT_COORDINATOR", "CASE_MANAGER"] },
];
const TEAM_ROLES = DEPARTMENTS.flatMap((department) => department.roles);

export default function GmDashboard() {
  const { activeBrandId, dateRange } = useFilters();

  const gm = useMetrics<GmOverview>(
    (signal) => fetchGmOverview(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
    // Mirror-backed since 2026-10-13, so it polls like the desk dashboards instead of waiting for a tab focus.
    { key: 'GmDashboard:0', refreshEvery: 60_000 },
  );
  const revenue = useMetrics<RevenueMetrics>(
    (signal) => fetchRevenueMetrics(activeBrandId, signal),
    [activeBrandId],
    { key: 'GmDashboard:1' },
  );
  const pm = useMetrics<PmMetrics>(
    (signal) => fetchPmMetrics(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
    { key: 'GmDashboard:2' },
  );
  const roster = useMetrics<ExpertNetworkMetrics>(
    (signal) => fetchExpertNetworkMetrics(signal),
    [],
    { key: 'GmDashboard:3' },
  );
  const board = useMetrics<OpportunityBoard>(
    (signal) => fetchOpportunityBoard(signal),
    [],
    { key: 'GmDashboard:4' },
  );

  const data = gm.data;
  const today = data?.today ?? null;
  const period = rangeLabel(dateRange).toLowerCase();

  // The whole team by department: the same picker list the assignment screens read, one call per role.
  const team = useMetrics<Record<Role, PickerOption[]>>(
    async (signal) => {
      const lists = await Promise.all(TEAM_ROLES.map((role) => fetchAssignable(role, signal)));
      return Object.fromEntries(TEAM_ROLES.map((role, i) => [role, lists[i]])) as Record<Role, PickerOption[]>;
    },
    [],
    { key: 'GmDashboard:5' },
  );
  const membersOf = (d: (typeof DEPARTMENTS)[number]) =>
    d.roles.flatMap((role) => (team.data?.[role] ?? []).map((member) => ({ ...member, role })));
  const [departmentName, setDepartmentName] = useState(DEPARTMENTS[0].name);
  const department = DEPARTMENTS.find((d) => d.name === departmentName) ?? DEPARTMENTS[0];

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
  // A server older than these tiles answers without `today`; saying so beats printing a 0 that is not a figure.
  const todayState: CardState =
    gm.state.kind === "ok" && data && !data.pipelineUnavailable && !today
      ? { kind: "error", note: "Not available from the server yet", onRetry: gm.reload }
      : pipelineState;
  // Same rule for the stage tiles: a server without them is "not available", never a 0.
  const stageState = (stage: unknown): CardState =>
    todayState.kind === "ok" && !stage
      ? { kind: "error", note: "Not available from the server yet", onRetry: gm.reload }
      : todayState;
  const up = (value: number | null | undefined) =>
    value == null ? undefined : { value, better: "up" as const };

  const untouched = staleDeals(board.data);
  const sourceWon = (data?.bySource ?? []).reduce(
    (sum, row) => ({ value: sum.value + row.value, deals: sum.deals + row.deals }),
    { value: 0, deals: 0 },
  );

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

  return (
    <section>
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
          {/* Two rows of four beside the revenue card, whatever the range filter says. Top: today's revenue, then the
              open deals standing in New leads / Hot / Invoice sent right now (count and value). Bottom: Sales and
              Marketing leads today and this month. Arrows: today against yesterday, this month against the same days
              of last month. Two to a row on a phone. */}
          <div className="col-span-12 grid grid-cols-2 gap-5 md:col-span-8 md:grid-cols-4">
            <KpiCard
              title="Today's revenue"
              delta={up(today?.changes?.wonValue)}
              money
              state={todayState}
              value={today ? Math.round(today.wonValue) : null}
            />
            {/* Where the open deals stand right now, whatever the range says: a count with what it is worth. */}
            {([
              ["New leads", today?.newLeads],
              ["Hot leads", today?.hot],
              ["Invoice sent", today?.invoiceSent],
            ] as const).map(([title, stage]) => (
              <KpiCard
                key={title}
                title={title}
                note="Open deals in this stage right now, on every desk's pipelines"
                state={stageState(stage)}
                value={stage ? stage.deals : null}
                to="/opportunities/board"
                action={
                  stage && (
                    <p className="font-num mt-1.5 text-sm tabular-nums" style={{ color: "var(--text-muted)" }}>
                      {formatMoney(Math.round(stage.value))}
                    </p>
                  )
                }
              />
            ))}
            <KpiCard
              title="Sales · today's leads"
              delta={up(today?.changes?.salesLeads)}
              state={todayState}
              value={today ? today.salesLeads : null}
              to="/opportunities/board"
            />
            <KpiCard
              title="Sales · this month"
              delta={up(today?.changes?.monthSalesLeads)}
              state={todayState}
              value={today ? today.monthSalesLeads : null}
              to="/opportunities/board"
            />
            <KpiCard
              title="Marketing · today's leads"
              delta={up(today?.changes?.marketingLeads)}
              state={todayState}
              value={today ? today.marketingLeads : null}
            />
            <KpiCard
              title="Marketing · this month"
              delta={up(today?.changes?.monthMarketingLeads)}
              state={todayState}
              value={today ? today.monthMarketingLeads : null}
            />
          </div>

          {/* The trend and the team, side by side from xl; stacked below that. */}
          <div className="col-span-12 xl:col-span-6">
            <TrendCard
              trend={data?.trend ?? null}
              state={pipelineState}
              period={period}
            />
          </div>
          {/* The trend card sets the row's height; from xl the team fills exactly that and scrolls inside it. Below
              xl the card stacks and grows with its list (capped, so a long team still scrolls). */}
          <div className="relative col-span-12 xl:col-span-6">
          <Card
            className="max-h-[32rem] xl:absolute xl:inset-0 xl:max-h-none"
            fill
            title="Team"
            state={emptyWhen(
              team.state,
              TEAM_ROLES.every((role) => (team.data?.[role]?.length ?? 0) === 0),
              "No active team members yet.",
            )}
          >
            {/* Every active member, a tab per department. A Sales or Marketing member opens their own dashboard
                (the GM's drill-down, D76); Production has no one-person dashboard yet, so those rows are names only. */}
            <div className="flex gap-1.5" role="tablist" aria-label="Departments">
              {DEPARTMENTS.map((d) => {
                const selected = d.name === department.name;
                return (
                  <button
                    key={d.name}
                    type="button"
                    role="tab"
                    aria-selected={selected}
                    onClick={() => setDepartmentName(d.name)}
                    className="h-8 px-3 text-sm"
                    style={{
                      borderRadius: "var(--radius-md)",
                      background: selected ? "var(--accent-soft)" : "var(--bg-surface)",
                      border: `1px solid ${selected ? "var(--accent-primary)" : "var(--border-default)"}`,
                      color: selected ? "var(--accent-primary)" : "var(--text-primary)",
                    }}
                  >
                    {d.name} <span className="font-num tabular-nums" style={{ color: "var(--text-muted)" }}>{membersOf(d).length}</span>
                  </button>
                );
              })}
            </div>
            <ul className="-mx-2 mt-3" role="tabpanel" aria-label={department.name}>
              {membersOf(department).length === 0 && (
                <li className="px-2 py-3 text-sm" style={{ color: "var(--text-muted)" }}>No active {department.name.toLowerCase()} members.</li>
              )}
              {membersOf(department).map((member, i) => {
                const body = (
                  <>
                    <Avatar name={member.label} colour={SERIES[i % SERIES.length]} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{member.label}</span>
                      <span className="block text-xs" style={{ color: "var(--text-muted)" }}>
                        {readable(member.role)}
                      </span>
                    </span>
                  </>
                );
                const href = department.dashboard && `/dashboard/${department.dashboard}?member=${member.id}`;
                return (
                  <li key={member.id}>
                    {href ? (
                      <Link
                        to={href}
                        title={`Open ${member.label}'s performance`}
                        className="flex items-center gap-3 rounded-lg p-2 hover:bg-(--bg-raised)"
                      >
                        {body}
                        <ChevronRight className="h-4 w-4 shrink-0" style={{ color: "var(--text-muted)" }} aria-hidden />
                      </Link>
                    ) : (
                      <div className="flex items-center gap-3 p-2">{body}</div>
                    )}
                  </li>
                );
              })}
            </ul>
          </Card>
          </div>

          <Card
            className="col-span-12 md:col-span-6"
            title="Business by source"
            note="The same sales wins, split by the lead source on the opportunity."
            action={
              sourceWon.deals > 0 ? (
                <span className="font-num text-xs tabular-nums" style={{ color: "var(--text-muted)" }}>
                  {formatMoney(Math.round(sourceWon.value))} · {formatCount(sourceWon.deals)} deal{sourceWon.deals === 1 ? "" : "s"}
                </span>
              ) : undefined
            }
            state={emptyWhen(
              pipelineState,
              (data?.bySource.length ?? 0) === 0,
              `No wins ${period}.`,
            )}
          >
            {/* A ranked list, not a chart: source names are long, there can be many, and a win with no amount
                still has to show. Each row's bar is its share of the won value (of the deals, if nothing had a value). */}
            <ul className="max-h-60 space-y-3 overflow-y-auto pr-1">
              {(data?.bySource ?? []).map((row, i) => {
                const share = sourceWon.value > 0 ? row.value / sourceWon.value : row.deals / Math.max(sourceWon.deals, 1);
                const unknown = row.source === "Unattributed";
                const colour = unknown ? "var(--status-amber)" : i < SERIES.length ? SERIES[i] : "var(--text-muted)";
                return (
                  <li key={row.source}>
                    <div className="flex items-baseline gap-2 text-sm">
                      <span className="h-2 w-2 shrink-0 self-center rounded-full" style={{ background: colour }} aria-hidden />
                      <span className="min-w-0 flex-1 truncate" title={row.source} style={unknown ? { color: "var(--status-amber)" } : undefined}>
                        {row.source}
                      </span>
                      <span className="font-num shrink-0 font-semibold tabular-nums">{formatMoney(Math.round(row.value))}</span>
                    </div>
                    <div className="mt-1 flex items-center gap-2 pl-4">
                      <span className="h-1.5 flex-1 overflow-hidden rounded-full" style={{ background: "var(--bg-raised)" }} aria-hidden>
                        <span className="block h-full rounded-full" style={{ width: `${Math.max(share * 100, 2)}%`, background: colour }} />
                      </span>
                      <span className="font-num w-24 shrink-0 text-right text-xs tabular-nums" style={{ color: "var(--text-muted)" }}>
                        {row.deals} deal{row.deals === 1 ? "" : "s"} · {Math.round(share * 100)}%
                      </span>
                    </div>
                  </li>
                );
              })}
            </ul>
          </Card>
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

          <KpiCard
            className="col-span-12 md:col-span-4"
            title="Open cases"
            state={gm.state}
            value={data ? data.evaluation.openCases : null}
            to="/board"
          />
          <KpiCard
            className="col-span-12 md:col-span-4"
            title="Delivered"
            state={gm.state}
            value={data ? data.evaluation.delivered : null}
            to="/delivery"
          />
          <KpiCard
            className="col-span-12 md:col-span-4"
            title="Delivered on time"
            state={pm.state}
            value={pm.data?.onTime.ratePct ?? null}
            unit="%"
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
 * Null — so no arrow — when the previous period's total is zero: growth from nothing is not a percentage.
 */
function trendChangePct(
  trend: GmTrend | null | undefined,
  now: (point: GmTrendPoint) => number,
  before: (point: GmTrendPoint) => number,
): number | null {
  if (!trend || trend.points.length === 0) return null;
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
