# Programme — EvalOS as the operational system over GHL

> **Status: SPECCED 2026-09-10, not built.** This is a **programme document**, not a unit. It sits
> beside `00-build-plan.md` and carries the decisions that Units 36–41 all read. A unit spec that
> contradicts this file is wrong; this file is amended first.

**Direction change, stated plainly.** `CLAUDE.md` opens with *"EvalOS never does marketing, sales,
or invoicing"* and `architecture.md` invariant 2 says the same at length. **That sentence dies
across this programme.** EvalOS becomes the interface Sales and Marketing work in; GHL stays the
CRM, the pipeline engine, the automation engine and the invoice/QuickBooks integration underneath.

The goal in one line: **operate GHL opportunities from EvalOS so Sales and Marketing never open
GHL**, without EvalOS becoming a second CRM.

---

## 1. The decisions every unit inherits

These were settled 2026-09-10. Each is a decision rather than a default, so each carries why.

### 1.1 Two roles, not six

`Role` gains exactly **`SALES`** and **`MARKETING`**, both `Tier.PIPELINE`.

The business has three kinds of each — **Attorney**, **Employer/Firm**, **Individual** — and they
are **not roles**, because they have identical permissions. They differ only in which clients they
handle, and GHL's workflows do the routing. So the kind is a **`segment` column** on `team_member`,
carried for display and reporting, and **nothing in the codebase branches on it**.

Six enum values would have grown every `switch (role)`, the `team_member_role_valid` CHECK, the nav
tests and the permission matrix by six, to express a distinction that changes no permission. That
is the cost of encoding an org chart in a security enum.

**The `segment` column has no access meaning and must never acquire one.** If a segment ever needs
a different permission, that is a new role, argued here first.

### 1.2 The pipeline is personal and exclusive

Each Sales and Marketing employee owns **one** GHL pipeline, and a pipeline has **one** live owner.
`uq_team_member_pipeline` stays globally unique and partial on `active`, exactly as Unit 36 specs
it. Two live holders would silently mean "sees each other's work".

### 1.3 GHL is truth; EvalOS caches, and the cache is droppable

| Owned by GHL | Owned by EvalOS |
| --- | --- |
| contact, opportunity, pipeline, stage, amount, status | the note stream (§1.4) |
| invoices, payments, QuickBooks sync | nothing else |
| calendars and appointments | |

Every write goes to GHL synchronously and **GHL's response is what EvalOS shows** — no optimistic
local state that can diverge. Reads go through a **non-authoritative cache** so boards paint inside
the browser's timeout.

**Why a cache at all, given invariant 2's warning that storing a pipeline fact ends it.** Because
the arithmetic forces it, the same arithmetic invariant 6 already records for
`MarketingPipelineService`: GHL allows **100 requests per 10 seconds per location**, and a year is
~11.4k opportunities across ~115 un-parallelisable cursor pages — a ~13s floor, past the browser's
15s timeout. A pure pass-through board is not slow, it is unusable.

**What keeps the cache honest:** it holds no field EvalOS is the source of, it is **droppable
without loss** (truncate it and the next read refills it), and it is never the answer to a write.
A `ponytail:` comment on the table names that ceiling. The moment a column appears in it that GHL
does not have, this decision is void and gets re-argued.

### 1.4 EvalOS owns the note stream, keyed by `ghl_opportunity_id`

**GHL has no opportunity notes.** The only note endpoints are `POST /contacts/{contactId}/notes`
and `PUT /contacts/{contactId}/notes/{id}` — notes hang off the **contact**. `GET
/opportunities/search` accepts `getNotes`, which returns the contact's notes joined onto the
result, not a per-opportunity stream.

So sales and marketing notes are **EvalOS rows**, keyed by **`ghl_opportunity_id`**.

**Keyed by opportunity, not contact, and this is the one place to resist a convenience.** It was
put to the business that "an opportunity is itself a contact", which is true of every contact
today. Invariant 7 exists because it stops being true: *"Three identifiers, never conflated:
`ghl_contact_id` = the client; `ghl_opportunity_id` = one purchase."* A repeat client is one
contact and two opportunities, and a note stream keyed on the contact would merge two deals'
histories with no way to separate them afterwards. Keying on the opportunity costs nothing now and
survives that.

**What this costs, named rather than buried:** a note written **in GHL** never reaches EvalOS.
That is acceptable *only* because this programme's whole purpose is that Sales and Marketing do not
open GHL. **If anyone works a deal in GHL directly, this decision is wrong** and must be revisited
rather than patched.

### 1.5 One brand, with the ceiling enforced in code

EvalOS is multi-brand. It talks to **one** GHL sub-account, named by the global
`evalos.ghl.location-id`, which has no link to a brand — invariant 1's one stated exception, held
today by making every screen over that location **GM-only**.

`SALES` and `MARKETING` are **brand-locked**, so the moment one of them reads that location the
exception stops holding: a single-brand role would be reading a location EvalOS cannot attribute to
a brand.

**Decision: single-brand, enforced, not documented.** A new property `evalos.ghl.sales-brand` names
the brand that owns the configured location. Assigning `SALES` or `MARKETING` to any other brand
answers **400 at assignment time**, not a silent empty board.

```
// ponytail: one GHL location, so one selling brand. Unit 25 (location + token per brand)
// is the upgrade path, and it is needed the day a second brand sells.
```

**Why not build Unit 25 now.** It is per-brand OAuth — agency authorization, a token store, refresh
handling, and a GHL marketplace app registered before any code runs. That is an external ask and a
unit in front of a six-unit programme, to serve a second selling brand that does not exist yet.
Rejected as premature, with the trigger for revisiting stated: **brand two sells.**

This **narrows** invariant 1's exception rather than widening it: from "a location nobody may be
told the brand of" to "a location whose brand is named in configuration".

---

## 2. Invariant ledger

The reversals this programme makes, in one place, so none of them happens by accident inside a
feature unit.

| Invariant | Fate | Where it changes |
| --- | --- | --- |
| **1** — brand isolation | **narrowed, not broken** | Unit 36. The exception becomes one *named* brand instead of GM-only. Every EvalOS row stays brand-scoped; only the GHL location is global. |
| **2** — EvalOS runs no sales/marketing/invoicing | **dies** | **Unit 37**, with the first write verb. Not Unit 36 — that unit reads nothing. |
| **7** — contact data is read-only, never mutated | **amended** | **Unit 39**. Marketing creating and editing leads writes contacts to GHL. The three-identifier rule in invariant 7 **survives unchanged** and is load-bearing for §1.4. |
| **8** — a case is born only of a won opportunity | **untouched, across the whole programme** | Sales works opportunities; a case still begins at payment through Handoff A. |
| **13** — append-only audit | **unchanged, and extended** | Every GHL write from EvalOS writes an audit row. A mutation that leaves no trace in EvalOS because it landed in GHL is the failure mode. |
| **14** — EvalOS sends no email | **ruled on, not reversed** | See below. |

**Invariant 14 is the one most likely to be misread, so it is ruled on here.** Sales scheduling a
meeting and setting a follow-up looks like EvalOS acquiring a channel. It is not: **EvalOS
instructs, GHL delivers.** EvalOS still has no SMTP, no outbound queue, no suppression list, no
bounce handling — it calls `POST /calendars/events/appointments` and GHL does the sending, exactly
as a client is "reached through GHL" today.

The invariant holds. **What would break it** is EvalOS composing and dispatching a message itself,
and that is still refused. Written down because the first feature that wants a "quick email from
the opportunity screen" will present itself as an obvious extension of this one.

**Invariant 2's round trip is worth reading before deleting it.** Unit 29 amended it for a sales
desk; the desk was removed and the amendment reverted. What made the revert cheap was the thing
that was never amended: *no `ghl_opportunity` table, no sales column on any EvalOS entity*. Unit 38
ends that. **This programme is therefore not reversible at the price the last one was**, and that
is the actual cost of the pivot — not the code.

---

## 3. What GHL actually provides

Verified 2026-09-10 against the live API surface, not assumed.

| Need | Endpoint | Scope | Have it? |
| --- | --- | --- | --- |
| Read opportunities by pipeline | `GET /opportunities/search` — filters `pipelineId`, `pipelineStageId`, `contactId`, `assignedTo`, `status` | `opportunities.readonly` | ✅ |
| Create / update opportunity | `POST /opportunities/`, `PUT /opportunities/{id}`, `PUT /opportunities/{id}/status` | `opportunities.write` | ✅ |
| Create / update contact | contacts endpoints | `contacts.write` | ✅ |
| Invoices with payment status | `GET /invoices/` — filters `contactId`, `status`; **`limit` and `offset` are REQUIRED** (omitting them is a 422, not a 400) | `invoices.readonly` | ✅ **granted, verified live 2026-09-11** |
| Meetings | `POST /calendars/events/appointments`, `PUT /calendars/events/appointments/{eventId}` | `calendars/events.write` | ❌ **ask** |
| Calendar list | calendars endpoints | `calendars.readonly` | ❌ **ask** |

**`pipelineId` is a server-side search parameter, and that matters for Unit 36.** The pipeline scope
is not merely an EvalOS filter over a fetched list — GHL never returns another pipeline's
opportunities in the first place. The EvalOS-side predicate stays anyway (defence in depth, and it
is what scopes the note table), but the network call is scoped too.

**Three OAuth scopes are an external ask** and belong in `00-build-plan.md`'s Step 0 table beside
the AWS credential. **Unit 41 is not blocked at all** — `invoices.readonly` was already granted, and the unit was exercised live on 2026-09-11.

---

## 4. The units

| # | Unit | Depends on | Ships |
| --- | --- | --- | --- |
| **36** | Pipeline-scoped access | 02, 03 | roles, `segment`, the pipeline column, `Tier.PIPELINE`, the brand ceiling, GM assignment routes |
| **37** | The GHL write door | 36 | `GhlHttp` gains `post`/`put`/`delete`; invariants 2 and 7 reversed in writing; `GhlHttpTest` rewritten |
| **38** | Opportunity reads and boards | 37 | `search-opportunity` by pipeline, the cache, the Marketing and Sales boards |
| **39** | Marketing lead desk | 38 | create and nurture contact + opportunity, valuations, notes |
| **40** | Sales desk | 38 | opportunity CRUD, notes, meetings, follow-ups |
| **41** | Client Portal invoices | 35 ✅ | `GET /invoices/` by contact, over Unit 35's party credential |

**Unit 41 is independent of 36–40 and should not wait for them.** Unit 35 shipped a portal
credential that names a `ghl_contact_id`, and that is exactly the key `GET /invoices/` takes. It is
**not blocked** — the grant was already in place, proven live 2026-09-11 — so it ran in parallel the moment that
scope lands.

**Unit 37 is its own unit, and folding it into 38 is the mistake to avoid.** It is one class gaining
three methods. It is separate because `GhlHttpTest` currently **fails the build** if `post` appears,
by design: *"the write capability is absent from the codebase, not merely unused."* Deleting that
guard as a step inside a feature unit is how a deliberate constraint disappears without anyone
deciding to remove it. Unit 37 is where somebody decides.

**PM, PC and CM need no change.** Their tiers (`TEAM`, `SELF`, `SELF`) already work and they act on
cases, which do not exist until payment. If they ever need to see an opportunity *before* the case
exists, that is a new unit, not a widened tier.

---

## 5. Open questions

| # | Question | Recommendation |
| --- | --- | --- |
| P1 | Does the GM's cross-brand board span every brand's pipelines or the selected brand's? | Moot while §1.5 holds — one selling brand. Unit 38 must state it anyway, because the brand switcher exists. **Recommend: the selected brand**, matching every other screen. |
| P2 | Do PM/PC/CM get pre-case opportunity visibility? | **Recommend no.** Zero work, and it keeps the case boundary at payment where invariant 8 puts it. |
| P3 | What happens to a note when its opportunity is deleted in GHL? | **Recommend: the note row survives**, orphaned and readable. Append-only truth (invariant 13) outranks tidiness, and a deleted opportunity is exactly when the history matters. |
| P4 | The three `evalos.ghl.*-pipeline-name` properties become duplicated truth once pipelines are keyed by id. | Already owned: Unit 38 supersedes `/sales/pipeline` and removes `sales-pipeline-name`; the two marketing properties go with the marketing board. |
