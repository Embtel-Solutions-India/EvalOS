# 00c — The GHL mirror and independence programme

> **Decided 2026-09-11, scope settled 2026-09-12.**
>
> EvalOS holds a **complete, id-faithful mirror of GHL**, syncs it both ways, and operates from
> its own copy. GHL remains the authority during Phase 1. In Phase 2 the sync is switched off
> and **nothing collapses** — the desks, the portal and the dashboards keep working, because
> they were never reading GHL directly.
>
> The user's framing, kept verbatim because it is the clearest statement of the target:
>
> > *"first we are depend on ghl then we will make system to remove the ghl. currently the
> > system is based on ghl but also have independent structure ready to work independently with
> > our new config in code later... our target is to take opportunities from ghl store and
> > process and sync everything with ghl so operating from evalos don't mislead data in both
> > platform. data, contact id — these are our priority. a single digit of mismatch will not be
> > tolerated."*

---

## 0. How this relates to `00b`

`00b-ghl-operational-programme.md` (2026-09-10) moved **the desk**: Sales and Marketing work in
EvalOS screens, with GHL underneath. Units 36–41 shipped.

`00c` moves **the record**. Under `00b`, unplugging GHL leaves EvalOS with nothing to draw.
Under `00c`, unplugging GHL changes nothing a user can see.

**`00b` §2's warning stands and is now being spent deliberately:**

> *"Unit 38's cache is the first EvalOS row holding a pipeline fact, so this reversal is not
> reversible at the price the last one was. That, not the code, is the cost of the pivot."*

The mitigation `00b` chose for that cache — *only fields GHL owns, droppable without loss, never
the answer to a write* — **does not apply here and must not be cited as if it does.** These rows
are answered from, written to, and not droppable.

---

## 1. The cutover that is already happening (operational, not a unit)

**IE replaced its GoHighLevel sub-account on 2026-09-11.** New location
**`WY6bW2xUCI8Tz8gw7aLJ`**, replacing `kBumF0uUOmMBB5bneYjx`. Fresh CRM, **no contact or
opportunity migration — the old account is abandoned.**

### 1a. Configuration — env only, no code

| Setting | Was | Now |
| --- | --- | --- |
| `GHL_LOCATION_ID` | `kBumF0uUOmMBB5bneYjx` | `WY6bW2xUCI8Tz8gw7aLJ` |
| `GHL_API_TOKEN` | old PIT | the new PIT, full `pit-` prefix (40 chars; a bare 36-char UUID 401s in a way that reads like a scope problem) |
| `GHL_ADS_PIPELINE_NAME` | `Google ADS Pipeline` | whatever the new account calls it |
| `GHL_EMAIL_PIPELINE_NAME` | `Shivangi's Email Marketing` | ″ |
| `GHL_SALES_PIPELINE_NAME` | `Aditya's pipeline` | ″ |
| `GHL_INTAKE_PIPELINE_NAME` | — | new, where a portal application lands |
| ~~`GHL_HOT_STAGE_NAME`~~ | — | **dropped 2026-09-15, never shipped.** EvalOS creates the opportunity and sends no stage; where it lands and who it is assigned to are GHL's automation's, which is what this programme keeps GHL for |

Pipelines are matched **by name** and the client answers 502 naming what it could not find. That
is the intended failure direction, and it is what a fresh account will produce until the names
are set.

### 1b. Rebuilt inside the new GHL

- ⚠️ **The `opportunity.won` workflow.** It must POST to
  `/api/webhooks/ghl/{brand.webhook_endpoint_token}`. The endpoint token is EvalOS-side and
  **unchanged**; the workflow that calls it lived in the old account. **Until it is recreated,
  Handoff A is dead and no case is created by anything.** Nothing else here stops the business.
- **The mirror's workflows** (§4a): opportunity created / updated / stage-changed, contact
  created / updated. `contact.created` and `contact.updated` are already `DEFERRED` in
  `WebhookRouter:41` — recognized, archived and acked, with only the handler missing.
- **Scopes on the new PIT**: `opportunities.readonly` + `.write`, `contacts.readonly` + `.write`,
  `invoices.readonly`, `calendars.readonly`, `calendars/events.write`. A fresh integration is the
  cheapest moment to grant all of them at once.
- Pipelines and stages; a calendar for Unit 40; the invoice / QuickBooks connection Unit 41 reads.

### 1c. The data consequence, and why it is this programme's opening argument

Every `ghl_contact_id` EvalOS holds names a contact **that no longer exists**.

| Surface | Keyed on | After cutover |
| --- | --- | --- |
| Cases, stages, audit | EvalOS ids | **fine** |
| Documents (S3) | `{brandId}/client/{...}` | **fine** |
| Drafts, approvals | EvalOS ids | **fine** |
| Sign-in (Unit 42) | `client_account` | **fine** — EvalOS-owned |
| **Invoices (Unit 41)** | `ghl_contact_id` | **empty** for every pre-cutover client |
| **Meetings (Unit 40)** | `ghl_contact_id` | **empty** for every pre-cutover client |

**The split in that table is the entire thesis, arriving as evidence rather than prediction.**
Everything EvalOS owned survived a CRM replacement with no migration. Everything keyed on GHL
went dark.

**One column proves it on its own, and it caught out this spec (corrected 2026-09-12).** Unit 42
first said to seed `client_account.ghl_contact_id` as NULL, reasoning that the id names a contact
the new location does not have, and a column that looks authoritative and 404s is worse than an
absent one.

**That weighs only the id's GHL job.** The same value is also EvalOS's **join key from a client to
their cases** — `PortalCaseService.authorized()` resolves a party token through it and fails
closed on a null — so seeding null would let every existing client sign in and then see **no
cases at all**. Unit 42 copies it forward instead.

| The id's job | Lives | After the swap |
| --- | --- | --- |
| join key to a client's cases | entirely inside EvalOS | **works** |
| lookup key for invoices, meetings | live GHL calls | **404s** |

Two jobs, one column, failing independently — and **only the GHL-facing one broke.** That is this
programme's argument in miniature, and the reason the fix was to keep the id rather than to keep
the null.

---

## 2. The mirror

**EvalOS holds GHL's structure using GHL's own ids.** Not a mapping, not a translation — the
same ids on both sides, which is what makes a mismatch detectable by comparison rather than by
interpretation.

```sql
pipeline          id uuid pk, brand_id, ghl_id unique, name, position, synced_at
pipeline_stage    id uuid pk, brand_id, pipeline_id, ghl_id unique, name, position, synced_at
contact           id uuid pk, brand_id, ghl_id unique null, name, email, phone, …
opportunity       id uuid pk, brand_id, ghl_id unique null,
                  contact_id, pipeline_id, stage_id, name, amount, status,
                  ghl_updated_at, local_updated_at, sync_state
```

### 2a. Why EvalOS mints its own primary key and keeps `ghl_id` beside it

A portal-born opportunity **exists before GHL has seen it**. With GHL's id as the primary key
that row cannot exist; and when GHL later assigns one, the row's identity would *change*, taking
every foreign key with it. So: one row, two names. `id` is stable from creation and is what
everything in EvalOS points at; `ghl_id` is the join key for sync and is null until GHL answers.

**This is what makes "zero mismatch" mechanically possible instead of aspirational.** Comparing
two systems requires a stable correspondence between rows, and a key that changes when a remote
system responds is not one.

### 2b. Stages are mirrored, so GHL's stage id is not opaque

**Decided 2026-09-12, and it corrects a position taken in this file on 2026-09-11.** The earlier
text argued that storing GHL's stage id alone could not work, because an opaque id means nothing
without GHL and EvalOS would not know which stage is "hot". **That argument assumed there was no
stage table.** With `pipeline_stage` mirrored, the id resolves against an EvalOS row that carries
the name and the position — so the id is not opaque, and the separate `stage_name` column Unit 43
was going to carry is redundant and is dropped.

Same ids on both sides is also strictly better than a mapping table: there is no translation to
get wrong, and a stage comparison is an equality check.

### 2c. Scope: everything the API exposes

**Decided 2026-09-12.** The mirror's target is the full GHL surface, not a useful subset.
Sequenced by whether a consumer exists today (§3) — that is ordering, not scope reduction.

| Tier | Entities | Why first / later |
| --- | --- | --- |
| 1 | pipelines, stages, contacts, opportunities | every desk, board and portal screen reads these |
| 2 | custom fields + values, tags | workflows key off tags; custom fields hold data desks need |
| 3 | notes, tasks, calendars + appointments | Unit 40 already reads appointments through GHL |
| 4 | invoices | ties into the invoicing decision (§3, Unit 49) |

---

## 3. The units

| # | Unit | Ships | Depends on |
| --- | --- | --- | --- |
| **42** | Client accounts and sign-in — **BUILT 2026-09-12** | the client's identity and credential | 34, 35 |
| **43** | Get Started intake funnel — **BUILT 2026-09-15** | the application and its answers (`V49`), and a GHL opportunity on the intake pipeline **with no stage and no assignee**. **Not** documents, and **not** `pipeline`/`pipeline_stage` — see below | 42, 37 |
| **44** | The tier-1 mirror | `pipeline`, `pipeline_stage`, `contact`, `opportunity`; replaces `CachedOpportunity` | 43 |
| **45** | The sync engine | webhooks + delta sweep + nightly audit + outbox + drift report | 44 |
| **46** | The desks move onto the mirror — **BUILT 2026-09-17** | Sales and Marketing boards read EvalOS rows, never GHL; every desk *edit* is a local write plus a queued push. Creates stay inline (D46) | 45 |
| **47** | Tier-2 and tier-3 mirror | custom fields, tags, notes, tasks, calendars | 45 |
| **48** | The switch | `evalos.ghl.sync.enabled=false` runs the whole business | 46, 47 |
| **49** | Invoicing | **the expensive one.** Reverses invariant 2's surviving half | 48, and a finance decision |

**42 and 43 have a customer waiting** — they are the client login and signup the business asked
for — and are also the first two bricks of the mirror. Build them first and independently.

**Unit 44 inherits two things 43 was expected to hand it, and does not get them.**
`pipeline`/`pipeline_stage` were listed as 43's because it "cannot move an application to a hot
stage without knowing which stage that is". **That premise is gone twice over**: 43 resolves the
pipeline id live behind a five-minute cache, and as of 2026-09-15 it moves nothing to any stage at
all — GHL's automation places the deal. **44 owes both tables, unconditionally.** Funnel *document* upload also did not ship: every upload route EvalOS has takes a
checklist item on a **case**, so a pre-case upload needs its own table, routes and S3 prefix —
a unit, not a step (`43` §5).

**44 does not start until 42 and 43 are in use.** The cutover is about to produce real evidence
about which GHL-keyed surfaces actually hurt when the key breaks; designing the mirror before
reading it is designing against a guess.

**48 is a real unit, not a config flip with no work.** It is where the "if we remove the sync it
must not collapse" claim is *tested* — a full run of the business with sync disabled, in a
staging environment, with a list of what degrades. A claim of independence that has never been
exercised is a claim, not a property.

---

## 4. The sync engine (Unit 45)

### 4a. Three layers, because one is never enough

| Layer | Cadence | Job |
| --- | --- | --- |
| **Webhooks** | immediate | the common case; GHL workflows POST to the existing gateway |
| **Delta sweep** | every 5 minutes | pulls everything with `updatedAt` past the last watermark; repairs dropped deliveries |
| **Full audit** | nightly | compares every row both ways and produces the drift report |

**Webhooks alone are not sufficient and never were.** Deliveries are lost, and EvalOS has had no
redelivery of its own since Unit 18 left. The delta sweep is the safety net; the nightly audit is
what turns "we think it is in sync" into a number.

`GhlOpportunityClient.BoardOpportunity` already carries `updatedAt`, and the search endpoint
already pages by cursor (`startAfter` / `startAfterId`) — the delta sweep needs no new GHL
capability. Sweeps live in `job`, under the advisory lock every other sweep uses.

### 4b. Conflicts: EvalOS wins, and every one is reported

**Decided 2026-09-12.** When a field changed on both sides between syncs, **EvalOS's value
stands** and is pushed to GHL. EvalOS is the system being operated from, so the alternative is a
GHL automation silently reverting a salesperson's edit — which is precisely the failure mode of
moving the desk to EvalOS.

**Every conflict is written to the drift report** with both values and both timestamps.
**Resolution is never silent.** The report is the deliverable that makes the policy acceptable:
without it, "EvalOS wins" is data loss with a rule attached.

### 4c. What "a single digit of mismatch will not be tolerated" can and cannot mean

**Stated plainly, because the difference matters.** With two writable systems and no distributed
transaction, **transient divergence cannot be prevented** — a stage moved in GHL is divergent for
the seconds before the webhook arrives. No design removes that, and one that claims to is hiding
it.

What Unit 45 **does** guarantee:

1. **Every divergence is detected.** The nightly audit compares every row; nothing drifts unseen.
2. **Nothing is silently overwritten.** Conflicts surface to a human (§4b).
3. **Convergence within a bounded window**, and the window is *measured and reported* rather than
   assumed.
4. **No duplicates, ever.** §4d.

The drift report counts, per entity: rows checked, diverged, repaired, conflicted. **If
`conflicted` is ever non-zero, somebody sees it.** That is the honest form of the requirement,
and it is worth more than a promise of zero that quietly stops being true.

### 4d. The outbox comes back

**Decided 2026-09-12.** Every push to GHL becomes a durable row with a dedupe key, retried with
backoff, dead-lettered on exhaustion.

`architecture.md` line 17 already anticipated this, about the queue removed with Unit 18:

> *"If outbound delivery ever returns, the argument to re-read is Unit 19's: a durable row with
> backoff, not a broker."*

**This is that day, and the chain of reasoning is short:** no mismatch requires that a failed
push is retried; a retry is only safe if it cannot double-apply; GHL marks its writes as needing
idempotency and **invariant 2 currently says "writes do not retry" precisely because EvalOS has
no key scheme.** The dedupe key is that scheme.

**No broker, no queue server.** A table, a sweep, and backoff — Unit 19's shape.

**Upserts stay the default write** even with the outbox, because they are what makes a retry
converge rather than duplicate. `39`'s spec established that as a recoverability argument, not a
tidiness one, and it is load-bearing here.

---

## 5. The invariant ledger

**An invariant is live and enforced until the unit named here ships.**

| Invariant | Fate | Unit |
| --- | --- | --- |
| **1** brand isolation | **strengthened, and the exception closes.** Today the GHL reads are GM-only because EvalOS cannot attribute a location to a brand. Every mirror row carries the `brand_id` of the brand whose location it came from, so the figure stops being unattributable — this finishes what Unit 25 started | 44 |
| **2a** invoicing is GHL's | **dies at 49, and not before** | 49 |
| **2b** "writes do not retry" | **dies at 45**, replaced by a durable outbox with a dedupe key | 45 |
| **7** `ghl_contact_id` is the canonical client identity | **amended at 42, rewritten whole at 44.** EvalOS's `contact.id` becomes the identity; `ghl_id` becomes a link. **The three-identifier rule survives** and is still load-bearing | 42, 44 |
| **8** a case is born only of a won opportunity | **untouched by 42–48.** Re-argued at 49 and nowhere earlier — if EvalOS raises the invoice, "won" stops being something only GHL can tell us | 49 |
| **11** outbound delivery is signed, retried, dead-lettered | **returns, narrowed.** Unit 18 removed the general dispatcher; 45 brings back its *shape* for one destination, GHL. Not a general subscriber channel | 45 |
| **13** append-only audit | **unchanged, and it widens**: every sync write and every conflict resolution audits | 44, 45 |
| **14** EvalOS sends no email | **amended at 42** to authentication mail only. **Not a general channel** — `42` §4 | 42 |

**Invariant 7 is the one to watch.** Unit 39 amended its first clause, Unit 42 amends it again,
Unit 44 replaces it. Three edits across three units is how an invariant dies without anyone
deciding to kill it. **At 44 it is rewritten whole, not annotated a third time.**

---

## 6. The cost, stated once

**Two systems owning the same data is drift**, and Unit 45 manages it rather than eliminating it.
`architecture.md` invariant 2 predicted this — *"the day EvalOS stores a pipeline fact, two
systems own it"* — and that day was Unit 38. This programme makes it the normal condition.

**Tier 3 and 4 of §2c are sync surface with few consumers today**, and sync surface with no
consumer is pure drift risk with no offsetting benefit. That is why they are sequenced at 47 and
not at 44 — the scope is not reduced, the exposure is just not taken before something reads it.

**What makes it worth paying:** §1c. IE replaced its CRM and the GHL-keyed half of the portal
went dark while the EvalOS-owned half did not notice. A business that changes CRMs cannot have
its client-facing system change with it.

---

## 7. Open questions

Each carries a recommendation, per house rule, and is resolved before the unit it gates.

| # | Question | Recommendation | Gates |
| --- | --- | --- | --- |
| a | ~~Conflict rule?~~ | **RESOLVED 2026-09-12: EvalOS wins, every conflict on the drift report** (§4b) | 45 |
| b | ~~Push, pull, or both?~~ | **RESOLVED 2026-09-12: both** (§4a) | 45 |
| c | ~~Is `CachedOpportunity` promoted or replaced?~~ | **RESOLVED: replaced, at 44.** Its `ghl_opportunity_id`, `ghl_contact_id` and `stage_id` are all `NOT NULL` — it cannot hold a portal-born opportunity GHL has not seen | 44 |
| d | Does EvalOS invoicing mean QuickBooks directly, or a payment processor? | **Not answerable by engineering.** A finance decision; 49 does not start without it | 49 |
| e | What happens to XpertsPortal? | **Out of scope until IE is proven.** XP has no GHL location today; giving it one is Unit 25, and giving it a mirror is a repeat of this programme, not a widening of it | — |
| f | ~~How is a stage held?~~ | **RESOLVED 2026-09-12: GHL's stage id verbatim, against a mirrored `pipeline_stage` row** (§2b) | 43, 44 |
| g | Does a deletion in GHL delete the EvalOS row? | **No — soft-delete and report.** A hard delete driven by a remote system is unrecoverable, and a mis-scoped GHL bulk action would take EvalOS's copy with it. Mark `deleted_in_ghl_at` and let the drift report show it | 45 |
| h | What is the dedupe key on an outbox row? | **`(entity_type, entity_id, intent)`**, not a hash of the payload — a retry of "push this opportunity" must collapse onto the pending one even if a field changed between attempts | 45 |
