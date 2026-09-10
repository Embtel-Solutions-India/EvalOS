# Unit 36 — Pipeline-scoped access

> **Status: SPECCED 2026-09-10, not built.** The first unit of the direction change taken on
> 2026-09-10: **EvalOS becomes the primary operational system for sales and marketing**, with GHL
> as the backend data layer. This unit builds the access model that every later one reads, and
> **nothing else** — no board, no screen, no GHL write.
>
> **It is a pivot, and pivots are specced before they are coded.** `CLAUDE.md` opens with "EvalOS
> never does marketing, sales, or invoicing". That sentence dies across this programme. It does
> **not** die here: this unit only decides who may see what. **Invariant 2 stays alive until Unit
> 37**, which is where the first write verb lands.
>
> **Amended 2026-09-10** against the business's answers on the three sales/marketing kinds, the
> truth model and the brand ceiling. Three changes: the `segment` column (§3), the single-brand
> ceiling (§4a), and the Unit 37 renumbering above. The programme-level decisions this unit
> inherits live in **`00b-ghl-operational-programme.md`** — read it first; if this file and that
> one disagree, that one wins.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 02 (the RBAC spine this widens), 03 (`team_member`), 24 (`GhlPipelineClient`, for
the pipeline list the GM picks from)
**Unlocks:** 37 (the write door), 38 (the boards), and every later unit that scopes by pipeline
**Migration:** `V39` (latest applied is `V38`)
**Gating open questions:** none

---

## 1. What changes, in one paragraph

Two roles come back — `SALES` and `MARKETING` — and `team_member` gains **`ghl_pipeline_id`**: the
one GHL pipeline that employee owns. That column is the whole access predicate. A sales employee
sees their pipeline and nothing else; the GM sees the union of every sales pipeline (§5 — which
brands that union spans is Unit 38's to settle, not this unit's).
No opportunity-level assignment check, no `ghl_user_id`, no per-row ownership — **the pipeline is
the separator**, because that is how the business already works: the pipelines in GHL today are
named `Shivangi's Email Marketing` and `Aditya's pipeline`.

## 2. Why not what Unit 29 built

Unit 29 mapped a `team_member` to a **GHL user** (`ghl_user_id`) and filtered opportunities by
assignee. `V30` dropped it eight days later with the unit. This is deliberately the coarser model:

| | Unit 29 | Unit 36 |
|---|---|---|
| Key | GHL user id | GHL pipeline id |
| Predicate | opportunity's assignee == me | opportunity's pipeline == mine |
| Stale when | anyone is reassigned in GHL | a pipeline is deleted or reassigned |
| Reads needed | the assignee of every opportunity | one id off the caller's own row |

Assignments change daily and a stale assignment silently shows one salesperson another's work.
Pipeline ownership changes when somebody joins or leaves. **The coarser key is the one that stays
true**, and it is also the one the business already maintains by hand.

## 3. The two roles

`Role` gains `SALES` and `MARKETING`. Both are **brand-locked** and both carry a new tier.

```java
SALES(Tier.PIPELINE),
MARKETING(Tier.PIPELINE),
```

**`Tier.PIPELINE` is a new tier, not a reuse of `SELF`.** `SELF` means "rows that name me in an
assignee column" and is about `evalos_case`; there is no assignee column on the thing these roles
read. Reusing it would make `ScopePredicate` answer a question the schema never asked, which is
the failure mode `Fields.unteamedVisible` already has a paragraph about.

**`seesCaseContent()` returns true for both**, and that is a decision rather than a default: a
salesperson works with the client directly and the client's name is the thing they work *with*.
The supply-side withholding exists for the Expert Network Manager, who must reach the row without
learning who it is about. That reasoning does not transfer.

**Neither role gets a case transition.** They act on opportunities, which are GHL's; the case does
not exist until payment (invariant 8, untouched). A sales employee who needs a case moved is
looking at the wrong screen.

### 3a. The three kinds are a column, not three roles

The business has **three kinds of each** role — Attorney, Employer/Firm, Individual. They are
**not** six enum values, because they carry **identical permissions**: each sees their own pipeline
and does the same things with it. What differs is which clients they handle, and GHL's workflows
do that routing.

```sql
ALTER TABLE team_member ADD COLUMN segment text;

CONSTRAINT team_member_segment_matches_role CHECK (
    (role IN ('SALES', 'MARKETING') AND segment IN ('ATTORNEY', 'EMPLOYER_FIRM', 'INDIVIDUAL'))
    OR (role NOT IN ('SALES', 'MARKETING') AND segment IS NULL))
```

**Nothing in the codebase branches on `segment`.** It is carried for display and reporting, and
that is the whole of its meaning. Six roles would have grown every `switch (role)`, the
`team_member_role_valid` CHECK, the nav tests and the permission matrix by six to express a
distinction that changes no permission — an org chart encoded in a security enum.

**It must never acquire an access meaning.** The day a segment needs a different permission it is
a new role, argued in `00b` first. A `segment` that starts filtering rows is the same drift
`Fields.unteamedVisible` has a paragraph about, and the CHECK above is deliberately shaped like
§4's — both directions — for the reason V29/V30 recorded.

## 4. The column, and what it is keyed on

```sql
ALTER TABLE team_member ADD COLUMN ghl_pipeline_id text;
```

**The id, not the name — and this is the one place that departs from `evalos.ghl.*`.** Those three
config properties match a pipeline **by name**, on the stated reasoning that a name is what a
provisioner can see in GHL and a rename should break the view loudly. That is right for a
dashboard and wrong here: this is an **access key**, and an access key that breaks on a rename
locks an employee out of their own work. A 502 on a GM's funnel chart is a bad afternoon; a
salesperson who cannot see their pipeline is a stopped desk.

The name is never stored. The board shows whatever name GHL returns for the id it holds, so a
rename in GHL appears on the screen and changes nothing about access.

**The CHECK is worth having, and it says both halves:**

```sql
CONSTRAINT team_member_pipeline_matches_role CHECK (
    (role IN ('SALES', 'MARKETING') AND ghl_pipeline_id IS NOT NULL)
    OR (role NOT IN ('SALES', 'MARKETING') AND ghl_pipeline_id IS NULL))
```

Both directions, deliberately. A sales row without a pipeline is a person who can see nothing; a
Case Manager *with* one is a column whose meaning has started to drift. **This is the mistake V29
made and V30 recorded**: it widened `team_member_brand_required` for a role whose NULL meant the
opposite of the existing NULL, leaving one column with two readings.

`team_member_brand_required` needs **no change** — only the GM may have a null brand, and neither
new role is cross-brand. One fewer constraint rewrite than V29 needed.

`team_member_role_valid` is rewritten for the third time. It has to be: it is the writer the enum
cannot reach — a seed, a hand-run UPDATE — and a row holding a role `Role.valueOf` cannot express
breaks everything that reads it.

**One live pipeline per employee, and one employee per pipeline:**

```sql
CREATE UNIQUE INDEX uq_team_member_pipeline
    ON team_member (ghl_pipeline_id)
    WHERE ghl_pipeline_id IS NOT NULL AND active;
```

**Globally unique, and this is the one index in the schema that is deliberately not brand-scoped.**
Everything else here leads with `brand_id` because EvalOS rows belong to a brand. A GHL pipeline
does not: `evalos.ghl.location-id` is a single global setting with no link to a brand, which
invariant 1 already records as its one stated exception. There is one GHL location, so there is
one pipeline namespace — and a pipeline id that appeared under two brands would not be two
pipelines, it would be **the same pipeline read by two people who cannot see each other**. Adding
`brand_id` to this index would make that silently legal.

Partial on `active` so a leaver's row does not block their replacement being given the same
pipeline. **Two live holders would silently mean "sees each other's work"** — the constraint is
what stops that being a typo.

## 4a. The single-brand ceiling, enforced rather than documented

`SALES` and `MARKETING` are brand-locked. EvalOS talks to **one** GHL sub-account, named by the
global `evalos.ghl.location-id`, which has **no link to a brand** — invariant 1's one stated
exception, held today by making every screen over that location **GM-only**.

That guard does not survive contact with a brand-locked role. The moment a `SALES` member reads
that location, a single-brand role is reading a location EvalOS cannot attribute to a brand, and
the exception's whole argument ("EvalOS cannot tell whose, so only the cross-brand role may look")
is gone.

**So the brand is named in configuration and the mismatch is refused:**

```yaml
evalos:
  ghl:
    sales-brand: ${GHL_SALES_BRAND_ID:}   # the brand that owns location-id
```

Assigning `SALES` or `MARKETING` to a member of any other brand answers **400 at assignment
time**, from the route in §7. Not a silent empty board: a salesperson who can see nothing looks
identical to a salesperson whose pipeline is misconfigured, and this is the failure §7's GET
already exists to prevent.

```
// ponytail: one GHL location, so one selling brand. Unit 25 (location + token per brand)
// is the upgrade path, needed the day a second brand sells.
```

**This narrows invariant 1's exception rather than widening it** — from "a location nobody may be
told the brand of" to "a location whose brand is named in configuration". Unit 25 closes it
entirely; until then the ceiling is real, enforced, and has a stated trigger.

**Why not Unit 25 now.** Per-brand OAuth is agency authorization, a token store, refresh handling
and a GHL marketplace app registered before any code runs — an external ask and a unit in front of
a six-unit programme, serving a second selling brand that does not exist. Rejected as premature,
with the trigger written down.

## 5. The predicate

`Tier.PIPELINE` adds one equality, exactly parallel to `TEAM`:

```java
case PIPELINE -> {
    if (fields.pipeline() != null && ctx.ghlPipelineId() != null) {
        predicates.add(cb.equal(root.get(fields.pipeline()), ctx.ghlPipelineId()));
    } else {
        return cb.disjunction();   // fail closed
    }
}
```

**Fail closed on a null**, matching `ScopePredicate`'s existing rule that a brand-locked caller
with no brand matches nothing rather than everything. A `SALES` principal minted before this
column existed carries no pipeline and sees an empty board — one re-login fixes it, and that is
the safe direction to be wrong in (`V37`'s lesson, applied to a second column).

`Fields` gains a nullable `pipeline` attribute name alongside `team`. **No production entity uses
it in this unit** — the opportunity work product that will is Unit 38's. The axis ships now so
that Unit 38 adds a table rather than a scoping model.

That leaves the predicate with nothing real to filter, and it must still be proved. **A
test-only `@Entity` carrying a brand and a pipeline column**, in the test source tree and mapped
only for the scoping tests, is how — the same shape `ScopePredicate`'s existing tests use for the
axes they exercise. Asserting the built `Specification` by inspection instead would test that the
code says what it says; running it against a real query tests that Hibernate agrees.

**The GM's union is not a predicate.** `Tier.ALL` short-circuits to `conjunction()` and always
has. "Every sales pipeline" is a *query* — the ids of active `SALES` members — and it belongs to
the board that asks it (Unit 38), not to the scope builder.

**Which brands that union covers is Unit 38's question, not this one**, and it is a real one: the
GM is cross-brand (`brandId` is null) but the app has a brand switcher, so "every sales pipeline"
means *every brand's* or *the selected brand's* depending on a choice nobody has made yet. This
unit deliberately does not decide it, because nothing here reads a pipeline. Unit 38 must.

## 6. Carrying it on the principal

`ghlPipelineId` joins `StaffPrincipal`, the JWT claims, and `TenantContext`:

```java
public record TenantContext(UUID memberId, Role role, UUID brandId, UUID teamId, String ghlPipelineId)
```

**On the token rather than a lookup per request**, for the reason `teamId` is: a scope predicate
that needs a database read to build is a read that happens on every scoped query. The cost is that
a pipeline reassignment does not take effect until the employee's next login, which is the same
property `brandId` and `teamId` already have and is stated here so nobody discovers it as a bug.

## 7. Who sets the mapping

Two GM-only routes, both **reads** of GHL — nothing here writes, and `GhlHttpTest`'s
write-refusal assertion stays green:

| Method | Path | Answers |
| --- | --- | --- |
| GET | `/api/ghl/pipelines` | every pipeline on the location, id + name, so the GM picks rather than types |
| PUT | `/api/team-members/{id}/ghl-pipeline` | sets one member's pipeline |

**`PUT` sets; it does not clear.** A null body on a `SALES` member would violate the CHECK in §4,
and answering 500 from a constraint is how a UI ends up with an untestable error path. Clearing a
pipeline only makes sense as part of a role change or a deactivation, both of which are the
existing team-member edit routes' job. This route refuses a null with a 400 that says so.

The GET exists because the id is opaque. Asking a GM to paste a GHL pipeline id is how the wrong
id gets pasted, and a wrong id here is silent — the employee sees an empty board and no error.
The list is the difference between a typo and a choice.

`PUT` is audited (`AuditAction.UPDATED` on `TEAM_MEMBER`): it changes what a person can see, which
is the class of change invariant 13 exists for.

## 8. What this unit deliberately does not do

- **No board, no screen.** Unit 38.
- **No GHL write.** Unit 37, and invariant 2 dies there rather than here.
- **No opportunity storage.** Unit 38.
- **The three `evalos.ghl.*-pipeline-name` properties are left alone**, and they are now
  *duplicated truth*: `sales-pipeline-name: Aditya's pipeline` names by config the same pipeline
  Aditya's `team_member` row will name by id. Both work; they disagree the moment one changes.
  **Unit 38 supersedes `/sales/pipeline` and removes all three properties together** — it builds
  both boards, so the sales and marketing duplicates end in the same commit rather than one
  outliving the other. Recorded here so the overlap is a known cost with an owner, not a
  discovery.

## 9. Invariant impact

- **1 (brand isolation)** — **narrowed by §4a**, and it cuts both ways here. The stated exception
  stops being "a location nobody may be told the brand of, so GM-only" and becomes "a location
  whose brand is named in `evalos.ghl.sales-brand`, so that brand's sales staff may read it". That
  is a smaller exception, not a bigger one, and Unit 25 removes it. The rest of the bullet stands: The pipeline predicate is added
  *beside* the brand predicate on every scoped read, never instead of it, so an EvalOS row is still
  brand-locked. But `uq_team_member_pipeline` is **globally unique**, because a GHL pipeline is not
  a brand's — it belongs to the single location that invariant 1 already names as its one stated
  exception. The row scope stays brand-scoped; the *key* is global, because the thing it names is.
- **2 (EvalOS never runs sales)** — **not yet.** This unit adds roles and a predicate; it reads no
  opportunity and writes nothing. The invariant dies in **Unit 37** with the first `post`.
- **8 (a case is created only by a won opportunity)** — untouched, and stays untouched across the
  whole programme.
- **13 (append-only audit)** — the pipeline assignment is audited, being a change to what a person
  may see.

## 10. Build order

1. `V39`: two roles into `team_member_role_valid`, `ghl_pipeline_id` + its CHECK + its unique
   index, and `segment` + its CHECK (§3a).
2. `Role` + `Tier.PIPELINE`, and **fix the stale javadoc that still says "seven roles"** while
   listing six — `V30` removed `SALES_EXECUTIVE` and missed the comment. It becomes eight.
3. `ScopePredicate.Fields.pipeline` + the `PIPELINE` arm; `TenantContext`, `StaffPrincipal`, JWT.
4. The two GM routes.
5. Tests.

## 11. Acceptance criteria

- [ ] A `SALES` member whose row has no `ghl_pipeline_id` cannot be inserted — the CHECK refuses
      it — and a `CASE_MANAGER` with one is refused by the same constraint.
- [ ] Two active members cannot hold the same pipeline **even in different brands** — the index is
      global, and this is the criterion that proves it rather than the one that would pass either
      way. The same pipeline **can** be given to a replacement once the previous holder is
      `active = false`.
- [ ] A `PIPELINE`-tier principal with a null `ghlPipelineId` matches **nothing**, asserted by
      running a scoped read against the test entity of §5 rather than by inspecting the
      `Specification`.
- [ ] A `PIPELINE` predicate never replaces the brand predicate — a member of brand A holding a
      pipeline id that also exists in brand B reads none of B's rows. Same test entity, two brands.
- [ ] `PUT .../ghl-pipeline` with a null pipeline answers **400**, not a constraint violation.
- [ ] `GET /api/ghl/pipelines` is GM-only; every other role answers 403.
- [ ] `PUT /api/team-members/{id}/ghl-pipeline` is GM-only, writes an audit row, and refuses a
      pipeline already held by any active member, in any brand.
- [ ] Setting a pipeline on a non-sales, non-marketing member answers 400, not 500 — the
      constraint is the backstop, not the error message.
- [ ] A `SALES` or `MARKETING` member of a brand other than `evalos.ghl.sales-brand` is refused at
      assignment with **400** (§4a) — the ceiling is enforced, not merely written down. A member of
      the configured brand is accepted.
- [ ] `segment` is refused as NULL on a `SALES`/`MARKETING` row and refused as non-NULL on every
      other role, both by the CHECK (§3a).
- [ ] **No production code branches on `segment`.** Asserted the way the invariant tests assert
      structure: a grep-style structural test, so the first `if (segment == ...)` fails the build
      rather than passing review.
- [ ] `GhlHttpTest` still passes: **this unit adds no write verb to `GhlHttp`.**
- [ ] `./mvnw verify` green; both portal apps build.
