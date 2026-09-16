import { api, unwrap } from '../../lib/api'
import type { DeadlineRisk } from '../board/boardRules'
import { rangeParams, type DateRange } from '../shell/filtersContext'

/** `PmMetricsService.OnTimeDelivery`. */
export type OnTimeDelivery = {
  delivered: number
  onTime: number
  /**
   * Null when nothing was delivered in the window — **not** zero. No cases delivered is not the
   * same as none delivered on time, and a 0% would accuse the team of a failure that did not
   * happen. The tile renders the `empty` state for null.
   */
  ratePct: number | null
  /** Percentage points against the previous window of the same length. Null with no comparison. */
  deltaPoints: number | null
}

export type ProductCompletion = {
  serviceType: string
  delivered: number
  medianBusinessHours: number
}

export type CmRevisionRate = {
  cmId: string
  name: string
  cases: number
  revised: number
  ratePct: number | null
}

export type CmWorkload = {
  cmId: string
  name: string
  active: number
  capacity: number
}

export type PmMetrics = {
  onTime: OnTimeDelivery
  /** Live, and deliberately **not** bounded by the date filter — "right now" means now. */
  atRiskNow: number
  /** Live. The desired value is zero. */
  unassigned: number
  completionByService: ProductCompletion[]
  revisionRateByCm: CmRevisionRate[]
  workload: CmWorkload[]
}

/**
 * `brandId` is the shell's brand switcher, and sending it is safe for the same reason the board
 * does: the server applies it after the scope, so it can only ever narrow.
 */
export async function fetchPmMetrics(
  range: DateRange,
  brandId: string | null,
  signal?: AbortSignal,
): Promise<PmMetrics> {
  // `rangeParams` rather than `{ range }`: a custom period needs `from`/`to` alongside it, and
  // the server refuses those on a named range rather than ignoring them — so which parameters go
  // on the wire is a property of the period, not of this call site.
  const params: Record<string, string> = rangeParams(range)
  if (brandId) params.brandId = brandId
  return unwrap<PmMetrics>(api.get('/metrics/pm', { params, signal }))
}

// --- the other four roles ---------------------------------------------------
//
// One module for every dashboard's wire types, because they share the `brandId`-narrows contract
// and the "a rate is null, never 0, when its denominator is empty" rule. Splitting per role would
// copy both notes four times.

export type CoordinatorMetrics = {
  documents: { outstanding: number; aging: number; medianWaitHours: number }
  /** `unopened` is real evidence: `client_portal_read_at` is stamped once, on first read. */
  clientReview: { awaiting: number; unopened: number; stale: number }
  delivered: { today: number; thisWeek: number }
  readyToDeliver: number
}

/** One row of the Case Manager's docket — the spec's "my active cases", already deadline-ordered. */
export type MyCase = {
  id: string
  caseCode: string
  clientName: string | null
  serviceType: string | null
  deadline: string | null
  deadlineRisk: DeadlineRisk | null
  stage: string
  expertName: string | null
  expertSignStatus: string | null
  /** Past the 24h signing budget. The spec's "reassign prompt" — which for a CM is the flag. */
  signingOverdue: boolean
  pmApprovalStatus: string | null
  draftVersionCount: number
  clientApprovalStatus: string | null
  /** The PM's notes. The CM reads them without writing — `SEES_STRATEGY_NOTES`. */
  strategyNotes: string | null
}

export type ClientFeedback = {
  caseId: string
  caseCode: string
  at: string
  note: string | null
}

export type CaseManagerMetrics = {
  /** Deadline-ordered by the server: this list *is* the priority queue. */
  cases: MyCase[]
  clientFeedback: ClientFeedback[]
  active: number
  /** Red band: past the deadline or inside 24 business hours — not a calendar day. */
  critical: number
  /** Amber band: inside 48 business hours. */
  atRisk: number
  draftsWithPm: number
  revisionsRequested: number
  awaitingExpertSignature: number
  expertOverdue: number
  deliveredOnTimePct: number
  delivered: number
  revisionRatePct: number | null
  /** Share of this CM's cases the client came back on. Forward-looking — see the audit action. */
  clientRevisionRatePct: number | null
  /** False means the rates rest on too few cases to compare against anyone. Shown, not judged. */
  comparable: boolean
  /** The spec's "consistently >30%". Only ever true when `comparable`. */
  revisionRateFlagged: boolean
}

/** The spec's availability board: available vs at-capacity vs inactive, per field. */
export type FieldCoverage = {
  field: string
  available: number
  atCapacity: number
  /** Inactive and on-leave together: for staffing the next case they are the same answer. */
  inactive: number
  total: number
  /** Fewer than five **available**. At-capacity does not count toward it. */
  gap: boolean
}

export type LowQualityExpert = { expertId: string; name: string; qualityScore: number }

export type ExpertNetworkMetrics = {
  roster: { available: number; atCapacity: number; onLeave: number; inactive: number; total: number }
  coverage: FieldCoverage[]
  onboarding: { thisMonth: number; target: number }
  acceptance: { ratePct: number | null; resolved: number }
  /**
   * How long the roster takes to answer an offer (gap **G9**).
   *
   * **The median, and derived from the offer ledger.** `expert.avg_response_hours` existed,
   * was written by nothing and read as permanently null; G9's instruction was to derive rather
   * than revive it. Median because these samples are few and skewed — one expert who answers
   * after a fortnight drags a mean somewhere nobody recognises.
   *
   * `null` means nothing has resolved yet. It is never zero, which would read as "answered
   * instantly".
   */
  turnaround: { medianHours: number | null; resolved: number }
  declining: { expertId: string; name: string; declines: number }[]
  lowQuality: LowQualityExpert[]
  activeCases: number
}

/** Money arrives as JSON numbers — Jackson serialises the Java `BigDecimal` that way. */
export type Money = {
  collected: number
  recognized: number
  openLiability: number
  refunded: number
  /** False means the three no longer add up; the screen says so rather than showing them anyway. */
  reconciles: boolean
}

export type RevenueMetrics = {
  total: Money
  perBrand: { brandId: string; name: string; money: Money; cases: number }[]
  openCases: number
}

export async function fetchCoordinatorMetrics(
  brandId: string | null,
  signal?: AbortSignal,
): Promise<CoordinatorMetrics> {
  return unwrap<CoordinatorMetrics>(
    api.get('/metrics/coordinator', { params: brandId ? { brandId } : {}, signal }),
  )
}

/** No `brandId`: this endpoint answers "my work", and the caller is the scope. */
export async function fetchCaseManagerMetrics(signal?: AbortSignal): Promise<CaseManagerMetrics> {
  return unwrap<CaseManagerMetrics>(api.get('/metrics/case-manager', { signal }))
}

export async function fetchExpertNetworkMetrics(signal?: AbortSignal): Promise<ExpertNetworkMetrics> {
  return unwrap<ExpertNetworkMetrics>(api.get('/metrics/expert-network', { signal }))
}

export async function fetchRevenueMetrics(
  brandId: string | null,
  signal?: AbortSignal,
): Promise<RevenueMetrics> {
  return unwrap<RevenueMetrics>(
    api.get('/metrics/revenue', { params: brandId ? { brandId } : {}, signal }),
  )
}

// --- G16: the portal links ledger -------------------------------------------

/** `ui-context.md`'s RAG vocabulary. The server bands the row; this app never re-derives it. */
export type LinkState = 'RED' | 'AMBER' | 'GREEN'

/**
 * One (case, audience) pair.
 *
 * **There is no `sentAt` and there must never be one.** EvalOS cannot observe a staff member
 * pasting a URL into somebody else's mail client, and a field claiming otherwise would be
 * reported by this very screen as if it were true. `openedAt` is the honest proxy: it is
 * evidence the link *arrived*, which is the thing worth knowing.
 */
export type PortalLinkRow = {
  caseId: string
  caseCode: string | null
  stage: string
  audience: 'CLIENT' | 'EXPERT'
  state: LinkState
  live: boolean
  /** When the recipient first opened it, or null for never. */
  openedAt: string | null
  expiresAt: string | null
  reMints: number
  /** Whether this stage wants this audience at all — the server derives it from the stage. */
  needed: boolean
}

export type PortalLinkLedger = {
  red: number
  amber: number
  rows: readonly PortalLinkRow[]
}

/**
 * Which portal links exist and whether anyone opened them (gap G16).
 *
 * **A safety net for a channel that does not exist.** EvalOS sends no mail, so a link reaches
 * its recipient because somebody sent it by hand — and nothing records that they did. The
 * 20h/24h expert signing clock runs regardless, which makes "a link nobody sent" the likeliest
 * way that SLA is breached.
 */
export async function fetchPortalLinkLedger(signal?: AbortSignal): Promise<PortalLinkLedger> {
  return unwrap<PortalLinkLedger>(api.get('/metrics/portal-links', { signal }))
}

// --- the GM overview --------------------------------------------------------
//
// One payload, two halves. The production half is EvalOS's own rows and is always present; the
// pipeline half is GHL's and is null when `pipelineUnavailable` says why. The screen prints that
// message on exactly the tiles it invalidates rather than blanking the page — see
// `GmOverviewService`.

/** `null` on any window that is not a calendar month: a monthly target has no other denominator. */
export type GmHeadline = { won: number; goal: number | null; pctToGoal: number | null }

export type GmSourceRow = { source: string; deals: number; value: number }

export type GmServiceRow = {
  serviceType: string
  openCases: number
  openValue: number
  delivered: number
  deliveredValue: number
}

export type GmSales = {
  newLeads: number
  newValue: number
  won: number
  wonValue: number
  /** Null when the previous window had no wins — a first win is not a percentage improvement. */
  wonDeltaPct: number | null
}

export type GmDeskRow = {
  memberId: string
  name: string
  role: 'SALES' | 'MARKETING'
  newLeads: number
  won: number
  wonValue: number
}

/** `noSourcePct` is null, never 0, when the window held no opportunities to attribute. */
export type GmMarketing = { newLeads: number; newValue: number; noSourcePct: number | null }

/** `late` is past the promised date, not `PmMetrics.atRiskNow`'s wider band. */
export type GmEvaluation = {
  delivered: number
  deliveredValue: number
  late: number
  openCases: number
  openValue: number
}

export type GmOverview = {
  headline: GmHeadline | null
  bySource: GmSourceRow[]
  byService: GmServiceRow[]
  sales: GmSales | null
  desks: GmDeskRow[]
  marketing: GmMarketing | null
  evaluation: GmEvaluation
  readAt: string
  /** Null when GHL answered. The reason it did not, otherwise. */
  pipelineUnavailable: string | null
}

export async function fetchGmOverview(
  range: DateRange,
  brandId: string | null,
  signal?: AbortSignal,
): Promise<GmOverview> {
  const params: Record<string, string> = rangeParams(range)
  if (brandId) params.brandId = brandId
  return unwrap<GmOverview>(api.get('/metrics/gm', { params, signal }))
}
