import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { Card } from "../../components/ui/card";
import { Donut, PillToggle, Sparkline } from "../../components/ui/widgets";
import { formatPayout } from "../../lib/money";
import { rangeLabel, useFilters } from "../shell/filtersContext";
import PayoutSummary from "./PayoutSummary";
import { fetchSummary, type ReportPeriod } from "./payoutApi";
import { fetchExpertTotals, fetchOverview } from "./registerApi";
import {
  STATUS_TONE,
  day,
  type ExpertTotals,
  type Overview,
  type RegisterRow,
  type RegisterStatus,
  type Tile,
} from "./registerRules";
import type { SummaryRow } from "./payoutRules";

/**
 * Where the money stands (Unit 65): the module's front page, drawn rather than tabulated.
 *
 * <p>Every chart answers one question, and the card says which: <em>where is the money</em> (the
 * pipeline donut), <em>is it moving</em> (pending, processing and paid per period), <em>who is owed</em>
 * (outstanding by expert) and <em>how late is it</em> (overdue, by how many days). The exact figures sit
 * in the tooltips, the legends and the table under "Table and exports", which is the Unit 63 summary
 * unchanged.
 *
 * <p><strong>One dashboard per currency.</strong> A GM across brands is never shown USD added to INR.
 * <strong>Three cards do not follow the shell's period, and each says so</strong>: the over-time chart has
 * its own Week / Month / Year, and who-is-owed is every offer still open, because an expert's balance is
 * not a function of the month being looked at.
 */

const STAGES: {
  key: keyof Pick<Overview, "committed" | "pending" | "processing" | "paid">;
  status: RegisterStatus;
  label: string;
  note: string;
}[] = [
  {
    key: "committed",
    status: "ACCEPTED",
    label: "Committed",
    note: "accepted, not yet delivered",
  },
  {
    key: "pending",
    status: "PENDING",
    label: "Pending",
    note: "delivered, not yet sent",
  },
  {
    key: "processing",
    status: "PROCESSING",
    label: "Processing",
    note: "sent, awaiting the expert",
  },
  {
    key: "paid",
    status: "PAID",
    label: "Paid",
    note: "confirmed by the expert",
  },
];

const MUTED = { color: "var(--text-muted)" } as const;
const TOOLTIP = {
  borderRadius: 12,
  border: "1px solid var(--border-tint)",
  boxShadow: "var(--shadow-pop)",
  fontSize: 12,
} as const;

export default function PayoutsOverview() {
  // The shell's period and brand switcher — one filter for the whole app, not a second one here.
  const { dateRange, activeBrandId } = useFilters();
  // Unit 70a phase 2: re-read on focus, after any write, and on a live `case.changed`.
  const query = useQuery({
    queryKey: ["payouts", "overview", dateRange, activeBrandId],
    queryFn: ({ signal }) => fetchOverview(dateRange, activeBrandId, signal),
  });
  const experts = useQuery({
    queryKey: ["payouts", "expert-totals", activeBrandId],
    queryFn: ({ signal }) => fetchExpertTotals(activeBrandId, signal),
  });
  const overviews = query.data ?? null;
  const failure =
    query.isError && !overviews
      ? query.error.message || "Could not load the overview"
      : null;

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Payouts</h1>
          <p className="text-sm" style={MUTED}>
            Fees on cases offered {rangeLabel(dateRange).toLowerCase()}, from
            promised to confirmed
          </p>
        </div>
      </header>

      {failure && (
        <p className="text-sm" style={{ color: "var(--status-red)" }}>
          {failure}
        </p>
      )}
      {!failure && !overviews && (
        <p className="text-sm" style={MUTED}>
          Loading…
        </p>
      )}
      {overviews?.length === 0 && (
        <p className="text-sm" style={MUTED}>
          No case offered in this period has a fee yet. Try a longer period.
        </p>
      )}

      {overviews?.map((o) => (
        <Dashboard
          key={o.currency}
          overview={o}
          showCurrency={overviews.length > 1}
          experts={(experts.data ?? []).filter(
            (e) => e.currency === o.currency,
          )}
          expertsLoaded={experts.data !== undefined}
        />
      ))}

      {/* The Unit 63 summary, unchanged: the exact figures behind the chart, and the two exports. */}
      {overviews && overviews.length > 0 && (
        <details
          className="rounded-[1.25rem] px-5 py-4"
          style={{
            background: "var(--bg-surface)",
            boxShadow: "var(--shadow-soft)",
          }}
        >
          <summary className="cursor-pointer text-base font-semibold">
            Table and exports
          </summary>
          <div className="mt-4">
            <PayoutSummary />
          </div>
        </details>
      )}
    </div>
  );
}

function Dashboard({
  overview,
  showCurrency,
  experts,
  expertsLoaded,
}: {
  overview: Overview;
  showCurrency: boolean;
  experts: ExpertTotals[];
  expertsLoaded: boolean;
}) {
  const [period, setPeriod] = useState<ReportPeriod>("MONTH");
  const summary = useQuery({
    queryKey: ["payouts", "summary", period],
    queryFn: ({ signal }) => fetchSummary(period, signal),
  });
  const money = (value: number) => formatPayout(value, overview.currency);
  const compact = (value: number) => compactMoney(value, overview.currency);
  const rows = (summary.data ?? [])
    .filter((r) => r.currency === overview.currency)
    .sort((a, b) => a.periodStart.localeCompare(b.periodStart));
  const total = STAGES.reduce((sum, s) => sum + overview[s.key].amount, 0);
  const cases = STAGES.reduce((n, s) => n + overview[s.key].count, 0);

  return (
    <section
      aria-label={`Payouts${showCurrency ? ` in ${overview.currency}` : ""}`}
      className="grid grid-cols-12 gap-5"
    >
      {showCurrency && (
        <h2
          className="col-span-12 text-sm font-semibold uppercase tracking-wider"
          style={MUTED}
        >
          In {overview.currency}
        </h2>
      )}

      {/* Where is the money? Four stages as tiles, each with its share and — where a series exists — its shape. */}
      {STAGES.map((s) => (
        <StageTile
          key={s.key}
          className="col-span-6 xl:col-span-3"
          tile={overview[s.key]}
          status={s.status}
          label={s.label}
          note={s.note}
          total={total}
          money={money}
          series={
            s.key === "committed"
              ? undefined
              : rows.map((r) => r[s.key as "pending" | "processing" | "paid"])
          }
          seriesLabel={`${s.label} per ${PERIOD_NOUN[period]}`}
        />
      ))}

      <Card
        className="col-span-12 md:col-span-5 xl:col-span-4"
        title="Where the money is"
        state={{ kind: "ok" }}
      >
        {total === 0 ? (
          <p className="text-sm" style={MUTED}>
            Nothing is committed, owed or paid in this period.
          </p>
        ) : (
          <div className="flex flex-wrap items-center gap-x-6 gap-y-4">
            <Donut
              size={132}
              centre={compact(total)}
              caption={`${cases} ${cases === 1 ? "case" : "cases"}`}
              slices={STAGES.map((s) => ({
                name: s.label,
                value: overview[s.key].amount,
                color: STATUS_TONE[s.status],
              }))}
            />
            <ul className="min-w-0 flex-1 space-y-2 text-sm">
              {STAGES.map((s) => (
                <li key={s.key} className="flex items-center gap-2">
                  <span
                    aria-hidden
                    className="h-2 w-2 shrink-0 rounded-full"
                    style={{ background: STATUS_TONE[s.status] }}
                  />
                  <span className="min-w-0 flex-1 truncate">{s.label}</span>
                  <span className="font-num tabular-nums">
                    {compact(overview[s.key].amount)}
                  </span>
                  <span
                    className="font-num w-9 text-right text-xs tabular-nums"
                    style={MUTED}
                  >
                    {Math.round((overview[s.key].amount / total) * 100)}%
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </Card>

      {/* Is it moving? The ENM's own period, not the shell's. */}
      <Card
        className="col-span-12 md:col-span-7 xl:col-span-8"
        title="Payouts over time"
        action={
          <PillToggle
            value={period}
            onChange={setPeriod}
            label="Report period"
            options={[
              ["WEEK", "Weekly"],
              ["MONTH", "Monthly"],
              ["YEAR", "Yearly"],
            ]}
          />
        }
        state={
          summary.isError && !summary.data
            ? {
                kind: "error",
                note: summary.error.message || "Could not load the summary",
              }
            : !summary.data
              ? { kind: "loading" }
              : rows.length === 0
                ? { kind: "empty", note: "No payouts yet." }
                : { kind: "ok" }
        }
      >
        <OverTime rows={rows} period={period} currency={overview.currency} />
      </Card>

      {/* Who is owed? Every offer still open, whatever the shell's period. */}
      <Card
        className="col-span-12 xl:col-span-7"
        title="Who is owed"
        state={
          !expertsLoaded
            ? { kind: "loading" }
            : experts.length === 0
              ? { kind: "empty", note: "Nobody is owed anything." }
              : { kind: "ok" }
        }
      >
        <WhoIsOwed experts={experts} currency={overview.currency} />
      </Card>

      {/* How late is it? Overdue by how many days, and what was sent and never confirmed. */}
      <Card
        className="col-span-12 xl:col-span-5"
        title="How late"
        state={
          overview.attention.length === 0
            ? {
                kind: "empty",
                note: "Nothing is overdue, and every transfer older than a week has been confirmed.",
              }
            : { kind: "ok" }
        }
      >
        <Lateness rows={overview.attention} />
      </Card>

      <Attention rows={overview.attention} className="col-span-12" />
    </section>
  );
}

const PERIOD_NOUN: Record<ReportPeriod, string> = {
  WEEK: "week",
  MONTH: "month",
  YEAR: "year",
};

function StageTile({
  tile,
  status,
  label,
  note,
  total,
  money,
  series,
  seriesLabel,
  className,
}: {
  tile: Tile;
  status: RegisterStatus;
  label: string;
  note: string;
  total: number;
  money: (value: number) => string;
  series: number[] | undefined;
  seriesLabel: string;
  className: string;
}) {
  const share = total > 0 ? (tile.amount / total) * 100 : 0;
  return (
    <Card
      variant="tile"
      className={className}
      title={label}
      state={{ kind: "ok" }}
      to={`/payouts/cases?status=${status}`}
    >
      <div className="flex items-center justify-between gap-3">
        <span className="font-num text-2xl font-semibold leading-none tracking-tight tabular-nums">
          {money(tile.amount)}
        </span>
        {series && series.length > 1 && (
          <Sparkline
            values={series}
            color={STATUS_TONE[status]}
            label={seriesLabel}
          />
        )}
      </div>
      <div
        aria-hidden
        className="mt-3 h-1.5 w-full overflow-hidden rounded-full"
        style={{ background: "var(--bg-raised)" }}
      >
        <div
          className="h-full rounded-full"
          style={{
            width: `${Math.max(share, tile.amount > 0 ? 2 : 0)}%`,
            background: STATUS_TONE[status],
          }}
        />
      </div>
      <p className="font-num mt-1.5 text-xs tabular-nums" style={MUTED}>
        {Math.round(share)}% · {tile.count}{" "}
        {tile.count === 1 ? "case" : "cases"}, {note}
      </p>
    </Card>
  );
}

function OverTime({
  rows,
  period,
  currency,
}: {
  rows: SummaryRow[];
  period: ReportPeriod;
  currency: string;
}) {
  const data = rows.map((r) => ({
    name: periodLabel(period, r.periodStart),
    Pending: r.pending,
    Processing: r.processing,
    Paid: r.paid,
  }));
  return (
    <>
      <ul className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {(["PENDING", "PROCESSING", "PAID"] as const).map((s) => (
          <li key={s} className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="h-2 w-2 rounded-full"
              style={{ background: STATUS_TONE[s] }}
            />
            {s.charAt(0) + s.slice(1).toLowerCase()}
          </li>
        ))}
      </ul>
      <div
        className="h-56 w-full"
        role="img"
        aria-label={`Pending, processing and paid payouts by ${PERIOD_NOUN[period]}`}
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            margin={{ top: 4, right: 8, bottom: 0, left: 0 }}
          >
            <CartesianGrid vertical={false} stroke="var(--border-default)" />
            <XAxis
              dataKey="name"
              tickLine={false}
              axisLine={false}
              minTickGap={16}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            />
            <YAxis
              width={52}
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
              tickFormatter={(n: number) => compactMoney(n, currency)}
            />
            <Tooltip
              cursor={{ fill: "var(--bg-raised)" }}
              contentStyle={TOOLTIP}
              formatter={(n) => formatPayout(Number(n), currency)}
            />
            <Bar
              dataKey="Paid"
              stackId="p"
              fill={STATUS_TONE.PAID}
              maxBarSize={28}
            />
            <Bar
              dataKey="Processing"
              stackId="p"
              fill={STATUS_TONE.PROCESSING}
              maxBarSize={28}
            />
            <Bar
              dataKey="Pending"
              stackId="p"
              fill={STATUS_TONE.PENDING}
              radius={[6, 6, 0, 0]}
              maxBarSize={28}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </>
  );
}

function WhoIsOwed({
  experts,
  currency,
}: {
  experts: ExpertTotals[];
  currency: string;
}) {
  const owed = (e: ExpertTotals) => e.committed + e.pending + e.processing;
  const top = [...experts]
    .filter((e) => owed(e) > 0)
    .sort((a, b) => owed(b) - owed(a))
    .slice(0, 8);
  const rest = experts.filter((e) => owed(e) > 0).length - top.length;
  const data = top.map((e) => ({
    name: e.expertName ?? "Unnamed expert",
    Committed: e.committed,
    Pending: e.pending,
    Processing: e.processing,
  }));
  if (data.length === 0)
    return (
      <p className="text-sm" style={MUTED}>
        Nobody is owed anything.
      </p>
    );
  return (
    <>
      <ul className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {(["ACCEPTED", "PENDING", "PROCESSING"] as const).map((s) => (
          <li key={s} className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="h-2 w-2 rounded-full"
              style={{ background: STATUS_TONE[s] }}
            />
            {s === "ACCEPTED"
              ? "Committed"
              : s.charAt(0) + s.slice(1).toLowerCase()}
          </li>
        ))}
      </ul>
      <div
        className="w-full"
        style={{ height: Math.max(data.length * 34 + 24, 120) }}
        role="img"
        aria-label="Outstanding fees by expert"
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            layout="vertical"
            margin={{ top: 0, right: 12, bottom: 0, left: 0 }}
          >
            <CartesianGrid horizontal={false} stroke="var(--border-default)" />
            <XAxis
              type="number"
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
              tickFormatter={(n: number) => compactMoney(n, currency)}
            />
            <YAxis
              type="category"
              dataKey="name"
              width={120}
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            />
            <Tooltip
              cursor={{ fill: "var(--bg-raised)" }}
              contentStyle={TOOLTIP}
              formatter={(n) => formatPayout(Number(n), currency)}
            />
            <Bar
              dataKey="Processing"
              stackId="o"
              fill={STATUS_TONE.PROCESSING}
              maxBarSize={16}
            />
            <Bar
              dataKey="Pending"
              stackId="o"
              fill={STATUS_TONE.PENDING}
              maxBarSize={16}
            />
            <Bar
              dataKey="Committed"
              stackId="o"
              fill={STATUS_TONE.ACCEPTED}
              radius={[0, 6, 6, 0]}
              maxBarSize={16}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
      {rest > 0 && (
        <p className="mt-2 text-xs" style={MUTED}>
          and {rest} more ·{" "}
          <Link
            to="/payouts/experts"
            className="font-medium"
            style={{ color: "var(--accent-primary)" }}
          >
            all balances
          </Link>
        </p>
      )}
    </>
  );
}

/** Days past due, in the buckets a reader acts on — and what was sent and never confirmed, which is a different clock. */
function Lateness({ rows }: { rows: RegisterRow[] }) {
  const today = Date.now();
  const bucket = (row: RegisterRow): string =>
    row.status !== "PENDING"
      ? "Sent, unconfirmed 7d+"
      : (() => {
          const days = row.dueDate
            ? Math.floor((today - Date.parse(row.dueDate)) / 86_400_000)
            : 0;
          return days <= 7
            ? "Overdue 1–7d"
            : days <= 14
              ? "Overdue 8–14d"
              : "Overdue 15d+";
        })();
  const order = [
    "Overdue 1–7d",
    "Overdue 8–14d",
    "Overdue 15d+",
    "Sent, unconfirmed 7d+",
  ];
  const groups = order.map((label) => {
    const inGroup = rows.filter((r) => bucket(r) === label);
    return {
      label,
      count: inGroup.length,
      amount: inGroup.reduce((n, r) => n + (r.amount ?? 0), 0),
      currency: inGroup.find((r) => r.currency)?.currency ?? null,
    };
  });
  const max = Math.max(1, ...groups.map((g) => g.count));
  return (
    <ul className="space-y-3">
      {groups.map((g) => (
        <li key={g.label}>
          <div className="flex items-baseline justify-between gap-3 text-sm">
            <span>{g.label}</span>
            <span className="font-num tabular-nums">
              {g.count}{" "}
              <span className="text-xs" style={MUTED}>
                {g.currency && g.amount > 0
                  ? compactMoney(g.amount, g.currency)
                  : ""}
              </span>
            </span>
          </div>
          <div
            aria-hidden
            className="mt-1 h-2 w-full overflow-hidden rounded-full"
            style={{ background: "var(--bg-raised)" }}
          >
            <div
              className="h-full rounded-full"
              style={{
                width: `${(g.count / max) * 100}%`,
                background: g.label.startsWith("Sent")
                  ? "var(--status-amber)"
                  : "var(--status-red)",
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

function Attention({
  rows,
  className,
}: {
  rows: RegisterRow[];
  className: string;
}) {
  if (rows.length === 0) return null;
  return (
    <Card className={className} title="Needs attention" state={{ kind: "ok" }}>
      <ul className="-mx-2">
        {rows.slice(0, 6).map((row) => (
          <li
            key={row.offerId ?? row.payoutId ?? row.caseId}
            className="flex flex-wrap items-center justify-between gap-2 rounded-xl px-2 py-2 text-sm"
          >
            <span>
              <Link
                to={`/cases/${row.caseId}`}
                className="font-medium hover:underline"
              >
                {row.caseCode ?? "Case"}
              </Link>
              <span style={MUTED}> for {row.expertName ?? "an expert"}</span>
            </span>
            <span className="flex items-center gap-3">
              <span className="font-num tabular-nums">
                {row.amount !== null && row.currency
                  ? formatPayout(row.amount, row.currency)
                  : "no amount"}
              </span>
              <span
                className="rounded-full px-3 py-1 text-xs font-medium"
                style={
                  row.status === "PENDING"
                    ? {
                        background: "var(--status-red-bg)",
                        color: "var(--status-red)",
                      }
                    : {
                        background: "var(--status-amber-bg)",
                        color: "var(--status-amber)",
                      }
                }
              >
                {row.status === "PENDING"
                  ? `Overdue since ${day(row.dueDate)}`
                  : `Sent ${day(row.sentAt)}, unconfirmed`}
              </span>
            </span>
          </li>
        ))}
      </ul>
      {rows.length > 6 && (
        <p className="mt-2 text-xs" style={MUTED}>
          and {rows.length - 6} more
        </p>
      )}
    </Card>
  );
}

function periodLabel(period: ReportPeriod, start: string): string {
  return period === "WEEK"
    ? start.slice(5)
    : period === "MONTH"
      ? start.slice(0, 7)
      : start.slice(0, 4);
}

/** `$12.5K` — axes and legends, where the exact figure is one hover away. */
function compactMoney(value: number, currency: string): string {
  try {
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency,
      notation: "compact",
      maximumFractionDigits: 1,
    }).format(value);
  } catch {
    return `${currency} ${Math.round(value)}`;
  }
}
