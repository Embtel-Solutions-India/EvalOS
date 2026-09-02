# Process & Automation — Production Process v2.0

> **⚠ AMENDED by Unit 31 — Production lifecycle v2 (SPECCED 2026-09-02, not built).**
> The workflow supplies a **complete event → owner → next stage → notification matrix**
> — §30 and §35 of `31-production-lifecycle-v2.md` — which is this register's shape
> applied to twelve stages instead of five. Reconcile the two before building: where they
> differ, the A-numbers here are the older reading.
>
> **Two notifications have no event behind them today** and are added by Unit 31:
> **QC failed → CM** (there is no `qc-fail` transition at all) and **sent to expert →
> Expert + ENM** (nobody presses send; the case enters signing automatically). Both are
> gaps in the current system, not merely relabelled rows.

The business process EvalOS runs, and the automation register behind it. Sourced
from the *International Evaluations CRM Build Spec* (June 2026) and reconciled
against what the code actually does.

This is the map from **a thing happening** → **the event EvalOS publishes** → **who
hears about it** → **the unit that owns it**. Nothing else in the repo answers that
question in one place, which is why this file exists.

---

## The one-home rule — read this before adding anything here

> **Every fact has exactly one home. This document points at homes; it never
> becomes one.**

A process document is the classic place for a threshold to get restated, drift from
the code, and then be trusted. So it does not happen here:

| Fact | Its single home | What this doc may do |
|---|---|---|
| SLA budgets, at-risk fraction | `service/SlaCalculator.java` | cite; the table below is a marked mirror |
| Business hours, weekends, holidays | `service/BusinessCalendar.java` | cite |
| Which transitions are legal | `service/CaseTransitions` whitelist | cite |
| Trigger → recipient | `notification/NotificationListeners.ROUTES` | cite |
| How a recipient set is resolved | `notification/RecipientResolver` | cite |
| Whether a recipient set falls back when empty | `notification/RecipientResolver` | cite — **only `pmsAndCoordinators` does.** Empty means "raise nothing" everywhere else, because an assignee lookup returning nobody means the work has an owner who is not this person. The pool arrival is the opposite case: nobody owning it is the point, and it is the only notice that a *paid* case exists — so an unstaffed brand escalates to the GM and its managers rather than taking the money and telling nobody |
| Brand / team / assignee scope | `security/ScopePredicate` | cite |
| Who may see money fields | `CaseController.SEES_DEAL_VALUE` | cite |
| RAG and capacity thresholds | `context/ui-context.md` | cite |

If a number here disagrees with its home, **the home wins and this file is the
bug**. Do not "fix" the code to match this document.

---

## Custody, and what GHL keeps

GHL owns every *pipeline* in this business; EvalOS takes custody the moment the
thing becomes real.

| Pipeline | GHL owns | EvalOS takes custody at |
|---|---|---|
| Client sale | lead → opportunity → invoice → collection | `opportunity.won` → the case is created, paid (spec `05b`) |
| Expert recruitment | prospect → outreach → agreement | the ENM adding the expert to the roster (Unit 11) |
| Retention & reviews | the 7-day review request and the 30/90/180/365 sequence, end to end | nothing — EvalOS emits `case.delivered` and schedules none of it |

The consequence for this document: **stages 1–2 and 8 of the eight-stage business
pipeline generate no EvalOS automation.** Everything below covers stages 3–7.

---

## The five stages EvalOS owns

### 04 · Document collection — Coordinator owns, PM oversees

| | |
|---|---|
| **Trigger in** | Case created from a won deal, already paid. The service-specific checklist is seeded `REQUIRED`, `checklist.requested` is published for GHL to send the client their upload link, and `NEW_CASE_IN_POOL` goes to that brand's **PMs and Coordinators**. |
| **What happens** | Client uploads documents against the checklist (Unit 21). The Coordinator reviews each item, chases anything missing or wrong, and loops until the package is complete. |
| **Trigger out** | Coordinator marks docs complete → `documents.completed` → the case reaches the PM. |
| **SLA** | Complete package within 3 business days of case creation. |
| **Automations** | A07 upload flags · 24h and 48h client chases (Unit 19 `DocChaseSweep`) · A08 push to PM · A09 day-3 escalation. |

**Human review, not AI.** The build spec's "AI reviews uploads and flags missing or
incorrect items" is **out of scope by decision** — the Coordinator does the review.
See Unit 20, which records the same exclusion so it is not read as a natural
extension.

### 05 · Expert evaluation & assignment — PM assigns, ENM advises on availability

| | |
|---|---|
| **Trigger in** | Docs complete; the case is in the PM's inbox. |
| **What happens** | PM reads the package, writes strategy notes and instructions for the Case Manager, and picks the expert (Unit 12 ranks the top 3; the PM confirms — never auto-assigned). Assigns the case to a CM with the notes attached. |
| **Trigger out** | `expert.assigned` → the CM begins the draft. |
| **SLA** | PM reviews and assigns within 4 hours of docs marked complete. |
| **Automations** | ENM notified when a case needs an expert · escalation to the **GM** if unassigned after 4h. |

### 06 · Draft / report generation — CM produces, PM reviews, client approves

| | |
|---|---|
| **Trigger in** | Case assigned to the CM with PM notes and the full document package. |
| **What happens** | CM builds the evaluation or EOL and submits. PM approves or returns with comments (loop). On approval the Coordinator shares it with the client, who approves or requests revisions (loop). |
| **Trigger out** | Client approves → the CM sends the final letter to the expert. |
| **SLA** | First draft within 48h of assignment · PM review within 12h · client review 48h per round. |
| **Automations** | A11 · A12 · A13 · A14 · A15, plus stalled-review alerts from `StageSlaSweep`. |

Both loops live **inside** `DRAFT_GENERATION` — they are not stages. The round in
progress is read from `pm_approval_status` / `client_approval_status`, and
`stage_entered_at` is restamped each round so a second round does not inherit the
first one's spent clock.

**⚠ A12 is PARTLY covered — read the row carefully.** "Comments visible inline on the draft" rested
on **Google Drive's own commenting** on
the draft document. EvalOS records the PM's return reason and builds no annotation
subsystem — the draft already lives somewhere that does this natively.

### 07 · Expert signing — CM drives, ENM covers supply

| | |
|---|---|
| **Trigger in** | Client approved the draft; the signature request goes to the assigned expert. |
| **What happens** | Expert reviews and signs. No answer by 20h raises a warning; by 24h the case is prompted for rematch. On signature the PM runs final QC. |
| **Trigger out** | PM QC-approves → `qc.approved` → the case is ready to deliver. |
| **SLA** | Expert signs within 24 business hours. |
| **Automations** | A16 · A17 · A18 · A19. |

**Auto-reassignment proposes; it never reassigns.** Pulling a case off an expert who
was about to sign, and sending a second expert the same letter, is worse than a late
case. The clock raises the prompt; a **human fires `EXPERT_TIMED_OUT`**. See Unit 15
for who — the CM sees the prompt, a PM or above fires it.

### 08 · Final delivery — Coordinator delivers

| | |
|---|---|
| **Trigger in** | PM approved the signed letter after final QC. |
| **What happens** | Coordinator sends the signed letter to the client, collects confirmation of receipt, and closes the case. |
| **Trigger out** | `case.delivered` → GHL starts the review and retention tracks · the payout ledger row (Pending) is written in the same transaction · revenue is recognized. |
| **SLA** | Delivery sent within 2 hours of PM final QC approval. |
| **Automations** | A20 · A21. Everything A21 schedules is **GHL's**. |

---

## The automation register

Business trigger and effect are the build spec's wording. `Event` is the published
`CaseEvents.Type`; `Alert` is the `NotificationType` and the recipient set as
resolved by `RecipientResolver`.

| A | Business trigger → effect | Event | Alert → recipients | Owner | Status |
|---|---|---|---|---|---|
| **A07** | Client uploads via the checklist link → flags raised, client notified, Coordinator alerted | *none yet* | Coordinator | **21** | **gap** |
| **A08** | All documents marked complete → case pushed to the PM inbox, PM notified | `documents.completed` | `STAGE_CHANGED` → assigned PM | 04/10 | **built** |
| **A09** | Docs not complete by day 3 → escalation to PM, flagged on the GM dashboard | `docs.escalation.day3` | *(new)* → **PM + GM** | **19** | **specced** |
| **A10** | PM assigns case to CM → CM notified with PM notes, case in their queue | `expert.assigned` | `CASE_ASSIGNED` → assigned CM | 04 | **built** |
| **A11** | CM submits draft → PM notified, draft at the top of the review queue | `draft.submitted` | `STAGE_CHANGED` → assigned PM | 04 | **built** *(queue view: Unit 17)* |
| **A12** | PM returns draft with comments → CM notified, comments inline | `draft.returned` | `STAGE_CHANGED` → assigned CM | 04, 32 | **partly**. Notification and return reason: **built**. Comments **per draft version**, stamped on `case_document.review_comment` and shown in the version history: **Unit 32**. Comments **positioned inside the document** (Drive's own feature, gone with Unit 30): **not covered and not planned** — anchors need a viewer that understands the file, which is a product rather than a migration |
| **A13** | PM approves draft → Coordinator notified to share; client notified | `draft.pm_approved` + `draft.ready_for_client` | `STAGE_CHANGED` → Coordinators | 04 | **built** *(client leg: touchpoints)* |
| **A14** | Client approves draft → CM notified to send to the expert | `draft.client_approved` | `STAGE_CHANGED` → assigned CM | 04/14 | **built** |
| **A15** | Client requests revisions → CM notified, revision logged with a version number | `draft.revision_requested` | `STAGE_CHANGED` → assigned CM | 04/14 | **built** — `draft_version_count` |
| **A16** | CM sends letter to expert → expert notified, 20h countdown begins | *(Unit 15)* | expert, ENM | **15** | **specced** |
| **A17** | Expert unsigned at 20h → alert to CM, reassign prompt shown | `expert.sign_overdue_warning` | → CM + PM | **15/19** | **specced** |
| **A18** | Expert unsigned at 24h → rematch prompted, ENM notified, CM confirms next expert | `expert.sign_overdue` | → CM + PM + ENM | **15/19** | **specced** |
| **A19** | Expert signs → PM notified for QC, signed letter attached to the case | `expert.signed` | `STAGE_CHANGED` → assigned PM | 04/15 | **built** |
| **A20** | PM completes QC → case in the delivery queue, Coordinator notified | `qc.approved` | `STAGE_CHANGED` → Coordinators | 04 → **17** | **built** *(delivery queue view: Unit 17)* |
| **A21** | Case delivered and closed → review queued at 7d, retention 30/90/180/365, revenue confirmed | `case.delivered`, `case.closed` | — | **GHL** + 17 | **GHL's** |

**One gap left, and it is real work rather than paperwork.**

- **A07** needs Unit 21 (client upload). Nothing exists.
- **A20 is closed.** It was the subtle one: `qc.approved` was published and
  `qc-approve` was built, but `NotificationListeners.ROUTES` had no entry, so a
  Coordinator learned a case was deliverable by looking at the board. One route did
  it. The *delivery queue screen* is still Unit 17's — the alert now arrives, the
  list it points at does not exist yet.

Statuses that are **built** are built as of Unit 05b (plus the A20 route). Where a
row names a unit above 14, that unit is specced and unbuilt — the whole timed half
of this register waits on Unit 19, which is the clock.

---

## SLA budgets — a mirror, not the source

**Source of truth: `backend/src/main/java/com/ie/evalos/service/SlaCalculator.java`.**
Reproduced here because the business reads this file. If they differ, the code is
right.

| Stage / sub-state | Budget |
|---|---|
| `DOC_COLLECTION` | 3 business days |
| `EXPERT_ASSIGNMENT` | 4h |
| `DRAFT_GENERATION` — first draft | 48h |
| `DRAFT_GENERATION` — PM review pending | 12h |
| `DRAFT_GENERATION` — client review pending | 48h |
| `EXPERT_SIGNING` | 24h |
| `FINAL_DELIVERY` (QC → delivered) | 2h |
| At risk once | three quarters of the budget is spent |

These match the build spec exactly — the business confirmed the numbers the code
already had, so nothing changed.

**All of them are business hours** on `BusinessCalendar`: America/Los_Angeles,
09:00–17:00, weekends and 11 US federal holidays excluded. "Three business days" is
therefore 24 clock-counted working hours, which is why the constant reads 24. A case
in any exception state has **no clock** — `SlaCalculator` returns null, and the
sweeps skip it.

**Known defect to fix when Unit 19 is built:** its spec tells `StageSlaSweep` to
match cases whose status is `AT_RISK` or `BREACHED`, but `SlaStatus` defines only
`ON_TRACK`, `AT_RISK`, `OVERDUE`. Read it as `AT_RISK` / `OVERDUE`; do not add a
fourth enum value.

---

## Outward touchpoints — the email table

Every message this process sends outside the company. **EvalOS sends none of them
today**, and whether it ever should is undecided.

| # | Touchpoint | Trigger event | To | Channel |
|---|---|---|---|---|
| T1 | Checklist + upload link | `checklist.requested` | client | **DECISION PENDING** |
| T2 | 24h chase | `checklist.reminder` (Unit 19) | client | **DECISION PENDING** |
| T3 | 48h chase | `checklist.reminder` (Unit 19) | client | **DECISION PENDING** |
| T4 | Upload flagged incomplete/incorrect (A07) | *(Unit 21)* | client | **DECISION PENDING** |
| T5 | Draft ready to review | `draft.ready_for_client` | client | **DECISION PENDING** |
| T6 | Signing link (A16) | *(Unit 15)* | expert | **DECISION PENDING** — see below |
| T7 | Evidence requested | `expert.evidence_requested` | client | **DECISION PENDING** |
| T8 | Final signed letter delivered | `case.delivered` | client | **DECISION PENDING** |
| T9 | Review request at 7 days, retention at 30/90/180/365 | `case.delivered` | client | **GHL** — decided |

**What "decision pending" is a decision between.** Either GHL delivers these off
the outbound event (the architecture's current answer, and the reason Handoff C
exists), or EvalOS sends mail itself — which **reverses invariant 14** and brings in
an SMTP provider, deliverability, bounce handling, unsubscribe and a suppression
list. That is a business call about who owns the client relationship, not a
technical preference. Nothing is built either way.

**T9 is settled — GHL owns retention and reviews.** **T6 moved back into the open**
when the signature provider was dropped: Dropbox Sign used to email the expert its own
signing link, so that touchpoint had an owner by default. Now the expert is reached by
an EvalOS portal link, and *something* has to carry it to them. Until the channel is
decided the Case Manager sends it by hand — the same stopgap Unit 14 shipped for the
client link, and for the same unanswered reason.

**Note this makes the email decision bite harder than it did.** It is no longer only
about client convenience: an expert who never receives their link cannot sign, and the
20h/24h clock runs regardless. Hand-sending works at current volume and will not
scale.

**Until it is decided, the compensating control is Unit 17's portal links ledger**
(metric 5 in `17-dashboards.md`, gap **G16**). Because delivery is a human copy-paste,
EvalOS cannot record that a link was *sent* — and must not pretend to, which is why
there is no `sent_at` column. What it can record is that one was *opened*
(`portal_access.last_seen_at`), and that is the honest evidence it arrived. The ledger
surfaces a live-but-never-opened link against a running stage clock, which is the
shape the failure actually takes. **If the channel decision ever lands on EvalOS
sending these, `sent_at` becomes a fact it can witness and belongs in that unit** —
not before.

**Convention while it is undecided:** wherever code will eventually sit for one of
these, leave a marker comment naming the touchpoint —

```java
// email: T5 draft ready for client — channel undecided (GHL vs EvalOS mail).
// See context/process-automation.md, outward touchpoints.
```

so `grep -rn "// email:"` lists every place the decision lands.

---

## Where the rest lives

- Trigger → recipient routing, and why silence is a decision: `06-notification-center.md`
- The clock, the sweeps, locking and the outbox queue: `19-background-jobs.md`
- Client upload: `21-client-document-upload.md`
- Dashboards, queues and per-role KPIs: `17-dashboards.md`
- What is still missing, with status: the **Gap Register** in `progress-tracker.md`
