import { useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useMemo, useState } from "react";
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
import { Avatar, Donut } from "../../components/ui/widgets";
import { useMe } from "../../lib/authContext";
import { formatPayout } from "../../lib/money";
import PaymentForm from "./PaymentForm";
import { fetchBatch } from "./payoutApi";
import {
  mondayOf,
  weekLabel,
  type BatchView,
  type ExpertGroup,
  type LedgerRow,
} from "./payoutRules";

/**
 * The payment batch: the week somebody works down on payout day.
 *
 * **One screen, not two.** The original design had a payout list and a separate "weekly batch"
 * view; they were the same rows, the same week and the same actions, so building both would
 * have been building it twice.
 *
 * Grouped by expert rather than listed flat, because the unit of payment is the transfer and
 * one transfer covers every draft that expert delivered — the grouping *is* the workflow. A
 * flat list would make the ENM do the addition that the server is about to refuse them for
 * getting wrong.
 *
 * **Above the groups, the week is drawn** (2026-10-09): how much of it has gone out, when the rest falls
 * due, who gets what, and which drafts cannot be settled because nobody set a fee. All of it is derived
 * from the rows the groups already list, so there is no second read to disagree with them.
 *
 * Writes are hidden from anyone outside the three roles that may record a payout. The server
 * refuses them either way (`PayoutService.requireMayRecord`, re-checked below `@PreAuthorize`);
 * this only avoids offering a button that answers 403 — the Unit 10 lesson.
 */

type LoadState =
  | { status: "loading" }
  | { status: "ready"; view: BatchView }
  | { status: "failed"; message: string };

const MAY_RECORD = ["GM", "BRAND_MANAGER", "EXPERT_NETWORK_MANAGER"];
const MUTED = { color: "var(--text-muted)" } as const;
const TOOLTIP = {
  borderRadius: 12,
  border: "1px solid var(--border-tint)",
  boxShadow: "var(--shadow-pop)",
  fontSize: 12,
} as const;
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function PayoutBatch() {
  const me = useMe();
  const [weekOf, setWeekOf] = useState<string | null>(null);
  const [settling, setSettling] = useState<ExpertGroup | null>(null);

  const mayRecord = MAY_RECORD.includes(me.role);

  // Unit 70a phase 2: a payment recorded elsewhere, or an expert's confirmation, shows without a reload.
  const query = useQuery({
    queryKey: ["payouts", "batch", weekOf],
    queryFn: ({ signal }) => fetchBatch(weekOf, signal),
  });
  const load = () => query.refetch();
  const state: LoadState = query.data
    ? { status: "ready", view: query.data }
    : query.isError
      ? {
          status: "failed",
          message: query.error.message || "Could not load this week",
        }
      : { status: "loading" };

  const view = state.status === "ready" ? state.view : null;

  // Remaining is derived here rather than sent: it is due minus paid by definition, and a
  // second figure the server also computes is a second thing that can disagree.
  const remaining = useMemo(() => (view ? view.due - view.paid : 0), [view]);

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p
            className="text-[11px] font-semibold tracking-[0.08em] uppercase"
            style={MUTED}
          >
            Payment batch
          </p>
          <h1 className="mt-1 text-xl font-semibold tracking-tight">
            {view ? weekLabel(view.weekStart, view.weekEnd) : "Weekly payouts"}
          </h1>
        </div>

        <div className="flex items-center gap-2 text-sm">
          <WeekStep
            label="Previous week"
            disabled={!view}
            onClick={() => view && setWeekOf(shiftWeek(view.weekStart, -7))}
          >
            <ChevronLeft className="h-4 w-4" aria-hidden />
          </WeekStep>
          <label className="flex items-center gap-2">
            <span style={MUTED}>Week of</span>
            {/* Native date input, not a picker library: the platform already ships one, and the
                value is normalised to that week's Monday so any day in a week finds its week. */}
            <input
              type="date"
              value={view?.weekStart ?? ""}
              onChange={(e) =>
                setWeekOf(e.target.value ? mondayOf(e.target.value) : null)
              }
              className="rounded-xl border-0 px-3 py-2 text-sm"
              style={{
                background: "var(--bg-surface)",
                boxShadow: "var(--shadow-soft)",
              }}
            />
          </label>
          <WeekStep
            label="Next week"
            disabled={!view}
            onClick={() => view && setWeekOf(shiftWeek(view.weekStart, 7))}
          >
            <ChevronRight className="h-4 w-4" aria-hidden />
          </WeekStep>
        </div>
      </header>

      {state.status === "loading" && (
        <p className="text-sm" style={MUTED}>
          Loading this week…
        </p>
      )}

      {state.status === "failed" && (
        <p className="text-sm" style={{ color: "var(--status-red)" }}>
          {state.message}
        </p>
      )}

      {view && (
        <>
          <Widgets view={view} remaining={remaining} />

          {view.groups.length === 0 ? (
            <p className="text-sm" style={MUTED}>
              Nothing is owed for this week.
            </p>
          ) : (
            <div className="flex flex-col gap-5">
              {view.groups.map((group) => (
                <Group
                  key={group.expertId}
                  group={group}
                  mayRecord={mayRecord}
                  onRecord={() => setSettling(group)}
                />
              ))}
            </div>
          )}
        </>
      )}

      {settling && (
        <PaymentForm
          group={settling}
          onCancel={() => setSettling(null)}
          onRecorded={() => {
            setSettling(null);
            void load();
          }}
        />
      )}
    </div>
  );
}

function WeekStep({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className="grid h-9 w-9 place-items-center rounded-full disabled:opacity-40"
      style={{
        background: "var(--bg-surface)",
        boxShadow: "var(--shadow-soft)",
        color: "var(--text-muted)",
      }}
    >
      {children}
    </button>
  );
}

/** A week earlier or later, as the Monday the server will compute. */
function shiftWeek(weekStart: string, days: number): string {
  const d = new Date(`${weekStart}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return mondayOf(d.toISOString().slice(0, 10));
}

/** Every drawing on this screen, derived from the groups' own rows. */
function Widgets({ view, remaining }: { view: BatchView; remaining: number }) {
  const money = (value: number) => formatPayout(value, view.currency);
  const compact = (value: number) => compactMoney(value, view.currency);
  const rows = view.groups.flatMap((g) => g.drafts);
  const unset = rows.filter((r) => r.amount === null);
  const sentPct = view.due > 0 ? Math.round((view.paid / view.due) * 100) : 0;
  const overdueOpen = Math.min(view.overdue, Math.max(remaining, 0));
  const stillToSend = Math.max(remaining - overdueOpen, 0);

  return (
    <div className="grid grid-cols-12 gap-5">
      <Tile
        className="col-span-6 xl:col-span-3"
        title="Due this week"
        value={money(view.due)}
        sub={`${rows.length} ${rows.length === 1 ? "draft" : "drafts"} · ${view.groups.length} ${view.groups.length === 1 ? "expert" : "experts"}`}
      />
      <Tile
        className="col-span-6 xl:col-span-3"
        title="Sent"
        value={money(view.paid)}
        sub={`${sentPct}% of the week`}
        bar={{ pct: sentPct, color: "var(--status-green)" }}
      />
      <Tile
        className="col-span-6 xl:col-span-3"
        title="Remaining"
        value={money(remaining)}
        sub={remaining > 0 ? "still to send" : "nothing left to send"}
        bar={{ pct: 100 - sentPct, color: "var(--accent-primary)" }}
      />
      <Tile
        className="col-span-6 xl:col-span-3"
        title="Overdue"
        value={money(view.overdue)}
        sub={view.overdue > 0 ? "past its due date" : "nothing overdue"}
        tone={view.overdue > 0 ? "var(--status-red)" : undefined}
      />

      <Card
        className="col-span-12 md:col-span-6 xl:col-span-4"
        title="Week progress"
        state={
          view.due > 0
            ? { kind: "ok" }
            : { kind: "empty", note: "Nothing is due this week." }
        }
      >
        <div className="flex flex-wrap items-center gap-x-6 gap-y-4">
          <Donut
            size={132}
            centre={`${sentPct}%`}
            caption="sent"
            slices={[
              { name: "Sent", value: view.paid, color: "var(--status-green)" },
              {
                name: "Still to send",
                value: stillToSend,
                color: "var(--accent-primary)",
              },
              {
                name: "Overdue",
                value: overdueOpen,
                color: "var(--status-red)",
              },
            ]}
          />
          <ul className="min-w-0 flex-1 space-y-2 text-sm">
            {[
              ["Sent", view.paid, "var(--status-green)"],
              ["Still to send", stillToSend, "var(--accent-primary)"],
              ["Overdue", overdueOpen, "var(--status-red)"],
            ].map(([label, value, color]) => (
              <li key={label as string} className="flex items-center gap-2">
                <span
                  aria-hidden
                  className="h-2 w-2 shrink-0 rounded-full"
                  style={{ background: color as string }}
                />
                <span className="min-w-0 flex-1 truncate">{label}</span>
                <span className="font-num tabular-nums">
                  {compact(value as number)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      </Card>

      <Card
        className="col-span-12 md:col-span-6 xl:col-span-4"
        title="When it falls due"
        state={
          rows.length > 0
            ? { kind: "ok" }
            : { kind: "empty", note: "Nothing is due this week." }
        }
      >
        <DueByDay view={view} rows={rows} />
      </Card>

      <Card
        className="col-span-12 xl:col-span-4"
        title="Who gets what"
        state={
          view.groups.length > 0
            ? { kind: "ok" }
            : { kind: "empty", note: "Nobody is owed this week." }
        }
      >
        <ByExpert groups={view.groups} currency={view.currency} />
      </Card>

      {unset.length > 0 && (
        <Card
          className="col-span-12"
          title={`${unset.length} ${unset.length === 1 ? "draft has" : "drafts have"} no fee set`}
          state={{ kind: "warning" }}
        >
          <p className="text-sm" style={MUTED}>
            These cannot be settled until somebody decides the fee, so they are
            not in the totals above.
          </p>
          <ul className="mt-3 flex flex-wrap gap-2">
            {unset.slice(0, 12).map((r) => (
              <li key={r.id}>
                <Link
                  to={`/cases/${r.caseId}`}
                  className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium"
                  style={{
                    background: "var(--status-amber-bg)",
                    color: "var(--status-amber)",
                  }}
                >
                  {r.caseCode}
                  <span style={MUTED}>· {r.expertName}</span>
                </Link>
              </li>
            ))}
          </ul>
          {unset.length > 12 && (
            <p className="mt-2 text-xs" style={MUTED}>
              and {unset.length - 12} more
            </p>
          )}
        </Card>
      )}
    </div>
  );
}

function Tile({
  title,
  value,
  sub,
  bar,
  tone,
  className,
}: {
  title: string;
  value: string;
  sub: string;
  bar?: { pct: number; color: string };
  tone?: string;
  className: string;
}) {
  return (
    <Card
      variant="tile"
      className={className}
      title={title}
      state={{ kind: "ok" }}
    >
      <span
        className="font-num block text-2xl leading-none font-semibold tracking-tight tabular-nums"
        style={tone ? { color: tone } : undefined}
      >
        {value}
      </span>
      {bar && (
        <div
          aria-hidden
          className="mt-3 h-1.5 w-full overflow-hidden rounded-full"
          style={{ background: "var(--bg-raised)" }}
        >
          <div
            className="h-full rounded-full"
            style={{
              width: `${Math.min(Math.max(bar.pct, bar.pct > 0 ? 2 : 0), 100)}%`,
              background: bar.color,
            }}
          />
        </div>
      )}
      <p className="font-num mt-1.5 text-xs tabular-nums" style={MUTED}>
        {sub}
      </p>
    </Card>
  );
}

/** The week's money by the day it falls due, with what is already overdue drawn in red. */
function DueByDay({ view, rows }: { view: BatchView; rows: LedgerRow[] }) {
  const start = Date.parse(`${view.weekStart}T00:00:00Z`);
  const days = WEEKDAYS.map((label, i) => ({
    key: new Date(start + i * 86_400_000).toISOString().slice(0, 10),
    name: label,
    "On time": 0,
    Overdue: 0,
  }));
  const earlier = { key: "earlier", name: "Earlier", "On time": 0, Overdue: 0 };
  for (const r of rows) {
    if (r.amount === null) continue;
    const bucket =
      days.find((d) => d.key === r.dueDate.slice(0, 10)) ?? earlier;
    bucket[r.overdue ? "Overdue" : "On time"] += r.amount;
  }
  const data =
    earlier["On time"] + earlier.Overdue > 0 ? [earlier, ...days] : days;
  return (
    <>
      <ul className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {[
          ["On time", "var(--accent-primary)"],
          ["Overdue", "var(--status-red)"],
        ].map(([label, color]) => (
          <li key={label} className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="h-2 w-2 rounded-full"
              style={{ background: color }}
            />
            {label}
          </li>
        ))}
      </ul>
      <div
        className="h-44 w-full"
        role="img"
        aria-label="Amount due each day of the week"
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            margin={{ top: 4, right: 4, bottom: 0, left: 0 }}
          >
            <CartesianGrid vertical={false} stroke="var(--border-default)" />
            <XAxis
              dataKey="name"
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            />
            <YAxis
              width={48}
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
              tickFormatter={(n: number) => compactMoney(n, view.currency)}
            />
            <Tooltip
              cursor={{ fill: "var(--bg-raised)" }}
              contentStyle={TOOLTIP}
              formatter={(n) => formatPayout(Number(n), view.currency)}
            />
            <Bar
              dataKey="On time"
              stackId="d"
              fill="var(--accent-primary)"
              maxBarSize={22}
            />
            <Bar
              dataKey="Overdue"
              stackId="d"
              fill="var(--status-red)"
              radius={[6, 6, 0, 0]}
              maxBarSize={22}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </>
  );
}

function ByExpert({
  groups,
  currency,
}: {
  groups: ExpertGroup[];
  currency: string;
}) {
  const top = [...groups].sort((a, b) => b.subtotal - a.subtotal).slice(0, 6);
  const data = top.map((g) => ({ name: g.expertName, Subtotal: g.subtotal }));
  return (
    <>
      <div
        className="w-full"
        style={{ height: Math.max(data.length * 30 + 20, 110) }}
        role="img"
        aria-label="This week's fees by expert"
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
              width={96}
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
              dataKey="Subtotal"
              fill="var(--chart-1)"
              radius={[0, 6, 6, 0]}
              maxBarSize={16}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
      {groups.length > top.length && (
        <p className="mt-1 text-xs" style={MUTED}>
          and {groups.length - top.length} more below
        </p>
      )}
    </>
  );
}

function Group({
  group,
  mayRecord,
  onRecord,
}: {
  group: ExpertGroup;
  mayRecord: boolean;
  onRecord: () => void;
}) {
  // How much of this expert's week has already gone out: a draft that is not PENDING is sent.
  const sent = group.drafts.reduce(
    (n, d) => n + (d.status !== "PENDING" ? (d.amount ?? 0) : 0),
    0,
  );
  const pct =
    group.subtotal > 0 ? Math.round((sent / group.subtotal) * 100) : 0;
  return (
    <section
      className="rounded-[1.25rem]"
      style={{
        background: "var(--bg-surface)",
        boxShadow: "var(--shadow-soft)",
      }}
    >
      <header className="flex flex-wrap items-center justify-between gap-3 px-5 pt-5 pb-3">
        <div className="flex min-w-0 items-center gap-3">
          <Avatar name={group.expertName} />
          <div className="min-w-0">
            <h2 className="truncate text-base font-semibold">
              {group.expertName}
            </h2>
            <p className="text-xs" style={MUTED}>
              {group.drafts.length}{" "}
              {group.drafts.length === 1 ? "draft" : "drafts"} · {pct}% sent
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className="font-num text-base font-semibold tabular-nums">
            {formatPayout(group.subtotal, group.currency)}
          </span>
          {mayRecord && (
            <button
              type="button"
              onClick={onRecord}
              className="rounded-xl px-4 py-2 text-sm font-semibold text-white"
              style={{ background: "var(--accent-primary)" }}
            >
              Record payment
            </button>
          )}
        </div>
      </header>
      <div
        aria-hidden
        className="mx-5 h-1.5 overflow-hidden rounded-full"
        style={{ background: "var(--bg-raised)" }}
      >
        <div
          className="h-full rounded-full"
          style={{ width: `${pct}%`, background: "var(--status-green)" }}
        />
      </div>

      <div className="overflow-x-auto px-2 pt-2 pb-3">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs" style={MUTED}>
              <th className="px-3 py-2 font-medium">Case</th>
              <th className="px-3 py-2 font-medium">Due</th>
              <th className="px-3 py-2 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody>
            {group.drafts.map((draft) => (
              <tr
                key={draft.id}
                className="border-t"
                style={{ borderColor: "var(--border-default)" }}
              >
                <td className="px-3 py-2">{draft.caseCode}</td>
                <td
                  className="px-3 py-2"
                  style={
                    draft.overdue ? { color: "var(--status-red)" } : undefined
                  }
                >
                  {draft.dueDate.slice(0, 10)}
                  {draft.overdue && " · overdue"}
                </td>
                <td className="font-num px-3 py-2 text-right tabular-nums">
                  {/* Null is not zero: nobody has decided this one yet, and it cannot be settled
                      until somebody does. Rendering it as an amount would hide that. */}
                  {draft.amount === null ? (
                    <span style={{ color: "var(--status-amber)" }}>
                      not set
                    </span>
                  ) : (
                    formatPayout(draft.amount, draft.currency)
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
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
