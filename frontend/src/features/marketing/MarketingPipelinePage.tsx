import { useEffect } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  Card,
  ChartCard,
  KpiCard,
  type CardState,
} from "../../components/ui/card";
import { emptyWhen, useMetrics } from "../dashboards/useMetrics";
import { formatCount, formatMoney } from "../../lib/money";
import { useFilters, type DateRange } from "../shell/filtersContext";
import {
  fetchMarketingPipeline,
  type MarketingFunnel,
  type MarketingPipeline,
  type Outcome,
  type StageFunnel,
} from "./marketingApi";

/**
 * The GM's marketing screen: one of GHL's funnels, stage by stage.
 *
 * **A window onto GHL, not a second copy of it.** Nothing here writes back and nothing is
 * stored — EvalOS still does no marketing, no sales and no invoicing. What this adds is the top
 * of the funnel beside the production numbers that come out of the bottom of it, which the GM
 * could previously only see by leaving the app.
 *
 * **Not brand-scoped, and it says so in the header.** `evalos.ghl.location-id` is a single global
 * setting, so these figures are whatever that one GHL sub-account holds — and since each brand has
 * its own sub-account, that is one brand's funnel rather than a roll-up. EvalOS has no mapping from
 * a location to a brand yet (Unit 25 adds one), so it cannot label or scope these numbers by brand
 * and must not imply it can. Stating the limit on screen is the point: every other screen here is
 * brand-scoped, so a reader would reasonably assume this one is too.
 *
 * **One component, one per funnel, and not by accident.** The Google Ads pipeline and the email
 * marketing pipeline have the same stage shape and answer the same question, so the second screen
 * is this one pointed at a second endpoint. If the two ever need to look different, split then —
 * a prop that switches a layout would be the harder thing to unpick later.
 *
 * @param funnel which endpoint to read — the server maps it to a configured GHL pipeline name
 * @param title what to head the page with **until GHL answers**. The pipeline's real name comes
 *              back in the payload and replaces it, so the heading matches what a GM sees in GHL
 *              rather than what this app decided to call it
 */
export default function MarketingPipelinePage({
  funnel,
  title,
}: {
  funnel: MarketingFunnel;
  title: string;
}) {
  // The shell's own period control. Listed in the deps so changing it refetches — `useMetrics`
  // clears the previous payload first, so last month's numbers never sit under this month's label.
  const { dateRange } = useFilters();
  const { data, state, reload } = useMetrics<MarketingPipeline>(
    (signal) => fetchMarketingPipeline(funnel, dateRange, signal),
    // `funnel` is in the deps for the same reason `dateRange` is: both screens are this one
    // component, so a route change that did not refetch would leave the other funnel's numbers
    // sitting under this one's heading.
    [funnel, dateRange],
  );

  // Every empty state below says WHICH DAYS found nothing, using the server's own window rather
  // than re-deriving it here. An empty funnel is a real answer — "nobody created a lead in the
  // last 30 days" — and it is indistinguishable from a broken integration unless the screen says
  // what it looked at.
  const windowLabel = data ? `${day(data.from, data.to)} – ${day(data.to, data.from)}`
    : "the selected period";
  const nothingInWindow = `No opportunities created ${windowLabel}. Widen the period to see more.`;

  // **Why two of the four cards can go quiet while the other two are exact.** Deal counts come
  // from GHL's own match count — one request per stage, exact at any size. Money and sources are a
  // sum and a group-by, which GHL does not aggregate, so they need every row: ~11.4k of them on
  // this pipeline's year, 115 cursor pages that cannot be parallelised. At GHL's 100-per-10s rate
  // limit that is ~13s of pacing minimum, past this client's 15s timeout — so the server totals a
  // window that large on a background thread and this screen picks the answer up by polling.
  //
  // Never a partial total in the meantime: a sum over whichever rows arrived so far reads exactly
  // like a finished one.
  const totalling = data?.detail === "TOTALLING";
  const missingDetail =
    data && data.detail !== "READY"
      ? totalling
        ? `Totalling ${formatCount(data.totalDeals)} deals ${windowLabel}. The counts beside this are already exact — money and sources are still being read from GHL and will appear here on their own.`
        : `${formatCount(data.totalDeals)} deals ${windowLabel} — too many to total one by one. The counts beside this are exact; narrow the period for money and sources.`
      : null;

  // **Polling, and only while the server says it is still working.** `reload` is the hook's own
  // refetch, so a poll goes down the same path as the first load and lands in the same cache on
  // the server — there is no second endpoint and no job id to hold. It stops the moment `detail`
  // leaves TOTALLING, which includes UNAVAILABLE: a background read that failed is finished, and
  // polling a failure forever is how a spinner becomes permanent.
  useEffect(() => {
    if (!totalling) return;
    const timer = setInterval(reload, TOTALLING_POLL_MS);
    return () => clearInterval(timer);
  }, [totalling, reload]);

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">
          {data?.pipelineName ?? title}
        </h1>
        <p
          className="font-num text-sm tabular-nums"
          style={{ color: "var(--text-muted)" }}
        >
          {/* Two facts a reader needs before trusting a number here, and both are easy to assume
              wrongly: this is ONE GHL sub-account, not a roll-up across the brands, and it is not
              this second's — the payload is cached and states its own age.

              This said "all brands" until the first real credential arrived. That was wrong: each
              brand has its own GHL sub-account, so the configured location holds one brand's
              funnel. Saying "all brands" over one brand's numbers is exactly the
              header-contradicting-the-instrument failure this project keeps finding. */}
          one GHL location ·{" "}
          {data ? `${data.range} · ${windowLabel}` : "loading"}
          {data ? ` · read ${clockTime(data.readAt)}` : ""}
        </p>
      </header>


      <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Deals in pipeline"
          wide
          state={state}
          value={data?.totalDeals ?? null}
          // Won and lost rather than the stage count, which said nothing a reader wanted. Both
          // come from the STAGE NAME, not GHL's `status` field — see `Outcome` in marketingApi.
          denominator={
            data
              ? `${dealsIn(data, "WON")} won · ${dealsIn(data, "LOST")} lost`
              : undefined
          }
          note="Opportunities created in the selected period, counted by the stage they are in now — a deal in a stage named Won counts as won, whatever GHL's own status field says."
        />
        <KpiCard
          title="Pipeline value"
          wide
          money
          // `empty` rather than a zero: this card's whole failure mode is a number that looks
          // computed and is not. Its copy is written for the reader and names what to do.
          state={missingDetail ? { kind: "empty", note: missingDetail } : state}
          value={data?.totalValue == null ? null : Math.round(data.totalValue)}
          denominator={
            data?.totalValue == null
              ? undefined
              : `${formatMoney(perDeal(data))} average per deal`
          }
          note="Summed from each opportunity's own value in GHL. Unpriced opportunities count as nothing."
        />

        <ChartCard
          title={`Leads by stage — ${PERIOD_LABEL[dateRange]}`}
          wide
          note="Opportunities created in this period, counted in the stage they are in now. Hover for exact figures."
          state={chartState(state, data)}
        >
          {data && (
            <StageBars
              stages={data.stages}
              empty={data.totalDeals === 0}
              period={PERIOD_LABEL[dateRange]}
            />
          )}
        </ChartCard>

        <Card
          title="Sources"
          wide
          note="GHL's own source on each opportunity, passed through as written."
          state={emptyWhen(
            state,
            (data?.sources.length ?? 0) === 0,
            missingDetail ?? nothingInWindow,
          )}
        >
          <table className="w-full text-sm">
            <thead>
              <tr style={{ color: "var(--text-muted)" }}>
                <th className="pb-1 text-left text-xs font-medium uppercase">
                  Source
                </th>
                <th className="pb-1 text-right text-xs font-medium uppercase">
                  Deals
                </th>
                <th className="pb-1 text-right text-xs font-medium uppercase">
                  Value
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.sources.map((row) => (
                <tr key={row.source}>
                  <td className="py-1">{row.source}</td>
                  <td className="font-num py-1 text-right tabular-nums">
                    {row.deals}
                  </td>
                  <td className="font-num py-1 text-right tabular-nums">
                    {formatMoney(row.value)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
    </section>
  );
}

/**
 * The chart card's state.
 *
 * <p><strong>An empty period is deliberately NOT the card's `empty` state.</strong> That state
 * replaces the chart with a line of text, and losing the axes loses the useful half of the answer:
 * which stages exist and that every one of them is at zero. The chart draws its own empty message
 * instead, over a real axis — see {@link StageBars}.
 *
 * <p>What is still guarded here is a payload missing `stages` altogether. `stages` is declared
 * non-optional and a running server is not the server the client was compiled against — that exact
 * mismatch threw inside render an hour ago and took the whole page down with it, because a throw
 * in React render is a white screen, not a degraded card.
 */
function chartState(
  state: CardState,
  data: MarketingPipeline | null,
): CardState {
  if (data && !data.stages) {
    return {
      kind: "error",
      note: "The server did not send stage figures for this period. Restart the backend.",
    };
  }
  return state;
}

/**
 * How often to re-ask while the server is totalling in the background.
 *
 * Five seconds against a read that takes twenty to thirty: a handful of polls, each one a cache
 * hit on the server until the last, which is the one that carries the money. Faster would spend
 * requests on an answer that is not ready; slower would leave a finished total sitting unread.
 */
const TOTALLING_POLL_MS = 5_000;

/** What the shell's period control means in a heading. */
const PERIOD_LABEL: Record<DateRange, string> = {
  today: "today",
  week: "this week",
  month: "this month",
  year: "this year",
};

/**
 * Lead count per stage, as one horizontal bar per stage.
 *
 * <p><strong>Bars rather than a line, and that is the honest form for this data.</strong> A line
 * across the stages implied a progression through them, which this pipeline does not have — Won,
 * Cold and Lost are parallel outcomes rather than steps after Hot, so the slope between two points
 * meant nothing while looking like it meant something. Bars carry only length, which is exactly the
 * one thing the data supports. It is also why this screen still prints no conversion percentage
 * between adjacent stages.
 *
 * <p>Horizontal because stage names are words: six of them along a bottom axis either truncate or
 * tilt, and neither reads. Down the left they simply fit.
 */
function StageBars({
  stages,
  empty,
  period,
}: {
  stages: StageFunnel[];
  empty: boolean;
  period: string;
}) {
  return (
    <div className="relative h-full w-full">
      <ResponsiveContainer width="100%" height="100%">
        {/* `layout="vertical"` is Recharts' name for a horizontal bar chart: it puts the CATEGORY
            axis on the left and the NUMBER axis along the bottom. So the two axis components below
            swap roles rather than swapping places — XAxis becomes the numeric one and YAxis takes
            the `dataKey`. Getting that backwards renders an empty grid with no error. */}
        <BarChart
          data={stages}
          layout="vertical"
          margin={{ top: 4, right: 28, bottom: 0, left: 8 }}
        >
          {/* Grid lines run with the number axis, so vertical now rather than horizontal. */}
          <CartesianGrid
            strokeDasharray="3 3"
            stroke="var(--border-default)"
            horizontal={false}
          />
          {/* Leads, along the bottom. Whole numbers only, and a fixed 0–1 floor when empty so the
              axis does not collapse and leave the message floating over nothing. */}
          <XAxis
            type="number"
            allowDecimals={false}
            domain={empty ? [0, 1] : [0, "auto"]}
            tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            tickLine={false}
            axisLine={false}
          />
          {/* Stages, down the left, in GHL's own order. `width` is explicit because a category axis
              defaults to 60px and "New Lead" does not fit in it. */}
          <YAxis
            type="category"
            dataKey="name"
            width={78}
            tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            tickLine={false}
            axisLine={false}
          />
          {!empty && (
            <Tooltip
              cursor={{ fill: "var(--bg-raised)" }}
              contentStyle={{
                background: "var(--bg-surface)",
                border: "1px solid var(--border-default)",
                borderRadius: "var(--radius-md)",
                fontSize: "0.8125rem",
              }}
              formatter={(value, _name, item) =>
                [
                  `${value} leads${item?.payload?.sharePct == null ? "" : ` · ${item.payload.sharePct}% of period`}`,
                  "In this stage",
                ] as [string, string]
              }
            />
          )}
          {/* No bars at all when the period is empty. A row of zero-length bars reads as "measured
              zero", which is a different claim from "nothing was created" — and the message below
              says which. */}
          {!empty && (
            <Bar dataKey="deals" radius={[0, 4, 4, 0]} maxBarSize={22}>
              {/* Darkest at the top of the funnel, lightening down it, so the eye reads the order
                  as well as the lengths. Mixed from two existing tokens rather than a new one, and
                  deliberately clear of --status-red/amber/green: RAG is load-bearing in this app
                  and six bars in traffic-light colours would read as stages in trouble. */}
              {stages.map((stage, index) => (
                <Cell
                  key={stage.stageId}
                  fill={shadeFor(index, stages.length)}
                />
              ))}
              {/* The count at the end of its own bar. A bar chart people read for "how many" should
                  not make them measure against a gridline. */}
              <LabelList
                dataKey="deals"
                position="right"
                offset={8}
                style={{ fontSize: 11, fill: "var(--text-muted)" }}
              />
            </Bar>
          )}
        </BarChart>
      </ResponsiveContainer>

      {empty && (
        <p
          className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm"
          style={{ color: "var(--text-muted)" }}
        >
          No lead opportunities found {period}.
        </p>
      )}
    </div>
  );
}

/**
 * A stage's bar colour: the accent, thinned into the card it sits on.
 *
 * Mixed from two existing tokens so there is no hex here and no new token for a ramp one screen
 * draws, and clear of `--status-red/amber/green` because RAG is load-bearing in this app.
 * Deliberately not `--chart-1..5` either: that is a *categorical* ramp of five and its own note
 * refuses a sixth, while this needs an ordered ramp of however many stages GHL has.
 *
 * **The second token is the card, not the rail, and that is the whole point.** This mixed
 * `--accent-primary` into `--sidebar-bg` and traversed only 40%-90% of it. Both endpoints are
 * dark blue — 3.09:1 apart — so half of an already-narrow range left adjacent bars 1.13:1 apart
 * and the whole funnel spanning 1.81:1. Measured in Chrome, not eyeballed. At that separation the
 * ramp is not subtle, it is absent: six bars render as one colour and the order the ramp exists
 * to show is unreadable. Mixing into `--bg-surface` instead runs the full 30%-100% and puts
 * adjacent bars ~1.28:1 apart with 3.34:1 end to end.
 *
 * Still a single hue, so it stays an *ordered* ramp rather than a categorical one. Adjacent
 * separation is a function of stage count and thins out past six or so; if GHL ever returns a
 * pipeline long enough for that to matter, the answer is grouping the tail, not more hues.
 */
function shadeFor(index: number, count: number): string {
  const pct = count < 2 ? 100 : 100 - Math.round((index / (count - 1)) * 70);
  return `color-mix(in srgb, var(--accent-primary) ${pct}%, var(--bg-surface))`;
}

/**
 * Average value per deal, or 0 where the average has no meaning to state — an empty pipeline, or a
 * period whose money was never totalled. Only ever read when `totalValue` is present.
 */
function perDeal(data: MarketingPipeline): number {
  return data.totalDeals === 0 || data.totalValue == null
    ? 0
    : data.totalValue / data.totalDeals;
}

/**
 * How many deals sit in stages meaning one outcome.
 *
 * Summed from the server's per-stage `outcome` rather than matched on stage names here: the rule
 * for which names mean what is a domain rule and lives in one place, on the server. A pipeline
 * with no stage of that kind sums to 0, which is the true answer.
 */
function dealsIn(data: MarketingPipeline, outcome: Outcome): number {
  return data.stages
    .filter((stage) => stage.outcome === outcome)
    .reduce((total, stage) => total + stage.deals, 0);
}

/**
 * A window edge, short form: "7 Jul", or "25 Aug 2025" when the window straddles a year boundary.
 *
 * **The year is conditional, not omitted.** This said "both edges share it in every range" and
 * that was simply false: a Year window is 365 days back, so its edges are always in different
 * years — and it rendered as "Aug 25 – Aug 25", a label that reads as a single day sitting over
 * a figure covering twelve months. Week and Month hit the same thing across New Year.
 *
 * The year is shown on *both* edges or neither, because "25 Aug 2025 – 25 Aug" reads as a typo.
 */
function day(iso: string, otherEdge: string): string {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    ...(iso.slice(0, 4) === otherEdge.slice(0, 4) ? {} : { year: "numeric" }),
  });
}

/** Local clock time, so "read 14:32" is checkable against the reader's own watch. */
function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
}
