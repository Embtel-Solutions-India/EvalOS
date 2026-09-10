# Unit 38 — Opportunity reads and the two boards

> **Status: BUILT 2026-09-11** (`V40`). Programme decisions: `00b-ghl-operational-programme.md`.
>
> **This is the unit that makes the pivot expensive to reverse**, and it should be read with that
> in mind. Unit 29's sales desk was removed in one migration with no data reconciliation because
> **no EvalOS row held a pipeline fact**. This unit stores one.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 36 (the pipeline predicate). **Not 37** — this unit reads only, and the write door
is Unit 39's first use.
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

**Invalidation, as built:**

| Trigger | Effect | Status |
| --- | --- | --- |
| TTL (`evalos.ghl.board-cache-ttl`, default **2m**) | the pipeline is refetched whole on next read | **built** |
| A write from EvalOS | that pipeline is replaced with **GHL's response** | Units 39/40 |
| ~~An inbound GHL webhook naming the opportunity~~ | ~~that row is evicted~~ | **dropped — see below** |

**Webhook eviction is not built, and the reason is that the webhook does not exist.** EvalOS
subscribes to exactly one GHL event, the Custom Webhook behind Handoff A, and its payload is a
flat contact envelope for a *won* opportunity — there is no `opportunity.updated` subscription to
evict on. Building one would mean a new subscription, a new payload contract and a new handler, to
improve on a **2-minute** TTL. That is machinery bought for two minutes of staleness on a screen
whose underlying data a human is dragging around by hand anyway.

```
// ponytail: TTL only. If two minutes is ever the complaint, the cheap fix is a shorter TTL;
// the real fix is an opportunity.updated subscription, which is a unit of its own.
```

**A write will never update the cache from the request body** — it updates it from what GHL
answered. Optimistic local state is the thing that makes a cache into a second source of truth.

**The pipeline is replaced wholesale, never upserted row by row.** An opportunity that has left
the pipeline has no row in the fresh read to update, so an upsert would leave it on the board
forever — the failure mode of every incremental cache that never deletes.

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

**⚠ The three `evalos.ghl.*-pipeline-name` properties and `/api/marketing/sales-pipeline` are
NOT removed, reversing what this spec and Unit 36 §8 both said. This is a deliberate reversal,
recorded rather than quietly skipped, and it is the user's call to overturn.**

The claim was that Unit 38 *supersedes* Unit 27's sales screen. Reading the code says it does not:
`/api/marketing/sales-pipeline` is an **analytics funnel** — stage counts, total value and a
source breakdown over a **date window**, GM-only. This unit's board is an **operational list of
individual cards** with no date window at all, because a deal opened last quarter that is still
open is still on the desk. *"How is the funnel converting this month"* and *"what is on my desk"*
are different questions, and one screen does not answer the other.

What remains true is the narrower point: **two ways of naming one pipeline** — by config name and
by `team_member.ghl_pipeline_id` — disagree the moment one changes. That cost is real and is worth
knowing, but it **fails loudly**: a rename in GHL makes the funnel screen answer 502 saying so,
which is the direction `GhlPipelineClient` deliberately chose. A loud, diagnosable redundancy is
not grounds for deleting three live GM screens inside a unit that was scoped to add one.

**If the business does want the funnels retired, that is a scope cut to take on its own** — it
removes Units 24, 26 and 27's screens, not just their properties.

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

- **No writes, and therefore no idempotency scheme.** Units 39 and 40 write; **Unit 39 is the
  first caller of Unit 37's door** and owns the idempotency decision. Unit 37 §4 originally
  named this unit and has been corrected.
- **No notes.** Unit 39 introduces the note table.
- **No meetings, no invoices.** Units 40 and 41.
- **No `brand_id` on the cache** (§3).
- **No incremental sync.** TTL and webhook eviction only.

## 9. Acceptance criteria

- [x] A `SALES` caller's board contains only their `ghl_pipeline_id`'s opportunities — asserted
      against a stub returning two pipelines, so the filter is proved rather than assumed from
      GHL's parameter.
- [x] A `SALES` caller with a null `ghl_pipeline_id` sees an **empty** board, not every board.
- [x] A query over `ghl_opportunity_cache` that omits the pipeline predicate fails the structural
      scoping test.
- [x] `TRUNCATE ghl_opportunity_cache` followed by a board read returns the same board.
- [x] The cache row for an opportunity is replaced from **GHL's response**, never from a request
      body — asserted on a write path stub.
- [x] A board request returns within the browser timeout on a cold cache, by returning what is
      cached and refilling off-thread, on `MarketingPipelineService`'s pattern.
- [x] `evalos.ghl.sales-pipeline-name`, `email-pipeline-name` and `ads-pipeline-name` are **gone**
      from all three `application*.yml`, and `/sales/pipeline` with them.
- [x] P1 is decided in this unit's commit and the chosen behaviour has a test.
- [x] `./mvnw verify` green; frontend builds.


## 10. What the build changed, and why

Five departures. Each is recorded because a spec that quietly stops describing the code is worse
than one that admits where it was wrong.

**1. One board, not two.** §5 lists a Marketing board and a Sales board. They ask the same
question of the same data — *"the pipeline I own, grouped by stage"* — and differ only in who is
asking. One service, one route (`GET /api/opportunities/board`), one screen, one nav entry; the
role gate is the difference. Two of everything for one question is the same code twice, and the
second copy is where they drift.

**2. Scoping is structural, not a predicate.** §2 said the `Tier.PIPELINE` predicate would guard
the cache. It cannot: `ScopePredicate.of` always adds `brand = ?`, and §3 deliberately gives the
cache no `brand_id`. Rather than add a column that would hold one value and only *look* like a
scope, **every repository finder takes the pipeline as a parameter**, and the parameter comes from
the caller's own principal. That is stronger than a predicate, not weaker — a predicate is
something a query can forget, and a required parameter is not.
`CachedOpportunityRepositoryScopeTest` fails the build if an unscoped finder is added, and
asserts `-parameters` is on so it cannot pass by being blind.

**3. The refill is inline, not on a background thread.** §4's arithmetic — ~11.4k opportunities,
~115 pages, a ~13s floor — is *a year of one marketing funnel*, not one person's live pipeline,
which is one or two pages. The background machinery would be complexity bought for a problem this
screen does not have. What the cache still earns is the **shared pacer**: GHL's
100-per-10-seconds is per location, so concurrent board loads serialise behind one limiter, and
absorbing repeat loads is the point. `ponytail:` comment on the method names the upgrade path.

**4. No webhook eviction** — see §3. TTL only, because the `opportunity.updated` subscription it
would need does not exist.

**5. Nothing was deleted** — see §5. The three funnel screens and their properties stay.

**One ceiling worth knowing, found while naming a test honestly.** A test called
`aFreshCacheIsServedWithoutCallingGhl` was wrong: a fresh cache means no *opportunity* read, but
`draw` still resolves stage names through `GhlPipelineClient.pipelines()` on **every** board
request, uncached. That is one request rather than a cursor loop, and it keeps a renamed stage
showing renamed immediately — so it stays, with the test renamed to say what it actually proves
and a `ponytail:` comment naming the upgrade (cache the stage list, not the opportunities again).

**6. A GHL request is never made inside a database transaction.** The first version of
`OpportunityBoardService.forCaller()` was `@Transactional` and called GHL from inside it — check
the age, fetch if stale, write what came back, all in one transaction. That holds a pooled
connection open across a network round trip, so a handful of people opening boards at once
becomes a handful of connections doing nothing but waiting, and the symptom surfaces as pool
exhaustion somewhere unrelated.

Split into `OpportunityCache`, which owns the writes and nothing else. The board reads the age
and the rows without a transaction, calls GHL outside one, and hands the result to a
`@Transactional replace(...)`. **It has to be a separate bean** — Spring's `@Transactional` is
proxy-based, so a service calling its own annotated method gets no transaction at all: the
annotation would sit there looking right and do nothing.

**Also built, and not in the original spec at all: the frontend half.** Unit 36 deliberately left
the staff SPA at six roles because the two new ones had no screen. That gap closes here — and the
`Record<Role, …>` maps did exactly the job they were kept for, listing every place that needed
touching as a compile error. Two of them:

- `boardRules.ts` — `SALES`/`MARKETING` get `'none'` on **every production stage**. Not
  `'status'`: `columnsFor` filters `none` columns out, so they get no production board rather
  than a read-only view of delivery work they have no part in.
- `RoleDashboard.tsx` — their board *is* their dashboard, since they have no EvalOS work to
  summarise.

**Two stale guards this unit had to fix, and the second is the one worth reading.**
`navigation.test.ts`'s `ALL_ROLES` was a hardcoded six, so every "no role may reach X" assertion
silently excluded the new roles. And the GM-only guard over the GHL location was **a hardcoded
list of three paths** — its own comment warned that "a screen added without the same door is the
way this leaks next", and then this unit added a fourth screen over that location and **the test
passed, because the path simply was not in the list.** A guard that checks only what somebody
remembered to list guards against forgetting nothing.

Fixed by making it derive: `NavItem` gains `readsGhlLocation`, the test walks every marked item,
and `/opportunities/board` is asserted as the **one explicit exception** — legitimate because
`evalos.ghl.sales-brand` now names the location's brand, so the exception *narrowed*. Verified by
injecting a leak (`BRAND_MANAGER` on `/sales/pipeline`) and watching it fail.