# 00d — Platform audit and alignment programme

> **Status: AUDIT COMPLETE 2026-09-13. Phase 0 is PART BUILT as of 2026-09-15** — see
> `00d-implementation-runbook.md` §2 for the item-by-item ledger. Done: the local `location-id`
> default, `V909`'s seeded-desk repoint, the Google Ads removal, `PortalCaseService.upload`, the
> client document re-key, and the board's `reloads` bug. **Still open and still blocking
> everything: the `opportunity.won` workflow and the new PIT**, neither of which is code.
> This is a **programme document**, third of its kind beside `00b-ghl-operational-programme.md` and
> `00c-ghl-independence-programme.md`. It does not replace them — it **amends `00c` before
> Unit 44 starts**, adds the work neither programme scheduled, and carries the ledger of what
> the code actually does versus what three programme documents claim.
>
> A unit spec that contradicts this file is wrong; this file is amended first.

> **Corrected 2026-09-13, same day.** The integration audit's delegated spec-reading task failed,
> so it reached its findings without reading the prose of Units 05, 05b, 19, 25, 37, 38, 39, 40,
> 41 and 43. A seventh pass read those ten specs against every finding
> (`context/audit/2026-09-13/verification-unit-specs.md`): **11 confirmed, 6 already answered, 5
> contradicted, 9 refined.** Eight corrections are applied below. Three of the five contradictions
> would have reversed decisions that were argued, priced and shipped; one — §5.4 as first written —
> **failed the build**, because `DomainInvariantsTest` permits exactly one injector of
> `CaseIntakeService` and that is invariant 8 doing its job. The P0 diagnosis in §2 survived all
> ten specs untouched.

**Method.** Six independent senior audits — architecture, backend engineering, frontend
engineering, UX, product, integration — each reading the code rather than the docs, with the
instruction that **where a spec and the code disagree, the code is the fact and the divergence
is itself a finding**. Their full reports are preserved at `context/audit/2026-09-13/` and are
the evidence base for everything below: a 129-route API inventory, a per-screen state matrix
across all three frontends, a 23-item do-not-break list, and a cutover repair list. This
document is the consolidation, not a summary — where the six disagreed, §6 records the ruling
and the reason.

---

## 1. The verdict in one page

**The build is real and the engineering is better than the brief assumed.**
`./mvnw test` is green: **966 tests, 0 failures, 93 test classes**. 20 tables, 46 contiguous
migrations, zero `@Transactional` in `web/`, `open-in-view: false`, two genuinely separate
security filter chains, SHA-256 token storage with constant-time compare, no IDOR found, no
`any` in 190 frontend files, and **zero mock-backed routed screens in any of the three apps** —
the mock debt the brief anticipated does not exist. Brand scoping at write time is structural,
not conventional. Invariant 13 (append-only audit) is enforced at three levels including a
database trigger.

**Roughly 60% of the brief's target is already the written plan.** `00b` (Units 36–41, shipped
2026‑09‑10/11) put Sales and Marketing *in* EvalOS. `00c` (Units 42–49) decided the id-faithful
GHL mirror, two-way sync and the "switch the sync off and nothing collapses" target. Invariant 2
is already dead. The brief is not asking for a change of direction; it is asking for the
direction to be finished.

**What the audit found that the documents do not record:**

| | |
|---|---|
| **The business may not be taking orders.** | The `opportunity.won` workflow lived in the GHL sub-account abandoned on 2026‑09‑11. Until it is recreated, **Handoff A is dead and no case is created by anything**. Four further cutover breaks compound it, including every Sales/Marketing desk being silently empty. |
| **Client document upload is broken three ways in one method.** | `PortalCaseService.upload` resolves the case by `findById(principal.caseId())` — null for every token Unit 42 mints — *and* requires a `ghl_contact_id` that post-cutover clients do not have *and* has no per-case variant. Verified in code. |
| **The founding requirement is architecturally prevented, and the fix is nearly free.** | `Case` has no pipeline column, so `ScopePredicate`'s PIPELINE arm returns `cb.disjunction()` — Sales matches *nothing*, by design. Meanwhile `Case.ghl_opportunity_id` and `OpportunityNote.ghl_opportunity_id` have held the same value in the same database since 2026‑09‑11, unjoined, and `PortalStageProjection` already converts a stage into a safe outsider phrase. |
| **ENM is the largest unscheduled gap.** | Sales and Marketing got four units. ENM got a table and a dashboard. No candidate entity, no hiring stages, no assignee axis. **Nothing in `00-build-plan.md`, `00b` or `00c` schedules any of it** — and Unit 22 slice 4 *refused it in writing*. |
| **`00c` needs amending before Unit 44 starts.** | Six concrete defects in the mirror plan, of which one — at-least-once delivery over a non-idempotent create — produces exactly the duplicates the programme's own framing forbids. |

**The through-line.** This system was built as a production CRM and then, in four days, grew a
selling front end. What was never built is **the connective tissue between them**, and every
operational pain in the brief lives in that gap. The good news is the arithmetic: the two
highest-value items in this audit are a read model with no migration and a third projection
function beside two that already exist.

---

## 2. P0 — the business is not running

These are not features. Nothing else in this document is testable until they are done, and they
are ordered by dependency.

### 2.1 Cutover repair (operational, hours, no new code)

The sub-account swap `kBumF0uUOmMBB5bneYjx` → `WY6bW2xUCI8Tz8gw7aLJ` (2026‑09‑11, no migration)
broke five things. **Order matters: C1 → C3 → C2 → C4.**

| # | Broken | Repair | Evidence |
|---|---|---|---|
| **C1** | **Handoff A is dead.** The `opportunity.won` Custom Webhook workflow lived in the abandoned account. No workflow ⇒ no webhook ⇒ no case, for anything paid. | Recreate it in the new location pointing at `/api/webhooks/ghl/{brand.webhook_endpoint_token}` — the EvalOS token is unchanged. **Verify with one live won opportunity before anything else.** | `00c` §1b; `WebhookRouter.java:50` |
| **C2** | Local config default still names the dead location, so every developer reads a dead account and sees empty screens that look like bugs. | `application-local.yml:112` — change the default. One line. | `application-local.yml:112` |
| **C3** | The old PIT authorises nothing; a 401 here reads as a scope problem. | New PIT, full `pit-` prefix (40 chars), with all six scopes at once: `opportunities.readonly`+`.write`, `contacts.readonly`+`.write`, `invoices.readonly`, `calendars.readonly`, `calendars/events.write`. Check `token length=` in the boot log. | `GhlHttp.java:105-117` |
| **C4** | **`team_member.ghl_pipeline_id` holds dead ids.** This is an *access key*: every SALES/MARKETING member is scoped to a pipeline that does not exist, and the board draws zero columns **with no error**. | Reassign every active SALES/MARKETING member through the GM route. Add a boot-time `(name → id)` log so the next recreate is visible in one grep. **⚠ There is no UI for this.** `PUT /api/team-members/{id}/ghl-pipeline` exists and has **zero frontend callers** (verified), so today C4 is a curl or a DB write. That makes §8.6's team-administration screen a **dependency of the cutover**, not a Phase 6 nicety — or C4 ships as a documented manual runbook step. | `V39:30`; `OpportunityBoardService.java:148`; `TeamMemberController.java:90` |
| **C6** | `ghl_opportunity_cache` holds a pipeline's worth of dead ids, and `PipelineScope` authorises writes from it. | `TRUNCATE ghl_opportunity_cache`. It is droppable by design; this is the situation that was designed for. | `V40`; `PipelineScope.java:63` |
| **C7/C8** | **Every pre-cutover client's invoices and meetings tabs are empty**, live, today — both are fetched from GHL by a contact id that no longer exists. An empty list reads as *"you have no invoices"*. Two screens shipped last week are now actively misleading. | A **third** message — *"we can't reach your billing records right now"* — distinct from both "no invoices" and the missing-scope hint. `41` §9's test `anUpstreamFaultIsNotBlamedOnTheScope` forbids reusing that hint for a generic outage (*"a guard that fires on 404s and timeouts too would teach the reader to ignore it"*), so this needs a new string, not a widened guard. **P1.** | `PortalInvoiceService.java:59-61`; `41` §9; `00c` §1c |

`client_account.ghl_contact_id` and `portal_access.ghl_contact_id` also name dead contacts —
**keep them as they are.** `V45`'s reasoning is correct and is the best-argued decision in the
repo: two jobs in one column, and only the GHL-facing one broke. Nulling them would blank every
client's case list.

**One more line belongs on this checklist and is on no list today.** `39` §3a records that contact
dedupe *"depends on a GHL location setting EvalOS does not control… EvalOS cannot detect or prevent
that, and **must not pretend to**."* So **confirm *Allow Duplicate Contact* is off in
`WY6bW2xUCI8Tz8gw7aLJ`** before Marketing opens a lead in it. That is a provisioning obligation,
not a code change — which is exactly why it gets forgotten.

### 2.2 The client cannot upload a document — three defects, one method

Verified directly in `service/PortalCaseService.java:342-362`:

1. **`upload` bypasses its own authorisation.** Line 345 is
   `cases.findById(principal.caseId())` where every other method on the class calls
   `authorized(principal)`. A Unit 42 sign-in mints a party- or account-scoped token whose
   `caseId` is **null by construction** (`PortalPrincipal.isPartyScoped()` *is defined as*
   `caseId == null`). `findById(null)` throws → **500 on every client document upload.**
   It survived because `ClientPortalTest:300` stubs the service with Mockito and
   `PortalCaseServiceTest` has no upload test at all.
2. **It then requires a live `ghl_contact_id`** to build the S3 key
   (`requireState(ghlContactId != null, …)`). Every client created after the cutover — and
   every client Unit 43's signup funnel is about to create — is in exactly that state.
3. **There is no per-case variant** of `documents`, `documentUrl` or `upload`, so a client with
   two cases gets a 409 `SAY_WHICH_CASE` and can never upload. `approve` and
   `request-revisions` were given `/cases/{caseId}/…` routes for precisely this reason; the
   three document routes were forgotten.

**Decision: MODIFY (1) + REDESIGN (2) + ADD (3).** One-line fix for (1) — and it closes a
latent cross-brand write, because `upload` also skips the brand check `byId` applies. Ship it with
the test that would have caught it.

For (2), **adopt `43` §5's key verbatim: `{brandId}/client/{clientAccountId}/{documentId}`.** That
spec already chose it and argued it against both `ghl_contact_id` *and* `case_id`, because funnel
documents are uploaded a step before either exists: *"keying it on the account is also what keeps
the documents readable if the contact is later deleted or the CRM replaced. `00c` inherits this as
the pattern."* An earlier draft of this document offered "`case_id` **or** `client_account.id`",
which reopens a settled question and names an option the funnel cannot use.

> **⚠ WHAT WAS BUILT DIFFERS, 2026-09-14, and the difference is deliberate.**
> `DocumentStore.clientKey` re-keyed on **`contact_snapshot.id`**, not `client_account.id`. Its one
> caller is `PortalCaseService.upload`, which uploads against a **case**, and a case always has a
> contact snapshot — it has no `client_account` in hand and no column joining the two. Unit 43's
> funnel uploads a step earlier, where the opposite is true, and keeps `client_account.id`.
> **Two EvalOS-owned prefixes for two moments in a client's life**, converging when Unit 44 merges
> the tables. The reasoning this paragraph gives is unaffected — both are EvalOS ids, and neither
> is an identifier a third party can revoke, which was the whole point. `case_document.object_key`
> stays authoritative on read, so neither choice moves an object.

### 2.3 Failures are invisible

A failed webhook is stamped, archived, rethrown as a 5xx — and **never retried by anyone**.
GHL's Custom Webhook action does not redeliver, and EvalOS's own redelivery left with Unit 18.
One transient blip during a deploy and a **paid case is silently never created**: the money is
taken, the client is told nothing, and the evidence is one row in a table no screen reads.

Nothing surfaces any of it. `webhook_event.error` and `scheduled_job.FAILED` are recorded and
unread; Actuator exposes `health` only; `grep GHL_UNAVAILABLE frontend/src` returns nothing.

**Decision: ADD** two things, both small.
- **`WebhookReplaySweep`** in `job/` under the existing `JobLock` — re-route unprocessed events
  with exponential backoff and a dead-letter. ~60 lines reusing `Sweep`, `JobLedger` and
  `JobSchedule` wholesale. **Three constraints from `19`, because a sweep that misses them ships
  dead or double-fires:** (i) `SweepRegistrationTest` is load-bearing — a fifth sweep needs
  `implements Sweep`, a `@Scheduled` tick **and** a matching `evalos.jobs.intervals` key keyed by
  `JOB_TYPE`, or it *"would pass the entire build and fail in production"*; (ii) it takes
  `JobLock` **session-scoped**, not `_xact_`, because these sweeps run one transaction per item —
  and **Run now** goes through the same lock, not around it; (iii) `19`'s boundary holds — the
  sweep decides *when*, never *what*, so re-route through the existing `WebhookRouter` and
  re-implement nothing. The `attempts`/backoff column belongs on `webhook_event`, not on a job row.
  Note: `05` step 6 promises *"a retriable 5xx **so GHL re-delivers**"* — GHL's Custom Webhook
  action does not, so that sentence and `05b`'s criterion 4 are void until this sweep makes them
  true again.
- **`GET /api/admin/ghl/status`** on the existing jobs panel (`features/jobs`, not a new admin
  area): configured, location id, last success, last failure + status, unprocessed webhook count
  with oldest timestamp, per-pipeline cache age. A red dot in the shell when any of them is bad.
  It inherits `19`'s **GM-only, gated at the route *and* in the service** rule — this is
  cross-brand infrastructure.

This is P0 not because it is hard but because **§2.1 and §2.2 are currently invisible, and an
invisible P0 cannot be triaged.**

### 2.4 Three interaction defects that discredit shipped features

- **The opportunity board blanks itself after every action.** `OpportunityBoardPage:33-37`
  passes `[reloads]` as `useMetrics` deps, and the hook clears `data` on a deps change — the
  exact behaviour it documents itself as having been fixed to avoid by providing a separate
  `reload()` that does not clear. A salesperson adding a note loses the board, their scroll
  position and every open card. **MODIFY**, one line.
- **No write updates the opportunity cache**, so a dragged card snaps back to its old column
  for up to the 2-minute TTL. Every write already receives GHL's authoritative row back; write
  it into the cache. That is not optimistic local state — it is GHL's own response, which
  `00b` §1.3 expressly permits. **MODIFY.** Salespeople will otherwise learn to distrust the
  board within a day, and the natural workaround is to open GHL.
- **Irreversible actions fire on one unguarded click** — the client's draft approval (which
  *is* Handoff B), the expert's decline, and Sales closing a deal lost or abandoned. An unused
  `ConfirmDialog` sits in `shared/` while the copy on those buttons already says "this cannot
  be undone". **ADD** the confirmation to all four.

---

## 3. Phase 1 — kill the status loop

This is the brief's founding requirement: *a client asks Sales for status → Sales messages
Production → Production replies → Sales relays it back*, plus experts asking about their own
cases. **Three independent audits found the same mechanism and the same cheap fix.**

### 3.1 Why it happens — verified, not inferred

1. **Sales cannot read a case, structurally.** `Role.SALES` is `Tier.PIPELINE`;
   `ScopePredicate`'s PIPELINE arm returns `cb.disjunction()` when the entity has no pipeline
   column; **`Case` has no pipeline column** (verified: `grep pipeline Case.java` returns
   nothing). So even a deep link yields nothing. Nav gives SALES exactly one route.
2. **Neither conversation can see the other.** `opportunity_note` is readable only by SALES and
   MARKETING — *the GM is excluded by an explicit decision in the controller javadoc* — while
   case notes live as `audit_event` rows readable only by production roles. **No role on earth
   can read both.**
3. **Nobody joins them.** `Case.ghl_opportunity_id` (`Case.java:224`) and
   `OpportunityNote.ghl_opportunity_id` hold the same value. The join is unwritten. This is
   gap **G11**, reopened and then declined on 2026‑09‑11 as "a scope decision". It is now a
   business requirement.

The loop is not a habit. **It is the only path the permission model leaves open.**

### 3.2 What kills it — four items, none large

| # | Item | Size |
|---|---|---|
| **S1** | **Carry the intake note through Handoff A.** `OpportunityWon` drops everything Sales and the client already said; the PM opens a paid case with a name and a number and re-interviews. Unit 23 §4 specced a `notes` field; it was never built and 05b wrote the contrary decision without reconciling the two. | ~4 lines on `customData` onto the `CREATED` audit snapshot |
| **S2** | **`UnifiedTimelineService` — one read model over three anchors** (contact → opportunity → case), each level's events merged in timestamp order and **filtered by the reader's role at serve time**. Pass a case id, get its events plus its opportunity's; pass an opportunity id, get the deal's plus any case born of it. | 1 service, 1 endpoint, **no migration, no new truth** |
| **S3** | **A SALES case-status read**, scoped by `Case.ghl_opportunity_id` ∈ the caller's pipeline, serving **`PortalStageProjection.forSales(stage)`** — a third projection beside the `forClient`/`forExpert` that already exist — plus `stage_entered_at`, SLA status and a derived expected-completion date. **Never** the draft, the strategy notes, the expert's identity or the checklist detail. | 1 read service, 1 screen, 1 projection method |
| **S4** | **Tell the clients their door exists.** Unit 42 shipped accounts on 2026‑09‑12 and `V45` seeded them **with no password**; there is no invitation mechanism and no evidence any client was invited. A "your account is ready" send using the existing `sendSetPassword` is *already* authentication mail under the amended invariant 14. | operational |

**The test for S3 is simple and defensible: a salesperson may see precisely what they are
allowed to repeat to the client, and nothing they would have to be careful about.** That is why
reusing the client projection is right rather than convenient — every leak objection
`41` §10 raises is already answered by the mechanism that exists.

**Do not build**: a status-update composer, a Sales-sends-an-email feature, or a shared inbox.
The loop is killed by giving Sales *read access to the truth*, not a second way to restate it —
a composer creates a second, staler answer to the same question.

### 3.3 Two supporting items

- **The GM must read the opportunity note stream.** The current exclusion is an implementation
  consequence (`PipelineScope` answers "is this *my* pipeline" and a GM owns none) that hardened
  into policy. Widening it for `Tier.ALL` is the honest fix and a prerequisite for S2 being
  useful above desk level.
- **Global search.** `TopBar` ships a permanently `disabled` input reading *"Search — not
  available yet"*. Sales could not use a case screen even once granted one; the ENM's
  `/cases/:id` grant is unreachable because no list of theirs produces a link. One brand-scoped
  search over case code, client name, applicant name and expert name. The honest disabled
  control was the right interim call; it is now the top structural IA gap.

---

## 4. Phase 2 — the expert becomes a first-class audience

The second status loop, which no document has named: **the expert asks Production or the ENM
what is next and what they are owed, and the answer is already computed and has no screen.**

| Finding | State |
|---|---|
| **No front door.** A CM mints a link, it is shown **once**, stored nowhere, and hand-pasted into an unknown channel. `PortalLinkLedger`'s own header: *"the likeliest way EvalOS breaches that SLA is a link nobody sent."* The 20h/24h clocks run regardless. | Gap **G15**, open |
| **One screen over a four-read backend.** `GET /portal/expert/cases` and `/portal/expert/payouts` are built and whitelisted; the SPA has exactly one route, `/case`. An expert with three assignments holds three links and can see none of the others. | backend done, frontend missing |
| **The expert cannot open the evidence.** `ExpertCaseView.evidence` is `readonly string[]` — rendered as an unlinked list of filenames under *"What the opinion rests on"*. They are asked to attest to a professional opinion able to see only the **names** of the documents it rests on. | **quality risk on a signed legal instrument** |
| **No payout visibility.** Decided 2026‑09‑04 (`34` D6: *"yes, rows only, never a payment detail"*), never built. | decided, unbuilt |

**Decision: ADD, in this order.** (1) **Expert accounts on the Unit 42 pattern** — one bcrypt
column, the existing per-IP limiter, the existing `PortalTokenFilter`, and `mintForParty`
already accepts an expert party. The justification for the asymmetry (no mail channel) expired
when invariant 14 was amended on 2026‑09‑11. (2) The case list and payouts screens — **zero
backend work**. (3) Return `{id,label}` for evidence and reuse the presigned-URL pattern already
proven twice.

**`payment_detail` keeps its property of having no read path anywhere, for anyone** (invariant
4). The expert portal must not become the first exception.

---

## 5. Phase 3 — foundations, before the mirror adds four tables

Every item here is cheaper now than after Unit 44, because the mirror will otherwise inherit
the convention rather than the constraint.

| # | Item | Why now | Decision |
|---|---|---|---|
| **5.1** | **Composite `(brand_id, id)` FKs.** No composite foreign key carrying `brand_id` exists in any of 46 migrations — every child FK is single-column. Nothing at the database level prevents a brand-A document pointing at a brand-B case; the guarantee rests on ~50 service classes remembering. | One migration converts the whole defect class from "a reviewer must notice" to "the insert fails". Must land **before** the mirror adds four tables and eighteen more finders. | **MODIFY / P0** |
| **5.2** | **`@Version` on `ScopedEntity`.** No optimistic locking anywhere. Two staff acting at once (PM approve + PM return, two Accepts, deliver + hold) both pass the transition guard and the second write wins — two audit rows, one surviving state, and the audit then disagrees with the case. Money is protected; the case machine is not. | The mirror adds webhook, delta sweep and outbox all writing one row concurrently. Without this there is no ordering guarantee at all. | **ADD / P1** |
| **5.3** | **`brand.ghl_location_id` + per-brand `GhlHttp`.** `GhlHttp` holds **one** `locationId` field and `brand` has no location column, so the single-brand ceiling is **schema and transport, not configuration**. The rate limiter is per-*location* by GHL's own rule, so a shared pacer across two locations is both wrong and invisibly wrong. | `00c` §5 requires every mirror row to carry `brand_id` and claims this "finishes what Unit 25 started" — **but Unit 25 has not started.** Shipping 44 without it means a `brand_id` column holding one value, the exact anti-pattern V40 refused. | **REDESIGN / P0 for sequencing** |
| **5.4** | **Implement `contact.created` / `contact.updated`.** Both are recognised, archived and acked, so `contact_snapshot` holds **only contacts that won an opportunity** — every prospect and every lead Marketing opened this month is `UNKNOWN` to the portal. | Closes the prospect gap for **sign-in recognition** and is half of Unit 45's inbound layer. **It must not be written through `CaseIntakeService`.** `05b` rules that `contact.created` *"must not route to intake"* and enforces it structurally — `DomainInvariantsTest` scans the classpath and permits **exactly one** injector, `GhlOpportunityHandler`. A `GhlContactHandler` injecting `syncContact` **fails the build**, which is invariant 8 working correctly. **Extract `syncContact` into a `ContactSnapshotService` first**; both handlers use it and `CaseIntakeService` keeps its single injector. Two corrections to the value claim: it is **not** a prerequisite for portal signup (`43` §1a/§6a resolve a returning client through `identify` + GHL's own upsert, not through `contact_snapshot`), and the handler is **not** the only missing piece — the GHL workflow that fires these events does not exist in the new location either, so this carries a C1-family operational item with it. | **ADD / P1** |
| **5.5** | **Performance, five items.** Cache `pipelines()` — **already recorded ponytail debt with the upgrade path named** in `38` §10 (*"`draw` still resolves stage names through `GhlPipelineClient.pipelines()` on every board request, uncached"*), so it is a scheduled item, not a new finding. Bound `GhlHttp.pace()` so it throws rather than parking a Tomcat thread (30 concurrent cold boards can park the whole pool inside `Thread.sleep`, taking down screens that never touch GHL) — **bound the shared pacer, never give sweeps their own**, which is the bug `37` §3 says `GhlHttp` was extracted to prevent. Push `PayoutService`'s three full-table scans into Specifications; batch `PortalLinkLedgerService`'s N+1; cache the portal's invoice and meeting calls. | Eight read models call `lifecycle.list(null,null,null)` — the whole scoped case table, SLA recomputed in Java — including `/api/metrics/nav` on **every page load**. Fine at 50–100 cases/brand/month; it is the mirror that changes the volume. | **REFACTOR / P1** |
| **5.6** | **Per-account sign-in throttle.** `PortalTokenFilter` keys 60/min on `getRemoteAddr()` — a *caller*, not an account. Nothing counts failures per `client_account`; BCrypt is the only brake, and `identify` is a deliberate enumeration oracle. The two compound. | Client accounts shipped last week; this is the moment to add the counter. | **ADD / P1** |

---

## 6. Phase 4 — `00c` amended, before Unit 44 starts

`00c`'s plan is sound in its bones — three layers, an outbox, verbatim ids — and should not be
restarted. **Six amendments, of which the first is the one that breaks the programme's own
promise if missed.**

### 6.1 At-least-once delivery over a non-idempotent create produces duplicates

`POST /opportunities/upsert` without an `id` keys on `(contactId, pipelineId)`. The outbox gives
at-least-once delivery. **A retry after a timeout on a create is exactly how one opportunity
becomes two** — the failure invariant 2 forbade retries to avoid. Upserts do not fix it; the
outbox does not fix it.

**Fix: a client-side correlation key.** Write the EvalOS `opportunity.id` into a GHL custom
field on create, and on retry-after-timeout search that field before creating. **That requires
tier-2 (custom fields) at Unit 44, not Unit 47** — one field, not the general tier-2 sync. This
is the single biggest sequencing error in `00c`.

The same mechanism is live today in a different form, and **Unit 39 §3a already decided it,
knowingly.** `upsertOpportunity` sends no `id`, so opportunity upsert means *one open opportunity
per contact per pipeline*. `39` §3a calls that **correct for a marketing pipeline** — *"a lead is
a lead"* — and names the escape hatch in the same breath: *"Unit 40 must not reuse this path for a
genuine second deal; the escape hatch is `POST /opportunities/`, and taking it **brings the
duplicate risk back, knowingly**."* Unit 40 complied — Sales never creates an opportunity, every
route is a `PUT` or a sub-resource — so the failure mode requires Marketing to re-open a lead for
a returning client **on the marketing pipeline**, which is the case `39` classed as correct. The
opportunity id, and therefore the whole `opportunity_note` stream, survives; what changes is the
lead's name and value.

**So: do not switch the create path to `POST /opportunities/` now.** It reverses `39` §3a against
its own reasoning and **breaks `43` §7**, which depends on both calls being upserts: *"Both GHL
calls are upserts, so a retry matches the existing contact rather than duplicating it… Retry is a
staff action on the application row."* `43` criterion 7 pins it. What is genuinely open is
narrower and belongs at Unit 44 with the correlation key: a **create** that must distinguish "this
contact's second deal in this pipeline" from "the same deal again" needs a key GHL does not offer.
Until then, surface the already-bound `isNew=false` as a confirmation step in the new-lead form
(*"this contact already has a deal in this pipeline — open it, or create a second?"*) rather than
changing the verb. That is the option `39` §7 left room for.

### 6.2 Per-field ownership, not a blanket "EvalOS wins"

`00c` §4b's blanket rule **reverts GHL automations — the one thing `00b` explicitly kept GHL
for.** A round-robin reassigning a deal or a workflow adding a tag is not a conflict to be
undone. Put ownership per field, in code:

| Entity.field | Owner | Conflict |
|---|---|---|
| `pipeline.*`, `pipeline_stage.*` | **GHL**, read-only | GHL always wins; EvalOS never pushes |
| `contact.email`, `.phone`, `.name` | shared | EvalOS wins, report |
| `contact.tags`, `.custom_fields` | **GHL** (workflows key off them) | GHL wins, report |
| `opportunity.name`, `.amount`, `.stage_id`, `.status` | shared | EvalOS wins, report |
| `opportunity.assigned_to` | **GHL** (round-robin automations) | GHL wins, report |
| `opportunity_note.*` | **EvalOS**, sole | never synced |
| invoices | **GHL**, read-only | n/a |

Two further corrections `00c` misses: **`ghl_updated_at` is nullable and GHL-supplied** — when
null there is no comparison and the policy degrades silently to "EvalOS always wins", so make
null an explicit *conflict*, never "EvalOS is newer". And **row-level timestamps make every
concurrent edit a conflict** — per-field ownership resolves that too.

### 6.3 Four smaller amendments

- **`sync_drift` must be a table.** `00c` §4b and §4c both say "the drift report" as if it were
  a document. §4c's guarantee that *every divergence is detected* is only checkable if
  yesterday's divergences are still queryable.
- **The outbox dedupe key needs a *partial* unique index** — `where sent_at is null and dead_at
  is null`. A plain unique constraint is the obvious wrong reading, and it makes the second edit
  of the same opportunity an hour later collide with the first's sent row. Also: store
  `entity_id`, **not a payload snapshot**, and read the current row at send time — otherwise
  "collapse onto the pending one even if a field changed" silently sends the older values. And
  keep `intent` coarse (`UPSERT`/`CLOSE`/`DELETE`), or the collapse never happens.
- **The nightly audit must be a paged full-list diff, never a per-row `GET`.** The budget works:
  ~115 opportunity pages + ~115 contact pages at 110ms ≈ **30s at ~9 req/s**, an order of
  magnitude inside 100/10s. The per-row version — 11.4k requests ≈ 21 minutes of continuous
  budget, starving every desk — is the one somebody writes first because it is easier to reason
  about. Say so in the spec.
- **Classify errors at the door.** `GhlHttp` flattens status into a message string, so 4xx, 5xx
  and timeout are indistinguishable. Only 5xx/timeout/408/429 are retriable; `429` must pause
  the **whole** outbox (the budget is per location); `401`/`403` must **halt** it and alert,
  because retrying a scope failure ten times across every pending row spends the entire budget
  on a credential problem.

### 6.4 The webhook replay is not the delta sweep

`00c` §4a says the delta sweep "repairs dropped deliveries". **True for drift, false for
events.** A dropped `opportunity.won` is not drift — no case exists to diverge — and the delta
sweep on `opportunity` will never create one, because Handoff A lives in `CaseIntakeService`,
not the mirror. Keep §2.3's replay sweep separate and do not let Unit 45 absorb it.

### 6.5 `CachedOpportunity` is unsalvageable — for five reasons, not three

`00c` §7c gives three (GHL's id as PK, `ghl_contact_id` and `stage_id` `NOT NULL`). Two more,
and the fifth is the deepest:

4. **No `brand_id` at all**, deliberately (V40) — so the single-brand ceiling is load-bearing
   schema.
5. **Its only write path is delete-all-then-insert-all per pipeline.** Every row's identity is
   destroyed on every board refresh, so nothing can hold a foreign key into it and any row
   carrying local state — a `sync_state`, an outbox reference, a portal-born row with no
   `ghl_id` — dies. **The current write model is "truth lives elsewhere, replace everything";
   the mirror's is the exact opposite.**

And a live consequence nobody recorded: **`PipelineScope.requireMine()` authorises every Sales
and Marketing write against that droppable cache.** Access control has a TTL. A newly opened
lead is *immediately* unauthorised until the next refill; a deal moved to another rep's pipeline
in GHL stays authorised here until someone loads that board.

**Do not add a `GET /opportunities/{id}` fall-through — `39` §7 considered and rejected exactly
that remedy:** *"Asking GHL instead would be a second round trip on every write against a
100-per-10-seconds budget, to close a gap the board read has already closed for anything the
caller can actually see."* Choosing a visible false negative over an unverified authorisation is
the right direction and should not be reversed here. **§2.4's cache-on-write closes the live half
for free** — `openLead` and every desk write already receive GHL's authoritative row back, so
writing it into the cache authorises the just-opened lead immediately, at no extra request. The
other half — a deal moved to another rep's pipeline staying authorised until someone loads that
board — is a staleness bound, not an authorisation hole, and the mirror is its fix. **No refactor
of `PipelineScope` before Unit 44.**

### 6.6 Scope: tier 1 only, until a screen names tier 2

`00c` §2c commits to mirroring *"everything the API exposes"* — four tiers. `00c` §6 already
argues against itself: *"sync surface with no consumer is pure drift risk with no offsetting
benefit."* **Follow that.** Tier 1 has consumers today and earns its cost; the one tier-2 item
pulled forward is §6.1's correlation field. Rewrite Unit 47 as "mirror what 46 needs", not as a
completeness exercise.

### 6.7 Pipelines: nine, not three, and one nobody owns

The target set is Marketing BDE 1/2/3; Sales Evaluation & Translation, PERM + IBP + Prefill,
Corporate, Law Firm; plus **Case Delivery**. Config carries three name properties and
`application.yml` explicitly forbids a list (*"THREE NAMES, NOT A LIST"*) — right at three,
wrong at nine. Worse: **Case Delivery is a pipeline no single person owns**, which
`uq_team_member_pipeline` (one live owner) cannot express and `PipelineScope.mine()` (returns
exactly one id) cannot read.

**REDESIGN at Unit 44, decided before 44 starts because it changes 44's schema:** the mirrored
`pipeline` table *is* the list; a `purpose` enum (`MARKETING`/`SALES`/`DELIVERY`) replaces every
`*-pipeline-name` property; access becomes a join table `team_member_pipeline`. Many-to-many is
the real shape once Case Delivery exists, and pretending otherwise costs a second migration.

Also **mirror stages with a secondary natural key** `(pipeline_id, position, name)`: GHL stage
ids are not stable across a delete-and-recreate, and IE has now demonstrated it will recreate
things. Without it a recreated pipeline reads as every opportunity in it having drifted.

**Three things this must not quietly do.**

- **The three name properties are not among the nine pipelines.** `38` §5's deliberate reversal
  kept them as **analytics funnel** config for Units 24/26/27 — stage counts and value over a date
  window — a different object from an operational board. So replacing *"every `*-pipeline-name`
  property"* with `purpose` **rewrites three shipped GM screens' data source**, which R2's "keep
  and fix" implies and never says. Say it. (`purpose` also needs an `INTAKE` member: `43`'s
  `intake-pipeline-name` is on the retirement list too; `hot-stage-name` never shipped and was
  dropped on 2026-09-15 — EvalOS sends no stage, GHL's automation places the deal.)
- **Case Delivery must not become a second case lifecycle.** A GHL pipeline whose stages track
  delivery is structurally a second case board, and invariant 8 plus `43` §9 forbid exactly that
  shape: *"An application is not a case and must never grow a lifecycle that looks like one — that
  is how a second case state machine gets built by accident."* Mirror it for **reporting and
  hand-off visibility**; `evalos_case` stays the only lifecycle.
- **`team_member_pipeline` is not only a schema change.** It moves `PipelineScope.mine()` from
  "one id" to "a set" across every desk that shares it — an amendment to Units 39 and 40's
  authorisation model, not just Unit 44's. And `40` §7 asserts *"there is no route to move a deal
  between pipelines"* as a **design boundary** (*"promotion is GHL's workflow, and a second path
  here would race the automation the business owns"*); mirroring Case Delivery must not create one.

### 6.8 Endorsed without change

`00c` §2b — **GHL's stage id verbatim against a mirrored `pipeline_stage` row** — is correct.
**The stronger argument for it is already written, in `43` §6c**, and this audit initially
credited it to itself: *"**Same ids on both sides, which is the point.** There is no mapping
table… A stage comparison between the two systems is then **an equality check rather than a
translation**, which is what makes a mismatch detectable."* That is the reasoning `00c` §4c's
guarantee actually rests on — with a mapping table a nightly audit reports mapping bugs as data
drift; with verbatim ids it is `a == b`. Cite `43` §6c, which decided it on 2026-09-12 and
superseded its own earlier position to get there. The `(pipeline_id, position, name)` recreate key
is this audit's genuine addition.

---

## 7. Phase 5 — Expert Network Management becomes a function

**ENM is a roster and a dashboard, not a domain**, and this is the largest gap between the code
and the brief. Sales and Marketing got four units each; ENM got a table.

What exists is good and stays: ~50-column `Expert` including the full dossier, `ExpertCaseOffer`,
the payout ledger and weekly settlement, the four-factor match engine, the sheet importer, and
the strongest widget set in the app. What does not exist:

- **No candidate entity.** `Expert` rows are created directly or bulk-imported; there is no
  pre-expert state.
- **No stage model.** `Availability` is *capacity*, not lifecycle. `AgreementStatus` has three
  values and **no transition guard** — compare `CaseTransitions`, a 225-line state machine.
- **No assignee axis.** `Tier.SUPPLY` reads the whole brand with no notion of "my candidates".
- **`expert.notes` is a single overwritable text box** on the one entity where relationship
  history matters most, while case notes are trigger-protected.
- **The ENM is never told a case needs an expert.** `process-automation.md` lists the automation;
  there is no `NotificationType` and no route. The supply-side role learns about demand by not
  learning about it.
- **No queue behind the KPIs.** `Offer turnaround` and `Acceptance rate` are computed and no
  screen lists the open offers they describe — the ENM watches the rate degrade and cannot see
  which offers are rotting. They also have **no board at all**, so their `/cases/:id` grant is
  unreachable in practice.

**This must be specced before it is coded, because it reverses two written refusals.**
`22-role-operations-ui.md` slice 4 refused the recruitment pipeline; `17-dashboards.md` marks it
*GHL*. Both predate the pivot that put Sales and Marketing pipelines into EvalOS. Supply is this
business's binding constraint, and running it in a system where the ENM cannot see it is the
same mistake the Sales pivot just corrected. **Reverse both documents in writing first, as
Unit 50.**

**The pipeline — six stages, mirroring Unit 31's shape exactly: one stage, one owner, one
action, one next stage.**

| # | Stage | Action | → |
|---|---|---|---|
| 1 | Sourced | Reach out | 2 |
| 2 | Contacted | Record response / qualify | 3 or Rejected |
| 3 | Qualified | Send agreement | 4 |
| 4 | Agreement Sent | Record signature | 5 |
| 5 | Onboarding | Complete profile, fee, payment detail | 6 |
| 6 | **Active** | *(mints the `expert` row, stamps `date_onboarded`)* | — |
| X | Rejected / Lapsed | — | terminal |

**Stage 6 is the join**, and it explains an existing oddity: `expert.date_onboarded` has always
meant this and has never had a writer, and `AgreementStatus` is this pipeline surviving on the
wrong entity.

**Ships:** `expert_application` + stage history (reuse the generic `audit_event` with
`object_type='EXPERT_APPLICATION'`), the six-stage Kanban, an application detail screen, an
**open-offers queue**, a **workload tab** on the expert sheet (assembly only — `ExpertLoadService`,
`expert_case_offer` and `ExpertNetworkMetricsService` already hold every figure), append-only
expert notes, dated availability windows, a relationship owner, and a coverage-gap →
"recruit for this field" action.

**Do not build an outreach log.** Per-call, per-email logging is the classic supply-side feature
that is enthusiastically populated for six weeks and then abandoned, after which the stage is
right and the log is wrong. **The stage move *is* the outreach record.**

One report is missing and is the one an ENM is actually judged on: **cost per signed letter, by
expert and by field** — `payout_ledger.amount` ÷ delivered cases, arithmetic over existing rows.

---

## 8. Phase 6 — workspaces, widgets and the journeys

### 8.1 The pattern problem

`components/ui/card.tsx` already makes click-through structural (*"a card takes an optional
`to`"*), and **`PmDashboard` is the only dashboard that uses it** — three tiles into
`/inbox?view=…`, which `InboxPage` reads back. That loop genuinely works and is the model.
`CaseManagerDashboard` and `RevenueDashboard` have **zero** clickable tiles. "12 at risk" is a
number the user must then go and find.

Five patterns exist on exactly one screen each and should be system-wide: **KPI → filtered
queue**, **row-click opens a sheet** (a documented rule, applied once), **`?view=` presets**,
**skeleton loading** (the portal has `TableSkeleton`/`ListSkeleton`; the internal app
hand-rolls `animate-pulse` per screen), and **error + retry** (4 of 16 internal screens; every
portal page).

### 8.2 The widget gaps that matter

The full matrix — every role, every widget, purpose, click-through, status and priority — is in
`context/audit/2026-09-13/audit-ux.md`. The P0s:

| Role | Missing | Why it is P0 |
|---|---|---|
| **Sales** | Case status for won deals · today's & upcoming meetings · overdue follow-ups · untouched opportunities · unpaid invoices | The desk is **write-only**: Sales books meetings and sets follow-ups **into GHL and no screen in EvalOS reads either back** — defeating the stated purpose of Units 36–41. These are reads of GHL's own data, not an EvalOS reminder engine, so `40` §5 is not violated. |
| **Marketing** | Access to the marketing funnels · untouched new leads · lead volume by source | **The role is named after two screens it cannot open** (`roles: ['GM']`), and has no measure of its own output. The `sales-brand` exception already narrows invariant 1 for the opportunity board; extend it identically. This is a cheaper partial pre-emption of **Unit 25a** (`25`'s named follow-on: admitting more roles to the funnels and closing invariant 1's exception) — say so, and note it touches `navigation.test.ts`'s GM-only assertion and `architecture.md`'s invariant-1 exception. |
| **GM / BM** | At-risk · blocked/exception · bottleneck by stage · delivered this period | The two most senior roles land on a finance summary with **no clickable tile**, while every operational figure they need is already computed one directory over for the PM. |
| **ENM** | Open-offers queue | The queue the two headline KPIs describe. |
| **Client** | Case-scoped document checklist · a completion/delivery screen · the intake funnel | **The journey has no beginning and no end** — see §8.3. |
| **Expert** | Case list · payouts · openable evidence | §4. |

**Rejected, with reasons** (the full list is in the UX report): a Sales KPI scoreboard or
leaderboard — what Sales needs is *queues*, and a leaderboard is a management decision, not a UI
one; Sales/Marketing access to the production board or case detail — a narrow projection instead,
because widening a tier to answer a status question spends an invariant on a read; a "pay now"
button — a second payment surface is a reconciliation liability, though the screen should at
least *link* to GHL's; in-app draft diffing; a client-side stepper over twelve stages; a portal
notification bell; bulk assignment; and a client-accounts admin screen.

### 8.3 The client journey has no beginning and no end

- **Beginning:** `/welcome`'s primary call to action promises *"Start a new evaluation"* and
  lands on `/start`, which replies *"We can't take new evaluations through the portal just
  yet."* — a dead end on the highest-intent page in the product, also shown to anyone whose
  email was just refused at sign-in. **Fix the copy today** (it should carry a phone number and
  an email address); build Unit 43 in this phase.
- **End:** there is no delivery screen and no way to download the finished letter.
  `PortalCaseService` filters `SIGNED_LETTER` out of the client's documents deliberately,
  *"because delivery is a decision nobody has taken"*. **Take the decision.** A paying client
  cannot take delivery of the thing they bought.
- **Routing:** every case row links to `/draft/:caseId` **regardless of what the action is**, so
  a client told to upload a transcript lands on a page reading *"The draft is not ready to read
  yet."* The server already computes `actionRequired`; have it name the destination too.
- **Session:** the portal now has passwords and **no sign-out**, and the token lives in a module
  variable, so a refresh, a back-button or a reopened tab bounces the client to sign-in. Give a
  *password* sign-in a `sessionStorage` session and a sign-out; keep the fragment token
  memory-only. Different credential, different lifetime.

### 8.4 Questionnaires — a core capability with no backend at all

The brief names questionnaires five times, across four audiences: the client *completes* them,
Sales reviews the *submissions*, Production *reviews* them, and the expert *views documents and
questionnaires* for their assignment. **Verified state today: they do not exist anywhere in the
backend.** No entity, no table, no route, no service — `grep -ri questionnaire backend/src/main`
returns nothing. The conditional engine and its seven-screen funnel were **deleted** in `f9f1165`
and live only in git history and in Unit 43's spec, which restores them.

Three of the four audiences are already answered by `43`, and one is not:

| Audience | Status |
|---|---|
| **Client completes** | `43` §3 restores the conditional engine — `getVisibleGroups` / `getVisibleQuestions` / `isGroupComplete`, with `showIf` predicates over the answers so far, and `clientType` seeded into the answer pool from the account. Unbuilt, specced in full. |
| **Sales reviews** | `43` §6c — decided 2026-09-12, a staff-side read-only panel. **Does not wait on §3.2's timeline.** |
| **Production reviews** | `43` §6c, same panel — *"the same answers the case will be built from."* |
| **Expert views** | **Nothing, and no spec covers it.** `ExpertPortalService` gives the expert `evidence` — *document labels only*, and §4 already records that even those are unopenable. The brief's *"view relevant documents and questionnaires"* is an unaddressed requirement, not a gap in an existing design. |

**Decisions.** Build `43` as specced (the client, Sales and Production halves are decided and
costed). **For the expert half, take the decision rather than drift into it:** the recommendation
is that the expert sees a *curated subset* of the answers — the ones that bear on the opinion
(field, degree, institution, visa category, stated goal) — and never the commercial answers
(budget, timeline pressure, how they found us). That mirrors the whitelist principle
`PortalStageProjection` already applies to stages, and it keeps `34` D5's rule that the portal
holds no model of its own. It is a new decision and belongs in a spec before code.

**One thing not to do:** do not copy the answers onto the case. `43` §6c settles it — *"the client
is the only authority on what they answered"* — and §12(g) carries that forward.

### 8.5 Interaction patterns and widget coverage — the full checklist

The brief enumerated patterns and widgets explicitly; the complete audited matrix is in
`context/audit/2026-09-13/audit-ux.md` (`## Widget matrix`, `## Interaction patterns`,
`## State coverage gaps`). Carried here so the build list is complete rather than a pointer:

| Pattern | State | Decision |
|---|---|---|
| Global search | Disabled input in every header | **ADD** · P0 (§3.3) |
| Filtering | Good on `/board` and `/inbox`; absent on `/opportunities/board`, `/experts`, `/payouts` | **ADD** · P1 |
| Sorting | **Nowhere.** `ui-context.md` promises "dense rows, sortable"; no table implements it | **ADD** on the expert roster first · P2 |
| Pagination | Only `/experts/roster` and `/notifications`. `/cases`, `/payouts`, `/payments`, `/metrics/*` are unbounded; the payout ledger never stops growing | **ADD** to `/payouts` and `/cases` first · P2 |
| Saved views | None | **KEEP absent** — hardcoded `?view=` presets are right at this scale |
| Bulk actions | Specced in `22` slice 1, unbuilt; only the payout batch has multi-select | **KEEP absent** on the case inbox — assignment is a judgement per case, and bulk-assigning judgement is how a queue gets rubber-stamped |
| Quick actions | 21 actions, one table driving labels, fields and role gates | **KEEP** — the best pattern in the app |
| Drawers / side panels | `ui-context.md` states row-click-opens-a-sheet as a rule; applied on **one** screen | **MODIFY** — make it system-wide (cases, deals, payouts, experts) · P1 |
| Kanban / table | Both present and good | **KEEP** |
| **Calendar view** | **None** — Sales books meetings into GHL with no calendar to see them in | **ADD** with the meetings widget · P0 (§8.2) |
| **Document preview** | Presigned URL in a new tab, no preview pane | **KEEP** for staff; the expert is the one who needs it, and §4 fixes access first |
| **Version history** | Staff see `DraftHistory`; **the client and the expert see none** | **ADD** client-visible revision history (audit data that already exists) · P1 |
| Approval interfaces | Consistently good, consequence copy at the point of action | **KEEP** |
| Activity timelines | Two streams, no bridge | **ADD** the join · P0 (§3.2) |
| Status indicators | RAG tokens, two-clocks discipline properly enforced | **KEEP** |
| Notifications | Staff bell + nav badges; **client and expert get nothing** | gated on §12(c)'s channel decision |
| **"My Tasks" / "Action Required"** | Exists on **one** surface — the client portal's *Needs you* split, which is the target experience on the audience that needed it least | **ADD** to every staff dashboard · P1 |
| Responsive | Portals full mobile; internal app desktop-only by decision | **KEEP**, stated as a decision |
| Optimistic updates | None; every mutation refetches | **KEEP** for money and stage moves; notes may prepend |

**ENM widgets the brief named explicitly**, against the audit: *applications / onboarding* —
**MISSING** (needs §7's pipeline); *active experts, availability, coverage gaps, acceptance rate,
offer turnaround* — **EXIST** but only one of six tiles clicks through; *specialisation* — data
exists as `primary_fields[]`/`letter_types[]`, **no widget**; *workload* — one aggregate number, no
per-expert view; *assignments* — **MISSING**; *inactive experts* — `Availability.INACTIVE` exists,
**no widget**; *completed work* — **MISSING**; *pending payouts / paid payouts / payout totals* —
data exists in the ledger, and the ENM's own tile reads `unavailable, blockedBy: Unit 16` while
Unit 16 is built and `/payouts` sits in their nav.

### 8.6 Two administrative gaps

- **No user/team administration screen.** `TeamMemberController` exists; nothing calls
  `PUT /{id}/ghl-pipeline` from a UI, and no screen creates a user, sets a role or assigns a
  team. **Onboarding a new salesperson requires a database write** — a hard ceiling for a pivot
  whose whole point is six new Sales and Marketing seats.
- **`/brands` is a nav entry with no screen**, violating the rule stated one line below it in
  the same file (*"add the entry with the screen, never ahead of it"*). Remove the entry until
  the screen ships.

### 8.7 Frontend consistency

`strict` is set in **none** of the four tsconfigs, which makes the entire `string | null` DTO
vocabulary decorative and the careful null-handling convention rather than contract — the
highest-leverage config change in the repo. The internal app has **no server-state library**
(28 hand-rolled `useEffect` fetchers; `/cases/board` fetched independently by five screens)
while the portals run React Query with a 60s `staleTime`. The internal app has **no
`ErrorBoundary`** while both portals do. The newest and most strategic screens — Sales and
Marketing — **abandoned the design system**, using raw `slate-*`/`emerald-*` classes including
decorative RAG colour the house rules forbid.

`shared/` is genuinely shared between the two portals and has **not** drifted; the drift is dead
weight, not divergence. **Delete before sharing** — 20 files with zero importers, including a
`passwordRules` duplicate that contradicts the live one while the live file claims to be *"the
one place the strength rule is written"*. Then extract one `vocabulary.ts` (the server enums
both trees spell) and one money module. **Do not** move `shared/` into `frontend/` — Tailwind v4
+ bespoke tokens vs Tailwind v3 + shadcn HSL is a rewrite, not a move. Decide that question
deliberately or write down that they are two products with two looks; the current state reads as
an intention nobody is executing.

---

## 9. Gap analysis by module

> **Per-screen and per-API decisions live in
> `00d-appendix-screen-and-api-decisions.md`** — all 129 routes across 28 controllers and all 39
> routed screens across the three apps, each with what exists, what the target expects, the
> difference, the decision, dependencies, affected roles, affected systems and priority. The
> untouched majority is collapsed one line per controller; only the 23 routes (18%) and 14 screens
> (36%) carrying a real decision are given detail rows. **The asymmetry is itself the finding:**
> 82% of the API needs no change, and the largest category of remaining work is *screens over
> endpoints that already exist and are already tested* — the expert's case list and payouts, the
> Sales case-status consumer, team administration, and the sync-status panel.

Legend: **K**eep · **M**odify · **R**efactor · **RD**esign · **A**dd · **RM**ove.

| Module | Exists today | Target requires | Gap | Dec | Depends on | Roles | P |
|---|---|---|---|---|---|---|---|
| Case lifecycle (12 stages) | Full state machine, 8-column board, SLA, audit | unchanged | none | **K** | — | PM/PC/CM | — |
| Case ↔ commercial half | `ghl_opportunity_id` (no FK), `paid` bool, dead `invoice_ref` | Case bridges client→opp→payment→case | **no payment entity at all**; client→case is two string hops | **RD** | U44 | all | P0 |
| Notes / activity | 2 disjoint systems, 6 text boxes, no shared reader | one shared timeline, role-projected | no join, no overlapping reader role | **A** read model | S2 | all | P0 |
| Brand scoping | structural at write, convention at read, **absent across FKs** | enforced | no composite FK anywhere | **M** | — | all | P0 |
| GHL transport | one global `locationId`, one pacer | per-brand | schema + transport, not config | **RD** | — | all | P0 |
| Opportunity cache | delete-and-reinsert, no `brand_id`, GHL id as PK, **authorises writes** | id-faithful mirror | 5 blockers | **RM** | U44 | Sales/Mktg | P0 |
| Sync engine | none; no retry, no outbox, no replay, no status | 3 layers + outbox + drift | `00c` needs §6's amendments | **A** | 5.3, U44 | all | P1 |
| Inbound webhook | token-in-path, dedupe, archive, **no retry, no visibility** | replayable, visible | a dropped won = a lost paid case | **A** | — | system | P0 |
| Handoff A payload | contact + service + amount | + intake note | drops everything Sales knew | **M** | — | PM | P0 |
| Sales desk | board, CRUD, stage, notes, follow-ups, meetings | + reads back, + case status | **write-only desk**, no case visibility | **A** | S3 | SALES | P0 |
| Marketing desk | board, new lead, notes | + funnels, own output, untouched leads | cannot open its own funnels | **M** | — | MKTG | P1 |
| Client portal | sign-in, cases, documents, draft, invoices, meetings | + signup, delivery, case-scoped docs, sign-out | **upload broken; no start; no end** | **M**+**A** | U43 | Client | P0 |
| **Questionnaires** | **nothing in the backend** — no entity, table, route or service; engine deleted in `f9f1165` | client completes · Sales reviews · Production reviews · **expert views** | entire capability unbuilt; the expert half is in **no spec** | **A** | U43 | Client, SALES, PM/PC/CM, Expert | P1 |
| Scalability | ~50–100 cases/brand/month; 8 read models scan the whole case table, payouts unbounded and monotonic | headroom for the mirror's volume | fine for ~a year, then not; the mirror changes the arithmetic | **R** | 5.5 | all | P1 |
| Maintainability | clean layering; `CaseLifecycleService` is 1280 lines / 40 methods with six duplicated portal twins | room for Sales, Marketing and ENM to grow next door | one god service on the growth path | **R** | §12(k) | PM/PC/CM | P1 |
| Expert portal | one screen | account, case list, payouts, openable evidence | 4 reads, 1 screen | **A** | §4 | Expert | P1 |
| ENM | roster, availability, import, match, payouts, dashboard | + hiring pipeline, workload, offers queue, notes | **no candidate entity, no stages, no assignee axis** | **A** U50 | S2 | ENM | P1 |
| Dashboards | 5 role dashboards, good card contract | click-through everywhere, GM operational view | 1 of 5 uses `to`; 4 stale `unavailable` cards | **M** | — | all | P1 |
| Search | disabled input | brand-scoped entity search | nothing findable by name | **A** | — | all | P0 |
| Admin | jobs panel only | + users/teams, + sync status, + brands | onboarding a user = a DB write | **A** | — | GM/BM | P1 |
| Invoicing (U49) | GHL's | GHL's | — | **defer** | finance decision | — | — |

---

## 10. Rulings where the audits disagreed

Recorded because a consolidated direction is the point of a six-perspective audit.

**R1 — Notes: a read model now, a table later.** Architecture proposed one polymorphic `note`
table; Product argued explicitly *not* to build one. **Product wins for Phase 1**: the read
model needs no migration, keeps two correctly-scoped append-only stores, and is available this
week. Architecture's concern is real but different — conflating the compliance log with the
collaboration surface means one retention policy serves both. **They converge at Unit 50**,
where the ENM needs an append-only *expert* note stream that has no home in either store. Build
the read model now; add one `note` table when Unit 50 forces the question, and migrate case
notes onto it then.

**R2 — The three GM funnel screens: keep and fix, do not retire.** Product recommended removing
them (three GM-only reads over a location that was just replaced, matched **by name**).
Architecture recommended keeping them (cheap, correctly reasoned, one component). **Keep them** —
the defect is not the screens, it is the name-matching, which `00c` §6.7 already kills by making
the mirrored `pipeline` table the list. Retiring built, working, correctly-scoped screens to
avoid fixing a lookup is a bad trade. Revisit only if the GM says they are unused.

**R3 — Expert accounts are P1, not P0.** UX and Product both called it P0. It is a whole unit
(auth, mail, migration, two screens), not a fix, and it sits behind the §2 block. It is the
**first item of Phase 2** and should not slip further — an expert who loses the tab loses the
case while a 24-hour clock runs.

**R4 — Unit 49 (EvalOS invoicing): defer indefinitely, and say so.** It reverses invariant 2's
surviving half, re-argues invariant 8 ("won" stops being something only GHL can tell us), and
needs a finance decision engineering cannot take. Nothing in the brief requires it. It should
stop appearing as "the last unit" and start appearing as **a decision nobody has taken.**

**R5 — Unit 25 must be re-specced before it can be sequenced anywhere.** `00c` §5 claims the
mirror *"finishes what Unit 25 started"*; Unit 25 has not started, and **it cannot start as
written.** Three reasons, all from `25`'s own text:

1. **Its scope is one read grant** — *"Out of scope: Any scope beyond `opportunities.readonly`."*
   Units 39, 40 and 41 now need five more.
2. **It deletes the PIT outright, by design** — *"OAuth replaces the PIT outright; it does not sit
   beside it… This is free right now precisely because **Unit 24 has never run live**… Do this
   before the PIT is used in anger, or the cutover stops being free."* **That window has closed:**
   §2.1 C3 mints a PIT with six scopes for two live desks, the boards, and the portal's invoices
   and meetings.
3. **It waits on a hard external dependency nobody has started** — a GHL Marketplace app, a
   `client_id`/`client_secret`, and a single exactly-matched public HTTPS redirect URI, with
   `25` itself flagging the token lifetimes as unverified.

**Take §5.3's cheaper half first and re-spec the rest.** `brand.ghl_location_id` plus a per-brand
`GhlHttp` and per-location pacer is a migration and a constructor argument — it removes the
"`brand_id` column holding one value" objection to Unit 44 **without** an OAuth flow, because the
credential can stay a per-brand PIT column until the marketplace app exists. Unit 25 then becomes
a credential-lifecycle unit on its own external timeline, re-specced with all six scopes, and its
"no dual path" argument re-read — it was written for a world where nothing was live.

---

## 11. Roadmap

Each phase is shippable and leaves the system working. Production behaviour that must not
regress is listed in `context/audit/2026-09-13/audit-backend.md` → *Do-not-break list* (23
items, most bought with a bug) — **read it before touching the portal chain, the payout window,
or `PortalAccessService.retire`.**

```
PHASE 0 · Restore service                                        days · mostly operational
  C1 rebuild opportunity.won → C3 new PIT → C2 local config → C4 reassign pipelines → C6 truncate
  Fix PortalCaseService.upload (+ the test that would have caught it)
  Re-key client documents off ghl_contact_id
  Per-case document routes + the client's CasePicker
  WebhookReplaySweep + GET /api/admin/ghl/status + a red dot in the shell
  Board reload bug · cache-on-write · confirmations on the four irreversible actions
  Retire the four stale `unavailable` cards

PHASE 1 · Kill the status loop                                   the founding requirement
  S1 intake note through Handoff A          (~4 lines)
  S2 UnifiedTimelineService                 (1 service, 1 endpoint, no migration)
  S3 PortalStageProjection.forSales + the Sales case-status read
  S4 invite the seeded client accounts      (operational)
  GM reads the opportunity stream · global search · derived expected-completion date

PHASE 2 · The expert                                             closes the second loop
  Expert accounts (Unit 42 pattern) → case list + payouts (zero backend) → openable evidence
  Decide the expert's questionnaire subset (§12 g2) — a new decision, in no spec today
  The four-message outbound decision, in writing, as a unit

PHASE 3 · Foundations                                            before the mirror
  Composite brand FKs · @Version · brand.ghl_location_id + per-brand GhlHttp (Unit 25)
  contact.created/updated handlers · the five performance items · per-account throttle

PHASE 4 · The mirror, amended                                    Units 44-46, 48
  44: tier 1 + the correlation custom field + pipeline.purpose + team_member_pipeline
      + merge contact_snapshot and client_account in the same migration
      ✅ RESOLVED 2026-09-15: pipeline / pipeline_stage are UNIT 44's, full stop. The
        argument for giving them to 43 was that "move it to the hot stage" needs to know
        which stage is hot — and 43 shipped without moving anything to any stage, because
        the business put placement back where it belongs, with GHL's automation. 43
        resolves the pipeline ID live through GhlPipelineClient and persists nothing.
  45: outbox (partial unique, entity_id not payload) · per-field ownership · sync_drift table
      · paged diff audit · error classification · the sync-status surface
  46: the desks move onto the mirror     48: the switch, exercised in staging
  47: cut to what 46 reads

PHASE 5 · ENM as a function                                      Unit 50
  Spec first — it reverses two written refusals
  expert_application + 6 stages + Kanban + open-offers queue + workload tab
  Append-only expert notes · dated availability · cost-per-letter report

PHASE 6 · Workspaces                                             continuous
  ⚠ Unit 43 does NOT belong here. §9 marks the client portal P0 and names U43 as its
    dependency, and `43` §11 exists so "a visitor who abandons after About You still
    leaves a GHL contact and an opportunity at the hot stage" — i.e. so the business
    stops losing leads. Move Unit 43 to Phase 4 (with its pipeline tables) or earlier.
  The delivery/completion screen · action-aware routing
  Sales meetings + follow-ups read-back · Marketing funnel access
  GM/BM operational row · KPI drill-through everywhere · team administration
  strict: true · React Query in the internal app · ErrorBoundary · design-token repair
  The deletion list (20 frontend files, 4 dead expert columns, GhlHttp.delete)
```

**The critical path in one sentence:** rebuild the webhook, fix the upload, make failure
visible, carry the note, join two tables on a key they already share, and give Sales and the
expert a screen onto an answer the system already computes.

---

## 12. Open questions

Each carries a recommendation, per house rule. Resolved before the phase it gates.

| # | Question | Recommendation | Gates |
|---|---|---|---|
| a | Does SALES get case reads, and how much? | **Yes — exactly the client's projection**, scoped by opportunity-in-my-pipeline. Never the draft, notes, expert or checklist detail. | Phase 1 |
| b | Who owns reaching the client now Sales exists? | **Split at Handoff A.** Before the case, the client belongs to Sales; from creation, the Coordinator owns operational contact and Sales is copied. `DOC_CHASE_DUE` gains a second recipient only while the case is under 48h old — **and that is an amendment to Unit 10, not Unit 19.** `19` is explicit that it decides *when* a rule runs and never *what* it does: *"A threshold changing is a change to the owning unit's spec, not this one."* | Phase 1 |
| c | Does EvalOS send client mail? | **Yes, four messages only, as a unit with a written invariant amendment**: checklist+link, draft ready, expert signing link, delivered. **T6 alone justifies it** — an expert who never receives a link cannot sign while a 24h clock runs. Not the chases. | Phase 2 |
| d | Do experts get accounts? | **Yes, on the Unit 42 pattern.** Built, proven, cheap to repeat; closes G15 and unblocks everything in §4. | Phase 2 |
| e | Is the Expert Network pipeline EvalOS's or GHL's? | **EvalOS's, as Unit 50.** Both prior refusals predate the pivot that put Sales and Marketing pipelines here. | Phase 5 |
| f | What resolves identity for a portal signup? | **Already decided in `43` §6a/§1a — adopt it, do not re-decide it.** `upsertContact` (skipped, and `ghl_contact_id` reused, when non-null), then `upsertOpportunity` on the intake pipeline **at the hot stage**, both fired at *About You*, which `43` calls *"a hard floor, not a preference"*. A returning client is matched by Unit 42's `identify` and gets **a second opportunity on the same contact**, never a second contact. **This audit's one added clause — *"before any EvalOS row is written"* — is refused by `43` §7 and must not be carried:** *"EvalOS's own rows commit whether or not GHL answers… 'your submission was lost because a third party was down' is not a sentence this business can send."* EvalOS commits first; the GHL ids are filled by the same call or a staff retry. | U43 |
| i2 | Do GHL-born leads share Unit 43's application table? | **`43` §8 explicitly leaves this to Unit 44** and recommends against forcing them in: *"A GHL lead has no service and no answers."* §11 Phase 4's contact merge is the other half of the same question — decide both together. | U44 |
| i3 | Does the GM's board span every brand's pipelines or the selected brand's? | `38` §7's P1 recommended **the selected brand** and was to be decided in that unit's commit; nothing records whether it was. §3.3 now widens the GM to the note stream, which reopens it for `opportunity_note`. **Recommend: the selected brand**, consistently. | Phase 1 |
| g | Where do Unit 43's questionnaire answers live? | **Nowhere new.** 43 files them as an `OpportunityNote`; S2's timeline makes the case show them. Do not copy them onto the case — the client is the only authority on what they answered. | U43 |
| g2 | Does the **expert** see the client's questionnaire answers? | **Yes, a curated subset — and this is a new decision, in no spec.** The answers bearing on the opinion (field, degree, institution, visa category, stated goal); never the commercial ones (budget, timeline pressure, attribution). Mirrors the whitelist principle `PortalStageProjection` already applies, and keeps `34` D5's rule that the portal holds no model. Today the expert gets document *labels* and cannot open even those. | §8.4, U43 |
| h | Does the client see who is working on their case? | **A role and a date, not a name.** "With your Project Manager, expected back to you by Tuesday" answers the real question; a named individual creates a channel around the Coordinator and makes holiday cover client-visible. | Phase 1 |
| i | Does the mirror mean per-brand credentials first (Unit 25)? | **Yes to per-brand location and transport (§5.3); no to Unit 25 as specced — see R5.** Unit 25 grants one read scope, deletes the credential six live features depend on, and waits on a marketplace app nobody has created. | U44 |
| j | Should brand isolation move to Postgres RLS? | **No.** Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path. RLS needs a session variable threaded through Hikari and interacts badly with Flyway and the legitimate cross-brand sweeps. | Phase 3 |
| k | Do the `…FromPortal` method twins collapse? | **Yes** — authorisation belongs in the controller/filter and the domain method should take an already-authorised `Case`. Do it while splitting the 1280-line `CaseLifecycleService`, not before; it touches every portal test. | Phase 5 |

---

## 13. Documents this audit obliges us to amend

Per the house rule that a changed decision is an **edit**, never a note beside the old one:

| Document | Amendment |
|---|---|
| `CLAUDE.md` | Add the pointer to this file beside `00b` and `00c`. |
| `00c-ghl-independence-programme.md` | §2c scope (tier 1 until a screen names tier 2) · §4b per-field ownership · §4d partial unique index, `entity_id` not payload · §4a the replay sweep is not the delta sweep · §7h the correlation key · sequence Unit 25 before 44 · add `sync_drift` as a table. |
| `00-build-plan.md` | Add Phases 0–6 and Unit 50; mark Unit 49 deferred-by-decision, not scheduled. |
| **`37` §7, `39` §3a, `43` §7** | **"Writes do not retry" is refused in three separate specs, each with the same argument, and the outbox reverses all three.** By the house rule a changed decision is an edit to the document that states the old one — so the outbox is not a side effect of a sweep, it is an amendment to three units. |
| **`43` §10, `40` §4, `19`'s invariant line** | All three state "EvalOS sends no client mail". §12(c)'s four-message decision must edit them, not sit beside them. |
| **`05` §Gateway step 6, `05b` criterion 4** | Both rest on *"a retriable 5xx so GHL re-delivers"* — GHL's Custom Webhook action does not redeliver. Void until §2.3's replay sweep makes them true. |
| **`38`'s acceptance list** | Carries a **checked** criterion that the three `*-pipeline-name` properties are *"gone from all three `application*.yml`"* — contradicted by `38` §5 and §10 and disproved by `application.yml:191-213`. Its cache-on-write criterion is checked against a stub with no production caller. Un-check both so the spec stops reading as done. |
| **`25`** | Re-spec with all six scopes and the marketplace-app dependency named — see R5. `25` also defines a follow-on **Unit 25a** (admitting more roles to the funnels, closing invariant 1's exception) that this document's §8.2 partially pre-empts and never names. |
| `ui-context.md`, `17-dashboards.md`, `22-role-operations-ui.md` | All three still describe **Unit 31 as unbuilt**; the code ships `STAGE_COLUMNS`, `STAGE_OWNER` and `STAGE_NEXT_ACTION`. The design-system doc describing the previous board is how the next designer designs the wrong thing. |
| `22-role-operations-ui.md` §4, `17-dashboards.md` | Reverse the ENM recruitment-pipeline refusal **in writing** before Unit 50 is coded. |
| `process-automation.md` | Re-file G13 against the unified timeline; narrow G1 to "the client is told"; add G18–G30 from the product report. |
| `architecture.md` | Invariant 11 is **dead** (mechanism deleted, returns narrowed at 45); invariant 7 is **prose only** and must be rewritten whole at 44, not annotated a third time; record that invariants 2 and 8 — protecting the GHL boundary and the door into paid custody — are held by **structural source tests alone**. |
| Code hygiene | Five stale Dropbox Sign javadocs; `CaseController:786`'s Unit 18 citation; two `.env.example` files describing a mock layer that no longer exists; `frontend/package.json` named `"client"`. |

---

## 14. What this audit did not find

Recorded because a clean finding is evidence too, and because the brief anticipated several of
these.

- **No IDOR, in either portal chain.** Every portal path takes no id or matches it against the
  credential first.
- **No unscoped repository read that is a bug.** All 21 repositories checked; every exception is
  deliberate, javadoc'd, and usually scoped structurally by a required parameter.
- **No mock-backed routed screen** in any of the three apps. One orphan mock file survives with
  zero importers.
- **No residue from the removed units** — 18, 20, 21, 29 and the deleted client link system all
  left cleanly; migrations V1–V46 are contiguous with a test guarding the tree.
- **No secrets in any committed config**, and no token, password or secret in any log line —
  with one exception, recipient email addresses in mailer WARN/ERROR logs.
- **No `any`** across 190 frontend files.

The engineering quality here is high and several decisions in this codebase are better-argued
than the equivalents in most production systems — the `CardState` union, `navigation.ts` as one
table serving nav, router and role gate, the two-clocks discipline, `V45`'s reasoning about one
column with two jobs, and the refusal to `new Date()` GHL's unzoned timestamps. **The gaps in
this document are gaps of connection, not of craft.**
