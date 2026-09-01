import type { DeadlineWindow } from './deadlineWindow'
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
/**
 * The twelve production stages (Unit 31), mirroring the server's `Stage` enum.
 *
 * `DELIVERED` and `CLOSED` never arrive on the board — `CaseBoardController.COLUMNS` omits them —
 * but they are in the union because the case detail page and every SLA read see them.
 */
export type Stage =
  | 'DOC_COLLECTION'
  | 'PM_REVIEW'
  | 'DRAFT_IN_PROGRESS'
  | 'DRAFT_REVIEW'
  | 'READY_TO_SEND'
  | 'CLIENT_REVIEW'
  | 'CLIENT_APPROVAL'
  | 'EXPERT_SIGNING'
  | 'FINAL_QC'
  | 'READY_TO_DELIVER'
  | 'DELIVERED'
  | 'CLOSED'

export type ExceptionState =
  | 'NONE'
  | 'ON_HOLD_AWAITING_CLIENT'
  | 'EXPERT_DECLINED_REMATCHING'
  | 'REFUND_REQUESTED'

export type SlaStatus = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE'

/**
 * How close the **promised date** is — a different question from {@link SlaStatus}, which
 * measures time in the current stage against a stage budget.
 *
 * The two disagree routinely: a case can sit comfortably inside a 12-hour PM-review budget with
 * its deadline nine hours away. They are therefore labelled distinctly wherever both appear
 * ("Stage SLA" and "Deadline"), and neither is ever substituted for the other.
 *
 * `OVERDUE` is the **red band** rather than literally past-due — `ui-context.md` puts "overdue"
 * and "deadline under 24h" in one colour. Null when no clock runs.
 */
export type DeadlineRisk = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE'

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
  /** Null when no clock runs — closed, holding an exception, or never given a date. */
  deadlineRisk: DeadlineRisk | null
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

/**
 * **Eight columns for ten stages** (Unit 31), in pipeline order.
 *
 * Twelve columns at the 1366px reference width are twelve narrow strips holding one card each,
 * and a board that cannot be scanned is not doing the one job it has. So two stages share a
 * column — **but only where they share an owner**. That is the whole rule, and it is what keeps
 * the folding from undoing the unit: a column still answers "whose turn is it", and the chip on
 * the card says only *what they must do next*. The arrangement Unit 31 replaced was the opposite
 * — one column holding three different owners, told apart by two nullable columns.
 *
 * `DELIVERED` and `CLOSED` are absent because they are outcomes; a lane of finished work only
 * grows. They are reachable by filter.
 */
export const STAGE_COLUMNS: readonly { stages: readonly Stage[]; label: string; owner: Role }[] = [
  { stages: ['DOC_COLLECTION'], label: 'Doc Collection', owner: 'PROJECT_COORDINATOR' },
  { stages: ['PM_REVIEW'], label: 'PM Review', owner: 'PROJECT_MANAGER' },
  { stages: ['DRAFT_IN_PROGRESS'], label: 'Drafting', owner: 'CASE_MANAGER' },
  { stages: ['DRAFT_REVIEW'], label: 'Draft Review', owner: 'PROJECT_MANAGER' },
  // Both the Coordinator's: they hold an approved draft, then they hold it while the client reads.
  { stages: ['READY_TO_SEND', 'CLIENT_REVIEW'], label: 'Client Review', owner: 'PROJECT_COORDINATOR' },
  // Both the CM's: they hold a locked approved letter, then they hold it while the expert signs.
  { stages: ['CLIENT_APPROVAL', 'EXPERT_SIGNING'], label: 'Expert Signing', owner: 'CASE_MANAGER' },
  { stages: ['FINAL_QC'], label: 'Final QC', owner: 'PROJECT_MANAGER' },
  { stages: ['READY_TO_DELIVER'], label: 'Ready to Deliver', owner: 'PROJECT_COORDINATOR' },
]

/**
 * Who the case is waiting on, as a property of the stage.
 *
 * **This is the point of Unit 31.** The card states the owner as fact instead of the reader
 * combining a stage with two nullable sub-status columns and getting it wrong whenever they
 * disagree. One table, here, so the board and any queue that asks the same question cannot drift
 * — the lesson `navigation.ts` records about the nav and the route guard being one table.
 */
export const STAGE_OWNER: Record<Stage, Role | null> = {
  DOC_COLLECTION: 'PROJECT_COORDINATOR',
  PM_REVIEW: 'PROJECT_MANAGER',
  DRAFT_IN_PROGRESS: 'CASE_MANAGER',
  DRAFT_REVIEW: 'PROJECT_MANAGER',
  READY_TO_SEND: 'PROJECT_COORDINATOR',
  CLIENT_REVIEW: 'PROJECT_COORDINATOR',
  CLIENT_APPROVAL: 'CASE_MANAGER',
  EXPERT_SIGNING: 'CASE_MANAGER',
  FINAL_QC: 'PROJECT_MANAGER',
  READY_TO_DELIVER: 'PROJECT_COORDINATOR',
  // Nobody is waiting on anybody. Naming an owner here would put a name on a card that needs no
  // action, which is how a finished case reads as outstanding work.
  DELIVERED: null,
  CLOSED: null,
}

/** What the owner has to do next, so the card never leaves the reader to infer it. */
export const STAGE_NEXT_ACTION: Record<Stage, string> = {
  DOC_COLLECTION: 'Collect and check documents',
  PM_REVIEW: 'Write strategy, assign expert and CM',
  DRAFT_IN_PROGRESS: 'Upload and submit the draft',
  DRAFT_REVIEW: 'Approve for client, or return',
  READY_TO_SEND: 'Send the approved draft to the client',
  CLIENT_REVIEW: 'Waiting for the client',
  CLIENT_APPROVAL: 'Send the approved letter to the expert',
  EXPERT_SIGNING: 'Waiting for the expert to sign',
  FINAL_QC: 'QC the signed letter',
  READY_TO_DELIVER: 'Deliver to the client',
  DELIVERED: 'Close the case',
  CLOSED: '—',
}

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
  // Oversight: the whole pipeline, narrowed by the actions each role is gated for.
  //
  // **DELIVERED is `full`, not `status`, and the reason is a real trap.** It looks terminal, but
  // `close` is declared *from* it — and `actionsFor` withholds every action declared from a stage
  // the role only watches. Marking it `status` silently took Close away from the GM while the
  // server still allowed it, which is the "a button the server would have accepted" failure in
  // reverse. A stage is `status` only when the role drives nothing *out* of it.
  GM: {
    DOC_COLLECTION: 'full', PM_REVIEW: 'full', DRAFT_IN_PROGRESS: 'full', DRAFT_REVIEW: 'full',
    READY_TO_SEND: 'full', CLIENT_REVIEW: 'full', CLIENT_APPROVAL: 'full', EXPERT_SIGNING: 'full',
    FINAL_QC: 'full', READY_TO_DELIVER: 'full', DELIVERED: 'full', CLOSED: 'status',
  },
  BRAND_MANAGER: {
    DOC_COLLECTION: 'full', PM_REVIEW: 'full', DRAFT_IN_PROGRESS: 'full', DRAFT_REVIEW: 'full',
    READY_TO_SEND: 'full', CLIENT_REVIEW: 'full', CLIENT_APPROVAL: 'full', EXPERT_SIGNING: 'full',
    FINAL_QC: 'full', READY_TO_DELIVER: 'full', DELIVERED: 'full', CLOSED: 'status',
  },
  PROJECT_MANAGER: {
    DOC_COLLECTION: 'full',
    // Theirs: strategy, expert, CM.
    PM_REVIEW: 'full',
    DRAFT_IN_PROGRESS: 'status',
    // Theirs: approve or return.
    DRAFT_REVIEW: 'full',
    READY_TO_SEND: 'status',
    CLIENT_REVIEW: 'status',
    CLIENT_APPROVAL: 'status',
    EXPERT_SIGNING: 'status',
    // Theirs: the QC gate, both rulings.
    FINAL_QC: 'full',
    // Delivery is the Coordinator's to run; the PM watches it land.
    READY_TO_DELIVER: 'status',
    DELIVERED: 'status',
    CLOSED: 'status',
  },
  PROJECT_COORDINATOR: {
    // The client-facing ends of the pipeline are theirs: chase documents, send the draft, deliver.
    DOC_COLLECTION: 'full',
    PM_REVIEW: 'status',
    DRAFT_IN_PROGRESS: 'status',
    DRAFT_REVIEW: 'status',
    READY_TO_SEND: 'full',
    CLIENT_REVIEW: 'full',
    CLIENT_APPROVAL: 'status',
    EXPERT_SIGNING: 'status',
    FINAL_QC: 'status',
    READY_TO_DELIVER: 'full',
    DELIVERED: 'full',
    CLOSED: 'status',
  },
  CASE_MANAGER: {
    // Never theirs — a case naming them as CM has already left doc collection.
    DOC_COLLECTION: 'none',
    PM_REVIEW: 'status',
    DRAFT_IN_PROGRESS: 'full',
    DRAFT_REVIEW: 'status',
    READY_TO_SEND: 'status',
    CLIENT_REVIEW: 'status',
    // **Both theirs as of Unit 31**: they send the client-approved letter to the expert, they get
    // the overdue alert, and they reassign.
    CLIENT_APPROVAL: 'full',
    EXPERT_SIGNING: 'full',
    FINAL_QC: 'status',
    // Confirmed intended: the case leaves their board once QC passes, even though assigned_cm
    // still names them. Not lost — still in scope, so an exception lane and the detail page reach it.
    READY_TO_DELIVER: 'none',
    DELIVERED: 'none',
    CLOSED: 'none',
  },
  // Unreachable today: the ENM has no `/board` entry in NAV_ITEMS, and `boardPathFor` says so in
  // as many words. The row stays because STAGE_ACCESS is `Record<Role, ...>` — dropping it would
  // weaken the type that forces a new role to declare its board access. Delete it only together
  // with that type change, never as a tidy-up.
  EXPERT_NETWORK_MANAGER: {
    DOC_COLLECTION: 'none',
    // Availability: which experts can take the work.
    PM_REVIEW: 'status',
    DRAFT_IN_PROGRESS: 'none',
    DRAFT_REVIEW: 'none',
    READY_TO_SEND: 'none',
    CLIENT_REVIEW: 'none',
    CLIENT_APPROVAL: 'status',
    // Response: whether the expert signed, declined, or needs replacing.
    EXPERT_SIGNING: 'full',
    FINAL_QC: 'none',
    // Payment: the payout follows delivery (Unit 16).
    READY_TO_DELIVER: 'status',
    DELIVERED: 'status',
    CLOSED: 'none',
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
  /** Roles the route's gate admits, excluding the GM — see {@link QuickAction.gm}. */
  roles: readonly Role[]
  /** Stages the transition is declared from; null means every active stage. */
  stages: readonly Stage[] | null
  /** Only legal while the case holds this exception state. */
  requiresException?: Exclude<ExceptionState, 'NONE'>
  /**
   * Where the GM stands on this action, when the default does not apply.
   *
   * Absent is **GM-also**: the GM is a superuser on almost every transition, so they are added
   * to `roles` rather than repeated in twenty lists. The two named exceptions:
   *
   * - `'only'` — GM and nobody else. The two refund rulings.
   * - `'never'` — `roles` is exact and excludes the GM. The two draft-review rulings, where
   *   approving or returning a Case Manager's work is the Project Manager's judgement and not
   *   an escalation path (Unit 23a). **This must stay in step with `CaseController`**, which
   *   drops `GM_OR` from exactly these two gates — a button the server answers 403 to is worse
   *   than no button.
   */
  gm?: 'only' | 'never'
  fields?: readonly ActionField[]
}

/**
 * Whether this role may perform this action at all, ignoring stage.
 *
 * Exported because `actionsFor` and the test that pins it must not derive the same rule twice —
 * a second copy is how "GM sees everything" quietly survives a decision to the contrary.
 */
export function admits(action: QuickAction, role: Role): boolean {
  if (action.gm === 'only') return role === 'GM'
  if (action.gm === 'never') return action.roles.includes(role)
  return role === 'GM' || action.roles.includes(role)
}

const REASON: readonly ActionField[] = [{ name: 'reason', label: 'Reason', kind: 'text' }]

export const QUICK_ACTIONS: readonly QuickAction[] = [
  // Stage-specific.
  {
    path: 'docs-complete',
    label: 'Docs complete',
    // The Brand Manager is here because the checklist screen gives them every other write on
    // this stage; see the gate on CaseController.docsComplete, which this list must match.
    roles: ['BRAND_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER'],
    stages: ['DOC_COLLECTION'],
  },
  {
    path: 'assign-cm',
    label: 'Assign CM + expert',
    roles: ['PROJECT_MANAGER'],
    stages: ['PM_REVIEW'],
    fields: [
      { name: 'cmId', label: 'Case manager', kind: 'member', memberRole: 'CASE_MANAGER' },
      { name: 'expertId', label: 'Expert', kind: 'expert' },
      // Unit 32. **"(optional)" is load-bearing** — the dialog makes a field required unless the
      // label says otherwise, and a required rationale is how "n/a" becomes a column's most
      // common value. It is asked here, where the choice is made, rather than in an edit box on
      // the case: a reason written after the fact is the one kind worth nothing.
      { name: 'expertRationale', label: 'Why this expert (optional)', kind: 'text' },
    ],
  },
  {
    path: 'draft/submit',
    label: 'Submit draft',
    roles: ['CASE_MANAGER'],
    stages: ['DRAFT_IN_PROGRESS'],
    // Where the letter is, which becomes `draft_link` — the one link the client's portal shows
    // (Unit 14). Optional, and the label has to say so: the dialog makes a field required unless
    // it does, and a second version filed in the same place needs no new link. Omitting it leaves
    // whatever link the case already carries rather than taking the draft away mid-review.
    fields: [{ name: 'draftLink', label: 'Link to the draft (optional)', kind: 'text' }],
  },
  // Draft review is the Project Manager's alone, GM included (Unit 23a). Approving a draft is a
  // judgement about a Case Manager's work by the person who assigned it and will answer for it;
  // an escalation path around that reviewer is not oversight, it is a second reviewer. Both gates
  // drop `GM_OR` in `CaseController` to match, and `/drafts` is PM-only in the nav.
  {
    path: 'draft/pm-approve',
    label: 'PM approve',
    roles: ['PROJECT_MANAGER'],
    gm: 'never',
    stages: ['DRAFT_REVIEW'],
  },
  {
    path: 'draft/pm-return',
    label: 'PM return',
    roles: ['PROJECT_MANAGER'],
    gm: 'never',
    stages: ['DRAFT_REVIEW'],
    fields: REASON,
  },
  {
    path: 'draft/send-to-client',
    label: 'Send to client',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['READY_TO_SEND'],
  },
  {
    path: 'draft/client-approve',
    label: 'Client approved',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['CLIENT_REVIEW'],
  },
  {
    path: 'draft/client-revisions',
    label: 'Client revisions',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['CLIENT_REVIEW'],
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
  // The CM's send, which is what starts the signing clock (Unit 31). Before this the case
  // entered signing on the client's approval and nobody sent anything.
  {
    path: 'send-to-expert',
    label: 'Send to expert',
    roles: ['PROJECT_MANAGER', 'CASE_MANAGER'],
    stages: ['CLIENT_APPROVAL'],
  },
  // The counterpart `qc-approve` never had. A failed QC used to have nowhere to go.
  {
    path: 'qc-fail',
    label: 'Return for correction',
    roles: ['PROJECT_MANAGER'],
    stages: ['FINAL_QC'],
    fields: REASON,
  },
  {
    path: 'qc-approve',
    label: 'QC approve',
    roles: ['PROJECT_MANAGER'],
    stages: ['FINAL_QC'],
  },
  {
    path: 'deliver',
    label: 'Deliver',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['READY_TO_DELIVER'],
  },
  {
    path: 'close',
    label: 'Close',
    roles: ['PROJECT_COORDINATOR'],
    stages: ['DELIVERED'],
  },

  // Legal wherever the case is still being worked.
  //
  // No 'Record payment' action, deliberately: Handoff A fires on the GHL opportunity being
  // marked Won, which GHL only does after collecting, so every case arrives paid and the
  // route this used to call no longer exists.
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

  // The human answer to the 24h sign prompt. **Not `requiresException` — this is what *sets*
  // one**, and it is the only door to `reassign-expert` below: `CaseTransitions` declares
  // REASSIGN_EXPERT legal only from EXPERT_DECLINED_REMATCHING, which a decline or this reach.
  //
  // The Brand Manager is here and the ENM is not, matching `CaseController.expertTimedOut` — the
  // reverse of the two sign callbacks beside it, and stated in the gate rather than inferred.
  {
    path: 'expert/timed-out',
    label: 'Mark expert overdue',
    roles: ['BRAND_MANAGER', 'PROJECT_MANAGER'],
    stages: ['EXPERT_SIGNING'],
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
    fields: [
      { name: 'expertId', label: 'Replacement expert', kind: 'expert' },
      // Left blank, the previous rationale stands rather than being erased — losing a recorded
      // reason because somebody reassigned in a hurry is the worse of the two failures.
      { name: 'expertRationale', label: 'Why this expert (optional)', kind: 'text' },
    ],
  },
  {
    path: 'refund/approve',
    label: 'Approve refund',
    roles: [],
    stages: null,
    requiresException: 'REFUND_REQUESTED',
    gm: 'only',
  },
  {
    path: 'refund/deny',
    label: 'Deny refund',
    roles: [],
    stages: null,
    requiresException: 'REFUND_REQUESTED',
    gm: 'only',
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
    if (!admits(action, role)) return false
    if (inException) return action.requiresException === card.exceptionState
    if (action.requiresException) return false
    if (action.stages === null) return true
    // A `status` role watches this stage rather than working it, so the actions declared
    // *from* this stage are withheld. The stage-preserving ones returned above are not.
    if (access === 'status') return false
    return action.stages.includes(card.currentStage)
  })
}

/**
 * The columns this role works or watches, in pipeline order. `none` cells are not drawn.
 *
 * `step` is the case's position in the **whole** pipeline, not in this role's subset — a Case
 * Manager's first column is still stage 3 of 5, and numbering it 1 would say the work starts
 * with them. The number is only worth drawing because the stages genuinely are a sequence.
 */
export function columnsFor(
  role: Role,
): readonly { stages: readonly Stage[]; label: string; owner: Role; access: StageAccess; step: number }[] {
  return STAGE_COLUMNS.map(({ stages, label, owner }, index) => ({
    stages,
    label,
    owner,
    // **The strongest access across the column's stages.** A folded column is one owner's work
    // (see STAGE_COLUMNS), so its two stages almost always agree — but taking the maximum rather
    // than the first is what stops a column being drawn read-only because the half the role does
    // not drive happened to be listed first.
    access: strongest(stages.map((stage) => STAGE_ACCESS[role][stage])),
    step: index + 1,
  })).filter((column) => column.access !== 'none')
}

const ACCESS_RANK: Record<StageAccess, number> = { none: 0, status: 1, full: 2 }

function strongest(levels: readonly StageAccess[]): StageAccess {
  return levels.reduce((best, level) => (ACCESS_RANK[level] > ACCESS_RANK[best] ? level : best), 'none')
}

/** Every card in a column, since a column can hold more than one stage. */
export function cardsInColumn(data: BoardData, stages: readonly Stage[]): BoardCard[] {
  return stages.flatMap((stage) => data.stages[stage] ?? [])
}

/**
 * How a column's cases are split across the RAG statuses, for the rail above it.
 *
 * `unknown` is its own band rather than being folded into `onTrack`: a case with no clock
 * running (closed, or holding an exception state — `SlaCalculator` returns null for both) is
 * not the same as one comfortably inside its budget, and colouring it green would overstate
 * the board's health. Counts, not percentages — the bar turns them into widths, and doing
 * that here would lose the numbers the header prints.
 */
export type SlaMix = { onTrack: number; atRisk: number; overdue: number; unknown: number }

export function slaMix(cards: readonly BoardCard[]): SlaMix {
  const mix: SlaMix = { onTrack: 0, atRisk: 0, overdue: 0, unknown: 0 }
  for (const card of cards) {
    if (card.slaStatus === 'ON_TRACK') mix.onTrack += 1
    else if (card.slaStatus === 'AT_RISK') mix.atRisk += 1
    else if (card.slaStatus === 'OVERDUE') mix.overdue += 1
    else mix.unknown += 1
  }
  return mix
}

/**
 * Whether the board may claim every case is inside its SLA.
 *
 * Not the same as "no red and no amber": a case with **no clock running** is not a case that is
 * doing well, and the rail draws that band grey rather than green for exactly this reason. The
 * headline has to agree with the instrument below it, on the same data — the live board found
 * this the hard way, reporting "all inside SLA" over 150 cases of which 127 had no clock.
 *
 * Requires `onTrack > 0` so an empty board makes no claim at all.
 */
export function allInsideSla(mix: SlaMix): boolean {
  return mix.overdue === 0 && mix.atRisk === 0 && mix.unknown === 0 && mix.onTrack > 0
}

/**
 * The board's deadline horizon, as the `dueBefore` cutoff it asks the server for.
 *
 * End-of-day on the far edge, so a one-week window includes a case due at 5pm on the seventh day.
 * Pure and exported for the same reason the tables are: an off-by-one here silently hides work.
 *
 * **Takes a `DeadlineWindow`, not the shell's `DateRange`, and the arithmetic below is unchanged.**
 * It used to take the shell filter, which meant a control labelled "This year" for the dashboards
 * silently became a twelve-month deadline horizon here. The split is in the type; the clamping is
 * the same code it always was, because that part was never the bug.
 *
 * There is no `today` case any more and there never really was one: the shell's `today` fell
 * through every branch to end-of-today, which was correct by accident. Now the window is a closed
 * set of three and the compiler enforces that each is handled.
 */
export function dueBeforeFor(window: DeadlineWindow, now: Date = new Date()): string {
  const end = new Date(now)
  end.setHours(23, 59, 59, 999)
  if (window === 'week') end.setDate(end.getDate() + 7)
  if (window === 'month') addMonths(end, 1)
  if (window === 'year') addMonths(end, 12)
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
