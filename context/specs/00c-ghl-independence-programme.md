# 00c — The GHL independence programme

> **Decided 2026-09-11.** EvalOS stops being a thin surface over GoHighLevel and becomes a
> system that **works when GHL is removed**. GHL becomes one optional integration rather than
> the floor everything stands on.
>
> This document is the **ledger and the sequencing**, not a spec. Each unit below gets its own
> `NN-name.md` when it is started, on the pattern `00b` established for Units 36–41.

---

## 0. Read this first: `00b` is not cancelled, it is completed and then inverted

`00b-ghl-operational-programme.md` (2026-09-10) made EvalOS **the interface** Sales and
Marketing work in, with GHL underneath as the CRM, pipeline engine, automation engine and
invoice integration. That programme shipped — Units 36–41 are built.

This programme takes the next step and it is a genuinely different one: **`00b` moved the
desk; `00c` moves the record.** Under `00b`, unplugging GHL leaves EvalOS with nothing to draw.
Under `00c`, unplugging GHL leaves EvalOS serving clients.

**`00b` §2 warned about exactly this cost and the warning stands:**

> *"Unit 38's cache is the first EvalOS row holding a pipeline fact, so this reversal is not
> reversible at the price the last one was. That, not the code, is the cost of the pivot."*

`00c` spends that property deliberately and at a much larger scale. The mitigation `00b` chose
for the cache — *only fields GHL owns, droppable without loss, never the answer to a write* —
**does not apply here and must not be cited as though it does.** These rows are authoritative.

---

## 1. The cutover that is already happening (do this first, it is not a unit)

**IE replaced its GoHighLevel sub-account on 2026-09-11.** New location
**`WY6bW2xUCI8Tz8gw7aLJ`**, replacing `kBumF0uUOmMBB5bneYjx`. Fresh CRM, **no contact or
opportunity migration — the old account is abandoned.**

This is operational work, not programme work, and some of it is urgent.

### 1a. Configuration — env only, no code

| Setting | Was | Now |
| --- | --- | --- |
| `GHL_LOCATION_ID` | `kBumF0uUOmMBB5bneYjx` | `WY6bW2xUCI8Tz8gw7aLJ` |
| `GHL_API_TOKEN` | old PIT | the new PIT, full `pit-` prefix (40 chars; a bare 36-char UUID 401s in a way that reads like a scope problem) |
| `GHL_ADS_PIPELINE_NAME` | `Google ADS Pipeline` | whatever the new account calls it |
| `GHL_EMAIL_PIPELINE_NAME` | `Shivangi's Email Marketing` | ″ |
| `GHL_SALES_PIPELINE_NAME` | `Aditya's pipeline` | ″ |
| `GHL_INTAKE_PIPELINE_NAME` | — | new, for Unit 43's submit |
| `GHL_HOT_STAGE_NAME` | — | new, the stage on that pipeline a completed application moves to |

Pipelines are matched **by name** (`GhlPipelineClient.pipelineNamed`) and the client answers
502 naming the pipeline it could not find. That is the right failure direction and it is
exactly what a fresh account will produce on all three until the names are set.

### 1b. Rebuilt inside the new GHL — one of these is critical

- ⚠️ **The `opportunity.won` workflow.** It must POST to
  `/api/webhooks/ghl/{brand.webhook_endpoint_token}`. The endpoint token is EvalOS-side and
  **unchanged**; the workflow that calls it lived in the old account. **Until it is recreated,
  Handoff A is dead and no case is created by anything.** Nothing else on this page stops the
  business; this does.
- **Scopes on the new PIT**: `opportunities.readonly`, `opportunities.write`, `contacts.write`,
  `invoices.readonly` — plus `calendars.readonly` and `calendars/events.write`, which `00b` §3
  listed as an outstanding external ask for Unit 40. A fresh integration is the cheapest moment
  to grant all six at once.
- Pipelines and their stages; a calendar for Unit 40; the invoice / QuickBooks connection
  Unit 41 reads.

### 1c. The data consequence, and why it is this programme's opening argument

Every `ghl_contact_id` EvalOS holds names a contact **that no longer exists**. What that breaks,
precisely:

| Surface | Keyed on | After cutover |
| --- | --- | --- |
| Cases, stages, audit | EvalOS ids | **fine** |
| Documents (S3) | `{brandId}/client/{...}` | **fine** |
| Drafts, approvals | EvalOS ids | **fine** |
| Sign-in (Unit 42) | `client_account` | **fine** — account is EvalOS-owned |
| **Invoices (Unit 41)** | `ghl_contact_id` | **empty** for every pre-cutover client |
| **Meetings (Unit 40)** | `ghl_contact_id` | **empty** for every pre-cutover client |

Unit 42 seeds `ghl_contact_id` as **NULL rather than copying it forward**: a column that looks
authoritative and 404s is worse than an absent one.

**The split in that table is the entire thesis of this programme, arriving as evidence rather
than as a prediction.** Everything EvalOS owns survived a CRM replacement without a migration.
Everything keyed on GHL did not.

---

## 2. What "independent" means here, and what it does not

**Means:** with GHL switched off, a client signs in, sees their cases and their documents,
reviews and approves a draft, and completes an intake application. Staff work every case to
delivery and pay every expert.

**Does not mean:** EvalOS becomes a CRM with marketing automation. It does not acquire campaign
tooling, a dialler, or a review-request engine. Those are GHL's and stay GHL's whether or not
GHL is present — if GHL goes, they go, and that is an accepted loss.

**The honest ceiling: invoicing.** GHL carries the QuickBooks integration and the payment
collection. Full independence means EvalOS raising invoices, which reverses the one half of
invariant 2 that `00b` explicitly kept — *"invoicing is still GHL's, full stop"*. **That is the
most expensive item in this programme and it is sequenced last**, because it is the one with a
finance and compliance surface rather than only an engineering one.

---

## 3. The units

| # | Unit | What it makes EvalOS-owned | Depends on |
| --- | --- | --- | --- |
| **42** | Client accounts and sign-in | the client's identity and credential | 34, 35 |
| **43** | Get Started intake funnel | the application, its answers, its documents **and its stage** | 42, 37 |
| **44** | Inbound lead sync | GHL-born leads get an EvalOS row; `CachedOpportunity` is replaced | 43 |
| **45** | Contacts as the record | `ghl_contact_id` demoted to a sync link everywhere, not just in the portal | 44 |
| **46** | Sync engine | one reconciler, both directions, with a conflict rule and a drift report | 44, 45 |
| **47** | Invoicing | **the expensive one.** Reverses invariant 2's surviving half | 45, and a finance decision |

**42 and 43 are the ones with a customer waiting** — they are the client login and signup the
business asked for on 2026-09-11 — and they are also, not coincidentally, the first two bricks
of independence. Build them first and independently of 44–47.

**44 onward do not start until 42 and 43 are shipped and in use.** The reason is §1c: the
cutover is about to produce real evidence about which GHL-keyed surfaces actually hurt when the
key breaks, and designing the sync engine before reading that evidence is designing against a
guess.

### Unit 44 is smaller than it looks, and the direction of travel changed

**The inbound door is already built and deferred, not missing.** `WebhookRouter.java:41` lists
`contact.created` and `contact.updated` in `DEFERRED` — recognized, archived, acked, nothing
routed. The gateway already brand-resolves, dedupes and archives them (invariant 10). Unit 44
is a handler on an existing route plus a reconcile sweep, not a new pipeline.

**It carries a reconcile sweep as well as the webhooks**, decided 2026-09-11. Webhooks are lost
sometimes and EvalOS has no redelivery of its own — the outbound retry machinery left with
Unit 18. A periodic pull of contacts changed since the last run repairs what a dropped delivery
missed. It belongs in `job`, under an advisory lock, like every other sweep.

**`CachedOpportunity` is replaced here rather than at 45**, which is question (c) resolved
early by the shape of the data: its `ghl_opportunity_id`, `ghl_contact_id` and `stage_id` are
all `NOT NULL`. A row that cannot exist without three GHL ids cannot be the record for a system
that outlives GHL.

---

## 4. The invariant ledger

Same shape as `00b` §2. **An invariant is live until the unit named here ships.**

| Invariant | Fate | Unit |
| --- | --- | --- |
| **1** brand isolation | **unchanged.** Every new table here is brand-scoped | — |
| **2** invoicing is GHL's | **dies at 47, and not before.** `00b` kept this half deliberately; it is the last thing to go | 47 |
| **7** `ghl_contact_id` is the canonical client identity | **amended at 42, replaced at 44.** At 42 it becomes a nullable *link* on `client_account`; at 44 the EvalOS client row is the record. **The three-identifier rule survives both** and stays load-bearing | 42, 44 |
| **8** a case is born only of a won opportunity | **untouched by 42–46.** Re-argued at 47 and nowhere earlier — if EvalOS raises the invoice, "won" stops being a thing only GHL can tell us | 47 |
| **13** append-only audit | **unchanged**, and it gets wider: every sync write audits | all |
| **14** EvalOS sends no email | **amended at 42** to authentication mail only. **Not a general channel** — see `42` §4 | 42 |

**The one to watch is 7.** Unit 39 amended its first clause, Unit 42 amends it again, Unit 44
replaces it. Three edits to one invariant across three units is how an invariant dies without
anyone deciding to kill it. **At Unit 44 it gets rewritten whole, not annotated a third time.**

---

## 5. The cost, stated once, plainly

**Two systems owning contact and deal data is drift.** A name edited in GHL that EvalOS never
hears about. An opportunity moved in EvalOS that GHL's automation does not see. Unit 46 exists
entirely to manage that and it cannot eliminate it — it can only give it a conflict rule and a
report.

**This was foreseen and the warning was explicit.** `architecture.md` invariant 2: *"the day
EvalOS stores a pipeline fact, two systems own it."* That day was Unit 38. This programme makes
it the normal condition rather than a single cache.

**What makes it worth paying:** §1c. IE replaced its CRM and the GHL-keyed half of the portal
went dark while the EvalOS-owned half did not notice. A business that changes CRMs cannot have
its client-facing system change with it.

---

## 6. Open questions

Each gets a recommendation, per house rule, and is resolved before the unit it gates.

| # | Question | Recommendation | Gates |
| --- | --- | --- | --- |
| a | On conflict, who wins? | **Last-writer-wins per field, with the losing value kept on a drift report a human reads.** Field-level rather than record-level: a name and a phone edited in two places are not one conflict | 46 |
| b | ~~Does the sync push, pull, or both?~~ | **RESOLVED 2026-09-11: both, and pull arrives at 44.** The earlier recommendation here was push-only until 46, on the reasoning that pulling before a conflict rule exists is how the first silent overwrite happens. **That reasoning still holds and is what constrains 44**: the inbound handler may *create* an EvalOS row and may update a row that has never been edited in EvalOS, and may **not** overwrite a field a human changed on this side. Until 46 gives it a conflict rule, a collision is written to the drift report and the EvalOS value stands | 44 |
| c | ~~Does Unit 38's opportunity cache become the record, or get replaced?~~ | **RESOLVED 2026-09-11: replaced, at 44 rather than 45.** `CachedOpportunity` has `ghl_opportunity_id`, `ghl_contact_id` and `stage_id` all `NOT NULL` — it cannot hold a lead that has no GHL ids yet, which is exactly the row Unit 43 creates | 44 |
| f | How is a portal lead's stage held? | **RESOLVED 2026-09-11: GHL's stage id verbatim, plus the stage name beside it.** The id keeps EvalOS faithful to GHL's real pipeline with no parallel vocabulary to drift; the name is the half that still means something when GHL is gone. See `43` §6a — an id alone cannot satisfy "EvalOS moves it to hot itself", because EvalOS would not know which id is hot | 43 |
| d | Does EvalOS invoicing mean QuickBooks directly, or a payment processor? | **Not answerable by engineering.** It is a finance decision and 47 does not start without it | 47 |
| e | What happens to XpertsPortal? | **Out of scope until IE is proven.** XP has no GHL location configured at all today; giving it one is Unit 25, and giving it independence is a repeat of this programme, not a widening of it | — |
