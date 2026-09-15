# Unit 44 — The tier-1 GHL mirror

**Status: slices A and D BUILT (2026-09-16). B and C are specced and unbuilt.**

Unit 44 is `00c`'s first real unit: EvalOS stops asking GHL what its structure is on every screen
render and starts holding it, **using GHL's own ids**, so a mismatch is detectable by comparison
rather than by interpretation. It is amended before it starts by `00d` §6, and this spec is where
those amendments become a build order.

**Read first:** `00c` §2 (the mirror), `00c` §2a (two names per row), `00c` §2b / `43` §6c (stage
ids verbatim), and `00d` §6.1–6.7 (the six amendments). Nothing here restarts that plan; it
sequences it.

---

## 1. The slices, and why this order

| Slice | Ships | Touches authorisation? | Status |
|---|---|---|---|
| **44a** | `pipeline`, `pipeline_stage`, the sweep, `purpose` | no | **BUILT** |
| **44b** | `team_member_pipeline`; `PipelineScope.mine()` becomes a set; retires `intake-pipeline-name` | **yes** | specced |
| **44c** | `contact` — merges `contact_snapshot` and `client_account` | no | specced |
| **44d** | `opportunity` + the correlation custom field; replaces `CachedOpportunity` | **yes** (via `PipelineScope`) | **BUILT** |

**44a first because GHL owns every column in it.** `00d` §6.2 puts `pipeline.*` and
`pipeline_stage.*` under *"GHL, read-only; GHL always wins; EvalOS never pushes"*. A table that can
only ever be **behind** GHL, never in conflict with it, needs none of Unit 45's conflict machinery
to be correct — so it is the one slice that is fully useful on its own and cannot break a desk.

**44d was built second, ahead of 44b and 44c, on the business's instruction.** It carries both the
largest consumer of the sync engine and the correlation key, so it is what unblocks Unit 45.

**44b is held back because it moves authorisation.** `00d` §6.7 is explicit that
`team_member_pipeline` *"is not only a schema change — it moves `PipelineScope.mine()` from 'one
id' to 'a set' across every desk that shares it, an amendment to Units 39 and 40's authorisation
model"*. That is not something to land in the same commit as a new table.

---

## 2. Slice 44a — what was built

### 2.1 Two tables, `V50__pipeline_mirror.sql`

```sql
pipeline       (id uuid pk, brand_id, ghl_id, name, position, purpose, synced_at, missing_since)
pipeline_stage (id uuid pk, brand_id, pipeline_id fk, ghl_id, name, position, synced_at, missing_since)
```

- **`ghl_id` is GHL's own string, verbatim** (`00c` §2), unique per brand rather than globally —
  two brands will one day hold two locations and ids are only unique within one.
- **EvalOS mints `id` beside it** (`00c` §2a). It matters for `opportunity` in 44d, where a
  portal-born row exists before GHL has seen it; the shape is kept uniform here so both tables read
  the same way.
- **`pipeline_stage.pipeline_id` is a real foreign key** — which `ghl_opportunity_cache` could
  never carry, because its rows are destroyed on every refresh.

### 2.2 `purpose` — the one column GHL does not own

`MARKETING | SALES | DELIVERY | INTAKE | UNASSIGNED`, default `UNASSIGNED`.

`00d` §6.7 is the decision: config carried three `*-pipeline-name` properties and
`application.yml` explicitly forbade a list (*"THREE NAMES, NOT A LIST"*) — right at three
pipelines, wrong at the nine the business is moving to. **The mirrored table is the list**, and
`purpose` says which row means what.

- **Nothing infers a purpose from a name.** A GM sets it, through
  `PUT /api/ghl/pipelines/{mirrorId}/purpose`. Guessing from text is how a rename in GHL silently
  reroutes a client's request.
- **A sweep never writes it** — pinned by `aRoutineSyncLeavesTheGmsPurposeAlone`. GHL has no such
  concept, so a sync that set it would reset a GM's judgement hourly.
- **`DELIVERY` exists and is fenced.** The target pipeline set includes Case Delivery. `00d` §6.7:
  a GHL pipeline whose stages track delivery is structurally a second case board, and invariant 8
  plus `43` §9 forbid that shape. Mirrored for reporting and hand-off visibility; `evalos_case`
  stays the only lifecycle.

**Two of the three name properties are already gone** — `email-pipeline-name` and
`sales-pipeline-name` went with the funnel screens on 2026-09-16, which removes `00d` §6.7's one
warning about this change (*"replacing every `*-pipeline-name` property rewrites three shipped GM
screens' data source"*). Only `intake-pipeline-name` is left, and it is **44b's** to retire,
because it needs a screen for setting `purpose` and 44b is where that lands.

### 2.3 Upsert in place, never delete

A row GHL stops returning is stamped `missing_since` and kept. This is the direct answer to `00d`
§6.5's fifth and deepest reason `CachedOpportunity` is unsalvageable — *"its only write path is
delete-all-then-insert-all per pipeline… the current write model is 'truth lives elsewhere,
replace everything'; the mirror's is the exact opposite."*

It is load-bearing rather than cautious: `purpose` is EvalOS's judgement, and a pipeline archived
in GHL for an afternoon that came back as a new row would come back meaning nothing. A stamped row
is also the evidence Unit 45's drift report is built from — a pipeline that vanished is a fact
about GHL, not an absence of one.

### 2.4 The stage re-match, which is the subtle part

`00d` §6.7: *"mirror stages with a secondary natural key `(pipeline_id, position, name)`: GHL stage
ids are not stable across a delete-and-recreate, and IE has now demonstrated it will recreate
things. Without it a recreated pipeline reads as every opportunity in it having drifted."*

Three outcomes per incoming stage, **in this order**:

1. known `ghl_id` → update;
2. unknown id whose natural key matches **exactly one** held row → **repoint** (the row keeps its
   `id`, so every foreign key survives);
3. anything else → insert.

The order matters: trying the natural key first would repoint two stages that swapped positions
onto each other. **An ambiguous match inserts and is counted, never guessed** — a wrong repoint
silently moves every opportunity in a stage, which is worse than a duplicate a drift report can
name. The index is therefore an index and not a unique constraint: GHL promises neither distinct
positions nor distinct names, and a constraint EvalOS cannot enforce upstream would turn somebody
else's duplicate into a failed sweep.

### 2.5 A sweep, not a read-through cache

`PIPELINE_MIRROR`, on Unit 19's existing machinery, default **1 hour**.

- **A sweep, because only a full pass can notice an absence.** A read-through cache refills what
  somebody asked for; `missing_since` needs the other half.
- **It joins the admin panel's staleness warning for free** — a mirror that quietly stopped running
  is exactly what that panel exists to name.
- **The bootstrap and the manual repair are the same existing button**:
  `POST /api/jobs/PIPELINE_MIRROR/run`, already GM-only. No new route.
- **It is not Unit 45's delta sweep.** That one reconciles *opportunities* and carries conflict
  resolution because both sides write them. Nothing writes a pipeline but GHL.

### 2.6 The first payoff: the board stops calling GHL to name a column

`OpportunityBoardService.draw` called `GET /opportunities/pipelines` on **every board render** — an
unpaginated network round trip against a 100-per-10-seconds budget shared with every desk, to turn
an opaque stage id into a word. It now reads `pipeline_stage`.

That also retires a `ponytail:` note in `OpportunityBoardServiceTest` which said *"if board loads
ever dominate the 100-per-10s budget, cache the stage list"* — the mirror did better than cache it.
`GET /api/ghl/pipelines` moved the same way, which changes one behaviour worth knowing: **an
unfilled mirror is an empty list, not a 502.**

---

## 3. Slice 44b — `team_member_pipeline` (specced, unbuilt)

```sql
team_member_pipeline (team_member_id, pipeline_id, primary key (team_member_id, pipeline_id))
```

Replaces `team_member.ghl_pipeline_id` and `uq_team_member_pipeline` (one live owner per pipeline).
**Many-to-many is the real shape once Case Delivery exists** — a pipeline no single person owns,
which the current unique constraint cannot express and `PipelineScope.mine()` (returns exactly one
id) cannot read.

**This is an authorisation change, and it must be treated as one.**

- `PipelineScope.mine()` → `mine()` returning a set; `requireMine(opportunityId)` follows.
- `ScopePredicate`'s `Tier.PIPELINE` arm matches `IN (:pipelines)` rather than `=`.
- Every desk route inherits it — Units 39 and 40's model, not just this unit's.
- **`40` §7's boundary survives**: *"there is no route to move a deal between pipelines"* is a
  design decision (*"promotion is GHL's workflow, and a second path here would race the automation
  the business owns"*). Mirroring Case Delivery must not create one.

**`intake-pipeline-name` retires here**, replaced by `purpose = INTAKE`, because that needs a screen
for setting `purpose` and this slice is where the team-administration gap (`00d` §8.6) is closed.

**Also here, and named so it is not forgotten:** `00d` C4 — every `team_member.ghl_pipeline_id`
holds a dead id from the abandoned sub-account, and there is no UI to fix it.

---

## 4. Slice 44c — `contact` (specced, unbuilt)

Merges `contact_snapshot` and `client_account`, which today *"are not joined — both can hold a
`ghl_contact_id` and nothing links them"* (`.claude/data-model.md`). `00d` §11 Phase 4 puts the
merge in the same migration as the mirror table deliberately.

Two facts to carry in: `client_account.ghl_contact_id` is **nullable and not unique**, so D6 (one
contact, many opportunities) is not enforced by the schema and two accounts may name one contact.
And `00d` §5.4's `contact.created`/`contact.updated` handlers are the inbound half — they belong to
Unit 45, but `ContactSnapshotService` must be extracted **first**, because
`DomainInvariantsTest` permits exactly one injector of `CaseIntakeService` and a second handler
would fail the build (correctly — that is invariant 8 working).

---

## 5. Slice 44d — `opportunity` (BUILT)

```sql
opportunity (id uuid pk, brand_id, ghl_id unique null, contact_id, pipeline_id, stage_id,
             name, amount, status, ghl_updated_at, local_updated_at, sync_state)
```

Replaces `CachedOpportunity` / `ghl_opportunity_cache` — `00d` §6.5's five reasons, of which the
fifth (delete-all-then-insert-all) is why nothing can be salvaged.

**The correlation custom field is pulled forward into this slice, and it is the single most
important item in Unit 44.** `00d` §6.1: at-least-once outbox delivery over a non-idempotent create
is exactly how one opportunity becomes two. The fix is a client-side correlation key — write the
EvalOS `opportunity.id` into a GHL custom field on create, and on retry-after-timeout search that
field before creating. *"That requires tier-2 (custom fields) at Unit 44, not Unit 47 — one field,
not the general tier-2 sync. This is the single biggest sequencing error in `00c`."*

**The machinery for it already exists as of Unit 52** (2026-09-16):
`GhlWriteClient.createOpportunity` takes custom fields and `ClientApplicationService` already sends
the requested service through one. This slice adds a second field and the search-before-create.

**Do not switch the create path to `POST /opportunities/`** — `00d` §6.1 is explicit that it
reverses `39` §3a against its own reasoning and breaks `43` §7.

**`PipelineScope` moved onto the mirror here, which is what `00d` §6.5 ruled.** It authorised every
Sales and Marketing write against the droppable cache — *access control with a TTL*, so "a newly
opened lead is immediately unauthorised until the next refill". A mirror that is upserted rather
than replaced closes that half by construction. The other half — a deal moved to another rep's
pipeline staying authorised until something refreshes — is a staleness bound, and Unit 45's delta
sweep is its fix. No `GET /opportunities/{id}` fall-through was added; `39` §7 considered and
rejected exactly that.

### 5.1 Two corrections to `00d` §6.1, found in the building

**GHL offers no filter on a custom field.** §6.1 says to "search that field before creating".
Verified against the API: neither `GET /opportunities/search` nor the advanced `POST` accepts one —
the advanced body takes a full-text `query` (75 chars) and nothing else. The implementable form is
to ask GHL for the **contact's** opportunities (`contactId` *is* a supported filter) and match the
correlation value locally. One contact has a handful of deals and a retry-after-timeout is rare, so
this is cheaper than it sounds and needs no assumption about what GHL full-text-indexes.

**The key must be persisted before the call, which costs a column.** A key minted in memory, sent,
and lost to a timeout is a key no retry can search for. So `client_application.opportunity_id`
(`V53`) holds the row the request opened, and the create path writes the local row *first*. That
column is also one step of the `client_application` → case join `.claude/data-model.md` lists as
missing.

### 5.2 What 44d actually shipped

- **`V51`** — `opportunity`. Two names per row (`00c` §2a): EvalOS's `id`, stable from creation and
  serving as the correlation key; `ghl_id`, null until GHL answers, unique per brand *where present*
  via a partial index.
- **`V52`** — `ghl_opportunity_cache` dropped. The ordering worked here where `ghl_funnel_cache`'s
  did not: nothing in either seed tree mentions it.
- **`V53`** — `client_application.opportunity_id`.
- **`OpportunityMirrorService`** — upsert in place, soft-delete by `missing_since`, refresh-on-read
  keeping the cache's two-minute staleness bound. **A local-only row is skipped by the absence
  pass**, or every portal-born deal would be stamped missing on the first refresh after it was made.
- **Deleted**: `CachedOpportunity`, `CachedOpportunityRepository`, `OpportunityCache`, and
  `GhlOpportunityClient` — the last being a second client over `/opportunities/search` with a
  narrower projection of the same rows than `GhlPipelineClient` already had.
- **`ghl_stage_id` is text, not a foreign key**, and that is a decision: the two mirrors run on two
  sweeps, so a FK would make an opportunity sync fail because a *different* sweep is behind. The id
  still resolves against `pipeline_stage.ghl_id` — `00c` §2b's equality check, not a translation.
- **`sync_state` is not a column**, deviating from `00c` §2's sketch. Today it is derivable
  (`ghl_id IS NULL` is the only state that exists) and every other value needs a writer that is Unit
  45's. Add it with the outbox, not before.

---

## 6. What Unit 44 deliberately does not do

- **No sync engine.** Webhooks, the delta sweep, the nightly paged diff, the outbox, `sync_drift`
  and error classification are **Unit 45** (`00c` §4, `00d` §6.3). 44a's hourly sweep is a
  structural refresh, not a reconciliation.
- **No tier 2 or 3**, beyond §5's one correlation field. `00d` §6.6: *"tier 1 only, until a screen
  names tier 2"* — `00c` §6 already argues against itself on this, and the ruling follows its own
  better half.
- **No webhook replay.** `00d` §6.4: a dropped `opportunity.won` is not drift, and the delta sweep
  will never create the missing case, because Handoff A lives in `CaseIntakeService`. Keep them
  separate.
- **Nothing is pushed to GHL.** Every mirrored column in 44a is GHL's.

---

## 7. Acceptance — slice 44a

- [x] `pipeline` and `pipeline_stage` exist, hold GHL's ids verbatim, and are brand-scoped to
      `evalos.ghl.sales-brand`.
- [x] A blank selling brand mirrors nothing and does not call GHL.
- [x] A pipeline GHL stops returning is marked missing, never deleted, and keeps its `purpose`.
- [x] A pipeline that comes back is live again with the meaning it had.
- [x] A stage recreated under a new GHL id is repointed, keeping its row id.
- [x] Two candidates for one stage are left alone, counted and logged.
- [x] A routine sync never writes `purpose`.
- [x] `DomainInvariantsTest` accepts both new scoped entities (it fails the build otherwise).
- [x] The opportunity board draws its columns from the mirror, with no GHL call for stage names.
- [x] `GET /api/ghl/pipelines` is GM-only, reads the mirror, and answers an empty list — not a
      502 — when the sweep has not run.
- [x] `PUT /api/ghl/pipelines/{mirrorId}/purpose` is GM-only and refuses an unknown value by name.

---

## 8. Acceptance — slice 44d

- [x] `opportunity` holds EvalOS's own `id` and GHL's `ghl_id` separately; `ghl_id` is unique per
      brand only where it is present.
- [x] The mirror upserts and never deletes; a row GHL stops returning is stamped `missing_since`.
- [x] A local-only row (no `ghl_id`) is never stamped missing by the absence pass.
- [x] The board, `PipelineScope` and the Sales desk's duplicate check all read the mirror; nothing
      reads `ghl_opportunity_cache`, which is dropped.
- [x] The board's age comes from the pipeline's `synced_at`, so an empty pipeline no longer reports
      "read just now".
- [x] A portal create opens the local row **before** calling GHL and sends its id as the correlation
      key; the order is asserted, not just the payload.
- [x] A retry reuses the row it already opened rather than minting a second key.
- [x] An unmirrored intake pipeline still opens the deal, without a correlation key.
- [x] An unconfigured correlation field omits it and changes nothing else.
- [x] `opportunity_note` still survives the opportunity it names being deleted — the property
      outlived the table it was written against.
