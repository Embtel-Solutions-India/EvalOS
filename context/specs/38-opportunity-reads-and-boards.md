# Unit 38 — Opportunity reads and the two boards

> **Status: SPECCED 2026-09-10, not built.** Programme decisions: `00b-ghl-operational-programme.md`.
>
> **This is the unit that makes the pivot expensive to reverse**, and it should be read with that
> in mind. Unit 29's sales desk was removed in one migration with no data reconciliation because
> **no EvalOS row held a pipeline fact**. This unit stores one.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 36 (the pipeline predicate), 37 (the write door — for the idempotency decision it
inherits, not because this unit writes)
**Unlocks:** 39, 40
**Migration:** `V40`
**Gating open questions:** P1 (see §7)

---

## 1. What changes, in one paragraph

Opportunities become readable in EvalOS. A `GhlOpportunityClient` wraps `GET
/opportunities/search`, scoped by the caller's `ghl_pipeline_id`; a **non-authoritative cache**
holds the result so a board paints inside the browser's timeout; and two screens read it — the
**Marketing board** and the **Sales board**, each showing exactly the caller's own pipeline. The
GM sees the union.

## 2. The scope is enforced twice, on purpose

`GET /opportunities/search` takes **`pipelineId` as a server-side parameter**. So GHL never returns
another pipeline's opportunities in the first place — the network call is already scoped.

The EvalOS-side `Tier.PIPELINE` predicate from Unit 36 **stays anyway**, for two reasons that are
not "defence in depth" hand-waving:

1. **The cache is an EvalOS table** and is read with a query, not an HTTP call. A cached row is
   reachable by any query that forgets to scope, which is the ordinary bug CLAUDE.md's first rule
   exists for.
2. **The note table (Unit 39/40) is scoped by the same predicate**, and it has no GHL side at all.

**The failure mode to avoid** is concluding that because GHL filters, EvalOS need not. That
reasoning holds only for the live call and silently stops holding the moment anything is stored —
which is this unit.

## 3. The cache

```sql
CREATE TABLE ghl_opportunity_cache (
    ghl_opportunity_id text PRIMARY KEY,
    ghl_pipeline_id    text NOT NULL,
    ghl_contact_id     text NOT NULL,
    stage_id           text NOT NULL,
    status             text NOT NULL,
    name               text,
    amount             numeric(12,2),
    currency           text,
    updated_in_ghl_at  timestamptz,
    fetched_at         timestamptz NOT NULL
);
```

**Every column is a field GHL owns.** That is the rule that keeps this from becoming a second CRM,
and it is checkable: if a column appears here that GHL has no field for, `00b` §1.3 is void and the
truth model gets re-argued. There is deliberately **no `brand_id`** — an opportunity belongs to a
GHL location, and while `00b` §1.5 holds there is exactly one selling brand. Adding `brand_id`
here would be a column with one value pretending to be a scope.

**Droppable without loss.** `TRUNCATE ghl_opportunity_cache` costs a refill and nothing else. No
write path reads it to decide anything; no screen shows a field that exists only here.

```
// ponytail: TTL + webhook invalidation, no incremental sync. If staleness becomes the
// complaint, the upgrade is a delta poll on updated_in_ghl_at, not a bigger cache.
```

**Invalidation, in order of authority:**

| Trigger | Effect |
| --- | --- |
| A write from EvalOS (Units 39, 40) | that row is replaced with **GHL's response**, synchronously |
| An inbound GHL webhook naming the opportunity | that row is evicted |
| TTL | the row is refetched on next read |

**A write never updates the cache from the request body** — it updates it from what GHL answered.
Optimistic local state is the thing that makes a cache into a second source of truth.

## 4. Why a cache exists at all

Invariant 2's warning is explicit: *"The day EvalOS stores a pipeline fact, two systems own it and
this invariant is gone."* This unit does it anyway, and the reason is arithmetic rather than
preference — the same arithmetic invariant 6 already records for `MarketingPipelineService`:

- GHL allows **100 requests per 10 seconds per location**.
- A year is **~11.4k opportunities** across **~115 cursor pages** that cannot be parallelised.
- That is a **~13s floor**, past the browser's 15s timeout.

A pass-through board is not slow; it does not load. The cache is the smallest thing that fixes
that, and §3 is how it is kept from growing into something else.

## 5. The two boards

| Screen | Who | Shows |
| --- | --- | --- |
| Marketing board | `MARKETING` | their own pipeline, by stage |
| Sales board | `SALES` | their own pipeline, by stage |
| Either | `GM` | the union of every active member's pipeline (see §7) |

Both are read-only in this unit. Editing is Units 39 and 40.

**Stage names come from GHL**, resolved through `GhlPipelineClient`, never stored. A stage renamed
in GHL shows renamed; a stage renamed in GHL does not change anyone's access, because access is
keyed on the pipeline id (Unit 36 §4).

**`/sales/pipeline` and `evalos.ghl.sales-pipeline-name` are removed in this unit**, as Unit 36 §8
records. They are the by-name duplicate of the by-id mapping and they disagree the moment one
changes. The two marketing properties go the same way when the marketing board lands — which is
this unit, so **all three properties leave together.**

## 6. Invariant impact

- **1** — the cache carries no `brand_id` and is reachable only by roles bound to
  `evalos.ghl.sales-brand` (Unit 36 §4a). The ceiling is what makes that safe; when Unit 25 lands,
  this table gains a brand column and this bullet is rewritten.
- **2** — already dead as of Unit 37. **This unit is where it becomes expensive**, per the header.
- **6** — the cache refill runs off the request thread, on the pattern `MarketingPipelineService`
  already established and that invariant 6 already carries an exception paragraph for. It writes
  no lifecycle row and loses nothing on failure, so it does not belong in `job`.
- **8** — untouched. A won opportunity still creates the case through Handoff A. **The cache must
  not become an alternative intake path**, and `DomainInvariantsTest` already refuses that shape.
- **13** — reads are not audited. Nothing here mutates.

## 7. Open question P1 — the GM's union

`Tier.ALL` short-circuits to `conjunction()`, so "every sales pipeline" is a **query**, not a
predicate: the ids of active `SALES`/`MARKETING` members. The question the brand switcher forces is
*whose* — every brand's, or the selected brand's.

**Moot in practice while `00b` §1.5 holds** — one selling brand means one answer. **It must still
be decided and coded**, because a screen whose behaviour is undefined for a state the UI can reach
is a bug waiting for brand two.

**Recommendation: the selected brand**, matching every other screen in the app, with the union
falling back to the configured sales brand when no brand is selected.

## 8. What this unit deliberately does not do

- **No writes.** Units 39 and 40. This unit consumes Unit 37's door only in the sense of inheriting
  its idempotency question.
- **No notes.** Unit 39 introduces the note table.
- **No meetings, no invoices.** Units 40 and 41.
- **No `brand_id` on the cache** (§3).
- **No incremental sync.** TTL and webhook eviction only.

## 9. Acceptance criteria

- [ ] A `SALES` caller's board contains only their `ghl_pipeline_id`'s opportunities — asserted
      against a stub returning two pipelines, so the filter is proved rather than assumed from
      GHL's parameter.
- [ ] A `SALES` caller with a null `ghl_pipeline_id` sees an **empty** board, not every board.
- [ ] A query over `ghl_opportunity_cache` that omits the pipeline predicate fails the structural
      scoping test.
- [ ] `TRUNCATE ghl_opportunity_cache` followed by a board read returns the same board.
- [ ] The cache row for an opportunity is replaced from **GHL's response**, never from a request
      body — asserted on a write path stub.
- [ ] A board request returns within the browser timeout on a cold cache, by returning what is
      cached and refilling off-thread, on `MarketingPipelineService`'s pattern.
- [ ] `evalos.ghl.sales-pipeline-name`, `email-pipeline-name` and `ads-pipeline-name` are **gone**
      from all three `application*.yml`, and `/sales/pipeline` with them.
- [ ] P1 is decided in this unit's commit and the chosen behaviour has a test.
- [ ] `./mvnw verify` green; frontend builds.
