import type { DateRange } from '../shell/filtersContext'
import type { Role } from '../../lib/session'

/**
 * The board's vocabulary and its two decision tables: which stages a role works, and which
 * quick actions a card offers.
 *
 * Pure data and pure functions, deliberately importing nothing but a type — which is what
 * lets `boardRules.test.ts` exercise it without a DOM or a server. The HTTP calls live in
 * `boardApi.ts`.
 *
 * The action table mirrors Unit 04's `CaseTransitions` plus the `@PreAuthorize` gate on
 * each route. **It is a convenience, not the rule.** The server decides every transition
 * and answers 409 with a reason when it refuses; this table only decides which buttons are
 * worth showing. Where the two disagree the server wins and the card does not move — which
 * is why every action surfaces its error inline rather than assuming success.
 */
export type Stage =
  | 'DOC_COLLECTION'
  | 'EXPERT_ASSIGNMENT'
  | 'DRAFT_GENERATION'
  | 'EXPERT_SIGNING'
  | 'FINAL_DELIVERY'

export type ExceptionState =
  | 'NONE'
  | 'ON_HOLD_AWAITING_CLIENT'
  | 'EXPERT_DECLINED_REMATCHING'
  | 'REFUND_REQUESTED'

export type SlaStatus = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE'

export type ServiceType =
  | 'CREDENTIAL_EVALUATION'
  | 'EXPERT_OPINION_LETTER'
  | 'PERM'
  | 'RFE_RESPONSE'
  | 'TRANSLATION'

export type BoardCard = {
  id: string
  caseCode: string
  clientName: string | null
  serviceType: ServiceType | null
  deadline: string | null
  slaStatus: SlaStatus | null
  currentStage: Stage
  exceptionState: ExceptionState
  poolStatus: 'IN_POOL' | 'ASSIGNED' | null
  assignedPm: string | null
  assignedCm: string | null
  assignedCoordinator: string | null
  expertSignStatus: 'PENDING' | 'SIGNED' | 'OVERDUE' | 'REASSIGNED' | null
  pmApprovalStatus: 'PENDING' | 'APPROVED' | 'RETURNED' | null
  clientApprovalStatus: 'PENDING' | 'APPROVED' | 'REVISION_REQUESTED' | null
  /**
   * Null for every role outside GM / Brand Manager / PM — the server omits it.
   *
   * A number, not a string: Jackson serializes the Java `BigDecimal` as a JSON number, which
   * `CaseBoardControllerTest` asserts (`.value(1450.00)`). Typed as a string it happened to
   * render, because React stringifies either — the mismatch only surfaced when something
   * tried to format it.
   */
  dealValue: number | null
}

export type BoardData = {
  stages: Record<Stage, BoardCard[]>
  exceptions: Record<Exclude<ExceptionState, 'NONE'>, BoardCard[]>
}

/** The five columns, in pipeline order, with the labels `ui-context.md` names. */
export const STAGE_COLUMNS: readonly { stage: Stage; label: string }[] = [
  { stage: 'DOC_COLLECTION', label: 'Doc Collection' },
  { stage: 'EXPERT_ASSIGNMENT', label: 'Expert Assignment' },
  { stage: 'DRAFT_GENERATION', label: 'Draft / Report' },
  { stage: 'EXPERT_SIGNING', label: 'Expert Signing' },
  { stage: 'FINAL_DELIVERY', label: 'Final Delivery' },
]

/**
 * How much of each stage a role works.
 *
 * - `full`   — drives the stage: every action their role is gated for.
 * - `status`  — watches the stage: sees the cards and their state, but not the actions that
 *              move work through it. The stage-preserving actions (hold, resume, request
 *              refund, staffing) stay, because they are not advancing anything — a
 *              Coordinator watching a draft can still put the case on hold.
 * - `none`    — the column is not theirs and is not drawn.
 *
 * **This is convenience, not enforcement** (architecture principle 7). The server gates
 * every transition by role and every read by scope; a hidden column only saves somebody
 * scanning work they cannot act on. Several `none` cells are already empty by scope alone —
 * a case assigned to a Case Manager is never in Doc Collection — so this makes the board
 * say what the data already meant.
 *
 * GM and Brand Manager are oversight and see all five: their view is the whole pipeline,
 * narrowed by the actions their role is gated for.
 */
export type StageAccess = 'full' | 'status' | 'none'

export const STAGE_ACCESS: Record<Role, Record<Stage, StageAccess>> = {
  GM: {
    DOC_COLLECTION: 'full',
    EXPERT_ASSIGNMENT: 'full',
    DRAFT_GENERATION: 'full',
    EXPERT_SIGNING: 'full',
    FINAL_DELIVERY: 'full',
  },
  BRAND_MANAGER: {
    DOC_COLLECTION: 'full',
    EXPERT_ASSIGNMENT: 'full',
    DRAFT_GENERATION: 'full',
    EXPERT_SIGNING: 'full',
    FINAL_DELIVERY: 'full',
  },
  PROJECT_MANAGER: {
    DOC_COLLECTION: 'full',
    EXPERT_ASSIGNMENT: 'full',
    DRAFT_GENERATION: 'full',
    // QC is the PM's gate out of signing.
    EXPERT_SIGNING: 'full',
    // Delivery is the Coordinator's to run; the PM watches it land.
    FINAL_DELIVERY: 'status',
  },
  PROJECT_COORDINATOR: {
    // The two ends of the pipeline are theirs: chase the documents, then deliver.
    DOC_COLLECTION: 'full',
    EXPERT_ASSIGNMENT: 'status',
    DRAFT_GENERATION: 'status',
    EXPERT_SIGNING: 'status',
    FINAL_DELIVERY: 'full',
  },
  CASE_MANAGER: {
    // Never theirs — a case naming them as CM has already left doc collection.
    DOC_COLLECTION: 'none',
    EXPERT_ASSIGNMENT: 'status',
    DRAFT_GENERATION: 'full',
    EXPERT_SIGNING: 'full',
    // Confirmed intended: the case leaves their board once QC passes, even though assigned_cm
    // still names them. Delivery is the Coordinator's stage, and a CM's board is the work in
    // front of them. Not lost — still in their scope, so an exception lane and the detail page
    // both still reach it.
    FINAL_DELIVERY: 'none',
  },
  EXPERT_NETWORK_MANAGER: {
    DOC_COLLECTION: 'none',
    // Availability: which experts can take the work.
    EXPERT_ASSIGNMENT: 'status',
    DRAFT_GENERATION: 'none',
    // Response: whether the expert signed, declined, or needs replacing.
    EXPERT_SIGNING: 'full',
    // Payment: the payout follows delivery (Unit 16).
    FINAL_DELIVERY: 'status',
  },
}

export const EXCEPTION_LANES: readonly { state: Exclude<ExceptionState, 'NONE'>; label: string }[] = [
  { state: 'ON_HOLD_AWAITING_CLIENT', label: 'On Hold' },
  { state: 'EXPERT_DECLINED_REMATCHING', label: 'Rematching' },
  { state: 'REFUND_REQUESTED', label: 'Refund Requested' },
]
/**
 * One input a dialog collects.
 *
 * `member` and `expert` fields are pickers, not free text: an assignment names a row that
 * already exists, so the only correct input is a choice from the rows the caller may
 * assign. The options come from endpoints that apply the same scope the write side
 * enforces, so the picker cannot offer somebody the transition would then refuse.
 */
export type ActionField = {
  name: string
  label: string
  kind: 'text' | 'amount' | 'member' | 'expert'
  /** Which role to list, for `member` fields. */
  memberRole?: Role
}

export type PickerOption = { id: string; label: string }
export type QuickAction = {
  /** The path suffix under /cases/{id}. */
  path: string
  label: string
  /** Roles the route's gate admits, excluding the GM, who is added below. */
  roles: readonly Role[]
  /** Stages the transition is declared from; null means every active stage. */
  stages: readonly Stage[] | null
  /** Only legal while the case holds this exception state. */
  requiresException?: Exclude<ExceptionState, 'NONE'>
  /** GM-only, not GM-also: the two refund rulings. */
  gmOnly?: boolean
  fields?: readonly ActionField[]
}

const REASON: readonly ActionField[] = [{ name: 'reason', label: 'Reason', kind: 'text' }]

export const QUICK_ACTIONS: readonly QuickAction[] = [
  // Stage-specific.
  {
    path: 'docs-complete',
    label: 'Docs complete',
    roles: ['PROJECT_COORDINATOR', 'PROJECT_MANAGER'],
    stages: ['DOC_COLLECTION'],
  },
  {
    path: 'assign-cm',
    label: 'Assign CM + expert',
    roles: ['PROJECT_MANAGER'],
    stages: ['EXPERT_ASSIGNMENT'],
    fields: [
      { name: 'cmId', label: 'Case manager', kind: 'member', memberRole: 'CASE_MANAGER' },
      { name: 'expertId', label: 'Expert', kind: 'expert' },
    ],
  },
  {
    path: 'draft/submit',
    label: 'Submit draft',
    roles: ['CASE_MANAGER'],
    stages: ['DRAFT_GENERATION'],
  },
  {
    path: 'draft/pm-approve',
    label: 'PM approve',
    roles: ['PROJECT_MANAGER'],
    stages: ['DRAFT_GENERATION'],
  },
  {
    path: 'draft/pm-return',
    label: 'PM return',
    roles: ['PROJECT_MANAGER'],
    stages: ['DRAFT_GENERATION'],
    fields: REASON,
  },
  {
    path: 'draft/send-to-client',
    label: 'Send to client',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['DRAFT_GENERATION'],
  },
  {
    path: 'draft/client-approve',
    label: 'Client approved',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['DRAFT_GENERATION'],
  },
  {
    path: 'draft/client-revisions',
    label: 'Client revisions',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['DRAFT_GENERATION'],
    fields: REASON,
  },
  {
    path: 'expert/signed',
    label: 'Expert signed',
    roles: ['PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER'],
    stages: ['EXPERT_SIGNING'],
  },
  {
    path: 'expert/declined',
    label: 'Expert declined',
    roles: ['PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER'],
    stages: ['EXPERT_SIGNING'],
    fields: REASON,
  },
  {
    path: 'qc-approve',
    label: 'QC approve',
    roles: ['PROJECT_MANAGER'],
    stages: ['EXPERT_SIGNING'],
  },
  {
    path: 'deliver',
    label: 'Deliver',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['FINAL_DELIVERY'],
  },
  {
    path: 'close',
    label: 'Close',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['FINAL_DELIVERY'],
  },

  // Legal wherever the case is still being worked.
  {
    path: 'mark-paid',
    label: 'Record payment',
    roles: ['BRAND_MANAGER'],
    stages: null,
    fields: [
      { name: 'dealValue', label: 'Amount collected', kind: 'amount' },
      { name: 'invoiceRef', label: 'Invoice ref (optional)', kind: 'text' },
    ],
  },
  {
    path: 'assign-pm',
    label: 'Assign PM',
    roles: ['BRAND_MANAGER'],
    stages: null,
    fields: [{ name: 'pmId', label: 'Project manager', kind: 'member', memberRole: 'PROJECT_MANAGER' }],
  },
  {
    path: 'assign-coordinator',
    label: 'Assign coordinator',
    roles: ['BRAND_MANAGER', 'PROJECT_MANAGER'],
    stages: null,
    fields: [
      { name: 'coordinatorId', label: 'Coordinator', kind: 'member', memberRole: 'PROJECT_COORDINATOR' },
    ],
  },
  {
    path: 'hold',
    label: 'Put on hold',
    roles: ['PROJECT_COORDINATOR', 'PROJECT_MANAGER'],
    stages: null,
    fields: REASON,
  },
  {
    path: 'refund/request',
    label: 'Request refund',
    // The one open route: any authenticated staff member may raise one.
    roles: [
      'BRAND_MANAGER',
      'PROJECT_MANAGER',
      'PROJECT_COORDINATOR',
      'CASE_MANAGER',
      'EXPERT_NETWORK_MANAGER',
    ],
    stages: null,
    fields: REASON,
  },

  // The ways out of an exception state, and nothing else is legal while one is held.
  {
    path: 'resume',
    label: 'Resume',
    roles: ['PROJECT_COORDINATOR', 'PROJECT_MANAGER'],
    stages: null,
    requiresException: 'ON_HOLD_AWAITING_CLIENT',
  },
  {
    path: 'reassign-expert',
    label: 'Reassign expert',
    roles: ['PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER'],
    stages: null,
    requiresException: 'EXPERT_DECLINED_REMATCHING',
    fields: [{ name: 'expertId', label: 'Replacement expert', kind: 'expert' }],
  },
  {
    path: 'refund/approve',
    label: 'Approve refund',
    roles: [],
    stages: null,
    requiresException: 'REFUND_REQUESTED',
    gmOnly: true,
  },
  {
    path: 'refund/deny',
    label: 'Deny refund',
    roles: [],
    stages: null,
    requiresException: 'REFUND_REQUESTED',
    gmOnly: true,
    fields: REASON,
  },
]

/**
 * The actions worth offering on this card to this role.
 *
 * A case holding an exception state accepts *only* its way out — the same rule
 * `CaseTransitions` enforces, so a card in a lane offers Resume or Reassign and not the
 * eight things its stage would otherwise allow.
 */
export function actionsFor(card: BoardCard, role: Role): readonly QuickAction[] {
  const inException = card.exceptionState !== 'NONE'
  const access = STAGE_ACCESS[role][card.currentStage]

  return QUICK_ACTIONS.filter((action) => {
    if (action.gmOnly ? role !== 'GM' : !(role === 'GM' || action.roles.includes(role))) return false
    if (inException) return action.requiresException === card.exceptionState
    if (action.requiresException) return false
    if (action.stages === null) return true
    // A `status` role watches this stage rather than working it, so the actions declared
    // *from* this stage are withheld. The stage-preserving ones returned above are not.
    if (access === 'status') return false
    return action.stages.includes(card.currentStage)
  })
}

/** The columns this role works or watches, in pipeline order. `none` cells are not drawn. */
export function columnsFor(role: Role): readonly { stage: Stage; label: string; access: StageAccess }[] {
  return STAGE_COLUMNS.map(({ stage, label }) => ({ stage, label, access: STAGE_ACCESS[role][stage] })).filter(
    (column) => column.access !== 'none',
  )
}

/**
 * The shell's date filter, as the deadline window the board asks the server for.
 *
 * End-of-day on the far edge, so "today" includes a case due this afternoon. Pure and
 * exported for the same reason the tables are: an off-by-one here silently hides work.
 */
export function dueBeforeFor(range: DateRange, now: Date = new Date()): string {
  const end = new Date(now)
  end.setHours(23, 59, 59, 999)
  if (range === 'week') end.setDate(end.getDate() + 7)
  if (range === 'month') addMonths(end, 1)
  if (range === 'year') addMonths(end, 12)
  return end.toISOString()
}

/**
 * Adds calendar months, clamping to the target month's length.
 *
 * `setMonth` alone overflows: 31 January plus one month is 3 March, and 29 February plus a
 * year is 1 March. Both silently widen the window past the range the user picked, which is
 * the same class of bug as narrowing it — the filter stops meaning what its label says.
 */
function addMonths(date: Date, months: number): void {
  const day = date.getDate()
  date.setDate(1)
  date.setMonth(date.getMonth() + months)
  // Day 0 of the following month is the last day of this one.
  const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  date.setDate(Math.min(day, lastDay))
}
