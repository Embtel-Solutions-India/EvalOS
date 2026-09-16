# Implementation status

**The authoritative file is `.claude/implementation-status.md` — a table with evidence per row.
Check it before claiming anything exists or is missing.**

Build is green: backend 1002 tests, 0 failures, 4 skipped; staff SPA 127 tests plus clean tsc;
portals 30 tests plus clean tsc. All run 2026-09-16.

**The newest work is uncommitted.** Units 40, 43 and 51 and the `00d` audit are 121 changed or
untracked paths on branch `development`, whose HEAD is `dee45c6 fix(42)`. CI runs on `main` only,
so none of it has been through CI.

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

**Nothing in Unit 45 is blocked any more** — Unit 44 is complete. What remains is 45c (the outbox),
45d (`opportunity.update` / `contact.*` webhooks + the delta sweep) and 45e (per-field ownership,
where a null `ghl_updated_at` must be an explicit CONFLICT rather than "EvalOS is newer").
`context/specs/45-sync-engine.md` §3 carries each one's amendments so they are not re-derived.

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
Sign-up upserts the GHL contact and stores the id; picking a service opens the opportunity carrying
the SERVICE ID as a custom field so a GHL workflow can route it to a pipeline; submitting writes
`SUBMITTED` to a second field through `GhlWriteClient.setOpportunityFields` — custom fields only, so
it structurally cannot undo GHL's routing. Both field ids default BLANK (field omitted, nothing
breaks); the routing WORKFLOW is UI work in GHL and is not built. GHL→EvalOS sync is Units 44–48,
decided 2026-09-16 — `opportunity.update` and `contact.*` are still archived-and-acked, not routed.
Spec: `context/specs/52-client-portal-ghl-integration.md`.

BUILT 2026-09-15 (Unit 51): the GM dashboard — `GmDashboard.tsx` + `GET /api/metrics/gm`
(`hasRole('GM')`, nothing wider) + `GmOverviewService`. GM and Brand Manager no longer share
`RevenueDashboard`. `context/specs/51-gm-dashboard.md` §3 maps every widget on the business's PDF
to its source and says which cannot be computed at all.

PARTIAL and worth knowing: SALES can read no case; a client with two or more cases is refused; notifications are in-app only; the Client and Expert
Portals are in neither `docker-compose.yml` nor CI.

Operational, not code: IE's GHL sub-account was replaced on 2026-09-11 with
`WY6bW2xUCI8Tz8gw7aLJ` and no contacts were migrated. Whether the `opportunity.won` workflow was
recreated **cannot be determined from this repo** — verify it in GHL. If it was not, no case is
created by anything. `evalos.ghl.intake-pipeline-name` defaults to blank, which makes every
request start answer 502.
