# Implementation status

**The authoritative file is `.claude/implementation-status.md` — a table with evidence per row.
Check it before claiming anything exists or is missing.**

Build is green: backend **1013 tests, 0 failures, 3 skipped**; staff SPA 127 tests plus clean tsc;
portals 30 tests plus clean tsc. Re-run 2026-09-16.

**It was RED on that re-run, from a time bomb rather than a regression.**
`GmOverviewServiceTest.countsOnlyCasesAlreadyPastTheirPromisedDateAsLate` set a delivery date from
the REAL `Instant.now()` while counting it against a window from a FIXED clock (2026-09-15,
exclusive end at midnight opening the 16th) — so it passed the day it was written and failed every
run after. Fixed with `CLOCK.instant()`. **The asymmetry that allowed it remains:**
`GmOverviewService.evaluation` takes its window from an injected clock and its lateness from its
own `Instant.now()`, so lateness cannot be tested at a fixed point in time.

**The tree is CLEAN and everything is committed**; HEAD is `e2d18bb feat(45c)` on `development`.
This paragraph said "uncommitted, 121 paths, HEAD `dee45c6`" until Units 51, 52, 44a-44d and
45a-45c landed. **CI still runs on `main` only**, so none of it has been through CI.

NOT IMPLEMENTED: conversations, outbound webhooks (Handoff C), expert accounts, the GHL mirror
(Units 44 to 48), request-stage documents, client payments (deliberate — they are GHL's).

REMOVED 2026-09-16, and do not restore it from git: the two GHL funnel screens, `/marketing/email`
and `/sales/pipeline` (Units 26 and 27). Both were GM-only under D19 — one global location, no
brand — so the audience for a marketing funnel could not open one, and the business removed the
screens rather than widen the gate. Gone with them: `MarketingPipelinePage`, `marketingApi`,
`MarketingController`, `MarketingPipelineService`, `GhlFunnelCache`, `GhlFunnelCacheRepository`,
`GhlPipelineClient.countIn` and three config properties. The `ghl_funnel_cache` TABLE survives,
orphaned: the drop has nowhere to live, because `V905` clears the table and `MigrationTreeTest`
forbids a `db/migration` script numbered 900 or above. A funnel screen returns only as a NEW
screen after Unit 25 puts the location on `brand`, and that one can admit Marketing.

BUILT 2026-09-16 (Unit 45, SLICE C): `sync_outbox` (`V57`/`V58`) — the durable EvalOS→GHL push.
`00c` §4d: invariant 2 said "writes do not retry" PRECISELY because EvalOS had no key scheme, and
44d's correlation key is that scheme.

It stores an ENTITY ID, NEVER A PAYLOAD (`00d` §6.3) — the sender reads the current row at send time,
so a collapse cannot send stale values, and the submit marker needs no intent of its own because an
UPSERT re-sends whatever the row now says. Dedupe key is PARTIAL (`where sent_at is null and dead_at
is null`), `intent` is COARSE or the collapse never happens, and `enqueue` is REQUIRES_NEW so a push
survives the caller's transaction rolling back.

THE RETRY-AFTER-TIMEOUT IS THE POINT: before creating, the drain asks GHL for THE CONTACT's
opportunities and looks for its own correlation key — GHL offers no custom-field filter, so that is
the only implementable form. Finding it means the create already landed and the row is LINKED, not
made twice.

Stop conditions are 45a's classification by name: 429 and 401/403 HALT THE WHOLE DRAIN (the budget is
per location; nothing in EvalOS fixes a missing grant), non-retriable refusals and EvalOS exceptions
are dead-lettered immediately, transient ones are retried to a cap of 5. Dead rows STAY.

WHAT IT DOES NOT DO: the desks still write to GHL synchronously and read the answer back — moving
them is UNIT 46. What it drains is the two portal writes that were previously swallowed and lost: the
opportunity create and the submit marker.

BUILT 2026-09-16 (Unit 45, SLICE B): `sync_drift` (`V56`) + a NIGHTLY `SYNC_AUDIT` sweep that asks
whether the mirror is actually right, and `GET /api/sync/drift` (GM) where a human reads the answer.
THE DETECTOR WENT BEFORE THE WRITERS deliberately — the outbox, the webhooks and the delta sweep are
all writers, and a writer you cannot audit is one you have to take on trust.

IT DETECTS AND RECORDS; IT NEVER REPAIRS, and there is no route to clear a row by hand: resolution is
per-field ownership (45e), and a detector that also mutates cannot be trusted because its own writes
become tomorrow's findings. A PAGED FULL-LIST DIFF, never a per-row GET (~30s vs ~21 minutes of GHL's
shared budget) — pinned by a test. One OPEN row per disagreement with first/last-seen rather than a
row per run; resolved rows stay as history. Two things it must NEVER call drift, both pinned: a
portal-born row with no `ghl_id` (a legal state per `00c` §2a) and `1000` vs `1000.00`. Opportunities
only: pipelines are overwritten hourly by the same code path so an audit would report zero by
construction, and contacts have no GHL READ client yet.

BUILT 2026-09-16 (Unit 45, SLICE A): GHL failures are CLASSIFIED at the door. `GhlFailure`
has 7 classes with `isRetriable()` and `stopsEverything()`; `GhlUnavailableException` carries
`failure()` and `status()`. Only 5xx/timeout/408/429 are retriable — a 4xx is NOT, and an empty body
on a 2xx is NOT (GHL considered that write successful, so a repeat writes twice). A 429 pushes
`GhlHttp`'s SHARED pacer forward honouring a capped `Retry-After`, because the budget belongs to the
location and not to the caller that hit the wall. No HTTP status EvalOS returns changed — every
class is still a 502. Two `missingScopeHint` string matches on "401" are deleted.

What remains in Unit 45: 45d (`opportunity.update` / `contact.*` webhooks + the delta sweep) and 45e
(per-field ownership, where a null `ghl_updated_at` must be an explicit CONFLICT rather than "EvalOS
is newer"). Neither needs a migration. `context/specs/45-sync-engine.md` §3 carries their amendments
so they are not re-derived from `00d`.

BUILT 2026-09-16 (Unit 44, SLICE C): `client_account.contact_id` (`V55`) joins the portal account to
its CRM row — the "one person is two rows with no link" gap — backfilled on `ghl_contact_id` within
the brand, and D6 is finally enforced by a PARTIAL unique index (many accounts with no contact may
coexist; two accounts claiming one contact may not). `ContactSnapshotService` is EXTRACTED from
`CaseIntakeService`, which `00d` §5.4 required before Unit 45's contact webhooks can exist at all:
`DomainInvariantsTest` permits exactly ONE injector of the intake service, so a second handler would
have failed the build. Sign-up now creates the CRM row, closing the prospect gap — until this, the
only writer was Handoff A, so `contact_snapshot` held only contacts that had WON an opportunity.

**The `contact_snapshot` → `contact` RENAME is deferred, and the reason is not laziness**: two seeds
(`V905` local, `V951` testprod) write that table and run after every migration, `MigrationTreeTest`
forbids a migration numbered 900+, and editing an applied seed is a checksum mismatch. Same trap as
`ghl_funnel_cache` and `team_member.ghl_pipeline_id`. `contact_snapshot` IS the mirror's contact
table; the name is the only thing wrong with it.

BUILT 2026-09-16 (Unit 44, SLICE B): `team_member_pipeline` (`V54`) replaces
`team_member.ghl_pipeline_id` as the authority — a member holds a SET of pipelines, assigned by
MIRROR id (a real FK, which closes `00d` C4 structurally: a dead GHL id can no longer be assigned at
all). `PipelineScope.mine()` returns a list, `mineForWrite()` refuses rather than guessing on a
create, `ScopePredicate`'s pipeline arm is `IN`. `PUT`/`DELETE /api/team-members/{id}/pipelines`
replace `PUT /{id}/ghl-pipeline`. `evalos.ghl.intake-pipeline-name` is RETIRED for
`pipeline.purpose = INTAKE`. The column and `uq_team_member_pipeline` survive VESTIGIAL — seeds
`V908`/`V909` write the column and a DROP cannot be ordered after them. The pipeline set is carried
in the TOKEN, so a reassignment takes effect on next sign-in (unchanged in kind; the fix is Unit 46).

BUILT 2026-09-16 (Unit 44, SLICE D): `opportunity` (`V51`) replaces `ghl_opportunity_cache`, which
is DROPPED (`V52`) along with `CachedOpportunity`, `OpportunityCache` and `GhlOpportunityClient`.
Two names per row: EvalOS's `id` is stable from creation AND IS THE GHL CORRELATION KEY, `ghl_id` is
null until GHL answers. The board, `PipelineScope` and the Sales desk's duplicate check all read it
now — which closes `00d` §6.5's "access control with a TTL", because a row written on create stays
written instead of being destroyed by the next refresh.

**The correlation key is why 44d exists** (`00d` §6.1 — "the single biggest sequencing error in
00c"). A create that TIMES OUT cannot be retried safely, because EvalOS cannot tell "GHL never got
it" from "GHL got it and the answer was lost". EvalOS now opens its own row FIRST, sends that row's
id in a GHL custom field (`GHL_OPPORTUNITY_CORRELATION_FIELD`), then records GHL's id — and
`client_application.opportunity_id` (`V53`) persists it so a retry reuses the row rather than
minting a second key. TWO CORRECTIONS TO §6.1, found in the building: GHL offers NO filter on a
custom field (verified), so a retry must search by `contactId` and match locally; and the key has to
be persisted before the call, which is what the new column is for.

BUILT 2026-09-16 (Unit 44, SLICE A): the tier-1 mirror has started. `pipeline` and
`pipeline_stage` (`V50`) hold GHL's ids verbatim, refreshed hourly by the `PIPELINE_MIRROR` sweep;
`pipeline.purpose` (MARKETING/SALES/DELIVERY/INTAKE/UNASSIGNED) is the one column EvalOS owns and a
sweep never writes it. Rows are upserted and NEVER deleted. A stage GHL recreated under a new id is
REPOINTED by `(pipeline, position, name)`, not duplicated — without that a recreated pipeline reads
as every opportunity in it having drifted. The opportunity board no longer calls GHL to name a
column. **Unit 44 is COMPLETE** (`context/specs/44-ghl-tier1-mirror.md`). `OpportunityRepository.SCOPE` is `brandOnly` until 44b lines
the pipeline axis up, so the pipeline scope lives in the finder SIGNATURES and
`OpportunityRepositoryScopeTest` guards it — delete that test at 44b, not before. There is still NO
sync engine beyond error classification — that is Unit 45.

BUILT 2026-09-16 (Unit 52, partial): the Client Portal ↔ GHL integration is EvalOS→GHL only.
Sign-up upserts the GHL contact and stores the id; **SUBMIT** opens the opportunity (D10 — it was
service-pick until 2026-09-16) carrying the SERVICE ID, the correlation key and `SUBMITTED` **all on
that one create**, so a GHL workflow can route it and `setOpportunityFields` is off this path
entirely. Custom fields only, so it structurally cannot undo GHL's routing. Both field ids default
BLANK (field omitted, nothing breaks); the routing WORKFLOW is UI work in GHL and is not built. GHL→EvalOS sync is Units 44–48,
decided 2026-09-16 — `opportunity.update` and `contact.*` are still archived-and-acked, not routed.
Spec: `context/specs/52-client-portal-ghl-integration.md`.

BUILT 2026-09-15 (Unit 51): the GM dashboard — `GmDashboard.tsx` + `GET /api/metrics/gm`
(`hasRole('GM')`, nothing wider) + `GmOverviewService`. GM and Brand Manager no longer share
`RevenueDashboard`. `context/specs/51-gm-dashboard.md` §3 maps every widget on the business's PDF
to its source and says which cannot be computed at all.

PARTIAL and worth knowing (**re-judged 2026-09-17** — three of these were never gaps):
a client with two or more cases is refused (Q8, still open); notifications are in-app only and
**push is owed** (D37 — in-app and push, never mail or SMS); request-stage documents do not exist
and are **Unit 53** (D33). *No longer listed as gaps:* **SALES reading no case is correct** (D19c —
their world ends at won, so `ScopePredicate`'s empty PIPELINE arm over `evalos_case` is the rule);
**a richer `client_application.status` is not owed** (D35 — review is a GHL pipeline stage); and
**the portals' absence from `docker-compose.yml` and CI is DevOps's, not this repo's** (D38).

Operational, not code: IE's GHL sub-account was replaced on 2026-09-11 with
`WY6bW2xUCI8Tz8gw7aLJ` and no contacts were migrated. The `opportunity.won` workflow **EXISTS** in the new
account (confirmed by the business 2026-09-16; the repo cannot prove it and never will). **A real
won opportunity producing a case has still not been observed** — 2026-09-17 the business confirms
the webhook is built in the new location and a dummy opportunity to fire it is coming. A firing needs
`POST /api/webhooks/ghl/{webhook_endpoint_token}` — that token is the whole credential, there is
no signature step — with `event_type`, `contact_id` and `full_name` snake_case at the top level,
and an optional camelCase `customData` of snake_case fields; `amount` is `@Positive` where
present, so **0 is refused** and absent is fine. `evalos.ghl.intake-pipeline-name` is GONE (retired at 44b): a GM must
mark exactly one mirrored pipeline INTAKE via `PUT /api/ghl/pipelines/{id}/purpose`, and **zero
(the fresh-deployment default) and two or more are both a 502** — guessing would file a client's
request onto a pipeline nobody chose.

**BUILT 2026-09-17 (Unit 45d)** — the mirror finally reads GHL as well as writing it.
`GhlMirrorHandler` routes `contact.created`/`contact.updated` into `contact_snapshot` and **six
spellings** of `opportunity.*` (create/created/update/updated/stage_changed/status_changed) into
`OpportunityMirrorService.absorbForContact`. **The event is a TRIGGER, not a payload**: GHL's
Custom Webhook action posts the contact record flat, with no stage/status/value, and anything under
`customData` is hand-typed — so an opportunity event carries only the contact id and the handler
re-reads `GhlPipelineClient.forContact`. A contact event is the one case where the payload IS the
entity, so it costs no read. `opportunity.won` is NOT in that set and must never join it (Handoff A,
invariant 8). `absorbForContact` has **no absence pass** — a contact's deals are not a pipeline's
list. New sweep `MIRROR_DELTA` (15m, `evalos.ghl.delta-ttl` 10m): **"delta" is a stale PIPELINE, not
a changed row**, because GHL's search has no updated-since filter. Parse-then-validate moved to
`WebhookPayload`, shared by both handlers. Suite: **1036 tests, 0 failures**. Left in Unit 45: **45e
only** (per-field ownership).

**BUILT 2026-09-17 (Unit 45e) — UNIT 45 IS COMPLETE.** `FieldOwnership` (domain) classifies every
mirrored field: **GHL owns the assignee and the pipeline** (and anything unclassified — a new column
follows GHL rather than starting to defend itself), `ghlStageId`/`status`/`amount`/`name` are
**SHARED**, `opportunityNote` is **EVALOS** and never synced. The blanket "EvalOS wins" from `00c`
§4b is rejected because it **reverts GHL automations**, the one thing GHL was kept for.
`Opportunity.syncFromGhl` keeps a shared field **only while `local_updated_at` says EvalOS holds an
edit GHL has not confirmed**; that flag is cleared by a GHL win and by `linkGhl`, or a portal-born
row would out-rank GHL for life. **A null `ghl_updated_at` is a conflict, but only when there is an
edit to defend** — the other half stops the rule degrading into "EvalOS always wins".
`GET /api/sync/drift` gained `owner`, `resolution` (GHL_WINS / EVALOS_WINS / NEEDS_A_HUMAN, derived
at read time, never stored) and `needsAHuman`; **still no resolve button** (D43). 45e pushes nothing
to GHL — correcting GHL is Unit 46's. Suite: **1049 tests, 0 failures**. No migration.

**BUILT 2026-09-17 (Unit 46) — the desks are on the mirror.** Spec `46-desks-on-the-mirror.md`.
**Read half = a deletion**: `OpportunityBoardService` no longer calls `refreshIfStale` on load, so
a board makes **no GHL request at all** — 45d's webhooks plus `MIRROR_DELTA` are what made the
refill removable. `evalos.ghl.board-cache-ttl` became `board-stale-after` (30m), a **label** only:
nothing acts on it, and 30m is the worst case the delta sweep promises (15m interval + 10m TTL).
**Write half = an inversion**: `SalesDeskService.update`/`moveToStage`/`close` and
`MarketingLeadService.value` call `OpportunityMirrorService.editLocally` (→ `Opportunity.editedLocally`,
null means leave alone) and `outbox.enqueue`, then answer **from the row** — no GHL on the request
path. The editable four are exactly 45e's SHARED set; the assignee is absent because it is GHL's.
The outbox's `UPSERT` now carries the **stage** (it was null), and a delivered push calls
`Opportunity.pushedToGhl()` to clear `local_updated_at` — without that, Unit 46 would freeze rows
against a location whose `updatedAt` comes back null. **Creates stay inline** (D46): the outbox
stores an id, never a payload, and a desk create carries custom fields (tier 2, Unit 47) the mirror
does not hold; the contact upsert and GHL tasks stay inline for the same reason.
**Cost, stated**: a won deal reaches GHL on the next drain (≤2m), so Handoff A's case arrives later.
**Cadence and honesty (same unit, 2026-09-17):** `MIRROR_DELTA` runs every **5m** (`delta-ttl` 4m)
because with the refill gone it is one of only two things putting a new GHL lead in front of a
salesperson. `Board.lastSyncedAt` is **nullable and null means never synced** — it used to
substitute `Instant.now()`, which claimed the board was current at the one moment nothing had been
read — and a null is stale. `board-stale-after` **5m — one missed pass** (business call over a proposed 15) drives
a **"Sync delayed"** banner; it equals the sweep interval, so a long pass blinks the banner briefly. `POST /api/opportunities/board/refresh` is the Refresh button: it **reconciles the mirror
then draws from it**, caller's own pipelines only, behind a 30s floor.
Suite: **backend 1052, frontend 127, both green**. No migration.

**BUILT 2026-09-17 (Unit 47) — the reference mirror.** Spec `47-reference-mirror.md`.
**Scoped by `00d` §6.6, NOT by `00c`'s tier list**: "a sync surface with no consumer is pure drift
risk", so the question was *what does a desk screen still read live from GHL after Unit 46* — three
lists. `V60` adds `ghl_custom_field`, `ghl_calendar`, `ghl_user` (prefixed because `user` is
reserved in Postgres); one `GhlReference` superclass, three repositories, `ReferenceMirrorService`,
and one hourly `REFERENCE_MIRROR` sweep. `/sales/opportunity-fields`, `/sales/calendars` and
`/sales/users` now read the mirror; **payload shapes unchanged**, so no form was touched.
**NOT mirrored, on purpose**: tags and GHL notes (no reader), custom field **values** (only
definitions have a reader — values are what a queued create would need, D46/D49), and **free slots,
which never will be** (availability is GHL's to compute; a mirrored slot is wrong within a minute —
D48, and Unit 48 inherits "runs without sync, cannot take a new booking").
`follow_up` and `meeting` stay write-through: a task completed or an appointment cancelled IN GHL is
still not reflected — named as accepted divergence, because GHL lists tasks only per contact.
An **empty** list does one live read on first access and never again (first-run cliff, not a
refill-on-read). Suite: backend **1065**, frontend **127**, both green.

**BUILT 2026-09-17 (Unit 47b) — the cuts reversed, and one of them was wrong on the facts.**
47 §4 cut tags, GHL notes, custom field VALUES and task read-back; the business overruled it, and
the task cut rested on a false claim: `GET /opportunities/search` takes **`getNotes`, `getTasks`,
`getCalendarEvents`** and returns `customFields`/`notes`/`tasks`/`calendarEvents` per row — verified
against the live operation contract. So all of it rides on the read the mirror already makes, for
**zero extra requests**. `V62`: `opportunity.custom_fields` (jsonb, keyed by GHL **field id** — a
rename keeps the id), `ghl_note`, `ghl_tag`. Read-back needed **no migration**: `FollowUp` and
`Meeting` already had the columns and were only missing the code. **`ghl_note` must never merge with
`opportunity_note`** (EvalOS prose, append-only trigger, never synced). **A task EvalOS never
created is not invented.** **Tags are read, never written** — GHL workflows key off them.
**D46 is unblocked**: the mirror now holds the values a queued desk create needs.
Suite: backend **1075**, frontend **127**.

**UI, 2026-09-17:** opening a deal and capturing a lead are **sidebar entries**, not controls on the
board — `/opportunities/new` (SALES) and `/marketing/leads/new` (MARKETING), each its own screen,
returning to the board on success. `NavItem.brandProven` replaces the hardcoded one-path exception
in `navigation.test.ts`: a `readsGhlLocation` screen is GM-only unless it is marked, and a marked
one may only be reached by GM/SALES/MARKETING.
