# EvalOS — Build Plan

The complete, ordered unit list. Each unit produces one visible, verifiable
result, stays inside one system boundary, and builds only on units before it.
Ordering follows: dependencies first, security before functionality, backend
before frontend wiring, UI shells before real data, install just-in-time.

Stack: Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) backend, React +
Vite + Tailwind frontend, monorepo `backend/` + `frontend/`. Multi-brand:
row-level tenancy by `brand_id`, brand-scoped queries throughout.

Sequence maps to the roadmap: Units 01–10 = Phase 1 (structure the data),
11–17 = Phase 2 (connect the seams), 18–20 = Phase 3 (close the loop).

The `## Phase 3` heading below used to sit above Unit 17, contradicting that line
and putting Dashboards in a different phase depending on which part of this file
you read. The heading moved to Unit 18 rather than the sentence changing, because
the sentence is the one quoting the roadmap. Unit order is unaffected either way —
what the boundary decides is when Unit 17's open questions (dashboard ownership,
StatCommand) become blocking.

Generate a `specs/NN-name.md` for a unit just before building it.

---

## Execution sequence for v2.0 — what to build next, and why

The phase order above is the *dependency* order and is still correct. This section is
the **schedule**, and it differs, because the remaining units are not equally
blocked. **As of the 2026-09-09 audit only two of the four external asks are still live**, and
Track A below is six items deep with nothing outside this repo in its way.

### Step 0 — the external asks, two of four still live

None of these is code, all have lead time. **Neither remaining one blocks Track A** — the AWS
credential gates *exercising* built code rather than writing it, so chasing it is valuable but no
longer the thing everything queues behind. Two rows are struck and kept as the record of why:

| Needed | Blocks | Note |
|---|---|---|
| ~~Google service account for Drive~~ **AWS credential + bucket for the S3 document store** | **Unit 15's and Unit 30's live paths, Unit 21's remaining half** | **Unit 30 replaced the blocker rather than working around it**, and has since been **built** — so this no longer gates any code, only its live exercise. Unit 13's criterion left the list with the unit. Still the most valuable thing to chase, and it is an AWS credential the business already controls rather than a Google service account that never arrived. `30-s3-document-store.md`'s open questions (b) key format and (f) the portal contract were answered by the build; **(d) PDF is closed by removal** — nothing generates documents |
| ~~GHL outbound contract~~ | ~~**Unit 18**~~ | **Struck 2026-09-09. Unit 18 was removed on 2026-09-02**, so there is no subscriber to register and no signing secret to hold. Invariant 14 is settled, not pending. What survives is not a contract but a question — *who reaches the client at all* — tracked as **G15** in `process-automation.md`, not here |
| The real `opportunity.won` payload, signature header name, HMAC encoding | **Unit 05b's live run** — not its code, which is **built** | Always true and now demonstrated: 05b shipped with unit tests and never needed this. Only the end-to-end firing does |
| ~~Anthropic key + a compliance decision~~ | ~~Unit 20's AI half~~ | **Struck 2026-09-04. There is no AI half and no Unit 20.** The unit was removed from scope on 2026-09-02 and is now `architecture.md` invariant 15; leaving its key in the "things to request" table kept the door open. **Nothing in EvalOS needs an LLM.** The anomaly figure it also proposed is arithmetic, not AI — if the business wants it, it is a Unit 17 tile over stored metrics, and it does not need a unit whose name invites the model back |
| **`invoices.readonly` on the GHL token** | **Unit 41's live use — the code is BUILT** | **Added 2026-09-10** with the GHL operational programme. Unit 41 is otherwise **unblocked**: Unit 35 shipped a portal credential naming a `ghl_contact_id`, and that is exactly the key `GET /invoices/` takes. This is the cheapest ask on the list and it unblocks a whole unit that waits on nothing else — **chase it first** |
| **`calendars/events.write` + `calendars.readonly`** | **Unit 40's meetings half only — still outstanding** | **Added 2026-09-10; still the only thing Unit 40 is missing.** The desk shipped 2026-09-11 without meetings, exactly as predicted. **Follow-ups turned out NOT to need this** — a GHL task is `POST /contacts/{contactId}/tasks`, which needs only `contacts.write`. Checking the scope per *endpoint* rather than per *feature* is what found that |

**All three are scopes on the existing token, not new credentials.** The current grant is
`opportunities.write` + `contacts.write`. Verified against the live GHL API surface 2026-09-10;
the endpoints and their filters are tabulated in `00b-ghl-operational-programme.md` §3.

### Track A — buildable today, nothing external. Do these in order.

**Rewritten 2026-09-09 after an audit against the code.** The previous A1–A5 had rotted: A1 named a
notification gap that is closed, A2 named a unit that has shipped, A3 was already marked built, and
A4/A5 were split apart by a charting decision that has since been taken. **`progress-tracker.md` →
"Next Up" is kept in step with this list** — that section names only what is immediately next, this
one carries the reasoning.

**A1 · Unit 35 — party-scoped portal access (D1, D5, D6).** The remaining half; **D8 and G14 shipped
2026-09-04**. D1 is the load-bearing one — `portal_access` gains `ghl_contact_id` and `case_id`
becomes nullable, so a credential names a *party* and a client with two cases has one link. Do it
first because **two later items wait on it and nothing waits on them**.

**A2 · 34b — the client's draft review screen. BUILT 2026-09-11.** Read, approve and
request-revisions had been in EvalOS since Unit 14 with no screen calling them. Building it
surfaced a gap Unit 35 left: **D1 gave party scoping to the reads and not to the writes**, so a
client with two cases could open either draft and approve neither. Closed with
`POST /cases/{caseId}/approve` and `.../request-revisions`.

**A3 · 34d — the two case lists. BUILT 2026-09-11**, over Unit 35's party reads and D5's
projection — and it finished the job by deleting the client's account shell, the model D1
refused. 53 files went; there is no mock left in the client portal.

**A4 · Unit 17a — BUILT 2026-09-11.** G16's portal-links ledger shipped (the tile that makes "a link nobody sent" visible), G9 closed by derivation, G10 accepted as a limitation, G11 reopened with a real answer and left as a scope decision. Was: dashboards without charts, carrying gaps **G9–G11** and **G16**. This is 17a
only: **Unit 17's read models are built** (five `*MetricsService` behind seven `MetricsController`
routes), so what remains is the per-role operational contract and the gap list, not the data layer.
**G16 is the one to read twice** — nothing shows which portal links exist or whether anyone opened
them, so "a link nobody sent", the likeliest way to breach the 24h signing SLA, is invisible today.

**A5 · Unit 19 — BUILT 2026-09-11.** V42's `scheduled_job` ledger, four sweeps, the advisory lock
and a GM panel at `/admin/jobs`. Every re-read note held: the lock is **session-scoped** (an
xact-scoped one would release after the first item, since a sweep runs one transaction per item),
chases are **wall-clock** against a business-hours escalation so the order is chase→chase→escalate,
and **no sweep calls a transition** — the 24h sign deadline raises a prompt and there is no call
site for `EXPERT_TIMED_OUT` in the package. Two things the build changed: three notification types
were added because two sweeps would otherwise have silenced each other through
`alreadyRaised`, and `DocChaseSweep` now raises its own Coordinator prompt because
`checklist.reminder` has had no subscriber since Unit 18 left.

**A6 · Unit 17b — the cycle-time chart. The last Track A item.** No longer blocked: **Recharts was settled in Unit 22
slice 1** and is installed. Last because it is one widget, not because anything gates it.

That is a lot of runway with no waiting. Track A is the default: **work A1→A6 and interleave Track B
as blockers clear**, rather than idling on a credential.

### Track B — slot in the moment its blocker clears

| When this arrives | Build |
|---|---|
| **AWS credential + bucket** — the only external blocker with code behind it | **Unit 30 is built**, and Unit 13's criterion died with the unit, so nothing here is *code* waiting any more. What waits is exercise plus one remaining half: **Unit 15's live round-trip**, **Unit 30's live path**, and **Unit 21's remainder** — the upload moved to the separate Client Portal, so EvalOS owes *reading* the `client/{ghl_contact_id}/` prefix and reconciling it against the checklist. Also **G14's infrastructure half**: bucket-side malware scanning, the scanning EvalOS deliberately does not do in code |
| ~~GHL outbound contract~~ | ~~**Unit 18**~~ — **struck: removed from scope 2026-09-02.** No outbound channel, so no contract to wait for. The live question is **G15** — how an expert's link actually reaches them, hand-sent today, and an expert who never gets theirs cannot sign while the clock runs (`process-automation.md`) |
| ~~10, 15 and 18 all done~~ | ~~**Unit 19**~~ — **no longer a Track B item.** Its prerequisites are met, so it moved to **A5** |
| ~~17 done~~ | ~~**Unit 20's anomaly half**~~ — **struck 2026-09-04.** See the Step 0 note: no Unit 20, and the anomaly arithmetic is a Unit 17 tile if it is wanted at all |

**Not scheduled, and deliberately:** **Unit 25** (GHL OAuth per brand) is specced and unbuilt — no
`ghl_connection` table, no OAuth code — and it needs GHL OAuth app credentials, so it is a Track B
item with no arrival date. It is also what the deferred `PaymentDetailConverter` extraction waits
on; **do not write that abstraction before its second caller exists.**

**Unit 25 acquired a second dependant on 2026-09-10** and it is worth knowing which: the GHL
operational programme runs **single-brand** until Unit 25 lands
(`00b-ghl-operational-programme.md` §1.5). That is a ceiling enforced in code, not a hope — but
the trigger for Unit 25 is now **"a second brand starts selling"**, which is a business event
rather than a credential arriving.

### Track C — the GHL operational programme (Units 36–41)

**Added 2026-09-10.** EvalOS becomes the interface Sales and Marketing work in; GHL stays the CRM,
pipeline, automation and invoice layer underneath. **Read
`00b-ghl-operational-programme.md` before any of these** — it carries the truth model, the
single-brand ceiling and the invariant ledger, and a unit spec that disagrees with it is wrong.

| # | Unit | Depends on | State |
|---|---|---|---|
| **36** | Pipeline-scoped access | 02, 03 | **BUILT** 2026-09-10, `V39` |
| **37** | The GHL write door | 36 | **BUILT** 2026-09-10. Invariant 2 died here |
| **38** | Opportunity reads and the board | 36 | **BUILT** 2026-09-11, `V40`. **Past the point of cheap return** |
| **39** | Marketing lead desk | 38 | **BUILT** 2026-09-11, `V41`. Amended invariant 7; first caller of the write door; idempotency answered with **upsert** |
| **40** | Sales desk | 38, 39 | **BUILT** 2026-09-11 **except meetings**, which alone need the `calendars/*` grant. Follow-ups shipped — a GHL task needs only `contacts.write` |
| **41** | Client Portal invoices | **35 ✅** | **BUILT** 2026-09-11. **Not exercised live** — `invoices.readonly` still ungranted, so it answers 502 by design until it lands |

**Track C does not queue behind Track A**, and two of its rows are the reason to say so:

- **Unit 41 is independent of 36–40 entirely.** It reads `GET /invoices/?contactId=…` using the
  credential Unit 35 already ships. The moment the scope lands it can be built beside anything.
- **Unit 36 is buildable today** with no external anything.

**The ordering rule inside Track C is strict, unlike Track A's.** 37 before 38 before 39/40 is not
a preference: 37 is where the write capability is *decided on* and 38 is where the pivot stops
being cheaply reversible. Skipping 37 into 38 deletes a build-failing guard as a side effect of a
feature, which is the specific thing 37 exists to prevent.

**Two corrections the build made to this table.** Unit 38 does **not** depend on 37 — it reads
only, and Unit 37 §4's claim that 38 was "the first caller" of the write door was wrong; **Unit 39
is**, and owns the idempotency decision. And Unit 38 did **not** delete
`/api/marketing/sales-pipeline` or the three `evalos.ghl.*-pipeline-name` properties, contrary to
what specs 36 and 38 both said: those screens are analytics funnels over a date window, the board
is an operational card list with none, and they answer different questions. **That cut is still
available, on its own, if the business wants the funnels retired** — it would remove Units 24, 26
and 27's screens, not just their config.

### Why not simply follow the phase order

The phase order would put Unit 15 next, which is what the tracker said before this
plan. Two things changed. First, 05b, 16 and 17 are not blocked at all, so following
the numbering strictly would leave them idling. Second — and this is new — **Unit 15
stopped being blocked on its own dependency**: dropping the signature provider removed
the account, key, template and callback secret it was waiting on, and left it needing
only the storage credential that Units 13 and 21 already need. It is now a
one-credential unit that shares that credential with two others.

**Unit 30 changed which credential that is** — an AWS one instead of a Google service
account — and changed what Unit 21 still owes: the upload moved to the separate Client
Portal, so 15 no longer "reuses 21's upload path wholesale". It streams to
`case/{caseId}/signed/` through the S3 client that Unit 30 builds.

Dependencies still constrain, though two of the three named here have since resolved: 16 before 17
(both built), 21 before 15 (both built, and Unit 30 rewrote what 21 owes), and **~~18 before 19~~ —
18 was removed, so 19 waits on nothing.** The remaining live constraint is **D1 before 34b and
34d**. Nothing here reorders a real dependency; it only stops the schedule being decided by
whichever unit happens to be numbered next.

**Units 01–10 followed that rule; Units 11–20 did not.** All ten remaining specs
were written in one pass at the start of Phase 2, by decision, so the whole
remaining shape is on paper at once. The rule stays as written because it is the
right default — a spec written ten units early is written against code that does
not exist. So specs 11–20 are **drafts to be re-read and revised at the start of
their own unit**, not settled contracts, and 18–20 say so in their own headers.
**Several already carry corrections** — found while writing later specs, while
building the units they depend on, and in review — which is the failure mode the
just-in-time rule exists to avoid, caught early rather than at build time. Deliberately
not counted here: this sentence said "two" and named Unit 11's derived load and Unit
16's payout uniqueness, and was out of date by the next unit. A correction is marked in
the spec that carries it; that is the record, and a tally beside it is just a second
thing to keep in step.

---

## Phase 1 — Structure the data (the spine)

### Unit 01 — Project scaffold & config
Builds: Spring Boot (Maven, Java 21) service with a health-check endpoint,
PostgreSQL via Spring Data JPA, Flyway wired for migrations, `application.yml`
profiles + externalized config; React/Vite + Tailwind frontend with the design
tokens from `ui-context.md`; monorepo layout; `./mvnw verify` and `npm run
build` both green.
Depends on: nothing.

### Unit 02 — Multi-tenancy + Auth & RBAC/ABAC (security foundation)
Builds: the Brand entity + tenancy plumbing (mandatory `brand_id` on scoped
entities, a query-layer scoping mechanism that injects `brand + team + assignee`
predicates); Spring Security + JWT for internal staff; the six roles as
authorities (`GM`, `BRAND_MANAGER`, `PROJECT_MANAGER`, `PROJECT_COORDINATOR`,
`CASE_MANAGER`, `EXPERT_NETWORK_MANAGER`); method-security (`@PreAuthorize`);
and a reusable brand/ownership-check helper in the service layer. No feature
endpoints yet — just the guard rails. (No Head-of-Evals role, no interns.)
Depends on: 01.

### Unit 03 — Domain model & migrations
Builds: JPA entities + repositories + Flyway migrations for Brand, TeamMember,
ContactSnapshot (read-only), Case, Expert, PayoutLedger, DocumentChecklist,
Notification (in-app), and the append-only AuditTrail. `Stage` / `PayoutStatus`
/ `ServiceType` / `VisaCategory` / `Role` enums. Every scoped entity carries
`brand_id` + an audit hook. Field-level encryption `AttributeConverter` for the
single optional expert `payment_detail`. Compound indexes on
`(brand_id, team_id, assigned_to, stage)`, `(brand_id, deadline)`,
`(brand_id, sla_status)`.
Depends on: 02.

### Unit 04 — Case lifecycle service (state machine)
Builds: the 8-stage internal state machine (`DOC_COLLECTION → EXPERT_ASSIGNMENT
→ DRAFT_GENERATION → EXPERT_SIGNING → FINAL_DELIVERY → CLOSED`, plus exception
states `ON_HOLD_AWAITING_CLIENT`, `EXPERT_DECLINED_REMATCHING`,
`REFUND_REQUESTED`); declared-transition-only enforcement; `@Transactional`
transition methods; an audit entry on every transition; the pool→PM→CM
assignment model; SLA-status computation on the Pacific business calendar; the
GM-only refund transition (revenue reversal + pending-payout void + GHL signal);
and the brand-scoped case REST controller. Each transition publishes an internal
domain event. Those events are still published and still consumed in-process by
`NotificationListeners`; the **outbound dispatcher that was their second subscriber left with
Unit 18**, so `event` now has exactly one consumer.
Depends on: 02, 03.

### Unit 05 — Inbound webhook gateway + GHL opportunity handler (Handoff A)
Builds: the reusable inbound gateway (secret verification, **per-brand endpoint
→ brand_id resolution**, idempotency on the **source event id** scoped by brand,
raw-payload archival, handler routing, fast ack) and its first handler — GHL
`opportunity.won` → contact-snapshot sync, brand-tagged **paid** case creation at
`DOC_COLLECTION` in the brand pool, document-checklist open, PM/Coordinator pool
notification. **One inbound source, GHL** — this gateway was going to be reused by a
signature provider in Unit 15, and dropping that provider means it stays
single-source. This is the only path that
may create a case.
Depends on: 03, 04. (Confirm GHL payload + per-brand signing secret first.
`refund.requested` / `contact.updated` / `contact.created` recognized but deferred
or deliberate no-ops.)

The trigger has moved twice, and **Case Creation v2.0** is current: it is
**`opportunity.won`**, and the webhook *is* proof of payment, because GHL invoices
and collects before the opportunity is marked Won. The case is created **paid**, in
the PM/Coordinator pool; `contact.created` is a recognized no-op and no staff action
sets `paid`. See **`context/specs/05b-opportunity-won-intake.md`**.

Superseded readings of this paragraph, for anyone reading old code or commits: the
original draft said `payment.confirmed` and keyed idempotency on the invoice id;
Unit 05a said "contact created, not payment confirmed" with an unpaid case and a
`mark-paid` staff act. Both are history. `architecture.md`'s Handoff A and spec 05b
are what the code implements.

### Unit 06 — In-app notification center
Builds: the Notification service + brand-scoped staff notification center
(create/list/mark-read), fed by domain events (assignment, SLA breach,
escalation, KPI flag). Client-facing notifications are emitted as domain events
for GHL to deliver — EvalOS sends no email.
Depends on: 04.

### Unit 07 — App shell + role/brand-scoped dashboard routing (UI shell)
Builds: the internal React app shell (left nav, top bar with global date filter,
brand switcher — all-brands/filter for GM, locked for everyone else, notification
bell), role-scoped routing, and empty/placeholder dashboard states.
Depends on: 02, 06.

### Unit 08 — Production Kanban board
Builds: the stage-column board wired to the case API (EvalOS stages: Doc
Collection · Expert Assignment · Draft/Report · Expert Signing · Final Delivery,
plus exception lanes), RAG deadline badges, brand + role-filtered views (pool +
unassigned queue for GM/BM/PM; own docket for CM).
Depends on: 04, 07.

### Unit 09 — Case detail page
Builds: the two-column case view — documents (S3, opened via a presigned link — Unit 30;
was a Drive link) / draft / expert on
the left, the timeline/audit trail on the right — with stage-action controls,
PM strategy notes, and the draft sub-status chips (PM review / client review).
Depends on: 04, 08.

### Unit 10 — Document checklist board + Coordinator flow
Builds: the Coordinator's checklist board (required/uploaded/missing status against the
objects under the client's own S3 prefix — Unit 30; was the Drive link),
mark-docs-complete → push to PM, and the doc-collection
SLA/reminder hooks. Client chase messages are emitted as domain events for GHL
to send (no EvalOS email).
Depends on: 04, 09.

---

## Phase 2 — Connect the seams

### Unit 11 — Expert database (ENM) + sheet upload
Builds: the Expert entity/repository detail (brand-scoped; field-tag taxonomy,
letter types, tier, availability, quality score, fee, turnaround/decline
history), the optional encrypted `payment_detail`, ENM CRUD endpoints, the
availability board, and **bulk sheet upload (CSV/XLSX import mapped to fields)**
as the roster's primary maintenance path.
Depends on: 03.

### Unit 12 — Match scoring engine (assist mode)
Builds: the rule-based ranked top-3 shortlist service (field match + letter-type
experience + acceptance rate + current load), brand-scoped, surfaced to the PM
at assignment. Suggests only; a human confirms. (AI-enhanced ranking/anomaly
detection is Phase 3.)
Depends on: 11, 04.

### Unit 13 — Redacted CV generation — **REMOVED (2026-09-02)**
Deleted: `RedactedProfileService`, `ExpertProfileController`, `RedactedProfilePanel`,
`redactionRules`, the `REDACTED_PROFILE` document kind and the client portal's
`expertProfile` / `expertReference` fields. `V33` narrows `case_document`'s kind CHECK.
**`AuditAction.EXPORTED` is deliberately kept** — the audit trail is append-only, its rows
can never be rewritten, and an enum that cannot read a value some historical row carries
would fail on read. A retired audit action stays readable forever.

**The client is now told nothing about the expert at all**, which is the stronger position:
there is no redaction to get wrong and no generated document to keep anonymous. The portal
test asserts it by putting a very identifiable name on the case and grepping the wire.

**It kills Unit 30's open question (d).** Drive's export produced the PDF for free and S3
converts nothing — but the redacted profile was the only thing that needed converting, so
removing this unit removes the PDF problem rather than solving it.

`mayMintPortalLink` moved to `client-portal/portalRules.ts` with its test. It was Unit 14's
rule and only shared a file with this one.

### Unit 14 — Client draft-review portal
Builds: the separate, scoped filter chain for passwordless client access (link
delivered via GHL), the single-page draft view, approve / request-revisions
actions, and read-receipt tracking. Draft-review only — no source-doc upload.
Depends on: 02 (separate auth surface), 04.

### Unit 15 — Expert portal + Handoff B + sign-off
**BUILT 2026-09-03 (backend + the staff case card).** Six portal routes, two new transitions
(`EXPERT_ACCEPTED` guarded on the offer, `EXPERT_REQUEST_EVIDENCE`), the signed-letter upload with
content sniffing and a required attestation, `V36`. Two knowing departures: **the hash of the letter
as sent is not recorded** (the draft is a pasted link EvalOS holds no bytes of) and **the 20h/24h
events are Unit 19's to declare**. The expert-facing SPA is Unit 34 slice 34e.
Builds: the separate scoped filter chain for expert access (CM-shared link), the
single-column assigned-case view (draft + evidence + goal), the accept /
request-evidence (opens client task) / decline (→ `EXPERT_DECLINED_REMATCHING`)
paths, **the sign step — download the letter, upload it back signed** (Unit 21's
upload path with `audience = 'EXPERT'`, PDF only, attestation required), the
provenance record (hash sent + hash received + attestation + `EXPERT` audit row), and
the 20h/24h SLA alerting + the human-fired reassign operation.
Depends on: 12, 14, **21** (the upload path it reuses).
**No e-signature provider** — that decision removed this unit's account, API key,
template, callback secret, inbound handler and SDK dependency, and with them its two
gating questions. It now needs only the Google service account that 13 and 21 need.

### Unit 16 — Payout ledger (manual)
Builds: payout entry auto-created (status Pending) when a case reaches
Delivered, tied to case + invoice; a **manual form** for the responsible team
member to record method/reference/amount/date and mark Paid/Confirmed; status
tracking; the weekly batch view; and expert-facing payout status in the portal.
Ledger only — no disbursement rail, no payment-platform integration.
Depends on: 03, 11.

### Unit 17 — Dashboards (read models)
Builds: the production-side role dashboards (GM cross-brand; Brand Manager, PM,
Coordinator, Case Manager, ENM within brand) — money-in vs. delivered (open
liability), cycle time by stage, expert utilization & acceptance rate, review
capture — reading precomputed read models refreshed on events.
Depends on: 04, 11, 16.

---

## Phase 3 — Close the loop

### Unit 18 — Outbound dispatcher + Handoff C — **REMOVED (2026-09-02)**
Never built: no `webhook_delivery` table, no dispatcher, nothing to delete.

**There are two handoffs now, not three.** A (GHL → EvalOS, inbound) and B (internal,
client approval → expert). **EvalOS emits nothing outbound at all** — its integration
surface is inbound GHL webhooks and read-only pulls of GHL's funnels. Domain events still
publish, but in-process only, and the notification centre is their sole consumer.

**What survives:** the payout ledger entry is still created on delivery, inside
`deliverToClient`'s transaction. That was always Unit 16's.

**What is genuinely lost, and must not be discovered later:** nothing tells GHL a case was
delivered, so the review sequence, the referral track and the suppression-list sync are
started **by hand in GHL**. And the document chase and "your draft is ready" have no
automated route to the client — either done by hand, or they become states the client sees
in the portal, which is a product decision still open.

**Invariant 14's "sends no email" stops being a pending decision** and becomes the
architecture: there is no outbound channel to argue about.

### Unit 19 — Background jobs consolidation
Builds: the full `job` package backed by the `scheduled_job` **run ledger** —
`@EnableScheduling`, a Postgres advisory lock per sweep so two instances cannot
double-fire, doc-collection reminders (24h/48h), the day-3 escalation, stage-SLA
escalations, and expert sign 20h/24h alerts (which **prompt**, never reassign) — on the Pacific
business calendar.
Depends on: 05, 10, 15. **All met.** **BUILT 2026-09-11** — see the spec's *What the build found*.
**Four sweeps, not six.** Two left this unit and for different reasons: retention/countdown timers
because **GHL owns** retention and the post-delivery review end to end, and the **outbox sender
because Unit 18 was removed (2026-09-02)** — there is no outbound channel, so there is no outbox to
drain. Re-read the spec before building: the advisory lock must be **session-scoped**, client chases
are **wall-clock** while escalation is business hours, and **no sweep may call a transition** — every
one of them prompts.

### Unit 20 — AI widgets — **REMOVED (2026-09-02)**
Never built. Removed rather than deferred, and the difference matters: a deferred unit
invites "we were going to do this anyway", and this one will be proposed again.

**Now invariant 15** in `architecture.md`: no AI makes a production decision, and there is
no AI in the system. Verification, expert selection, drafting, review, client approval,
reassignment and QC are human judgements. Notifications, transitions, timestamps,
versioning, audit and dashboard arithmetic are unaffected.

**Unit 12's match engine is not an exception** — four declared factors, inspectable
arithmetic, never auto-assigns, no model. Making it "smarter" by asking something to reason
about a case is a reversal of invariant 15 and needs writing down first.

### Unit 21 — Client document upload (A07)
**⚠ Reshaped by Unit 30 and never built in its original form.** The upload no longer
happens in EvalOS: clients upload in the **separate Client Portal**, which writes to the
S3 document store under the client's own id. What remains for EvalOS is the *reading*
half — list `client/{clientId}/`, let the Coordinator associate an arrived object with a
checklist item, and review what came in. Unit 10's checklist statuses and Unit 14's
portal token model are unchanged and still do the work.

**The upload trust boundary moves with the upload** — content-sniffed allowlist, size cap,
rate limit, generated filenames — and is now the Client Portal's to enforce. That is a
real transfer of responsibility and must be confirmed with whoever builds that portal
(open question (f)), not assumed because the requirement moved it off our side.
Depends on: 10, 14, 30.

### Unit 23 — Case notes, and routing intake to the PM
Builds: the Project Manager as the front door for incoming work — the pool lane
leaves the GM's board, `/inbox` and `/checklists` leave their sidebar (nav only,
no backend gate narrowed), and `assign-pm` admits the PM so they claim a pooled
case from their own inbox and then staff the coordinator and case manager. Plus
**notes on a case**: any caller the case scope admits appends free text to the
append-only trail as `NOTE_ADDED`, and `Timeline` becomes *Notes & timeline*. The
GHL won-opportunity payload carries an optional `notes` onto the `CREATED` row, so
a case arrives with what sales wrote on it. Adds one column-equivalent of scope
(`ScopePredicate.Fields.unteamedVisible`, set on cases only) and **no new table** —
a note is an audit row for the reasons in `architecture.md`'s storage model.
**23a** then removed `GM_OR` from `draft/pm-approve` / `draft/pm-return` and made
`/drafts` PM-only — the one place the GM is *excluded* from a transition rather
than added to it, because approving a Case Manager's draft is the judgement of the
PM who assigned it.
Depends on: 04, 08, 09, 22.

### Unit 24 — Marketing: the Google Ads funnel (GM)
Builds: the GM's one view of GHL's front of house — the **Google ADS Pipeline** as
a chevron funnel (deals and value per stage, share of pipeline) with the sources
behind it. First **read** integration against GHL's public API: two calls, a
five-minute cached payload, `opportunities.readonly` and no write method anywhere.
**Resolves the open question this list has carried since Unit 17** — sales/marketing
dashboards were defaulted to GHL-native, and the answer is now *one read-only GM
screen in EvalOS, everything else stays in GHL*.
Invariant 2 is intact: EvalOS reads the funnel, it does not run marketing. Nothing
is persisted — there is no `ghl_opportunity` table and there must not be.
**The one screen in EvalOS that is not brand-scoped, deliberately**: it reads one
GHL location that the brands share, so no `brand_id` predicate exists that could
narrow it — hence GM-only, hence no `brandId` parameter, hence the Brand Manager
is excluded. See `24-marketing-google-ads-funnel.md` for the full argument and for
the process note that this spec was written **after** the code.
Depends on: 07, 17, 22.

### Unit 26 — Marketing: the email funnel (GM)
Builds: a second GM screen over the same GHL location — **Shivangi's Email
Marketing**, the email acquisition channel — through Unit 24's client, service,
cache and card system. A `Funnel` enum (`ADS`, `EMAIL`) keys into configured
pipeline names; the cache key becomes `(funnel, range)` so two identically shaped
payloads can never answer for each other; one React component serves both screens.
**Answers the question Unit 24 explicitly left open** — "a second marketing screen
is a new question" — as *yes for a second reading of a pipeline in the location
EvalOS already reads, on the same terms*. Invariant 2 is intact: still no write, no
persistence, no `ghl_opportunity` table. Still GM-only and still not brand-scoped,
for Unit 24's reason unchanged; **Unit 25a re-scopes all three screens together** (Unit 27 added the third).
Also fixes a defect this pipeline exposed: it holds ~11.4k opportunities a year
against a 5,000-row page cap, so a truncated read now reports `truncated` and the
screen says every figure is a floor, instead of stating 5,000 as the total.
See `26-marketing-email-funnel.md`.
Depends on: 24.

### Unit 27 — Sales: the sales pipeline (GM)
Builds: the third GHL pipeline read — the sales team's own working funnel
(*Aditya's pipeline*) — under a **new `Sales` nav group**, GM-only.
`GET /api/marketing/sales-pipeline`, `evalos.ghl.sales-pipeline-name`, and
`Funnel.SALES`. **Total cost: a property, an enum constant, a route method, a nav
entry and a union member — no new class on either side**, which is the return on
Unit 26's shape and was the explicit prediction left in `application.yml`.
Under **Sales** rather than Marketing on purpose: the other two are campaign
funnels, this is a salesperson's pipeline and carries stages they do not
(`Meeting booked`, `Invoice sent`, `Refund` — all `OPEN`, no special cases). The
API route stays under `/api/marketing/` — a stated naming debt, smaller than
splitting one integration across two controllers.
**The substantive finding is a defect this pipeline exposed**: GHL stores its name
with **two spaces**, so the single-space spelling a human types into config did
not match and the screen answered 502 — a failure whose cause is invisible in
both places anyone would look. `GhlPipelineClient` now collapses whitespace runs
before matching, in the *shared* client so all three funnels benefit; a name
differing by a real character still fails loudly, which is the point of matching
by name at all. Verified live: the single-space name resolved to the real
pipeline with all nine stages.
Invariant 2 intact — no write, no persistence, no `ghl_opportunity` table.
Still GM-only and not brand-scoped, for Unit 24's reason unchanged;
**Unit 25a now re-scopes three screens, not two.**
See `27-sales-pipeline.md`.
Depends on: 24, 26.

### Unit 28 — Dashboard date filters (calendar, completed, custom)
Builds: the shell's period control becomes four **calendar-to-date** buttons
(Today / This week / This month / This year), a dropdown for **Last month** and
**Last year**, and a **date-to-date** range on two native date inputs.
`range=last-month|last-year|custom` plus `from`/`to` on `/api/metrics/pm` and all
three `/api/marketing/*-pipeline` routes.
**The substantive change is a split, not an addition.** This one value was read in
two opposite directions — backwards by the dashboards and the GHL screens, forwards
by `BoardView` through `dueBeforeFor` — a collision `ui-context.md` had recorded for
two units and that had already left the production board unfiltered once. Every new
option breaks the sharing outright: `last-month` as a "due before" cutoff returns
every open case, and an interval is two edges where a cutoff needs one. So the board
now owns `DeadlineWindow` (`week|month|year`, forward) and the shell owns `DateRange`
(seven periods, backward) — **enforced by the type**, not by a comment.
`DateRange` also stopped carrying `int days`: a to-date period has no fixed width and
`last-month` does not end today, so `DateWindow` resolves a name into inclusive days
and owns that arithmetic alone.
**V26 re-keys `ghl_funnel_cache` on the resolved window** (`window_key`), because every
custom period is *named* `custom` and name-keyed rows would serve one period's figures
for another — undetectable on screen, identical payload shapes. Rows are deleted rather
than translated: which window a row covered depends on the day it was written, which
the row never recorded.
**Behaviour change on live screens:** every dashboard figure moves — `month` was the last
30 days and is now since the 1st. The labels were the half that was already lying.
See `28-dashboard-date-filters.md`.
Depends on: 08, 17, 24, 26, 27.

### Unit 29 — Sales desk — **BUILT (2026-08-29), REMOVED (2026-09-02)**
The sales desk and the `SALES_EXECUTIVE` role are gone from the codebase. Deleted:
`SalesController`, `SalesBoardService`, `GhlSalesClient`, `features/sales/`, the
`/sales/board` nav entry, `Role.SALES_EXECUTIVE`, `team_member.ghl_user_id` and its
mapping endpoint, and the V906 seed login. `V30__drop_sales_executive.sql` reverses V29's
two constraint rewrites and drops the column; V29 itself stays, because an applied
migration is never edited or deleted.

**What the removal cost, and what it did not.** It cost one migration and no data
reconciliation at all — the unit's central decision was that **nothing is stored here**,
no `ghl_opportunity` table and no sales column anywhere, so there was nothing to unwind.
The decision that made a write safe is the same one that made the write cheap to remove.

**Invariant 2 reverts.** Unit 29 was the only unit that has ever cost an invariant:
"EvalOS never runs sales" died and the boundary moved from read-vs-write to custody. Both
come back. `GhlHttp` now exposes no `post`, `put` or `delete` — EvalOS reads GHL and
writes nothing to it, asserted by `GhlHttpTest` rather than claimed by a comment.

**What survives the unit.** `GhlHttp` itself stays extracted: it was pulled out to hold
one shared rate-limit pacer, and that limit is a property of the GHL *location*, not of
whoever is reading it this month. Folding it back into `GhlPipelineClient` is how the next
client silently gets a pacer of its own, so the shared-pacer test now runs across two
instances of the one remaining client. The PII widening reverts with the board that needed
it: `SalesOpportunity` carried a contact's name, email and phone under `/api/sales/**`, and
that endpoint tree no longer exists.

`29-sales-desk.md` is kept as the record of a decision that was made, shipped and undone.
Unit 27's `/sales/pipeline` — the GM's *read* of the same funnel — is untouched.

### Unit 30 — S3 document store + shared client identity
**SPECCED 2026-09-02, not built.** Replaces Google Drive with an S3 bucket for every
document: client uploads (scans/images), draft PDFs, the redacted expert profile and the
expert's signed letter. The **Client Portal is a separate frontend whose backend is
EvalOS** — it holds no AWS credential and uploads by calling EvalOS's portal API, which
streams to S3. Reads are 5-minute presigned URLs issued after the case's scope check.
**One client across GHL, the Client Portal and EvalOS**, keyed on GHL's contact id, with
email consistent alongside but still a fallback key only (V27). **Amends invariant 14 and
deletes "No object storage".** Unblocks Units 13, 15 and 21, all stuck on a Google service
account that never arrived. Two capabilities Drive was quietly providing must be repaid:
PDF conversion, and A12's inline draft comments.
See `30-s3-document-store.md`. Open before code: (b) per-brand key format, (d) PDF, (f) the
portal contract.
Depends on: 02, 04, 10, 14.

### Unit 31 — Production lifecycle v2 (twelve stages, one owner each; eight board columns)
**SPECCED 2026-09-02, not built. All six of its open questions are answered.** The
five-stage pipeline with sub-status chips becomes **twelve explicit stages**, each with one
owner, one primary action, one event and one next owner — drawn as **eight board columns**,
since two stages share one only where they share an owner. Adds the two transitions the workflow needs and the state machine lacks: **`qc-fail`**
(a failed final QC currently has nowhere to go) and **`send-to-expert`** (which is what
should start the 24-hour signing SLA — today it runs from stage entry, so an expert sent
the letter late is charged for the delay). Adds a `case_document` version history; a draft
may not be submitted without a file and no version is ever overwritten. **Manual by
decision — no AI in any production decision.**
**Reverses spec 08's derived-grouping decision**, and says why: a chip states what state
work is in, not whose turn it is.
See `31-production-lifecycle-v2.md`. **Amends 04, 08, 09, 10, 14, 15, 17, 22** — it is a
state-machine change and the blast radius is wide.
Depends on: 04, 30.

### Unit 32 — PM notes panel + draft status board
**SPECCED 2026-09-02, not built.** Small — both surfaces exist in part and neither needs a new
subsystem. Two gaps: the PM's **expert selection rationale** becomes its own column (different
lifetime from case strategy — it is rewritten per expert and Unit 31 made reassignment a normal
path; different audience — the ENM reads it and the CM does not; and it is the evidence for "why
this expert" that gets asked for after something goes wrong), and the PM's return comment is
**stamped on the draft version** (`case_document.review_comment`) instead of living only in the
audit trail. Angle and key points stay one field: they are one act of writing.

**Closes A12 in the sense EvalOS can.** Comments *per version* — yes. Comments *positioned inside
the document* — no; that was Drive's feature and needs a viewer, not a migration. The register now
says "partly" with the distinction written out.

**Do not join a comment to a version by timestamp.** Two rapid review rounds would attach the wrong
comment to the wrong version, silently and plausibly. The transition writes the column.
See `32-pm-notes-and-draft-status.md`.
Depends on: 09, 22, 23, 31.

### Unit 25 — GHL OAuth connection (per brand)
Builds: the GM connects a brand to a GHL sub-account from inside EvalOS, replacing
Unit 24's hand-pasted Private Integration Token. A brand-scoped `ghl_connection`
row holds the grant; the refresh token is the **second encrypted column in EvalOS**
and that needs sign-off, because `code-standards.md` currently states there is only
one. `state` nonce on the callback (single-use, constant-time compare, brand taken
from the state row and never from the request), refresh serialized with
`SELECT … FOR UPDATE` because **GHL rotates the refresh token** and two instances
refreshing concurrently would otherwise retire each other's grant.
**No dual path** — the PIT is deleted, which is free only because Unit 24 has never
run live. One permitted path in `SecurityConfig`, no third filter chain.
Its live connection also closes **Unit 24's** one outstanding acceptance item.
The follow-on (**25a**) re-scopes the funnel — **all three GHL screens: Unit 24's,
Unit 26's and Unit 27's**: `brandId` becomes legal, the Brand Manager is admitted, and invariant 1's stated exception is removed. (It was four while the sales desk existed, and that screen's re-scoping was the one that changed shape rather than just gaining a parameter. The desk is gone; three again.) Deliberately not
in 25 — a credential's lifecycle and a screen's role list are different boundaries.
Depends on: 02, 24.

### Unit 33 — The full record: applicant, case discipline, expert dossier — BUILT
Builds: the record EvalOS holds becomes the record the business holds. An audit of
`IE_Case_Sample_Data.xlsx` and `IE_Expert_Sample_Data.xlsx` against V1–V34 found
**19 of 36 expert facts absent** — degree, position, affiliation type, location,
LinkedIn, supported visa categories, publications, citations, h-index, patents,
awards, memberships, editorial roles, languages, rush capability, `IE-EXP-###`,
turnaround — and **no applicant name on any case**, which is a correctness gap the
moment a client is a law firm rather than an individual. Nineteen columns widen
`expert` (**no child table** — 1:1, no history, no second implementation);
`evalos_case` gains `applicant_name`, `field_of_expertise` and `rfe_date`.
**It reverses Unit 12's documented omission** of a case field tag: the objection was
to an *intake source*, not to persistence, and the tag the PM types at match time is
now stored by the assignment. `FieldTag` gains 11 values — its 28 were drawn for
credential-evaluation degree fields and **10 of the 22 disciplines in the expert
sheet cannot be spelled**, so those experts score zero on a 40-point factor.
`ServiceType`, `LetterType`, `ClientType`, `VisaCategory` gain the values the sheets
use; **the V18 CHECKs move with the enums** or the app writes rows the database
rejects. `last_active_date` is **derived** from `expert_case_offer`, never stored.
List stays lean, detail shows everything. Still no payment column, ever.
See `33-case-and-expert-dossier.md`.
Depends on: 11, 12, 31.

### Unit 34 — The portal frontend, and wiring it to EvalOS — **PARTLY BUILT (34a, 34c, 34e)**
Builds: the external SPA that arrived in `client/` on 2026-09-03 — **both portals, one
deployment, port 5174, the origin the backend already allows** — becomes a real client of
this backend. It is a **pivot spec**, because the app was built against a different auth
model (email+password accounts in `localStorage`), a different case model (a *list* of
cases per user, which no case-scoped token can answer) and **four duplicate lifecycle
vocabularies**. Three invariants are in its path: a guided intake funnel that mints its
own case reference (**8**), payments and invoices pages (**2**), and client messaging plus
support tickets (**14** — EvalOS has had no outbound channel since Unit 18 was removed).
And the one thing the backend already implements — **draft review, approve, request
revisions** — has **no screen in the app at all**.

Five decisions gate it, each with a recommendation: **D1** widen `portal_access` to name a
*party* rather than a case (rather than building an account system, whose password reset
needs a mail channel invariant 14 forbids); **D2–D4** cut intake, invoicing and messaging;
**D5** one lifecycle vocabulary, EvalOS's, projected into the payload so the SPA holds no
enum. Slices **34a** seam → **34b** draft review → **34c** documents → **34d** case list +
projection → **34e** expert portal. **34a, 34c and 34e are BUILT (2026-09-03)**; 34e went in against
Unit 15's six routes — the one case the token names, at `/case#<token>` outside the account shell.
**34b and 34d are not built**, and 34d cannot be until D1 lands: D1 still gates the assignments
*list*, D6 still gates payments.
See `34-portal-frontend-wiring.md`.
Depends on: 14, 30, 31, and 15 for slice 34e.

---

## Notes

- **The monorepo is three applications as of 2026-09-03**, not two: `backend/`,
  `frontend/` (staff, 5173, same-origin `/api` proxy) and `client/` (the external portal
  frontend, 5174, cross-origin against `/api/portal/**`). The stack line at the top of
  this file predates the third and is corrected here rather than there, because the line
  is quoting the roadmap. `client/` is mock-backed and calls nothing until Unit 34.
- Automation rules from the CRM spec are covered across Units 04–21, and
  **`context/process-automation.md` is the register** — it maps every A-number to
  the event, the recipients, the owning unit and whether it is built yet. Read that
  rather than re-deriving coverage from this list. Rules A01–A06 (lead / sales /
  marketing) and A21's post-delivery scheduling are GHL's and out of scope.
- **Multi-tenancy is not a unit — it is a property of every unit.** From Unit 02
  onward, every scoped query filters by `brand_id`; brand resolution at Handoff A
  is by per-brand endpoint token. **The three GHL reads (Units 24, 26, 27) are the only
  screens that are not scoped, and they are an exception with a stated reason rather than a
  gap**: they read a GHL location EvalOS cannot attribute to a brand, so no `brand_id`
  predicate exists that could narrow them — which is why all three are GM-only and take no
  `brandId`. **Unit 27's separate `Sales` nav heading does not change this**: a different
  heading over the same `location-id` is still unattributable. Read the rule as *every query
  over EvalOS rows*, and treat a new unscoped query over EvalOS rows as the defect it still is.
  **25a's re-scoping sweep covers these three.** It briefly covered a fourth, Unit 29's sales
  board, which has been removed along with the `SALES_EXECUTIVE` role that worked it.
- **Object storage as of Unit 30, and EvalOS still hosts nothing in it.** Documents are
  **S3 object keys**, read through 5-minute presigned URLs. Client documents are written by
  the **separate Client Portal** under `client/{clientId}/` — EvalOS's credential is
  read-only there — and EvalOS's own artefacts live under `case/{caseId}/`. The line here
  used to say "no object storage": that half is now false and the half that matters,
  **EvalOS stores no bytes**, is unchanged and still a test. The one upload EvalOS still
  accepts is the expert's signed letter (Unit 15), which **streams**. Staff alerts are
  in-app (Unit 06); clients are reached through GHL and experts through a scoped portal
  link. **No mail server** — whether EvalOS ever sends mail is still an **open decision**
  (`context/process-automation.md`); until it is taken, no mail dependency.
- **Webhook subsystem is inbound only**: the gateway is built once in Unit 05 and
  **stays single-source (GHL)**. The outbound dispatcher this line used to promise was Unit 18's,
  **built and then removed (2026-09-02)** — EvalOS has no outbound channel, and the domain events
  Unit 04 onward publishes are consumed in-process.
- Open questions gate specific units (see `progress-tracker.md`): the **full
  brand list**, **StatCommand**, the **GHL inbound contract** (per-brand secret + payload for
  Unit 05 — the *outbound* subscriber URL, secret and client-message capability went with Unit 18
  and are no longer asks), and **staff SSO** (optional/later). Resolve each
  before starting the gated unit. *(The Dropbox Sign callback secret was on this list
  until the signature provider was dropped; Unit 15 no longer has a gating question.)*
