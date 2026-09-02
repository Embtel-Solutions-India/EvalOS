# Unit 29 — Sales desk (the sales executive operates GHL from EvalOS)

> # ⚠ **REMOVED (2026-09-02). This spec is history, not a plan.**
>
> The sales desk and the `SALES_EXECUTIVE` role were deleted from the codebase. Everything below
> describes what was built and why; **none of it is live**. Do not implement 29b-29d from it, and
> do not cite it as precedent that EvalOS writes to GHL — `GhlHttp` has no write verb, and
> invariant 2 has reverted to "EvalOS runs no sales".
>
> **What the removal touched:** `SalesController`, `SalesBoardService`, `GhlSalesClient`,
> `features/sales/`, the `/sales/board` nav entry, `Role.SALES_EXECUTIVE`,
> `team_member.ghl_user_id` and its mapping endpoint, the V906 seed login, and `GhlHttp`'s `post`
> and `put`. `V30__drop_sales_executive.sql` reverses V29's two constraint rewrites and drops the
> column; V29 stays, because an applied migration is never edited or deleted.
>
> **What survived, and why it is the useful part of this file:** `GhlHttp` stays extracted. It was
> pulled out to hold one shared rate-limit pacer, and that limit belongs to the GHL *location*
> rather than to whichever client is reading — §"prerequisite finding" below is still the reason,
> and `GhlHttpTest` still pins it. The `ghl_opportunity` decision (store nothing) is why removing
> this unit cost one migration and no data reconciliation.
>
> **Kept as the record of a decision made, shipped and undone.** Unit 27's `/sales/pipeline` — the
> GM's *read* of the same funnel — is a different screen and is untouched.
>
> Original status line: **29a BUILT (2026-08-29). 29b-29d specced, not built.** Spec first, per the process
> Unit 25 set and Unit 24 apologised for not following — then 29a implemented against it.
>
> **What 29a shipped:** `GhlHttp` (the shared-pacer extraction), `GhlSalesClient`,
> `SalesBoardService`, `SalesController`, `Role.SALES_EXECUTIVE`, V29, `features/sales/`, and the
> nav entry. Gates green: 589 backend tests, 152 frontend, `tsc -b` and `npm run build` clean.
>
> **Three things the build changed in this spec**, recorded rather than quietly corrected:
> the null-body guard in `GhlHttp` had to distinguish a read from a write (§2); `ALL_ROLES` in the
> frontend became `PRODUCTION_ROLES` because it stopped being all of them (§4); and the
> `team_member_brand_required` CHECK needed rewriting, which no unit test could have caught (§4).
>
> **This is the unit that costs an invariant.** Every unit before it could point at
> invariant 2 and say "intact". This one amends it, deliberately, with the amendment
> written down before a line of code exists. If you read only one section, read §1.
>
> Phased: **29a** is the phase that pays the invariant's price; **29b–29d** are additions
> on a boundary already crossed. Each phase ships alone.

## Why this exists

Units 24, 26 and 27 put three GHL pipelines on a GM screen as **windows**. Unit 27's
window is the sales team's own working funnel — *Aditya's pipeline* — and the request
that followed it was the obvious next one and the one the architecture forbade: let the
salesperson **work** in it from here, instead of reading it here and acting in GHL.

The business reason is thin on its own and is better stated plainly than dressed up: a
salesperson who lives in GHL all day loses nothing by staying there. What EvalOS adds is
**one login and one place** for someone whose day spans selling and the production side,
plus an audit trail GHL's own does not reach into. If the sales team never opens EvalOS
for anything else, this unit is not worth its price. That is the acceptance question for
29a, and it is why 29c and 29d wait behind it (§7).

## §1 The invariant, amended

Invariant 2 currently reads, in part: *"EvalOS never runs marketing, sales, nurture/cold
email, ad attribution, or invoicing… The day something here writes to a GHL pipeline,
two systems own it and this invariant is gone."*

Two separate claims live in that sentence, and only one has to die.

- **"EvalOS never *runs* sales."** Dies. A sales executive moving a stage from an EvalOS
  screen is running sales. No reading of the word survives that.
- **"A case is in exactly one system's custody."** Survives untouched. GHL still owns
  every opportunity, before and after this unit. EvalOS stores no sales fact.

So the amendment keeps the load-bearing half and moves the line from *read-vs-write* to
*custody*:

> 2. A case is in exactly one system's custody at any moment. **EvalOS may operate GHL's
>    sales pipeline on a salesperson's behalf (Unit 29) — as a client of GHL that holds no
>    state of its own.** The boundary is no longer read-vs-write; it is **custody**: there
>    is no `ghl_opportunity` table, no sales column on any EvalOS entity, no sales row in
>    any EvalOS table. GHL is the sole system of record for every opportunity. The day
>    EvalOS *stores* a pipeline fact, two systems own it and this invariant is gone.
>
>    Marketing, nurture/cold email, ad attribution and **invoicing** stay entirely GHL's
>    and are untouched by this amendment. `Invoice sent` and `Refund` remain stages this
>    system reads and never acts on.

### What the old invariant was buying, and what replaces it

The old line bought a guarantee that was **free and total**: the credential was
`opportunities.readonly`, so a mistake in EvalOS's code still could not write. That is
gone and cannot be replaced in kind — the grant now permits writes, so the guarantee has
to be earned in code. Two replacements, both testable, neither a comment:

1. **One door.** Every GHL write goes through `GhlSalesClient`. Nothing else in the
   codebase performs a mutating GHL call. Enforced by a test that fails the build on a
   `post`/`put`/`patch`/`delete` to GHL outside that class — the same shape as the nav
   test that keeps the three funnel screens GM-only.
2. **Nothing is destructive — see §10.** The door opens one way: create and change, never
   remove. `GhlHttp` exposes no `delete` at all, so the capability is absent from the
   codebase rather than merely unused.
3. **Every write leaves an audit row.** Append-only, actor-resolved, brand-null. This is
   the **only** EvalOS-side record of sales activity — which is exactly the precedent
   Unit 23 set for case notes: *the audit trail is the store, and a second store beside
   it would have to re-earn everything the trail already has.*

### The property that proves the design

When the exec marks a deal **Won** from EvalOS, EvalOS calls GHL, GHL fires
`opportunity.won`, and the case is created through the **existing Handoff A gateway** —
unchanged, idempotent, brand-resolved from the per-brand endpoint token exactly as today.

That is the whole argument in one sentence: **remote control creates no second path into
custody.** Handoff A remains the only door a case enters through, and it neither knows
nor cares which screen the salesperson used. Had this unit instead created the case
directly on a Won click, there would be two doors, and the second would skip brand
resolution, idempotency and the raw-payload archive.

## §2 Two clients, one pacer — the finding that makes a second client legal

`GhlPipelineClient` keeps its javadoc promise and its three-field projection. Both are
correct for the three funnel screens, which this unit does not change. The new work goes
in `GhlSalesClient`.

**A second client cannot simply be added, because the rate limiter is per instance and
the rate limit is per location.** `GhlPipelineClient.pace()` synchronizes on `this`,
holding `nextRequestAt` for that bean. GHL allows **100 requests per 10 seconds per
location**. Two beans, two pacers, one location: 200 requests per 10 seconds, each bean
correctly under the limit and the pair over it. The class comment already states why the
lock is on the instance rather than the thread — *"a per-thread or per-call limiter would
let two concurrent reads each stay under the limit while together being over it."* A
second bean is that same defect one scope out.

So the extraction comes first, as its own step:

- **`GhlHttp`** (`integration`) — one `@Component` holding the `RestClient` (base url,
  bearer token, `Version` header, bounded timeouts), the shared pacer, and exactly
  **`get`, `post` and `put`** — **no `delete`, by §10** — with the
  `GhlUnavailableException` mapping and the status-code-in-the-message behaviour Unit 27
  added for a reason.
- `GhlPipelineClient` and `GhlSalesClient` both depend on it and own only their wire
  shapes and their endpoints.

**This is an extraction, not a new abstraction.** No interface, no strategy, no registry:
one concrete component with two callers, extracted because *shared mutable rate-limit
state must actually be shared*. With one caller it would stay where it is.

**Config:** `evalos.ghl.timeout` currently bounds a dashboard read. A write wants a
different bound — a read that times out costs a stale screen; a write that times out
leaves the caller not knowing whether GHL applied it (§5). Separate
`evalos.ghl.write-timeout`, defaulting higher, so tuning one does not silently retune the
other.

## §3 PII: a deliberate decision, deliberately reversed, and confined

`GhlPipelineClient.Opportunity` binds three fields — stage id, value, source — and the
javadoc says why: *"GHL's search response also carries the contact's name, email, phone
and tags on every row — none of which a stage count needs, and all of which would then be
marketing PII sitting inside an EvalOS response."*

A working sales board needs exactly that PII. There is no version of this feature that
does not. So the decision is reversed **for one endpoint family and no other**:

- **`GhlSalesClient.SalesOpportunity`** binds: opportunity id and name, contact id, name,
  email, phone, stage id, monetary value, status, assigned user id, updated-at.
- It appears **only** in `/api/sales/**` responses, to `SALES_EXECUTIVE` and `GM`.
- It is **never** joined onto a case, never written to any table, and never placed in an
  audit snapshot beyond the opportunity id and the fields a write actually changed.
- The three marketing funnel screens keep the three-field projection **unchanged**. The
  narrow record was right for a stage count and stays right; this is a second projection
  for a second question, not a widening of the first.

**Security consequences, stated rather than assumed:**

- `/api/sales/**` payloads are the first in EvalOS to carry **marketing-contact PII for
  people who are not clients of any case**. A prospect who never buys has a name, email
  and phone in these responses and in nothing else EvalOS serves.
- No payload logging. The client logs status codes and GHL's error body, never a
  `SalesOpportunity`.
- Invariant 4 (`payment_detail`) is untouched and unrelated — nothing here reads an
  expert.
- Anonymous is 401 and every other role 403, asserted per route rather than per
  controller.

## §4 Role, identity and scoping

### `Role.SALES_EXECUTIVE(Tier.SELF)`

A seventh role. `Role`'s javadoc ("The six EvalOS staff roles") is **edited**, not
appended to.

**`Tier.SELF` is correct by construction, which is why no new tier is added.** SELF means
*own brand + rows assigned to the caller*, and a sales executive is never assigned a
case, an expert or a payout — so every scoped query returns **zero rows** with no new
branch in `ScopePredicate` and no new predicate anywhere. A `Tier.NONE` would be
self-documenting and would also be a lie the first time this role legitimately needs a
row.

Because "correct by construction" is a claim about code that does not exist yet, it is
tested rather than asserted: the role is **403 or empty** across board, cases, experts,
payouts, metrics and the three funnel routes, in one parameterised loop, with the
reasoning written where someone would go to add an eighth role.

### Identity: `team_member.ghl_user_id`

GHL opportunities carry `assignedTo`, a GHL user id. EvalOS users have no GHL identity
today.

- New **nullable** `text` column on `team_member`, set by the GM from GHL's own user list
  (`GET /users/`, `users.readonly` — a read).
- The board filters on `assignedTo = my mapped id`.
- **An unmapped sales executive sees nothing, and never everything.** The filter is built
  from the mapping, so a null mapping produces an empty board and a message naming the
  fix — not an unfiltered read of the whole pipeline. Failing closed is the reason the
  column exists at all; matching on login email instead was rejected precisely because
  its failure direction depends on how the comparison happens to be written.
- A **DB unique index**, not a service check: two EvalOS users must not map to one GHL
  user, and the index is the place that cannot be forgotten.

### Brand scoping, and why Unit 25 is not a prerequisite

`evalos.ghl.location-id` is still one global value with no link to a brand, so this
role's data is as unattributable as the three funnel screens'. `brand_id` stays **NULL**
for a `SALES_EXECUTIVE`, and invariant 1's stated exception gains a second clause:

> **The GM remains the only role that reads cross-brand *EvalOS rows*.
> `SALES_EXECUTIVE` reads none at all** — `Tier.SELF` over rows it is never assigned —
> **and reads exactly one GHL location.** That is not a widening of the GM's reach; it is
> a role whose entire data surface is the unattributable location the exception already
> covers.

**Unit 25 closes this the same way it closes the other three screens**, and 25a's
re-scoping sweep grows from three screens to four. It is not a blocker: waiting on an
unscheduled unit would park this one indefinitely, and the exception's stated reasoning
already covers the location.

## §5 Consistency, failure and the ceilings

### The GM's cached funnel is deliberately **not** invalidated

This reverses the position taken when the design was first presented, and the reason is
worth keeping: **a stage moved through EvalOS must behave exactly like a stage moved in
GHL's own UI, and that one invalidates nothing.** `ghl_funnel_cache` holds a 5-minute
aggregate whose payload carries `readAt`, so the screen already states its own age. An
eviction path would make one business event produce two different freshness behaviours
depending on which door it came through — and it would race the background totaller,
which can `store()` a payload computed before the write and stamp it fresh.

So: **no eviction, no `deleteByFunnel`, no race.** The GM's funnel lags by its TTL as it
always has, and says so on screen. The lazy answer is also the consistent one.

**The exec's own board is uncached** — a working board cannot serve a 5-minute-old stage,
and it reads a filtered slice rather than the whole funnel's aggregate.

### Write ordering: GHL first, audit second

A lost audit row beats an audit row claiming something that did not happen. If the GHL
call succeeds and the audit insert fails, the response is still an error and the write
**did** land — logged at error with the opportunity id, because that is a reconciliation
a human can perform and a silent success is not. The reverse order manufactures history.

No transaction spans the two, and none may: one is an HTTP call to a third party and the
other a local insert, and a `@Transactional` around both would roll back the row while
GHL kept the change — the exact "partially-applied state" the architecture's integration
rule forbids.

### `object_id` is a UUID and a GHL opportunity id is not — resolved without a migration

`audit_event.object_id` is `uuid NOT NULL`; GHL opportunity ids are 20-character opaque
strings. Widening the column would touch the append-only audit table and every reader of
it, for one object type.

Instead, `object_id` is a **deterministic name-based UUID** of the GHL id —
`UUID.nameUUIDFromBytes(("ghl-opportunity:" + id).getBytes(UTF_8))`, stdlib, no
dependency — with `object_type = "GHL_OPPORTUNITY"` and the **real GHL id in the
snapshot**, where a human reads it. Deterministic means every row for one opportunity
shares one `object_id`, so the trail groups correctly and stays queryable by recomputing
the value. Stated ceiling: it is MD5-based (JDK v3), fine for an identity mapping and not
fine for anything security-bearing.

`AuditAction` needs **no new constants for 29a–29c** — the enum is explicitly open
vocabulary with no CHECK, and `STAGE_CHANGED`, `CREATED`, `UPDATED` and `NOTE_ADDED`
already say exactly what these writes do. 29d adds `TASK_CREATED` and
`APPOINTMENT_BOOKED`. `brand_id` on the row is null, which the column already allows.

### Lost updates: only the changed field is sent

GHL exposes no ETag or version on an opportunity, so there is no compare-and-set to be
had. Each write therefore sends **only the field it changes** — `PUT /opportunities/{id}`
carrying just `pipelineStageId`, `PUT /opportunities/{id}/status` for won/lost — so two
execs editing different fields of one deal do not clobber each other.

`// ponytail: last-write-wins per field; GHL offers no version. Upgrade path is a
read-before-write comparison and a "changed underneath you" refusal, which costs a
request per write and earns its keep only if two people really do work one deal.`

### Timeouts are the genuinely ambiguous failure

A write that times out has an **unknown outcome** — GHL may have applied it. The UI must
not claim either way. The rule: 502 with a message saying the outcome is unknown and that
the board will show what GHL actually has, and the refresh is automatic. **No automatic
retry**: a retried stage move is harmless and a retried *create* duplicates a deal, and
one rule for all writes beats a per-endpoint judgment call. GHL marks these operations
idempotency-capable; using that key is the upgrade path, not the first version.

### Optimistic UI, with rollback to GHL's answer

The board moves the card immediately and reconciles against the refetch. On failure it
rolls back to **GHL's** state, never to the exec's intent — a UI that keeps a failed move
on screen is a local copy with a lifetime of one session, which is the thing this whole
design refuses.

### Rate limit under a board that is actually being worked

Nine stages is nine count requests; at 110ms pacing a full board refresh is ~1s, shared
with the three funnel screens and the background totaller through the one `GhlHttp`
pacer. Fine for one to three execs. `// ponytail: one shared pacer, first-come queue; a
fair queue or per-caller budget only if a busy board starts starving the GM's funnel.`

## §6 Surfaces

- **`GET /api/sales/board`** — the exec's pipeline: stages in GHL's `position` order, each
  with its opportunities (`SalesOpportunity`), filtered to the caller's mapped GHL user.
  GM sees it unfiltered. Uncached.
- **`PUT /api/sales/opportunities/{id}/stage`** — 29a.
- **`PUT /api/sales/opportunities/{id}/status`** — won / lost / abandoned. 29a.
- **`POST /api/sales/opportunities/{id}/notes`** — 29b.
- **`POST /api/sales/opportunities`**, **`PUT /api/sales/opportunities/{id}`** — 29c.
- **`POST /api/sales/opportunities/{id}/tasks`**, **`…/appointments`** — 29d.
- **`PUT /api/team/{id}/ghl-user`** — GM maps an EvalOS user to a GHL user; reads GHL's
  user list for the picker.

**No route on this list is a `DELETE`, and that is a rule rather than a coincidence of
what was asked for — see §10.** 29d's appointment support ships with cancellation only if
GHL exposes it as a status write; if the only route is a DELETE, cancellation is out of
scope and the exec cancels in GHL.

`SalesController` + `salesApi.ts`, **not** an extension of `MarketingController`. Unit 27
deliberately kept its route under `/api/marketing/` and recorded the naming debt as
smaller than the split; **that trade inverts here** — this is a different credential
scope, a different role, a different projection with PII in it, and mutating verbs. A
screen's heading was not worth a second door; all of that is.

Frontend: `features/sales/`, a board reusing the production board's column and card shape
where it fits, `tokens.css` untouched. The **RAG status vocabulary is not reused**: a
sales stage is not a case's health, and borrowing those colours would say something false
in a familiar language.

## §7 Phases

| Phase | Scope | New GHL grant |
|---|---|---|
| **29a** | `GhlHttp` extraction, `GhlSalesClient`, the role, `ghl_user_id` + GM mapping screen, board, move stage, set status. **This phase pays for the invariant.** | `opportunities.write`, `users.readonly` |
| **29b** | Notes on the contact — what turns a viewer into a working tool | `contacts.write` |
| **29c** | Create + edit opportunity (new deal, value, rename) | — |
| **29d** | Tasks (`/contacts/{id}/tasks`) and appointments (`/calendars/events/appointments`, plus a calendar picker via `calendars.readonly`) | `calendars/events.write`, `calendars.readonly` |

**Ship 29a and stop.** The acceptance question is not "does it work" but *does the sales
executive actually work in EvalOS* — because if they do not, 29c and 29d are a second
sales UI nobody opens and the invariant was spent for nothing. Build them once 29a is in
daily use, not before. All four are specced because all four were asked for; the order is
the recommendation.

## §8 Verification

- **`GhlHttpTest`** — the pacer is shared: two clients through one `GhlHttp` issue
  requests spaced by `MIN_REQUEST_INTERVAL` **in aggregate**. This is the test that would
  have caught the two-pacer defect, so it is written to fail if either client ever
  reacquires its own.
- **`GhlSalesClientHttpTest`** — every parameter spelling pinned against a fixture, in the
  spirit of Unit 27's snake/camel pinning. Write bodies are asserted to carry **only** the
  field being changed; a body that also sends `name` or `monetaryValue` fails.
- **`SalesControllerTest`** — per route: `SALES_EXECUTIVE` allowed, GM allowed, **every
  other role 403, anonymous 401**. Per route and not per controller, because Unit 27
  recorded that identically-shaped payloads make a copy-paste invisible.
- **Scoping** — an unmapped exec gets an empty board, not a full one; a mapped exec sees
  only their own rows. Both are failure-direction tests and both must fail without the
  filter.
- **Role containment** — the parameterised loop of §4: `SALES_EXECUTIVE` is 403 or empty
  on board, cases, experts, payouts, metrics and all three funnel routes.
- **One-door test** — the build fails on a mutating GHL call outside `GhlSalesClient`.
- **No-delete test (§10)** — no method on `GhlSalesClient` issues an HTTP DELETE, and
  `GhlHttp` has no `delete` to call. Asserted, because unlike the old read-only guarantee
  this one is **not** backed by the credential: `opportunities.write` and `contacts.write`
  both permit deletes, so code is the only thing holding the line.
- **Audit** — every write path asserts a row with the deterministic `object_id`, the real
  GHL id in the snapshot, the resolving actor and null `brand_id`. Plus: a failed GHL
  write leaves **no** audit row.
- **No `GhlSalesClientLiveTest` — decided, see §9.** `GhlPipelineClientLiveTest` is
  unchanged; reads stay live-tested.
- **Acceptance step, not a test: the supervised first write.** One real deal, moved once,
  in daylight, with the audit row and GHL's own record checked afterwards. 29a is not done
  until this has happened, because §9 leaves the request bodies unproven until it does.
- Gates unchanged: `./mvnw verify`, `npm run build`, `tsc --noEmit` clean.

## §9 The live write test — decided: there is not one

**Decided 2026-08-29 (§10's no-delete rule decides it).** This section was an open
question recommending a create → move → delete live test. That test cannot exist, and
neither can any other live write test worth having.

Unit 27's history is still the argument *for* one: the unit test proved a normalisation
against a fixture *we* wrote, and only a real call proved the fixture matched GHL. That
argument is sound and it loses anyway, to two facts about this pipeline:

1. **Nothing created can be cleaned up.** With no delete method (§10), a test that
   creates an opportunity leaves it in the sales team's real working pipeline
   permanently, once per run.
2. **The obvious substitute is worse, not safer.** "Move a stage and move it back" looks
   conservative and is the most dangerous option on the list: **a stage change fires that
   pipeline's GHL automations**, and a move into `Won` fires `opportunity.won`, which
   creates a real case in EvalOS through Handoff A. A test that manufactures a case and a
   round of client automation is not a test. Moving it back does not un-fire anything.

So: **fixtures pin the wire format, and there is no `GhlSalesClientLiveTest`.**
`GhlPipelineClientLiveTest` stays exactly as it is — reads are safe and remain live-tested.

**What replaces it, and it is not nothing.** The first real write is a **supervised first
write**: one real deal, moved once, in daylight, with the audit row and GHL's own record
checked afterwards. That is an acceptance step for 29a, listed in §8, not an informal
intention. It is the same evidence a live test would have produced, bought with a
deliberate action instead of a scheduled one — and it is *cheaper* than the live test,
because the deal was going to be moved anyway.

**The residual risk, stated plainly:** a wrong parameter spelling — the exact class of
defect that cost Unit 27 an afternoon and Unit 24 a live 422 — will now surface on that
first supervised write rather than in a test run. Reads mitigate part of it: the sales
board exercises `/opportunities/search` live on every page load, so the shared parameter
names are proven before any write is attempted. What stays unproven until the first write
is the **request bodies**, which are new surface with no read equivalent.

## §10 No delete, anywhere — the capability is absent, not unused

**Decided 2026-08-29.** This unit implements **read and write only**: see the pipeline,
and save progress in it. Nothing EvalOS does can remove anything from GHL.

Concretely, and these are not guidelines:

- **`GhlHttp` has no `delete` method.** Not private, not unused, not `@Deprecated` —
  absent. A future unit that needs one has to add it and answer for it, which is the
  point. This is the same shape as the guarantee `opportunities.readonly` used to give,
  scoped to the destructive half and kept.
- **`GhlSalesClient` exposes no removal of anything**: no delete opportunity, no delete
  note, no delete task, no delete contact, no remove-from-pipeline.
- **The GHL grant is `opportunities.write` + `contacts.write`, which do permit deletes.**
  So unlike the old read-only guarantee, this one is **not** backed by the credential and
  rests entirely on code. That is exactly why it is a build-failing test and not a
  convention: `GhlSalesClientTest` asserts no method issues an HTTP DELETE, and the
  one-door test's verb list includes `delete` for calls made anywhere else.

**What is a write and not a delete, so the line is unambiguous:**

| Action | How | Verdict |
|---|---|---|
| Mark a deal lost or abandoned | `PUT /opportunities/{id}/status` | **Write.** The deal stays; its status changes |
| Complete a task | `PUT /contacts/{id}/tasks/{taskId}/completed` | **Write** |
| Cancel an appointment | status update on the event | **Write**, if GHL exposes it as a status; **out of scope** if the only route is a DELETE |
| Correct a mistyped note | not possible — notes are append-only here | Matches EvalOS's own case notes exactly (Unit 23): *a note can never be edited or withdrawn* |
| Remove a deal created in error | **not possible from EvalOS.** Do it in GHL | The stated cost, below |

**The cost, stated rather than hidden.** A deal created by mistake through 29c can only
be cleaned up in GHL itself. That is a real inconvenience and it is the correct trade:
the destructive-mistake surface of a screen that cannot delete is zero, and the recovery
path is one login away in the system that owns the data anyway. It also removes the whole
category of "did EvalOS delete that, or did someone in GHL?" — a question the audit trail
could answer only for its own half.

**One knock-on, recorded in §9:** without delete there is no way to clean up after a live
write test, which is what decides that there is not one.

## Non-goals, so they are decisions and not omissions

- **No local mirror.** No `ghl_opportunity`, no sync worker, no conflict resolution, no
  drift reconciler, no backfill. "Sync data with GHL" is **zero code** in this design,
  because nothing is copied: two-way sync was considered and rejected for the reason
  `architecture.md` already gives — *a stage a salesperson dragged five seconds ago is
  already wrong in a copy*.
- **No invoicing, no payment, no campaign send.** `Invoice sent` and `Refund` remain
  stages this system reads and never acts on. A refund is a payment fact and reaches
  EvalOS through the payment record if at all.
- **No sales reporting, targets, quotas or commission.** Those are EvalOS-side state
  *about* sales, which is exactly what the amended invariant 2 forbids. If they are ever
  wanted, they are a question about what EvalOS may **store**, and that conversation
  starts by reopening §1.
- **No recruitment pipeline**, unchanged from the standing decision.
- **No new nav group.** These screens go under the `Sales` heading Unit 27 created.

Depends on: 02 (roles/RBAC), 24 (client, config), 27 (the pipeline and its name).
Related: 25 / 25a — the location moves onto `brand` and re-scopes four screens, not three.
