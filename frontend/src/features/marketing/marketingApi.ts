import { api, unwrap } from '../../lib/api'
import { rangeParams, type DateRange, type NamedRange } from '../shell/filtersContext'

/**
 * `MarketingPipelineService.StageFunnel` — one stage of GHL's funnel.
 *
 * `sharePct` is null when the pipeline holds no deals at all, **not** zero. Same rule as every
 * other rate in this app: an empty pipeline is not a claim that 0% of its deals are in this
 * stage, and a row of 0% across an empty funnel reads as a collapse that did not happen.
 */
/**
 * What a stage means, worked out **from its name** by the server — see
 * `MarketingPipelineService.Outcome`.
 *
 * **Not GHL's `status` field, which cannot be trusted for this.** 144 opportunities in this
 * account sit in the stage named "Won" while only 3 carry `status: "won"`: the rest were dragged
 * into the column without anyone pressing GHL's separate win button. The stage is what actually
 * happened. Matching ignores case, so `Won`/`won`/`WON` are one thing.
 *
 * `Cold` is `OPEN` — it reads like an ending but is not one of GHL's status words, and is not
 * promoted into one.
 */
export type Outcome = 'WON' | 'LOST' | 'ABANDONED' | 'OPEN'

export type StageFunnel = {
  stageId: string
  name: string
  /** How many, from GHL's own match count. **Exact for a period of any size.** */
  deals: number
  /**
   * What they are worth, or **null when the period was too large to total** — see
   * `detailAvailable`. Null, never zero: "not counted" and "worth nothing" are different claims.
   */
  value: number | null
  sharePct: number | null
  outcome: Outcome
}

/**
 * Where the deals came from, as GHL recorded it.
 *
 * `source` is GHL's own free-text opportunity source — not an EvalOS `SourceChannel`. It is
 * whatever the campaign, form or workflow that created the opportunity wrote there, so it is
 * rendered as data and never matched against a vocabulary this app owns.
 */
export type SourceRow = {
  /**
   * The first spelling seen for this source. Rows are grouped **case-insensitively**, so
   * "Application Form" and "Application form" are one row — two rows for one source halves a
   * figure for a reason nothing on screen explains. The label is a real GHL string rather than a
   * canonical casing invented here.
   */
  source: string
  deals: number
  value: number
}

export type MarketingPipeline = {
  pipelineName: string
  totalDeals: number
  /** What the period's deals are worth, or **null when it was too large to total**. */
  totalValue: number | null
  stages: StageFunnel[]
  sources: SourceRow[]
  /** When GHL was actually asked. The payload is cached, so the screen states its own age. */
  readAt: string
  /**
   * The period these figures cover, echoed back by the server.
   *
   * Rendered rather than assumed: if this ever disagrees with the control the user clicked, the
   * server's value is what the numbers actually mean, and showing it is how that becomes visible
   * instead of silent.
   *
   * **A bare wire name, not the shell's `DateRange` union.** For a custom period this is just
   * `'custom'` — the actual days are `from`/`to` below, which the header renders anyway, so
   * echoing the dates twice in two shapes would give them two places to disagree.
   */
  range: NamedRange | 'custom'
  /** First day of the created-at window, inclusive. ISO date, server-computed. */
  from: string
  /** Last day of it, inclusive. Sent so an empty funnel can name the days that found nothing. */
  to: string
  /**
   * Whether the money and source figures are here, coming, or not coming.
   *
   * **The counts never depend on it.** `totalDeals` and every stage's `deals` come from GHL's own
   * match count and are exact whatever the period holds. A *sum* and a *group-by* are different:
   * GHL aggregates neither, so they need every row, and this pipeline's year is ~11.4k of them —
   * 115 cursor pages that cannot be parallelised, which at GHL's 100-requests-per-10s limit is
   * ~13s of pacing minimum and past this client's own 15s timeout.
   *
   * - `READY` — `totalValue`, every stage `value` and `sources` are computed and present.
   * - `TOTALLING` — too many rows to total inside the request. The server is reading them on a
   *   background thread; **poll and the same window comes back `READY`.** Money fields are null
   *   and `sources` is empty until then.
   * - `UNAVAILABLE` — they are not coming: past the server's row ceiling, or the background read
   *   failed. Stop polling and tell the reader to narrow the period.
   *
   * In none of the three may a **partial** total be rendered: a sum over whichever rows fitted
   * looks exactly like a real number.
   */
  detail: Detail
}

/** Mirrors `MarketingPipelineService.Detail`. */
export type Detail = 'READY' | 'TOTALLING' | 'UNAVAILABLE'

/**
 * Which funnel to read, matching `MarketingPipelineService.Funnel` on the server.
 *
 * **A closed set, not a pipeline name.** The GHL location holds seven pipelines and most are
 * other teams' — the server routes each of these to a *configured* name, so what this app can see
 * is a deployment decision rather than something a caller types.
 *
 * `sales` is the sales team's own funnel rather than a marketing campaign, and its screen sits
 * under the Sales nav heading. It stays in this module because it is the identical read against a
 * third pipeline — the same endpoint prefix, the same payload, the same GM-only door. Splitting a
 * `salesApi.ts` off would duplicate every type below to rename one string.
 */
export type MarketingFunnel = 'ads' | 'email' | 'sales'

/**
 * `range` filters on the opportunity's **created-at** date in GHL, so the funnel answers "deals
 * created in this window, grouped by the stage they are in now" — not "deals that entered this
 * stage in the window", which GHL's search cannot express.
 *
 * **No `brandId`, and that is not an omission.** This reads the one GHL sub-account named by
 * `evalos.ghl.location-id`, a global setting with no link to any brand — so EvalOS cannot say
 * which brand these figures belong to, let alone filter them by one. A brand id would narrow
 * nothing while implying it had. GM-only follows from the same gap, not from the figure being a
 * cross-brand roll-up: it is not one. Unit 25 maps locations to brands and closes this.
 */
export async function fetchMarketingPipeline(
  funnel: MarketingFunnel,
  range: DateRange,
  signal?: AbortSignal,
): Promise<MarketingPipeline> {
  return unwrap<MarketingPipeline>(
    api.get(`/marketing/${funnel}-pipeline`, { params: rangeParams(range), signal }),
  )
}
