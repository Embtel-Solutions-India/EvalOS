# Unit 39 — The marketing lead desk

> **Status: BUILT 2026-09-11** (`V41`). Programme decisions: `00b-ghl-operational-programme.md`.
>
> **Scoped, not detailed.** `CLAUDE.md` generates a unit's full spec just before it is built, and
> Units 38's boards will change what the right screens are. What is fixed here is the **contract**:
> what this unit owns, what it must not do, and the invariant it amends. Screen-level detail is
> written when it is built.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 37 (the write door), 38 (the board it edits)
**Unlocks:** 40 shares the note table introduced here
**Migration:** `V41`
**Gating open questions:** P3 (`00b` §5)

---

## 1. What changes, in one paragraph

A `MARKETING` employee creates and nurtures leads without opening GHL: create a contact, create an
opportunity on **their own** pipeline, edit lead fields, record a **valuation**, and write **notes**.
Contacts and opportunities are written **to GHL** and read back from GHL's response. Notes are
**EvalOS rows**. This is the unit that amends invariant 7.

## 2. Invariant 7 is amended here, and only in one clause

Invariant 7 today says two things. **Only the first changes.**

**Changes:** *"Contact data is a read-only, brand-tagged snapshot synced from GHL and is never
mutated."* Marketing creating and editing leads writes contacts to GHL. The snapshot stops being
read-only.

**Does not change, and is load-bearing for this programme:**

> `ghl_contact_id` is the canonical external client identity — everywhere. EvalOS never mints one
> [...] Three identifiers, never conflated: `ghl_contact_id` = the client; `ghl_opportunity_id` =
> one purchase; `evalos_case.id` = one service engagement.

**EvalOS still never mints a `ghl_contact_id`.** It asks GHL to create a contact and GHL returns
the id. That distinction is the whole reason the amendment is narrow: the identity authority does
not move, only the write direction.

**The amended clause must say what "mutated" now means**, or it will be read as licence to store
contact fields: EvalOS may **ask GHL to change** a contact and must **display what GHL returns**.
It still holds no authoritative contact field of its own.

## 3. The note table

Introduced here, shared with Unit 40.

```sql
CREATE TABLE opportunity_note (
    id                 uuid PRIMARY KEY,
    ghl_opportunity_id text NOT NULL,
    brand_id           uuid NOT NULL REFERENCES brand(id),
    ghl_pipeline_id    text NOT NULL,
    author_id          uuid NOT NULL REFERENCES team_member(id),
    body               text NOT NULL,
    created_at         timestamptz NOT NULL
);
```

**Keyed on `ghl_opportunity_id`, not `ghl_contact_id`** — `00b` §1.4 carries the argument, and it
is the one decision in this programme most likely to be "simplified" back into a bug. A repeat
client is one contact and two opportunities.

**`ghl_pipeline_id` is denormalised onto the row** so `Tier.PIPELINE` can scope notes without
joining the cache. The cache is droppable (Unit 38 §3); a scope predicate that depends on a
droppable table is a scope that fails open when the table is empty. **`brand_id` is carried for
the same reason every EvalOS row carries it** — the note is an EvalOS row, whatever the
opportunity is.

**Append-only.** No update path, no delete path, on invariant 13's pattern and enforced the way
`audit_entry` is — a trigger, not a convention. A correction is a new note.

**P3 (`00b` §5): a note outlives its opportunity.** If the opportunity is deleted in GHL the row
stays, orphaned and readable. Append-only truth outranks tidiness, and a deleted opportunity is
exactly when the history matters.

## 3a. Idempotency — decided here, because this is the first caller

Unit 37 deferred this to the first caller of the write door. That is this unit.

**There is no idempotency key to send, and inventing one would have been the wrong answer.** GHL's
operation metadata marks every write `idempotencyRequired: true`, but the request schemas carry
**no `Idempotency-Key` header and no client-token field** — verified against the live API surface
2026-09-10. The flag is a warning not to blindly retry, not a protocol feature.

**What GHL offers instead is upsert, keyed on data it already owns**, and it is a better answer
than a key would have been:

| Need | Endpoint | Dedupes on | Tells you which happened |
| --- | --- | --- | --- |
| Create a lead | `POST /contacts/upsert` | the location's *Allow Duplicate Contact* setting — email, then phone | the returned contact |
| Open an opportunity | `POST /opportunities/upsert` | `contactId` + `pipelineId` | **`new: true/false`** |

**So the rule for this unit: creates go through upsert, never through `POST /opportunities/` or
`POST /contacts/`.** A marketer who double-submits gets one lead, and the response says whether
anything was created.

**Two limits, stated because they are real:**

1. **Opportunity upsert means one open opportunity per contact per pipeline.** That is correct
   for a *marketing* pipeline — a lead is a lead. It is **not** correct for a repeat client
   buying a second evaluation, which is exactly the case invariant 7's three-identifier rule
   exists for. **Unit 40 must not reuse this path for a genuine second deal**; the escape hatch
   is `POST /opportunities/`, and taking it brings the duplicate risk back, knowingly.
2. **Contact dedupe depends on a GHL location setting EvalOS does not control.** If *Allow
   Duplicate Contact* is switched on over there, upsert stops merging and two leads with one
   email become two contacts. EvalOS cannot detect or prevent that, and must not pretend to.

**Retries remain forbidden** (Unit 37 §7). Upsert makes a *repeated user action* safe; it does
not make an automatic retry safe, because a retry after an ambiguous failure can still race a
first attempt that succeeded.

## 4. Valuation

A valuation is the marketer's estimate of what the lead is worth. **It is GHL's `monetaryValue` on
the opportunity, not an EvalOS column** — GHL has the field, `00b` §1.3 says GHL owns every field
GHL has, and a parallel EvalOS estimate is two numbers that disagree.

**If the business needs a valuation history**, that is notes (§3) or a new decision in `00b` —
not a column added quietly here.

## 5. What this unit deliberately does not do

- **No sales screens.** Unit 40, even though it shares the note table.
- **No promotion to a sales pipeline.** **GHL's workflows do that**, on qualification, and that is
  the whole reason this design works: the handoff is automation the business already owns. EvalOS
  must not offer a "move to sales" button — it would be a second promotion path racing GHL's.
- **No case creation.** Invariant 8, untouched: a case is born of a won opportunity through
  Handoff A.
- **No email or nurture sending.** Invariant 14 stands — GHL's campaigns do it. See `00b` §2 for
  why scheduling and follow-ups do not breach it and composing a message would.
- **No valuation column** (§4).

## 6. Acceptance criteria

- [x] A `MARKETING` caller can create a contact and an opportunity on **their own** pipeline; the
      created ids come from GHL's response and EvalOS mints neither.
- [x] Creating an opportunity on a pipeline the caller does not own answers **403**.
- [x] A note is scoped by pipeline **and** brand; a caller reads no note from another pipeline.
- [x] `opportunity_note` has no update and no delete path, enforced by trigger.
- [x] A note survives its opportunity vanishing from the cache and from GHL (P3).
- [x] Every contact and opportunity write produces an audit row (Unit 37 §5's structural test).
- [x] `architecture.md` invariant 7's first clause is amended **and the three-identifier clause is
      verbatim unchanged** — asserted by review, and by the fact that no code mints a contact id.
- [x] The `.serena/memories/` entry stating the old invariant 7 is **edited**, not supplemented.
- [x] `./mvnw verify` green; frontend builds.


## 7. What the build added, and the two bugs the tests caught

**The scope check in front of every write is the cache, not GHL, and the failure mode is
deliberate.** `MarketingLeadService.requireInMyPipeline` asks `ghl_opportunity_cache` whether the
opportunity is in the caller's pipeline. The cache is droppable, so a real-but-unfetched
opportunity is **refused** — a false negative. That is the correct direction: a refusal is visible
and recoverable (the board refreshes, the write succeeds), while trusting an unverified id would
let a caller reach another desk's deal by guessing one. Asking GHL instead would be a second round
trip on every write against a 100-per-10-seconds budget, to close a gap the board read has already
closed for anything the caller can actually see.

**A lead needs an email or a phone, and the reason is idempotency rather than data quality.** GHL
matches an existing contact on email, then phone. With neither there is nothing to match on, so
the upsert chosen precisely for its idempotency silently stops being idempotent and every save
creates another contact. Refused in the service, and asked for in the form.

**`audit_event.object_id` is a UUID and a GHL id is a string.** Widening an append-only table
whose trigger refuses `UPDATE` is the most expensive migration in the schema, to store a foreign
id that is already recorded verbatim in `after_snapshot`. So the key is **derived**
(`UUID.nameUUIDFromBytes`), which keeps `idx_audit_object` answering "this deal's history". It is
an audit key, not an identity: nothing outside that method sees it, no column stores it, and
invariant 7's "EvalOS never mints one" is untouched.

### Two bugs the tests caught, both of which would have shipped silently

**1. The audit keys collided.** The first version hashed `"ghl:" + id` for both contacts and
opportunities — an identical prefix — so a contact and an opportunity sharing an id hashed to the
same `object_id` and their histories merged. **The javadoc claimed the prefix prevented exactly
that.** It did not; the object type does. Caught by
`GhlLeadClientTest.contactAndOpportunityAuditKeysDoNotCollide`.

**2. GHL's `new` flag never bound.** The JSON key is `new`, which is a Java keyword, so the record
component is `isNew` — and without `@JsonProperty("new")` Jackson found nothing and left it null.
Every upsert would have reported "matched an existing deal" regardless of what GHL said: a wrong
audit action and a wrong message to the marketer, with **nothing failing**.

### And one lesson about testing an append-only table

`notesAreScopedWithoutTouchingTheCache` passed on its first run and failed on its second. Nothing
was flaky: `opportunity_note` is append-only by trigger, so a test row **can never be cleaned
up**, and `evalos_test` persists between runs — so a count over a fixed pipeline id grew every
time. The append-only property was working exactly as designed; the test was wrong to assume an
empty table. Every note test now generates ids unique to its run, and the fix was verified by
running the suite twice.

### The screens

`MARKETING` gets a **New lead** form above the board, and every card expands to its note stream.
Notes load per card on expand rather than eagerly — a stream per card would be one request per
deal against the shared budget, to show text nobody has asked to read. Opening a lead **refetches
the board** rather than inserting a card locally: the opportunity now lives in GHL, and the only
honest confirmation is reading it back.

**Sales does not see the form.** It shares the note table and works the same opportunities, but
*opening* a lead is a marketing act — widening the route is Unit 40's argument to make, not a
convenience to add because the two desks look similar.