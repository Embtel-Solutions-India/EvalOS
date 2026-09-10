# Unit 39 — The marketing lead desk

> **Status: SPECCED 2026-09-10, not built.** Programme decisions: `00b-ghl-operational-programme.md`.
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

- [ ] A `MARKETING` caller can create a contact and an opportunity on **their own** pipeline; the
      created ids come from GHL's response and EvalOS mints neither.
- [ ] Creating an opportunity on a pipeline the caller does not own answers **403**.
- [ ] A note is scoped by pipeline **and** brand; a caller reads no note from another pipeline.
- [ ] `opportunity_note` has no update and no delete path, enforced by trigger.
- [ ] A note survives its opportunity vanishing from the cache and from GHL (P3).
- [ ] Every contact and opportunity write produces an audit row (Unit 37 §5's structural test).
- [ ] `architecture.md` invariant 7's first clause is amended **and the three-identifier clause is
      verbatim unchanged** — asserted by review, and by the fact that no code mints a contact id.
- [ ] The `.serena/memories/` entry stating the old invariant 7 is **edited**, not supplemented.
- [ ] `./mvnw verify` green; frontend builds.
