import { Link } from "react-router-dom";
import {
  AlertTriangle,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Ban,
  RotateCw,
} from "lucide-react";
import type { ReactNode } from "react";
import { formatCount, formatMoney } from "../../lib/money";

/**
 * The dashboard card system: one shell, one state union, and the specialisations that actually
 * differ.
 *
 * **Six kinds, not the brief's seven components.** The brief lists KPI, alert, queue, summary,
 * chart, progress and list cards. Three of those — alert, queue, summary, list — are this shell
 * with different children and no different behaviour, so they are not separate components until
 * they diverge. What genuinely differs is how a *figure* is drawn ({@link KpiCard}), how a chart
 * reserves its space ({@link ChartCard}), and how capacity maps to RAG ({@link CapacityBar}).
 */

export type CardState =
  /** Data is on the way. Draws a skeleton, never a zero. */
  | { kind: "loading" }
  | { kind: "ok" }
  /** Correct data that needs attention — unassigned cases above zero, a coverage gap. */
  | { kind: "warning" }
  | { kind: "error"; note: string; onRetry?: () => void }
  /**
   * Nothing to show, and that is an operational statement rather than a missing value.
   * `note` is written for the reader: "All incoming cases are assigned", not "No data".
   *
   * **Not the same as zero.** A figure whose rows sum to zero renders `0` through
   * {@link KpiCard} — an empty month is an answer, and a tile that goes blank reads as broken.
   */
  | { kind: "empty"; note: string }
  /**
   * The metric cannot be computed yet because the unit that writes its data is unbuilt.
   *
   * The honest alternative to a zero. `blockedBy` names the unit so the tile explains itself
   * rather than looking broken — a tile naming a metric it cannot compute is the
   * header-contradicting-the-instrument failure this project keeps finding.
   */
  | { kind: "unavailable"; blockedBy: string };

type CardProps = {
  title: string;
  /** Optional one-line explanation of what the figure means. */
  note?: string;
  state: CardState;
  /**
   * Where clicking goes. **Presence of this prop is the whole clickability rule**: with it the
   * card renders as a link and gets the affordance, without it there is none. That is what stops
   * every tile looking interactive, and it makes "clicking 12 at risk opens that queue" a prop
   * rather than a convention somebody has to remember.
   */
  to?: string;
  /** Spans two columns in the dashboard grid — the role's PRIMARY KPI, per `ui-context.md`. */
  wide?: boolean;
  children?: ReactNode;
};

export function Card({ title, note, state, to, wide, children }: CardProps) {
  const interactive = to !== undefined && state.kind !== "loading";

  const body = (
    <>
      <div className="flex items-start justify-between gap-2">
        <h2 className="text-sm font-medium">{title}</h2>
        {state.kind === "warning" && (
          <AlertTriangle
            className="h-4 w-4 shrink-0"
            style={{ color: "var(--status-amber)" }}
            aria-hidden
          />
        )}
        {interactive && (
          <ArrowRight
            className="h-4 w-4 shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
            style={{ color: "var(--accent-primary)" }}
            aria-hidden
          />
        )}
      </div>

      <div className="mt-3">{renderState(state, children)}</div>

      {note && state.kind !== "unavailable" && (
        <p className="mt-3 text-sm" style={{ color: "var(--text-muted)" }}>
          {note}
        </p>
      )}
    </>
  );

  const className = `group block rounded-lg border p-5 text-left ${wide ? "md:col-span-2" : ""}`;
  const style = {
    background: "var(--bg-surface)",
    borderColor:
      state.kind === "warning"
        ? "var(--status-amber)"
        : "var(--border-default)",
    boxShadow: "var(--shadow-card)",
  };

  if (interactive) {
    return (
      <Link to={to} className={className} style={style}>
        {body}
      </Link>
    );
  }
  return (
    <article className={className} style={style}>
      {body}
    </article>
  );
}

function renderState(state: CardState, children: ReactNode): ReactNode {
  switch (state.kind) {
    case "loading":
      return (
        <div
          className="h-8 w-28 animate-pulse rounded-md"
          style={{ background: "var(--bg-raised)" }}
          role="status"
          aria-label="Loading"
        />
      );
    case "unavailable":
      return (
        <p
          className="flex items-start gap-2 text-sm"
          style={{ color: "var(--text-muted)" }}
        >
          <Ban className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span>
            Not available until {state.blockedBy} ships the data behind it.
          </span>
        </p>
      );
    case "empty":
      return (
        <p className="text-sm" style={{ color: "var(--text-muted)" }}>
          {state.note}
        </p>
      );
    case "error":
      return (
        <div className="text-sm" style={{ color: "var(--status-red)" }}>
          <p>{state.note}</p>
          {state.onRetry && (
            <button
              type="button"
              onClick={state.onRetry}
              className="mt-2 inline-flex items-center gap-1 font-medium"
              style={{ color: "var(--accent-primary)" }}
            >
              <RotateCw className="h-3.5 w-3.5" aria-hidden />
              Try again
            </button>
          )}
        </div>
      );
    default:
      return children;
  }
}

/**
 * A figure, its denominator, and how it moved.
 *
 * **The denominator is not decoration.** `17-dashboards.md`'s rule: "100%" over two cases must
 * not read the same as "100%" over two hundred. So a rate always renders what it is a rate *of*.
 */
/**
 * How this figure is doing, which decides the colour of the number itself.
 *
 * **This is status use, not decoration**, and so is a legitimate call on the RAG tokens: the
 * number *is* the health indicator on a KPI tile. A tile with no health reading — a plain count
 * whose value is neither good nor bad — passes nothing and stays in the primary text colour.
 */
export type KpiTone = "good" | "warn" | "bad";

const TONE_COLOR: Record<KpiTone, string> = {
  good: "var(--status-green)",
  warn: "var(--status-amber)",
  bad: "var(--status-red)",
};

export function KpiCard({
  title,
  note,
  state,
  to,
  wide,
  value,
  money,
  unit,
  denominator,
  delta,
  tone,
}: Omit<CardProps, "children"> & {
  value: number | null;
  /**
   * Render the figure as an amount — `$86,950` — instead of a bare count.
   *
   * **Opt-in, and it has to stay that way.** A currency symbol is a claim about what a number
   * *is*, so only the caller can make it — defaulting it on would put `$93` on a deal count and
   * `$94%` on a rate across all 28 tiles.
   *
   * That is the failure this prevents, not one this tile shipped: it previously rendered a bare
   * `{value ?? 0}` with no currency symbol anywhere in this file. Stated because an earlier
   * version of this note described the unconditional `$` as history, and a fix credited to a bug
   * that never happened is one somebody later removes as dead caution.
   */
  money?: boolean;
  unit?: string;
  denominator?: string;
  /** Change against the previous comparable period. Omitted when there is nothing to compare. */
  delta?: { value: number; better: "up" | "down" };
  tone?: KpiTone;
}) {
  return (
    <Card title={title} note={note} state={state} to={to} wide={wide}>
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span
          className={`font-num tabular-nums ${wide ? "text-[2.25rem] leading-none" : "text-3xl leading-none"} font-semibold tracking-tight`}
          style={{ color: tone ? TONE_COLOR[tone] : "var(--text-primary)" }}
        >
          {(money ? formatMoney : formatCount)(value ?? 0)}
          {unit}
        </span>
        {delta && <Delta {...delta} />}
      </div>
      {denominator && (
        <p
          className="font-num mt-1.5 text-sm tabular-nums"
          style={{ color: "var(--text-muted)" }}
        >
          {denominator}
        </p>
      )}
    </Card>
  );
}

function Delta({ value, better }: { value: number; better: "up" | "down" }) {
  // A rise is not automatically good: on-time delivery wants "up", revision rate wants "down".
  // The caller says which, so no tile has to be read against an assumption.
  const good = value === 0 ? null : value > 0 === (better === "up");
  const color =
    good === null
      ? "var(--text-muted)"
      : good
        ? "var(--status-green)"
        : "var(--status-red)";
  const Arrow = value > 0 ? ArrowUp : value < 0 ? ArrowDown : null;

  return (
    <span
      className="font-num inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-medium tabular-nums"
      style={{
        color,
        background:
          good === null
            ? "var(--bg-raised)"
            : `color-mix(in srgb, ${color} 10%, transparent)`,
      }}
    >
      {Arrow && <Arrow className="h-3 w-3" aria-hidden />}
      {/* The direction is in the sign as well as the arrow: an arrow alone is a shape, and
          shape plus colour with no text is two signals that both fail the same way. */}
      {Math.abs(value)}
      <span className="sr-only">
        {value > 0 ? "up" : value < 0 ? "down" : "unchanged"} versus the
        previous period
      </span>
    </span>
  );
}

/** A chart's frame, so every chart shares the card's states rather than each inventing an empty. */
export function ChartCard({
  title,
  note,
  state,
  to,
  wide,
  children,
}: CardProps) {
  return (
    <Card title={title} note={note} state={state} to={to} wide={wide}>
      <div className="h-56 w-full">{children}</div>
    </Card>
  );
}

/**
 * Capacity as a bar, with `ui-context.md`'s fixed bands: green under 70%, amber 70–90%, red
 * above 90%. **Those thresholds are the contract** — a workload indicator does not invent its
 * own, which is why they are here once and not per caller.
 */
export function CapacityBar({
  label,
  used,
  capacity,
}: {
  label: string;
  used: number;
  capacity: number;
}) {
  const pct = capacity > 0 ? Math.round((used / capacity) * 100) : 0;
  const tone = pct > 90 ? "red" : pct >= 70 ? "amber" : "green";
  const color = `var(--status-${tone})`;

  return (
    <div className="py-1.5">
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="truncate">{label}</span>
        <span
          className="font-num tabular-nums"
          style={{ color: "var(--text-muted)" }}
        >
          {used}/{capacity}
          {/* The percentage is stated as text, not only as bar width and colour: status must
              never be carried by colour alone. */}
          <span className="ml-2" style={{ color }}>
            {pct}%
          </span>
        </span>
      </div>
      <div
        className="mt-1 h-1.5 w-full overflow-hidden rounded-md"
        style={{ background: "var(--bg-raised)" }}
        role="img"
        aria-label={`${label}: ${used} of ${capacity} cases, ${pct}% of capacity`}
      >
        <div
          className="h-full rounded-md"
          style={{ width: `${Math.min(pct, 100)}%`, background: color }}
        />
      </div>
    </div>
  );
}
