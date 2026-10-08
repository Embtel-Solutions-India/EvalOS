import { useState } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Card, type CardState } from "../../components/ui/card";
import { PillButton, PillToggle } from "../../components/ui/widgets";
import { formatCount, formatMoney } from "../../lib/money";
import { changePct } from "./journeyMath";
import type { GmTrend } from "./pmMetricsApi";

const MUTED = { color: "var(--text-muted)" };
const day = (iso: string) =>
  new Date(`${iso}T12:00:00Z`).toLocaleDateString("en-US", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });

/**
 * Are leads (or wins) rising or falling — over the period the shell's filter names, against the period
 * of equal length before it.
 *
 * <p>The slices come from the server already cut (daily up to a month, wider beyond), so this only draws
 * them. The comparison line is the previous slice at the same offset, not the same calendar date: day 3 of
 * this period beside day 3 of the last.
 */
export function TrendCard({
  trend,
  state,
  period,
}: {
  trend: GmTrend | null;
  state: CardState;
  period: string;
}) {
  const [metric, setMetric] = useState<"leads" | "won">("leads");
  const [compare, setCompare] = useState(true);
  const money = metric === "won";
  const format = (n: number) =>
    money ? formatMoney(Math.round(n)) : formatCount(n);
  const comparing = compare && !!trend?.comparable;

  const rows = (trend?.points ?? []).map((p) => ({
    name: day(p.start),
    current: metric === "leads" ? p.leads : p.won,
    previous: metric === "leads" ? p.previousLeads : p.previousWon,
  }));
  const total = rows.reduce((n, r) => n + r.current, 0);
  const before = rows.reduce((n, r) => n + r.previous, 0);
  const change = trend?.comparable ? changePct(total, before) : null;

  return (
    <Card
      title={`${money ? "Won value" : "New leads"} · sales desks`}
      state={
        state.kind === "ok" && trend === null
          ? { kind: "error", note: "The server sent no trend for this period." }
          : state.kind === "ok" && rows.length === 0
            ? { kind: "empty", note: `No data ${period}.` }
            : state
      }
      action={
        <div className="flex flex-wrap items-center justify-end gap-2">
          <PillToggle
            value={metric}
            onChange={setMetric}
            options={[
              ["leads", "Leads"],
              ["won", "Won"],
            ]}
            label="Trend measure"
          />
          <PillButton
            on={comparing}
            disabled={!trend?.comparable}
            onClick={() => setCompare((on) => !on)}
            title={
              trend?.comparable
                ? undefined
                : "The previous period reaches back further than the won-deal lookback"
            }
          >
            vs previous period
          </PillButton>
        </div>
      }
    >
      <p className="font-num mb-2 flex flex-wrap items-baseline gap-x-2 text-sm tabular-nums">
        <span className="text-xl font-semibold">{format(total)}</span>
        <span style={MUTED}>{period}</span>
        {comparing && (
          <span style={MUTED}>
            vs {format(before)}
            {change !== null && (
              <span
                className="ml-1 font-medium"
                style={{
                  color:
                    change >= 0 ? "var(--status-green)" : "var(--status-red)",
                }}
              >
                {change > 0 ? "▲" : change < 0 ? "▼" : "•"} {Math.abs(change)}%
              </span>
            )}
          </span>
        )}
      </p>
      <div
        className="h-52 w-full"
        role="img"
        aria-label={`${money ? "Won value" : "New leads"} ${period}: ${format(total)}${comparing ? `, previous period ${format(before)}` : ""}`}
      >
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart
            data={rows}
            margin={{ top: 8, right: 8, bottom: 0, left: 0 }}
          >
            <defs>
              {/* Each line casts a shadow of its own colour that fades out toward the x axis. */}
              <linearGradient id="trendNow" x1="0" y1="0" x2="0" y2="1">
                <stop
                  offset="0%"
                  stopColor="var(--accent-primary)"
                  stopOpacity={0.28}
                />
                <stop
                  offset="100%"
                  stopColor="var(--accent-primary)"
                  stopOpacity={0}
                />
              </linearGradient>
              <linearGradient id="trendBefore" x1="0" y1="0" x2="0" y2="1">
                <stop
                  offset="0%"
                  stopColor="var(--text-muted)"
                  stopOpacity={0.18}
                />
                <stop
                  offset="100%"
                  stopColor="var(--text-muted)"
                  stopOpacity={0}
                />
              </linearGradient>
            </defs>
            <CartesianGrid vertical={false} stroke="var(--border-default)" />
            <XAxis
              dataKey="name"
              tickLine={false}
              axisLine={false}
              minTickGap={24}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            />
            <YAxis
              width={48}
              allowDecimals={false}
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
              tickFormatter={(n: number) =>
                money ? `$${Math.round(n / 100) / 10}k` : String(n)
              }
            />
            <Tooltip
              formatter={(n) => format(Number(n))}
              contentStyle={{
                borderRadius: 12,
                border: "1px solid var(--border-tint)",
                boxShadow: "var(--shadow-pop)",
                fontSize: 12,
              }}
            />
            {/* Drawn first, so this period's line and shadow sit over it. */}
            {comparing && (
              <Area
                type="monotone"
                dataKey="previous"
                name="Previous period"
                stroke="var(--text-muted)"
                strokeOpacity={0.7}
                strokeWidth={1.75}
                strokeDasharray="5 4"
                fill="url(#trendBefore)"
                dot={false}
                activeDot={false}
              />
            )}
            <Area
              type="monotone"
              dataKey="current"
              name="This period"
              stroke="var(--accent-primary)"
              strokeWidth={2.5}
              fill="url(#trendNow)"
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </Card>
  );
}
