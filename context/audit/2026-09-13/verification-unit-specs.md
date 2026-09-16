# Verification of `00d` against the ten unit specs the integration audit never read

> **Method.** The integration audit (`audit-integration.md`, 765 lines) was thorough on code and
> migrations, but its delegated spec-reading sub-task failed, so it reached its 15 findings and 9
> open questions **without reading the prose of Units 05, 05b, 19, 25, 37, 38, 39, 40, 41 and 43**.
> Those findings were consolidated into `00d-platform-audit-and-alignment.md`, which is now a live
> programme document. This file reads the ten specs and re-judges every finding, every open
> question, and everything `00d` inherited from them.
>
> **Headline: `00d` is mostly right, and four of its recommendations reverse decisions that were
> already argued and settled.** Two of those four would break shipped behaviour. One — §5.4 — is
> unbuildable as written: it fails the build on a structural test.

---

## Verdict table

### The integration report's fifteen findings

| Item | Source | Verdict | Spec evidence | What `00d` should say |
|---|---|---|---|---|
| **F1** — no inbound authenticity check; `signature_verified` lies | integration F1; `00d` §2.1 C12, §13 | **REFINED** | `05` banner: *"There is no inbound signature check… The endpoint token, resolved against an **active** brand, is the whole credential."* `05b` banner: *"GHL's Custom Webhook action **cannot compute an HMAC**… this closes **G17**."* | The token-in-path design **is** argued, and the dead columns **are** documented in both banners — so the documentation half is not a gap, only the code cleanup is. The compensating controls (rotation, per-token rate limit, `UNKNOWN_ENDPOINT` alerting) are genuinely absent and unargued: that half stands. Add: dropping `brand.ghl_webhook_secret` / `webhook_event.signature_verified` is a **new migration**, never an edit to `V11`/`V12` — `05` states the never-edit-an-applied-migration rule twice. |
| **F2** — a failed webhook is never retried and nothing sees it | integration F2; `00d` §2.3, §6.4 | **CONFIRMED** | `19` refuses only the *outbound* queue: *"**there is no queue** … Unit 18 was removed from scope on 2026-09-02, taking the table, the sender and the only cross-process work EvalOS had."* Inbound replay is not addressed anywhere in `19`. `05` §Gateway step 6 assumed *"a retriable 5xx **so GHL re-delivers**"* — a premise the audit has now falsified. | Keep `WebhookReplaySweep`. Add the three `19` constraints it must satisfy (see Correction 5) and record that `05` step 6 and `05b` criterion 4 rest on a redelivery that does not happen. |
| **F3** — deferred `contact.created`/`contact.updated` cost the portal its prospect base | integration F3/Q3; `00d` §5.4 | **REFINED — and the recommendation as written fails the build** | `05b`: *"`contact.created` moves into the existing `DEFERRED` no-op set… **it must not route to intake**"*, enforced by `DomainInvariantsTest`, which *"scans the classpath for who may inject `CaseIntakeService`. Change the **single allowed class** to `GhlOpportunityHandler`."* `43` criterion 10 confirms the same guard. | A new `GhlContactHandler` injecting `CaseIntakeService.syncContact` — which is exactly what §5.4 instructs — **fails `DomainInvariantsTest`**. Extract the resolver first. Also: the value claim is overstated — `43` resolves a returning client through GHL's own upsert, not through `contact_snapshot`, so §5.4 is **not** a prerequisite for portal signup. See Correction 2. |
| **F4** — `upsertOpportunity` sends no `id`, so a second deal overwrites the first | integration F4; `00d` §6.1 | **ALREADY ANSWERED (the defect) / CONTRADICTED (the fix)** | `39` §3a decided it in writing: *"**Opportunity upsert means one open opportunity per contact per pipeline.** That is correct for a *marketing* pipeline — a lead is a lead. It is **not** correct for a repeat client buying a second evaluation… **Unit 40 must not reuse this path for a genuine second deal**; the escape hatch is `POST /opportunities/`, and taking it **brings the duplicate risk back, knowingly**."* | This is not an undiscovered defect; it is a documented trade with the escape hatch already named and priced. `00d`'s *"**MODIFY now**: use `POST /opportunities/` for creates"* reverses `39` §3a and **breaks `43` §7**, which depends on both calls being upserts. See Correction 1. The forward half (correlation custom field at 44) stands. |
| **F5** — `PipelineScope` authorises writes against a droppable cache | integration F5; `00d` §6.5 | **CONTRADICTED (the fix) / CONFIRMED (the symptom)** | `39` §7 considered and rejected the exact remedy: *"the failure mode is **deliberate**… a refusal is visible and recoverable… **Asking GHL instead would be a second round trip on every write against a 100-per-10-seconds budget**, to close a gap the board read has already closed."* | Do not add a `GET /opportunities/{id}` fall-through. `00d`'s own §2.4 **cache-on-write** closes direction (a) — the newly-opened lead — for free, because `openLead` already receives GHL's authoritative row back. Direction (b) — a deal moved to another rep's pipeline staying authorised — is staleness the mirror fixes. See Correction 3. |
| **F6** — no write updates the cache, so the board snaps back | integration F6; `00d` §2.4 | **ALREADY ANSWERED (as a missed criterion)** | `38` §3 invalidation table: *"A write from EvalOS \| that pipeline is replaced with **GHL's response** \| Units 39/40"*. `38` criterion: *"[x] The cache row for an opportunity is replaced from **GHL's response**, never from a request body."* Marked done, against a stub, with no production caller. | Cite `38` §3. This is an **unbuilt acceptance criterion**, not a new design — which makes it cheaper to argue and harder to defer. One narrowing is needed: `38` §3 also says *"the pipeline is replaced wholesale, **never upserted row by row**"*, which was written about refills; writing one row from a write's own response is the intended exception and should be stated as such. |
| **F7** — outbound writes have no durability or retry | integration F7/Q1; `00d` §6.1, §11 Phase 4 | **CONFIRMED, but it reverses a refusal taken three times** | `37` §7: *"**No retry.** … a blind retry on a write with no idempotency key is how one opportunity becomes two."* `39` §3a: *"**Retries remain forbidden** (Unit 37 §7)… a retry after an ambiguous failure can still race a first attempt that succeeded."* `43` §7: *"no automatic retry loop… Retry is a **staff action** on the application row."* | The outbox is coherent **only after** the correlation key exists — which is exactly the precondition `37` §7 and `39` §3a name, and `00d`'s Phase 4 ordering (44 = key, 45 = outbox) is correct. But `00d` §13 must list Units **37, 39 and 43** as documents to amend when the outbox ships. A reversal of three written refusals is not a side effect of a sweep. |
| **F8** — audit-after-the-call, and derived audit keys | integration F8; not carried into `00d` | **ALREADY ANSWERED** | `37` §5 `ponytail:` comment: *"the call-graph check is a structural test… It catches the class that forgot; **it does not catch a class that audits the wrong thing.** The upgrade path if that matters is **an aspect on the three verbs**."* `39` §7 argues the derived key and `00d` §14 endorses it. | Dropping F8 from `00d` is fine. If it is ever taken, the cheap path is `37` §5's named aspect, which does not wait for the outbox — not `00d`/integration's intent-row-then-result-row, which does. |
| **F9** — pipelines by name (config) vs id (access), and the recreate direction | integration F9; `00d` §6.7 | **CONFIRMED — genuinely new** | `38` §5 considered only the rename direction: *"a rename in GHL makes the funnel screen answer 502 saying so… **it fails loudly**."* The delete-and-recreate direction — name survives, id changes, dashboards keep working while every access key points at nothing, silently — is nowhere in `38`. | Keep as written. Note that `38` §5's "it fails loudly" reassurance is now known to be true of only one of the two directions, and say so, because that sentence is what made the redundancy acceptable. |
| **F10** — nine pipelines, and a Case Delivery pipeline nobody owns | integration F10; `00d` §6.7 | **REFINED** | `38` §5's reversal keeps the three `*-pipeline-name` properties as **analytics funnel** config for Units 24/26/27 — *"an **analytics funnel** — stage counts, total value and a source breakdown over a **date window**"* — a different object from the nine operational pipelines, which are matched by `team_member.ghl_pipeline_id`. `39` §5 and `40` §7 forbid any EvalOS promotion path between pipelines. | Two things to add. (i) `purpose` replacing *"every `*-pipeline-name` property"* means **rewriting Units 24/26/27's data source**, which R2's "keep and fix" implies but never says. (ii) A mirrored **Case Delivery** pipeline is structurally a second case board; `43` §9 and invariant 8 forbid that, and `40` §7 asserts *there is no route to move a deal between pipelines* as a design boundary. Say both. See Correction 6. |
| **F11** — verbatim stage ids, plus recreate detection | integration F11; `00d` §6.8 | **ALREADY ANSWERED (the argument) / CONFIRMED (the addition)** | `43` §6c already makes the "stronger argument" `00d` says the spec lacks: *"**Same ids on both sides, which is the point.** There is no mapping table… A stage comparison between the two systems is then **an equality check rather than a translation**, which is what makes a mismatch detectable."* | `00d` §6.8 credits this reasoning to the audit and says *"the spec says…"*. That is true of `00c` and **false of `43` §6c**, which decided it on 2026-09-12 and superseded its own earlier position to get there. Cite `43` §6c. The `(pipeline_id, position, name)` recreate key is a genuine addition and stands. |
| **F12** — the 100/10s budget and the blocking pacer | integration F12/Q9; `00d` §5.5 | **CONFIRMED / partly ALREADY ANSWERED** | `38` §10: *"`draw` still resolves stage names through `GhlPipelineClient.pipelines()` on **every** board request, uncached… with a `ponytail:` comment naming the upgrade (cache the stage list, not the opportunities again)."* | "Cache `pipelines()`" is a **recorded ponytail debt item with the upgrade path already named** — cite it, because that turns it from a new finding into a scheduled one. The bounded pacer and the concurrency semaphore are new and stand. |
| **F13** — zero failure visibility | integration F13; `00d` §2.3 | **CONFIRMED** | `19` already built the shape: `GET /api/jobs/runs`, the run ledger, the stale-job warning, and *"**GM-only, gated at the route and in the service**… this is cross-brand infrastructure."* Screen is `features/jobs`, not `features/admin`. | Keep. Add that `GET /api/admin/ghl/status` inherits `19`'s **GM-only, gated twice** rule, and lands on `features/jobs`. |
| **F14** — `GhlHttp.delete` has no caller | integration F14; `00d` §11 Phase 6 | **CONFIRMED** | `37` §3 makes the audit's own argument against itself: *"There is no `patch`: GHL's API does not use it, and **adding a verb no endpoint takes recreates the 'present and unused' problem this unit is being careful about**."* | Keep the removal, and cite `37` §3 — the spec that added `delete` supplies the reason to remove it. `37` §5's closed-verb-list test narrows from four to three; that is a narrowing, not a weakening. |
| **F15** — one GHL location, hardcoded, in a multi-brand product | integration F15/Q6; `00d` §5.3, R5, §12(i) | **CONTRADICTED as sequencing advice** | `25` is specced but predates Units 36–41. Its scope: *"**Out of scope:** Any scope beyond `opportunities.readonly`. Least privilege."* Its cutover rule: *"**OAuth replaces the PIT outright; it does not sit beside it.** … This is free right now precisely because **Unit 24 has never run live**… **Do this before the PIT is used in anger, or the cutover stops being free.**"* | The PIT **is** in anger — `00d` §2.1 C3 mints a new one with six scopes for the live desks, boards, invoices and calendars. Unit 25 as specced would delete `GHL_API_TOKEN`/`GHL_LOCATION_ID` and grant one read scope, breaking Units 39, 40 and 41 the day it lands. It also has a **hard external dependency**: a GHL Marketplace app (private), a `client_id`/`client_secret`, an exactly-matched public HTTPS redirect URI, and two lifetimes its own spec calls unverified. "Sequence it first" is not buildable as written. See Correction 4. |

### The nine open questions

| Item | Source | Verdict | Spec evidence | What `00d` should say |
|---|---|---|---|---|
| **Q1** — outbox replaces synchronous desk writes | integration Q1; `00d` §11 Phase 4 | **CONFIRMED, with the reversal recorded** | As F7. | Same as F7: correct, and it must be written as an amendment to `37` §7, `39` §3a and `43` §7, not alongside them. |
| **Q2** — what resolves identity for a portal signup | integration Q2; **`00d` §12(f)** | **ALREADY ANSWERED on substance / CONTRADICTED on ordering** | `43` §6a already specifies it: *"**`GhlWriteClient.upsertContact`** — or skip it and reuse `ghl_contact_id` when it is non-null"*, then *"**`GhlWriteClient.upsertOpportunity`** on the intake pipeline, **at the hot stage**"*. `43` §1a decides returning-client matching: *"**`identify` is the safety net**… a returning client who clicks *Get started* out of habit is recognised from their email and routed to the existing path instead of being **duplicated as a second contact**."* `43` §7 decides the ordering the opposite way: *"**EvalOS's own rows commit whether or not GHL answers.** … **'your submission was lost because a third party was down' is not a sentence this business can send.**"* | Unit 43 answers this question in full — identity resolution, the intake pipeline (`evalos.ghl.intake-pipeline-name`), the hot stage (`evalos.ghl.hot-stage-name`), that an application **is** a GHL opportunity, and how a returning client is matched. The only novel clause in `00d` §12(f) — *"before any EvalOS row is written"* — is the one `43` §7 explicitly refuses. See Correction 7. |
| **Q3** — implement `contact.created`/`.updated` now | integration Q3; `00d` §5.4 | **REFINED** | As F3. | Do it, but not through `CaseIntakeService`, and not as a prerequisite for Unit 43. Also: the GHL **workflow** that fires these events does not exist in `WY6bW2xUCI8Tz8gw7aLJ` either, so this is a C1-family operational item as well as a code one — `00d` calls the handler *"the only missing piece"*, which is not true. |
| **Q4** — per-field ownership vs blanket EvalOS-wins | integration Q4; `00d` §6.2 | **CONFIRMED** | `39` §4: *"It is GHL's `monetaryValue` on the opportunity, **not an EvalOS column**"*; `38` §3: *"**Every column is a field GHL owns.**"*; `39` §3/`40` §3: notes are EvalOS's sole property. The table is consistent with all three. | Keep. One consequence to name: `contact.email/.phone/.name` as "shared, EvalOS wins" means EvalOS holds an authoritative contact field, which `39` §2's amendment of invariant 7 refused (*"It still holds no authoritative contact field of its own"*). `00d` §13 already schedules invariant 7 to be *"rewritten whole at 44"* — good; just note that §6.2 is the reason. |
| **Q5** — correlation custom field at 44 not 47 | integration Q5; `00d` §6.1 | **CONFIRMED** | `37` §4: *"the right key depends on what is being written, and inventing a scheme with no caller produces a scheme the caller then works around."* `39` §3a: *"the request schemas carry **no `Idempotency-Key` header and no client-token field** — verified against the live API surface 2026-09-10."* | Keep. `39` §3a's verification is the evidence base for "GHL offers no key of its own", which is what makes a custom field the only option — cite it. |
| **Q6** — Unit 25 before Unit 44 | integration Q6; `00d` R5, §12(i) | **CONTRADICTED as written** | As F15. | See Correction 4. |
| **Q7** — keep webhook replay separate from mirror sync | integration Q7; `00d` §6.4 | **CONFIRMED** | `05b`: Handoff A creates a case, a checklist, a snapshot and a notification through `CaseIntakeService` — none of which a state comparison can reconstruct. `19`: *"one place that answers 'what does EvalOS do on its own, and when'."* | Keep, unchanged. |
| **Q8** — what the portal shows when GHL is unreachable | integration Q8; `00d` §2.1 C7/C8 | **CONFIRMED, with a precedent to reuse** | `41` §9: the 403 is already explained in the client's own terms — *"This link opens one case rather than your account."* — and `missingScopeHint` decorates **only** 401/403, pinned by `anUpstreamFaultIsNotBlamedOnTheScope`, *"a guard that fires on 404s and timeouts too would teach the reader to ignore it"*. | Keep. Add: the new "billing is temporarily unavailable" state must be a **third** message, not a reuse of the missing-scope hint — `41` §9 has a test that forbids blaming an upstream fault on the scope. |
| **Q9** — bound the blocking pacer | integration Q9; `00d` §5.5 | **CONFIRMED** | `37` §3: *"**The same shared pacer.** … GHL's 100-requests-per-10-seconds is per *location*, not per verb — a write path with its own limiter is the exact bug `GhlHttp` was extracted to prevent."* | Keep. Note that a bound must be applied to the **shared** pacer, not by giving sweeps their own — that is the bug `37` §3 names. |

### What `00d` inherited and restated

| Item | Source | Verdict | Spec evidence | What `00d` should say |
|---|---|---|---|---|
| **Re-key client documents** off `ghl_contact_id` | `00d` §2.2, §11 Phase 0 | **ALREADY ANSWERED** | `43` §5: *"**Keyed on the EvalOS account id, not on `ghl_contact_id`**… At this point in the funnel there is no GHL contact yet… keying it on the account is also what keeps the documents readable if the contact is later deleted or the CRM replaced. `00c` inherits this as the pattern."* | `43` §5 already chose `client_account.id` and argued it. `00d`'s *"`case_id` **or** `client_account.id`"* leaves open an option the funnel cannot use — documents are uploaded a step before any case exists. Adopt `{brandId}/client/{clientAccountId}/{documentId}` verbatim. |
| **`00d` §12(g)** — where Unit 43's answers live | `00d` §12(g) | **ALREADY ANSWERED** | `43` §6b files them as an `OpportunityNote`; `43` §6c: *"**No editing.** A staff member correcting a client's answer creates a version of the truth the client never gave, and **the client is the only authority on what they answered**."* | Correct, and drawn straight from `43` — say so. One omission: `43` §6c already ships a **staff-side read-only panel** for Sales *and* Production, so the answers do not wait on S2's timeline. `00d` §8.2 lists the client intake funnel and not that panel. |
| **`00d` §12(b)** — `DOC_CHASE_DUE` gains a second recipient under 48h | `00d` §12(b) | **REFINED** | `19` Out of scope: *"**Any new business rule.** Every rule here already exists in the unit that owns it; this unit decides *when* it runs, never *what* it does. **A threshold changing is a change to the owning unit's spec, not this one.**"* `19` build note: `DOC_CHASE_DUE` *"deliberately carries **no** `alreadyRaised` guard."* | An age-conditional recipient is a rule, so it is an amendment to **Unit 10**, not to Unit 19's sweep. Write it there or `19`'s own boundary is breached by the first item that touches it. |
| **`00d` §12(c)** — EvalOS sends four client messages | `00d` §12(c) | **CONFIRMED, with two more documents to amend** | `43` §10: *"**No status email.** … Unit 42's mail channel is for authentication only."* `40` §4: *"EvalOS composing and sending a message itself is still refused."* `19` invariants: *"no email — every message is a domain event for GHL, or a portal link an expert opens (14)."* | The reversal is correctly flagged as needing a written invariant amendment. Add `43` §10, `40` §4 and `19`'s invariant line to `00d` §13's amendment table — all three state the old rule. |
| **R2** — keep the three GM funnel screens | `00d` §10 R2 | **CONFIRMED** | `38` §5: *"**⚠ … NOT removed, reversing what this spec and Unit 36 §8 both said. This is a deliberate reversal, recorded rather than quietly skipped, and it is the user's call to overturn.**"* | Keep. Note that `38`'s own acceptance list still carries a **checked** criterion saying those three properties are *"gone from all three `application*.yml`"*, which §5 and §10 contradict and which the audit's own evidence (`application.yml:191-213`) disproves. That criterion should be un-checked. |
| **§11 Phase 4 — `pipeline`/`pipeline_stage` land at Unit 44** | `00d` §11, §6.7 | **CONTRADICTED** | `43` §6c: *"this unit carries the pipeline half of `00c`'s tier-1 mirror, two small tables… **They are here rather than in Unit 44 because this unit cannot work without them** — 'move it to the hot stage' requires knowing which stage is hot."* | Two units now create the same two tables, and `00d` schedules Unit 43 in **Phase 6**, *after* Unit 44. Decide the owner explicitly. See Correction 8. |
| **§11 Phase 6 — Unit 43** vs §9's P0 client-portal row | `00d` §9, §11 | **REFINED** | `43` §11 criterion 1b: the whole 2026-09-12 reordering exists so *"a visitor who abandons immediately after About You still leaves a GHL contact and an opportunity at the hot stage"* — i.e. so the business stops losing leads. | §9 marks the client portal **P0** with *"no start; no end"* and `Depends on: U43`, while §11 puts Unit 43 in the last, "continuous" phase. Either the dependency is not P0 or the phase is wrong. Say which. |

---

## Corrections required in `00d`

### Correction 1 — §6.1, second paragraph: do not reverse Unit 39's upsert decision

**Replace** the paragraph beginning *"The same defect is live today in a different form…"* with:

> The same mechanism is live today in a different form, and **Unit 39 §3a already decided it,
> knowingly**: `upsertOpportunity` sends no `id`, so opportunity upsert means *one open
> opportunity per contact per pipeline*. `39` §3a calls that **correct for a marketing pipeline**
> — *"a lead is a lead"* — and names the escape hatch in the same breath: *"Unit 40 must not
> reuse this path for a genuine second deal; the escape hatch is `POST /opportunities/`, and
> taking it **brings the duplicate risk back, knowingly**."* Unit 40 complied — Sales never
> creates an opportunity, every route is a `PUT` or a sub-resource — so the failure mode requires
> Marketing to re-open a lead for a returning client **on the marketing pipeline**, which is the
> case `39` classed as correct behaviour. The opportunity id, and therefore the whole
> `opportunity_note` stream, survives; what changes is the lead's name and value.
>
> **So: do not switch the create path to `POST /opportunities/` now.** Doing so reverses `39` §3a
> against its own reasoning and **breaks `43` §7**, which depends on both calls being upserts:
> *"Both GHL calls are upserts, so a retry matches the existing contact rather than duplicating
> it… Retry is a staff action on the application row."* `43` criterion 7 pins it — *"Submitting
> twice does not create a second contact or a second opportunity."*
>
> What is genuinely open is narrower and belongs at Unit 44 with the correlation key: a **create**
> that has to distinguish "this contact's second deal in this pipeline" from "the same deal
> again" needs a key GHL does not offer. Until that key exists, surface `isNew=false` as a
> confirmation step in the new-lead form (*"this contact already has a deal in this pipeline —
> open it, or create a second?"*) rather than changing the verb. That is the option `39` §7 left
> room for, since the flag is already bound and already returned to the UI.

### Correction 2 — §5.4: the recommendation as written fails the build

**Replace** the Why/Decision cell of row 5.4 with:

> Highest value-per-line change available today: it closes the prospect gap for **sign-in
> recognition** and it is half of Unit 45's inbound layer.
>
> **It must not be written through `CaseIntakeService`.** `05b` sets the rule — *"`contact.created`
> … **must not route to intake**"* — and enforces it structurally: `DomainInvariantsTest` *"scans
> the classpath for who may inject `CaseIntakeService`"* and permits **exactly one** class,
> `GhlOpportunityHandler`. A `GhlContactHandler` injecting `syncContact` fails the build, which is
> invariant 8 working correctly. **Extract `syncContact` into a `ContactSnapshotService` first**,
> have both handlers use it, and leave `CaseIntakeService` with its single injector. That still
> gives one identity function, which is the point of the recommendation.
>
> **Two corrections to the value claim.** (i) It is **not** a prerequisite for resolving portal
> signups: `43` §1a/§6a resolve a returning client through `identify` plus GHL's own
> `POST /contacts/upsert`, which dedupes on email then phone in GHL, not in `contact_snapshot`.
> (ii) The handler is **not** the only missing piece — the GHL workflow that fires these two
> events does not exist in `WY6bW2xUCI8Tz8gw7aLJ` either, so this carries a C1-family operational
> item with it.
>
> **ADD / P1.**

### Correction 3 — §6.5, final paragraph: cache-on-write, not a GHL fall-through

**Replace** the sentence *"**REFACTOR: on a cache miss, fall through to `GET /opportunities/{id}` rather than 403**, until the mirror makes it moot."* with:

> **`39` §7 considered and rejected exactly that remedy**, and the rejection is on the record:
> *"Asking GHL instead would be a second round trip on every write against a
> 100-per-10-seconds budget, to close a gap the board read has already closed for anything the
> caller can actually see."* Its choice of a visible false negative over an unverified
> authorisation is the right direction and should not be reversed here.
>
> **§2.4's cache-on-write closes the live half for free.** `openLead` and every desk write already
> receive GHL's authoritative row back, so writing it into the cache makes the just-opened lead
> authorised immediately — which is the (a) symptom in full, at no extra request. The (b) symptom —
> a deal moved to another rep's pipeline in GHL staying authorised until someone loads that
> board — is a staleness bound, not an authorisation hole, and the mirror is its fix. **No
> refactor of `PipelineScope` before Unit 44.**

### Correction 4 — R5 / §12(i) / §5.3: Unit 25 as specced is not buildable first

**Replace** R5 in §10 with:

> **R5 — Unit 25 must be re-specced before it can be sequenced.** `00c` §5 claims the mirror
> *"finishes what Unit 25 started"*; Unit 25 has not started, and **it cannot start as written**.
> Three reasons, all from `25`'s own text:
>
> 1. **Its scope is one read grant.** *"Out of scope: Any scope beyond `opportunities.readonly`."*
>    Units 39, 40 and 41 now need `contacts.write`, `opportunities.write`, `invoices.readonly`,
>    `calendars.readonly` and `calendars/events.write` — the six scopes §2.1 C3 mints a PIT for.
> 2. **It deletes the PIT outright**, by design: *"OAuth replaces the PIT outright; it does not sit
>    beside it… This is free right now precisely because **Unit 24 has never run live**… Do this
>    before the PIT is used in anger, or the cutover stops being free."* That window closed. The
>    cutover now moves every live GHL surface — two desks, the boards, the client portal's
>    invoices and meetings — in one unit.
> 3. **It has a hard external dependency nobody has started.** A GHL Marketplace app (private
>    distribution), a `client_id` and `client_secret`, and a **single exactly-matched** public
>    HTTPS redirect URI. `25` also flags `user_type`, `expires_in` and the refresh-token lifetime
>    as *"unverified… the MCP operation registry does not expose the auth endpoints."*
>
> **Recommendation: take §5.3's cheaper half first and re-spec the rest.** `brand.ghl_location_id`
> plus a per-brand `GhlHttp` and a per-location pacer is a migration and a constructor argument;
> it removes the "`brand_id` column holding one value" objection to Unit 44 **without** an OAuth
> flow, because the credential can stay a per-brand PIT column until the marketplace app exists.
> Unit 25 then becomes a credential-lifecycle unit that can be scheduled on its own external
> timeline, re-specced with all six scopes, and `25`'s "no dual path" argument re-read — it was
> written for a world where nothing was live.
>
> If the business will not fund even that, `44`'s tables carry `ghl_location_id` and derive the
> brand — **but that must be decided, not drifted into.**

Amend §12(i)'s recommendation to: *"**Yes to per-brand location and transport (§5.3); no to Unit 25 as specced — see R5.** Unit 25 needs a re-spec with six scopes and an external marketplace app before it can be sequenced anywhere."*

### Correction 5 — §2.3: the three constraints `WebhookReplaySweep` must satisfy

**Append** to §2.3's `WebhookReplaySweep` bullet:

> Three constraints from `19`, because a sweep that misses them ships dead or double-fires:
>
> - **`SweepRegistrationTest` is load-bearing.** A fifth sweep needs `implements Sweep`, a
>   `@Scheduled` tick, **and** a matching `evalos.jobs.intervals` key **keyed by `JOB_TYPE`** —
>   `19` records that without all three *"a fifth sweep whose interval nobody added would pass the
>   entire build and fail in production"*, because the one full-context test sets
>   `evalos.jobs.enabled=false`.
> - **It takes `JobLock`, session-scoped.** `19`: `pg_try_advisory_xact_lock` *"releases on commit,
>   and these sweeps run one transaction per item… so an xact lock would be dropped after the
>   first item."* The **Run now** button must go through the same lock, not around it.
> - **`19`'s boundary holds: the sweep decides *when*, never *what*.** Re-routing through the
>   existing `WebhookRouter` satisfies that; a replay path that re-implements any part of
>   `GhlOpportunityHandler` does not.
>
> The `attempts`/backoff column belongs on `webhook_event`, not on a job row — `19`'s own rule
> (*"idempotency already exists in the data"*) names the outbox's *"delivery row's own `status` and
> `attempts`"* as the correct shape. Note also that `19`'s DB-gated cross-brand isolation test for
> brand-wide sweep finders was **never written**; this sweep inherits that gap.

### Correction 6 — §6.7: two additions

**Append** to §6.7:

> **The three name properties are not one of the nine pipelines.** `38` §5's deliberate reversal
> kept them as **analytics funnel** config for Units 24/26/27 — *"stage counts, total value and a
> source breakdown over a date window"* — a different object from an operational board. Replacing
> *"every `*-pipeline-name` property"* with `pipeline.purpose` therefore **rewrites three shipped
> GM screens' data source**, which R2's "keep and fix" implies and does not say. Say it.
>
> **Case Delivery must not become a second case lifecycle.** A GHL pipeline whose stages track
> delivery is structurally a second case board, and invariant 8 plus `43` §9 forbid exactly that
> shape: *"An application is not a case and must never grow a lifecycle that looks like one — that
> is how a second case state machine gets built by accident."* It is mirrored for **reporting and
> hand-off visibility**; `evalos_case` remains the only lifecycle. Related: `40` §7 asserts
> `thereIsNoRouteToMoveADealBetweenPipelines` as a **design boundary** — *"promotion is GHL's
> workflow, and a second path here would race the automation the business owns"* — and `39` §5
> forbids a "move to sales" button. A `team_member_pipeline` many-to-many changes
> `PipelineScope.mine()` from "one id" to "a set" across all three desks that share it; that is a
> change to Units 39 and 40's authorisation model, not only to 44's schema.

### Correction 7 — §12(f): Unit 43 already answers this, and the one novel clause is refused

**Replace** the §12(f) recommendation cell with:

> **Already decided in `43` §6a/§1a — adopt it rather than re-deciding it.** Unit 43 specifies:
> `GhlWriteClient.upsertContact` (skipped, and `ghl_contact_id` reused, when it is non-null);
> then `upsertOpportunity` on the intake pipeline **at the hot stage**, both fired at *About You*,
> which `43` §6a calls *"a hard floor, not a preference"* because GHL's upsert matches on email
> then phone and the funnel holds no way to identify a human before that step. A returning client
> is matched by Unit 42's `identify` — *"the safety net… a returning client who clicks Get started
> out of habit is recognised from their email and routed to the existing path instead of being
> duplicated as a second contact"* — and gets **a second opportunity on the same contact**, never
> a second contact.
>
> **The one clause this audit added — *"before any EvalOS row is written"* — is refused by `43`
> §7 and must not be carried:** *"EvalOS's own rows commit whether or not GHL answers… A lead with
> null GHL ids is not lost, it is un-pushed… 'your submission was lost because a third party was
> down' is not a sentence this business can send."* The EvalOS rows commit **first**; the GHL ids
> are filled in by the same call or by a staff retry.

### Correction 8 — §11 Phase 4 and §6.7: name the owner of `pipeline` / `pipeline_stage`

**Append** to §11's Phase 4 block:

> **`pipeline` and `pipeline_stage` are Unit 43's, not Unit 44's** — `43` §6c ships both *"because
> this unit cannot work without them — 'move it to the hot stage' requires knowing which stage is
> hot"* — and this roadmap schedules Unit 43 **after** Unit 44. Pick one: either move Unit 43's
> intake funnel into Phase 4 beside the mirror, or have Unit 44 create the two tables **with
> §6.7's shape** (`purpose`, `deleted_in_ghl_at`, the `(pipeline_id, position)` natural key) and
> have Unit 43 consume them. The second is cheaper and avoids a second migration over a table that
> is one sprint old. Either way `43`'s two settings — `evalos.ghl.intake-pipeline-name` and
> `evalos.ghl.hot-stage-name` — are among the name properties §6.7 retires, so §6.7's `purpose`
> enum needs an `INTAKE` member.

---

## What the audit missed

Decided constraints, refusals and open items in the ten specs that `00d`'s roadmap would walk into.

1. **`DomainInvariantsTest` permits exactly one injector of `CaseIntakeService`.** `05b` names
   `GhlOpportunityHandler` as *"the single allowed class"*, and `43` criterion 10 re-asserts it for
   `ClientApplicationService`. §5.4 as written fails the build. (Correction 2.)

2. **"Writes do not retry" is refused in three separate specs** — `37` §7, `39` §3a, `43` §7 — each
   with the same argument, and the outbox reverses all three. `00d` §13's amendment table lists
   `00c` and `architecture.md` and none of the units. By the house rule a changed decision is an
   edit to the document that states the old one.

3. **`37` §5's structural test: every class calling `post`/`put`/`delete` must reach
   `AuditService`, or the build fails.** An `OutboxSweep` in `job/` is such a class. `19` also
   scopes `job/` to *"never what it does"*, so conflict resolution and per-field ownership cannot
   live in the sweep.

4. **`43` §5 already chose the document key** — `{brandId}/client/{clientAccountId}/{documentId}` —
   and argued it against both `ghl_contact_id` and `case_id`, because documents are uploaded a step
   before either exists. §2.2's *"`case_id` or `client_account.id`"* reopens a settled question.

5. **`43` already ships `pipeline`/`pipeline_stage`** and is scheduled after the unit `00d` assigns
   them to. (Correction 8.)

6. **`43` §8 leaves an open decision for Unit 44 that `00d` §12 does not carry:** *"Unit 44 decides
   whether GHL-born leads share this table or get their own, once the inbound `contact.created`
   payload has been read. A GHL lead has no service and no answers, so forcing it in here is the
   likely wrong answer."* §11 Phase 4's *"merge `contact_snapshot` and `client_account`"* is the
   contact half of that question and is silent on the lead half. Add it to §12.

7. **`43` §9 explicitly refuses a second case-like lifecycle**, which is what a mirrored Case
   Delivery pipeline is one design meeting away from becoming. (Correction 6.)

8. **`39` §3a's second limit is a provisioning obligation, not a code one:** *"Contact dedupe
   depends on a GHL location setting EvalOS does not control… EvalOS cannot detect or prevent that,
   and **must not pretend to**."* The audit's *"nobody has checked"* is right — but the fix is a
   line on the §2.1 cutover checklist confirming **Allow Duplicate Contact is off** in
   `WY6bW2xUCI8Tz8gw7aLJ`, not a code change. It is currently in neither.

9. **`25`'s follow-on, Unit 25a, exists and `00d` never names it.** `25` §What this unlocks:
   admitting more roles to the marketing funnels and removing invariant 1's stated exception is
   *"Unit 25a, because it is a different boundary (a screen's role list) from this one (a
   credential's lifecycle)"*. §8.2's Marketing item — *"extend [the `sales-brand` exception]
   identically"* — is a cheaper partial pre-emption of 25a and should say so, with `navigation.test.ts`'s
   GM-only assertion and `architecture.md`'s invariant-1 exception named as what it touches.

10. **`05` §Gateway step 6 is factually void:** *"Any processing failure returns a retriable 5xx **so
    GHL re-delivers**."* It does not. `05b` criterion 4 — *"a redelivery after a handler failure
    retries and succeeds"* — is testable only with a synthetic redelivery. Both belong in §13's
    amendment table; the replay sweep is what makes the sentence true again.

11. **`38`'s acceptance list contradicts `38` §5 and §10.** It carries a **checked** criterion that
    the three `*-pipeline-name` properties *"are gone from all three `application*.yml`"*, while §5
    records the deliberate reversal that kept them and the audit found them live. `38` §3's
    cache-on-write criterion is checked against a stub with no production caller (F6). Both should
    be un-checked so the specs stop reading as done.

12. **`38` §7's open question P1 — the GM's union across brands — was to be *"decided in this
    unit's commit"*** with the recommendation *"the selected brand"*. Nothing in `00d` records
    whether it was, and §3.3 now widens the GM's reach to the note stream, which reopens the same
    question for `opportunity_note`. Worth one line in §12.

13. **`19`'s own unmet criteria are inherited by everything `00d` adds to `job/`:** the holiday
    chase→chase→escalate ordering is pinned as thresholds rather than as a calendar walk, and the
    **DB-gated cross-brand isolation test for the brand-wide sweep finders was never written**. A
    replay sweep and an outbox sweep are two more brand-wide finders with no such proof.

14. **`41` §9's `anUpstreamFaultIsNotBlamedOnTheScope`** forbids reusing the missing-scope message
    for a generic outage — which is exactly the state C7/C8 and Q8 need a message for. It needs a
    third string, not a widened guard.

---

## Net assessment

**`00d` is safe to build from for Phases 0, 1 and 2, and is not safe to build from as written for
Phases 3 and 4.** Its P0 diagnosis is sound and in several places better-evidenced than the specs
it did not read: F1's compensating controls, F2's replay, F9's delete-and-recreate direction, F13's
invisibility and F12's blocking pacer are all genuine, and the four highest-value items — the
cutover repair, the broken upload, the unified timeline and the Sales projection — survive contact
with all ten specs untouched. The defects cluster in the two places the missing specs actually
lived: **§5.4 is unbuildable as written** (it fails `DomainInvariantsTest`, which is invariant 8
doing its job), **§6.1's "MODIFY now" and §6.5's fall-through each reverse a decision that was
argued, priced and shipped** in Units 39 and 43, **§12(f) restates Unit 43's §6a in full and then
inverts its §7 commit ordering**, and **R5 sequences a unit whose own spec grants one read scope,
deletes the credential six live features depend on, and waits on a marketplace app nobody has
created**. The residual risk after the eight corrections is concentrated in one place and it is a
sequencing risk, not a design one: Units 25, 43 and 44 each believe they own a piece of the same
foundation — the per-brand credential, the `pipeline`/`pipeline_stage` tables, and the client's
front door — and `00d`'s roadmap currently orders them in a way that contradicts two of the three
specs. That is cheap to fix on paper this week and expensive to discover at Unit 44.
