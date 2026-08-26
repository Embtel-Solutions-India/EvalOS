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
