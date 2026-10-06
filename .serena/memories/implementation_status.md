# Implementation status

**The authoritative file is `.claude/implementation-status.md` — a table with evidence per row.
Check it before claiming anything exists or is missing.**

Build is green: backend **1177 tests, 0 failures, 4 skipped** (2026-09-24); staff SPA 131 tests (2026-09-23) plus clean tsc
and oxlint; portals 30 tests plus clean tsc and a clean `npm run build`. Re-run 2026-09-22 after
the dead-code pass below.

**Unit 72 (D71), built 2026-10-01:** `V83` terms columns on client/expert accounts; `PortalTermsService` + `/api/portal/{client,expert}/terms`; `shared/src/legal/` (pages, `PolicySummary`, `TermsGate`); `AuthSplit` sign-in artwork on both portals; the portal `SiteFooter` removed (policies accepted once instead). Chrome-checked.

**Board look 2026-10-01:** both boards: outlined stage-colour header (icon, name, count, Sales "+" → `/opportunities/new?stage=`), no column panel, stage-tinted cards (tag pill, title, muted line, footer: date + white chip — SLA bars+word / deal value); `board/stageColors.ts` ten non-RAG hues, Won green / Lost rose / lanes slate; `--card-text-muted` on tints.

**Unit 70a phase 1, built + Chrome-checked 2026-10-01:** `lib/queryClient.ts` + `api` interceptor (`afterRequest`); bell, case page panels, board + queues (`board/useBoard.ts`), draft review, PM notes, checklists on `useQuery`. Phase 2 built 2026-10-02: `payouts`/`experts` join `CASE_KEYS` (live + after writes); payouts, roster, availability, case history, offer fee, shortlist on queries; `useMetrics` is a query (`['metrics', useId(), ...deps]`, focus-only), covering dashboards, meetings, boards; `ExpertProfile` never overwrites an open edit.

**Unit 71 (D69/D70), built 2026-10-01:** `V82` adds `expert_case_offer.note` + `opportunity_note.handoff`; offers refuse a blank note, retake carries it; the expert's Answers shows it; Won needs a note (`SalesDeskService.close(id, status, note)`, and `moveToStage(id, stage, note)` for a stage named Won: board drop / picker open `WinNote`); case page `SalesNote` card. Not browser-checked.

**Fix 2026-10-01 (D36 staffing):** COORDINATOR_ASSIGNED / CASE_MANAGER_REASSIGNED now notify the assignee; assignment audit notes name who ("Coordinator: X"); More menu gains PM "Assign/Change case manager" (PATCH case-manager, any stage) and says "Change …" once filled. Not browser-checked.

**Unit 70 (D68), BUILT 2026-10-01:** `CaseOwned` + `CaseLiveHibernate` + `CaseLive` (after-commit `case.changed` to `live:brand:{brand}` + client/expert channels; `notifications.changed` from `NotificationService`); package `onLive`/`liveChannels`; staff `lib/live.ts`, nav badges on a query; portals `LiveInvalidate`. Runtime-checked locally. Gaps closed 2026-10-02: GM token names every brand channel; `CaseLiveHibernateTest` on Postgres (mutation-checked). Deal delete (Unit 69) verified by the business. Chat not live in prod because the Ably key could not publish (40160) — root key fixed it locally; prod key needs publish+subscribe+presence. Push off in prod for want of `EVALOS_PUSH_SUBJECT`.

**Unit 64c, 2026-09-30 (D58).** Case progress emails: `mail/case-update.html`, `MailTemplates.CaseUpdate`, `CaseMailListener` (checklist / draft ready / signing / delivered, + expert offer D67); not sent to a real inbox yet. **2026-10-01:** Send chase publishes `CHECKLIST_CHASED` → `CaseUpdate.CHASE` reminder mail (before, nothing listened and no client was contacted); the sweep's `CHECKLIST_REMINDER` still mails nobody.

**Unit 64b, 2026-09-30 (D65).** Case-opened emails: `case-started.html` / `case-signin.html`, `openForCase(contact, caseCode, service)`, 7-day `CASE_LINK_TTL`; not sent to a real inbox yet.

**Unit 39b, 2026-09-30 (D64).** The BDE's `/marketing/leads/new` renders `NewDealFields lead`; `openLead` upserts with stage + close, then `setOpportunityFields` for intake; `/api/sales/opportunity-fields` + `/api/sales/users` are SALES or MARKETING; Owner picker on both forms. Fix: opportunity create/upsert now send `locationId` (the sales Add opportunity 502). Live-verified 2026-09-30 via the API as bde-1 and sales-1 (stage, close, value, custom field land in GHL on each desk's own pipeline); forms not browser-checked.

**Opportunity board, 2026-09-23.** `OpportunityBoardPage` reuses the production board's
`StageColumn` (fixed width, pinned header, per-column scroll). SALES drags a deal between stages:
native HTML5 DnD delegated on the strip, no state change per pointer move, optimistic
`boardMove.moveDeal` (untouched columns/cards keep identity, so memoised ones skip), one
`PUT /sales/opportunities/{id}/stage`, rollback on refusal. Name search via `useDeferredValue`;
`content-visibility: auto` on cards instead of a virtualiser. `moveToStage` refuses a stage not
live on the deal's own pipeline (Q12 resolved 2026-09-24, D44). 2026-09-24: board `Deal` carries `source` and
`service` (row fields first, then `opportunity.lead_source` / the portal request), one read each
per board; cards show Value / Source / service with "—" placeholders, headers show stage Value.

**Production board drag, 2026-10-01.** Revises spec 22's "no drag". `QuickAction.to` (the
`CaseTransitions` target) + `boardRules.dropActionFor` pick the one action into the dropped-on column;
field-free runs at once, with fields opens `QuickActionDialog`. Not optimistic; illegal columns refuse
the drop; lanes are not targets. Per-column SLA rail removed the same day. Expert set/change in
place before signing: `changeExpert` + `PATCH /cases/{id}/expert` (PM/ENM/GM), under More beside
Assign/Change CM. **Unit 73 (2026-10-02, D36 edited):** `assign-cm` = CM only; the expert is offered after the first draft (`changeExpert`, PM/CM/ENM/GM, CM standard fee only, `DRAFT_IN_PROGRESS`…`CLIENT_APPROVAL`, publishes `EXPERT_ASSIGNED` → offer mail + chat); CM reads picker + shortlist; client approval no longer needs an expert; board card `hasExpert`/`draftVersionCount`; spec `73-expert-offer-after-draft.md`. Expert evidence request now
reaches Coordinators + CM + PM, stays on Doc checklists while held (chaseable), banner on the case page.

**Unit 54a, note edit/delete — COMPLETE 2026-09-24.** Author-only `PUT`/`DELETE` on a note; `V67`
drops the append-only triggers; the outbox overwrites or deletes the GHL copy; audited without text.
An edit made during its own push is re-queued (`editedSince`), not lost. Outbox `enqueue` is a native
`ON CONFLICT DO NOTHING` (`enqueueIfAbsent`) — the old caught-violation collapse failed the commit (500),
for deal edits too. A delete leaves a link with the contact (`V68` delete marker, null GHL id) when a contact is known.
`enqueueIfAbsent` is `@Transactional` itself: the drain's re-queue is a self-call that skips
`enqueue`'s REQUIRES_NEW.

**Unit 54, two-way note sync — COMPLETE 2026-09-24.** `V66` link table (insert-only) + backfill;
outbox `OPPORTUNITY_NOTE` pushes a note once to the deal's GHL contact (retry reads contact notes
for the `#id8` reference before re-posting); `OpportunityNoteService.on` merges deal + contact
`ghl_note` rows minus echoes; the mirror files GHL notes by `relations` and reads
`createdBy.userId`. No note has been posted to a real contact yet. Locally `EVALOS_GHL_WRITE_MODE=stub` (in `.env`)
means notes are NOT sent and NOT linked; a note written while stubbed is never re-sent.

**Dead-code pass 2026-09-22 — deletions only, no behaviour change, all four suites green either
side.** Gone: nine unimported shadcn wrappers in `client-expert/shared/src/components/ui/`
(`avatar`, `dropdown-menu`, `pagination`, `popover`, `separator`, `switch`, `tabs`, `tooltip`,
`dialog`) plus `common/ConfirmDialog.tsx`, their only reader; `shared/src/utils/storage.ts`,
`constants/storage.ts`, `constants/upload.ts` and `hooks/useMediaQuery.ts`, all mock-auth
leftovers; `frontend/src/components/ui/tabs.tsx`, which no screen imported (`ExpertRoster` has its
own local `Tab`); five unused exports from `shared/src/utils/formatters.ts`, including a
hand-rolled `relativeTime` that `Intl.RelativeTimeFormat` covers; and nine repository finders with
no caller. `frontend/src/components/ui/menu.tsx` is now **`popover.tsx`** — the DropdownMenu and
Tooltip halves had no callers, and `AssignPopover` and `DateFilter` were repointed. Eleven
dependencies left `client-expert/package.json`; `@radix-ui/react-dialog` stays because
`MobileNavDrawer` uses it directly.

**Over-engineering pass 2026-09-30.** Untracked the gitignored `client-expert/expert/.vite/` and
`mail-preview/` (~29k lines). Deleted unimported `select`, `radio-group`, `date-picker`,
`mock/mockDelay.ts`, and deps `@radix-ui/react-select`, `@radix-ui/react-radio-group`,
`framer-motion` (`PageTransition` = CSS `page-enter` keyframe). `HealthController`/`/api/health`
gone — `/actuator/health`. `ClientMailer` injects its one `MailTransport`; `evalos.mail.transport`
removed. `docs/seed-desks.sql` deleted. `formatDateShort` → `formatDate(value, 'short')`.

**Deliberately kept:** `TeamMemberPipelineRepository.membersOn` (no production caller, but a test
covers it). **Deliberately deferred**, as refactors rather than deletions: `MailTransport` is an
interface over one implementation, `ScopedRepository` is inherited by seven repositories that
never call `findScoped`, and much of the javadoc retells git history.

**Use `mvnw clean test`, not `mvnw test`, after a signature change.** The VS Code Java extension
writes error-tolerant classes into the same `target/classes` and Maven's incremental build keeps
them — the suite reports green over stale bytecode, and Mockito then fails with "Byte Buddy could
not instrument all classes within the mock's type hierarchy", which is not a Mockito problem.

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

NOT IMPLEMENTED: outbound webhooks (Handoff C), expert accounts, the GHL mirror
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

**Contact mirror 2026-09-23 — `CONTACT_MIRROR`, hourly.** `GhlContactClient.page()` over
`POST /contacts/search` (cursor `searchAfter`, sort `dateUpdated asc` so a contact edited mid-pass
moves AHEAD of the cursor rather than being skipped), writing via `ContactSnapshotService`. THIS IS
WHAT MAKES THE CONTACTS LIST COMPLETE: before it the table held 32 of the location's 1,407.
Full pass, no high-water mark — 15 pages ~1.7s; switch to `dateUpdated >= lastRun` past ~20k.
`GhlHttp.search()` is a FIFTH helper (not a fifth HTTP verb) for reads GHL exposes as POST; it
refuses any path not ending `/search`, so it cannot become the hole in the write-audit guard.

**`GhlHttpTest` write-audit guard was crying wolf** and is fixed: `\.(post|put|delete)\(` matched
`body.put(...)` on a Map. It now binds the verb to the CAPTURED `GhlHttp` reference name.

**Deal READ scope fixed 2026-09-23 — `PipelineScope.requireVisible`.** ALL=any deal,
BRAND=own brand, PIPELINE=delegates to `requireMine`. Closes `00d` row 73 (P0): `requireMine`
reads pipelines off the principal and D19e says A GM HOLDS NONE AND MUST NOT, so the GM's board
listed every deal and 403'd on opening any. READS WIDENED, WRITES DID NOT — every edit/close/
booking/note is still `requireMine`, and `PipelineScopeVisibilityTest` asserts the GM's write
refusal so the two methods cannot be collapsed later. `SalesMeetingService.forOpportunity` now
queries with the DEAL's brand (the caller's is null for a GM). 403-never-404 unchanged.
`GET /opportunities/{id}/notes` also widened to +GM/+BRAND_MANAGER — the route's own javadoc named
this exact precondition, and `00d` §3.1's "no role can read both note streams" is the DEFECT three
audits found, not an invariant. THE POST STAYS SALES/MARKETING and a test asserts that refusal.

**Contacts directory 2026-09-23.** `GET /api/contacts?search=`, nav `/contacts` under Records.
THREE WIDTHS AND THEY ARE JUST THE TIERS: `GM(Tier.ALL)` = every brand, `BRAND_MANAGER(Tier.BRAND)`
= own brand, `SALES`/`MARKETING(Tier.PIPELINE)` = contacts on the deals they work (join
`contact_snapshot -> opportunity -> pipeline`, match `pipeline.ghl_id`). No brand/pipeline
parameter on the request — a width the client could send is a width the client could change.
JDBC rather than `ScopePredicate` ONLY because the pipeline arm needs a join and `Fields` has no
vocabulary for one; do not add a join axis there for one screen. The contact→deal join is
BRAND-MATCHED as well as id-matched (`ghl_contact_id` is unique per brand, not globally).
PAGED server-side (`?page=&size=`, 15 default, clamp 100); offset not keyset because the pages are numbered and jumped between; total is a separate count wrapping the GROUPED query, since a bare count over the join counts DEALS. `ORDER BY full_name, id` — the id tiebreak is what stops a row showing on two pages. `ContactDirectoryScopeTest` pins every arm and both refusals.

**Deal screen rebuilt 2026-09-23 (two columns).** Left = the record, right = Actions +
Contact details (sticky at `xl`). `DealApplication`/`DealDocuments` became tables (`.tbl`);
`DealApplication` is the "Portal request" panel (service, purpose, submitted, status) since Unit 55. `ContactView` gained `source` / `assignedTo` / `createdAt` — the assignee
is resolved to a NAME via the `ghl_user` mirror so the raw GHL user id never reaches the browser.
`ContactSnapshot.getSourceChannel()` is new. `DealEditDialog` wraps the existing
`PUT /api/sales/opportunities/{id}` (name + value, SALES only).

DOCUMENT TABLE: `Type` and `Verification` are PLACEHOLDER columns (2026-09-23) rendering `—`
and `Not reviewed`, with a note under the table. The document list is still being agreed, and
VERIFICATION IS THE PROJECT COORDINATOR'S WORK — Sales displays the verdict and never sets it.
Do not fill either with a plausible value; a row reading "Verified" that nobody verified is worse
than an empty column. `carried_to_case_document_id` has its own "On the case" column and is NOT
the verification verdict. Format and size sit under the filename, where they are real.

STILL NOT BUILT from that design: a "Hot" lead-temperature badge (nothing scores a lead).
Questionnaires are REMOVED entirely (Unit 55, D13).

**Mail branding 2026-09-23.** The logo is the horizontal mark served from the marketing site —
`MailTemplates.LOGO_URL`, the APEX host (`www` 301s and image proxies drop redirects), sized
`240x57` for a 1230x290 mark; the old `152x70` belonged to the stacked 380x175 portal logo and
would squash this one. The accent is the client portal's `--brand-crimson` `#C8102E` in place of
navy `#003152`, panel tint `#FBECEE` in place of `#EBF4F9`; neutrals unchanged, because an email
whose prose is red reads as a warning. NOT the logo's own red (`#E60914`) — portal token wins.
`MailTemplates.load` now strips HTML comments: `layout.html` is 55% comment and all of it was
being mailed to clients. Safe only while no template uses an Outlook `<!--[if mso]>` conditional.

**Dead-member sweep 2026-09-22 (backend).** Five unused `@Value` imports, `SalesDeskService`'s
second `asDeal` overload, and `OpportunityBoardService`'s `TeamMemberRepository` — kept five days
after the GM's union stopped using it, with `verify(teamMembers, never())` guarding behaviourally
what removing the field now guarantees structurally. Zero unused imports remain in the whole
backend. NOT touched, because they are not dead: Spring's package-private constructors, JUnit
`@BeforeEach`/`@AfterEach` methods, and the fixture arguments `OpportunityBoardServiceTest` passes
for documentation (`mirrored(..., pipelineId, ...)`, `givenMirrored(..., pipelineIds)`) — its own
javadoc says the row does not store them.

**The gap that survived all of that closed 2026-09-22.** None of those writers fires for a contact
that already existed in GHL before EvalOS met it, and no sweep pulls contacts, so the deal screen
was blank for every deal a salesperson typed into GHL. `ContactSnapshotService.findOrFetch` reads
`GET /contacts/{id}` on a mirror miss and keeps the row; `GhlContactClient` is the new read client;
`ContactSnapshotFetchTest` pins mirror-wins, fetch-and-keep, and outage-degrades-to-empty.

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
notifications are in-app **and push since 2026-10-02** (`BellPushNotifier` over chat's
`push_subscriptions`; the GM and Marketing in-app only — no brand / no chat connection); request-stage documents do not exist
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
`opportunity_note`** (EvalOS prose; synced both ways and author-editable since Units 54/54a). **A task EvalOS never
created is not invented.** **Tags are read, never written** — GHL workflows key off them.
**D46 is unblocked**: the mirror now holds the values a queued desk create needs.
Suite: backend **1075**, frontend **127**.

**UI, 2026-09-17:** opening a deal and capturing a lead are **sidebar entries**, not controls on the
board — `/opportunities/new` (SALES) and `/marketing/leads/new` (MARKETING), each its own screen,
returning to the board on success. `NavItem.brandProven` replaces the hardcoded one-path exception
in `navigation.test.ts`: a `readsGhlLocation` screen is GM-only unless it is marked, and a marked
one may only be reached by GM/SALES/MARKETING.

**2026-09-18 — a review pass fixed nine defects in Units 45d–47b and the mail refactor**, none
caught by a test. Server side: the outbox push sent all four shared fields and confirmed with
`save(row)` on a stale entity (both fixed, `V63` + `confirmPushed`); `revoke` was undone by
`backfillFromLegacyColumn` every sweep (`V64`, soft delete); `absorb` stamped freshness BEFORE
absorbing, so a mid-loop failure left a half-absorbed mirror claiming to be current; the four
reference absorbs had no name guard, so one nameless GHL row failed its whole list inside
`guarded()`; `ContactSnapshot.syncFromGhl` blanked name/email/phone/company from a partial webhook
payload; `ClientMailer`'s audit write could 500 a known address and 204 an unknown one — the
enumeration oracle it exists to close; both creates did not write the mirror, so the next edit 400'd
for a full `MIRROR_DELTA`; Refresh re-read pipeline structure on every press; and
`application-local.yml` defaulted S3 to the **live** bucket. **The four client-side findings were fixed and
then REVERTED on request** — `frontend/src` is byte-identical to `9a1f3e7`, so all four are still
live bugs: the deal page's Contact panel hangs on "Loading…" when the contact is not mirrored
(`useMetrics` reads `data === null` as loading, and the endpoint answers 200 with `null` on
purpose); a failed Refresh is silent (`try/finally`, no `catch`); a GM opening any deal gets an
access error under an unusable "Add note" box (`navigation.ts` grants GM, the note controller does
not — **gating the panel was declined, so fix it server-side if at all**); and navigating away
`onOpened` hides "that contact already had an open deal".

**Two findings were rejected and that is part of the record.** `board-stale-after` stays at **5m**
— a business decision of 2026-09-17 over a proposed 15, with the boundary blink already named as
the accepted cost; the defect was the code comment claiming 15m, and it was the comment that
changed. And `syncNow` carries no `@Transactional`, so it was never holding a connection across a
GHL round trip.

**2026-09-18 — the client questionnaire's autosave 403'd at the CORS preflight.**
`PortalSecurityConfig` allowed `GET, POST, OPTIONS`; `PUT /api/portal/applications/{id}` is the
autosave and the only non-GET/POST route on `/api/portal/**`. A refused preflight comes back as a
bare 403 with a plain-text body, so the client could not read a message out of it and fell back to
"We could not save your answers." — nothing was unauthorised and nothing logged an error. Fixed by
adding PUT; `ClientApplicationRoutesTest` now asserts the preflight per method AND that an unserved
verb is still refused, so the list stays enumerated rather than becoming `*`.

**2026-09-18 — the portals workspace served the client app on the expert port.** `npm run dev` was
an alias for `cd client && vite` and neither vite config set `strictPort`, so Vite incremented past
a busy 5174 and a second `npm run dev` answered on **5175**, the expert portal's port. Both configs
now set `strictPort: true`; `dev` refuses and names `dev:client` (5174) / `dev:expert` (5175). The
expert portal also gained a `/` holding page (`expert/src/pages/Welcome.tsx`) — it had no `/` at
all, so the bare origin answered 404. `/case` and `mintForExpert` stay live until the new expert
sign-in process is specced (Q6).

**2026-09-18 — Unit 53 (request documents) BUILT.** `application_document` (`V65`),
`ApplicationDocumentService`, portal routes (POST/GET/DELETE on
`/api/portal/applications/{id}/documents`), Sales routes
(`GET /api/opportunities/{id}/documents` + `/{d}/url`), `RequestDocuments` on the portal review
step, `DealDocuments` on the deal page, and `RequestDocumentCarryForward` at Handoff A. Suite:
backend **1106**, staff SPA 127, portals 30.

Three things not obvious from the code: the S3 object is **never copied or re-keyed** at Handoff A
(the key is the contact's prefix, so the case row points at the same object — the payoff of `53` §1
keying by the person); the carry-forward is an **event listener on `CASE_CREATED`**, a deliberate
deviation from `53` §4 that gets "never fails the case" structurally, since `opportunity.won` is
the only door into a case; and **submit is still never gated on documents** (`43` §5).

Adding DELETE for the document routes **failed `ClientApplicationRoutesTest`'s preflight
assertion**, which is exactly why it exists — one commit before a client would have met it as a
bare 403. Portal CORS is now GET/POST/PUT/DELETE/OPTIONS; PATCH is the unserved verb the negative
assertion uses.

**2026-09-19 — document routes now work on a laptop.** `DocumentStore` takes an optional
`evalos.s3.endpoint`; set it and the client AND the presigner are both overridden (overriding only
the client uploads fine and then hands out AWS URLs that 404) and addressing switches to path-style,
which is what MinIO serves. `docker-compose.local.yml` — a SEPARATE file from the deployment
compose, which is DevOps's (D38) — runs MinIO and creates the bucket. Credentials come from
AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY in the environment, never a profile: `ConfigSecretsTest`
forbids credential defaults. `application-local.yml` names a local bucket again, safe ONLY because
the endpoint is localhost — `DocumentStoreEndpointTest` pins those two together and pins the
endpoint blank in `application.yml` and `application-prod.yml`. **Not verified end to end: Docker
was not running, so nothing has been written to MinIO yet.**

**2026-09-19 — documents go to LOCAL DISK in development, never a bucket.** `evalos.s3.local-dir`
(`EVALOS_S3_LOCAL_DIR` in `.env`, loaded by launch.json as real env vars) makes `DocumentStore`
write to a directory; `LocalDocumentController` serves reads on a five-minute capability token with
`Content-Disposition: attachment`, mirroring a presign. **MinIO was dropped** — it needed a
container running before any document route worked, and `docker-compose.local.yml` was never
written. **Production is unchanged and takes bucket + region from `application.yml`.** Three
guards: blank in `application.yml`/`application-prod.yml`, a startup refusal outside the `local`
profile, and `@ConditionalOnProperty` so the unauthenticated route is not mapped otherwise. The
profile check must use `acceptsProfiles`, NOT `getActiveProfiles` — `spring.profiles.default: local`
leaves the active list EMPTY while `application-local.yml` is loaded, and reading the active list
refused every integration test. Suite: backend **1114**.

**2026-09-19 — GHL WRITES ARE STUBBED ON A LAPTOP.** `evalos.ghl.write-mode` is `stub` in
`application-local.yml`, `live` everywhere else; `StubGhlWriteClient` answers all nine write verbs
locally. **Reads stay live** (the mirror needs the real pipelines/stages/fields, and a faked read
would stop catching shape mismatches). Reason: `location-id` names the BUSINESS'S REAL CRM, so
exercising the portal intake flow created real opportunities somebody had to delete. It refuses to
start outside the `local` profile — a deployment holding it would report every write as successful
and send none, diverging silently and permanently. `EVALOS_GHL_WRITE_MODE=live` to write for real.

**Also 2026-09-19:** a fresh environment has NO pipeline marked INTAKE, and submit then 400'd with
"we could not reach our systems, please try again in a moment" — untrue and unactionable, with a
log line claiming "queued for retry" while queueing nothing. `intakePipeline()` now resolves
outside the outage catch and logs at ERROR; the catch only claims a queue when it made one. Suite:
backend **1121**.

**Desk logins (2026-09-19).** Six: `sales-1..3` and `bde-1..3` (`@evalos.local` locally), password
**`DevPassw0rd!`** — the seeded throwaway, kept on the business's instruction, verified against each
stored bcrypt hash. Sales hold the three service pipelines, BDE the three BDE pipelines. **BDE is
MARKETING, not SALES** (MARKETING opens a lead via upsert; SALES opens a deal via true create and
gets meetings/follow-ups/close). `V911` converges the naming, because five had been renamed by hand
locally while the seeds still said `sales.attorney...`.

**Production seeds them through `db/seed-prod/V960__seed_ie_desks.sql` since 2026-09-19** — a
Flyway migration in a tree only `application-prod.yml` names, the sibling-directory mechanism
`MigrationTreeTest` enforces. It replaced the hand-run `docs/seed-desks.sql` (the pointer left behind was
deleted 2026-09-30; applied V911/V960 still name it, git history holds it). The password is the `desk-password-hash` placeholder from
`DESK_PASSWORD_HASH`, **no default**, so `DevPassw0rd!` — a PUBLISHED credential, hash in V908 and
plaintext in its comments — never reaches a real database. Costs: prod now needs
`out-of-order: true` (a seed numbered above every migration makes the next V-N look out of order),
and `DESK_PASSWORD_HASH` is required on every prod boot, not only the migrating one. V960 sets
`ghl_pipeline_id` but grants no `team_member_pipeline` row on a fresh DB — `pipeline` is filled by
PIPELINE_MIRROR, so the six sign in to an empty board.

**THERE IS NO PIPELINE-ASSIGNMENT SCREEN (found 2026-09-19).** Unit 44b's `PUT`/`DELETE`/`GET
/api/team-members/{id}/pipelines` are GM-only and audited, but nothing in `frontend/` calls them —
it calls one team-member route, `/team-members/assignable` (`features/board/boardApi.ts`), and has
no team-admin feature folder or route. `TeamMemberController`'s javadoc names "the assignment
screen"; it was never built, so Unit 44's COMPLETE means API + model, not an operable GM flow.
Grants are made with curl or SQL, or by re-running V960's second statement after a mirror pass.
There is likewise no create-team-member endpoint, which is why a seed is the only route to a login.

**2026-09-25 — Unit 55: the client questionnaire is removed (D13, spec `55`).** Portal funnel is
Service → Review (documents + send). Deleted: `lib/questionnaire.ts`, `constants/questionGroups.ts`,
`constants/countries.ts`, `components/intake/QuestionField.tsx`, `saveApplication`/`parseAnswers`,
`questionGroupIds`, the question types. Backend: `ClientApplicationService.save`, the portal `PUT`
route, `ClientApplication.answers`, `ApplicationView.answers`; portal CORS methods are now
GET/POST/DELETE/OPTIONS (`ClientApplicationRoutesTest` refuses PUT and PATCH at preflight). Staff:
`DealApplication` is "Portal request", no answers. **`client_application.answers` column NOT dropped
yet — `V71` awaits an explicit go-ahead** (it destroys client data). Suites: backend 1179/0/4 skipped,
staff 131, portals 30, all green.

**2026-09-25 — client portal legal pages.** Public `/privacy`, `/disclaimer`, `/document-retention`
(`client/src/pages/legal/`, JSX content, no markdown dependency; paths + contact in
`constants/legal.ts`). ~~`SiteFooter`~~ (removed 2026-10-01, Unit 72: the summaries are accepted on first sign-in) — was: (three summary paragraphs + links + address) under every screen
via `PortalLayout`, a `PublicLayout` route for the signed-out screens, and `LegalPage`. Linked in
place at sign-up, the document uploader and the send step. The business's "not reviewed by an
attorney" drafting notes are NOT published; attorney review before go-live is theirs to decide.

**2026-09-26 — Unit 57 case chat, PHASE 1 (backend) BUILT.** `V69`: conversations (3 per case),
conversation_members (history, trigger-guarded), messages (text, replies 1 level, soft delete, FTS),
message_reactions, message_reads (watermark), push_subscriptions. Membership computed from the case team,
pipeline Sales, brand ENMs, client account, offered/accepted expert; follows case events after commit
(new `CASE_MANAGER_REASSIGNED`) and the hourly `CHAT_RECONCILE` sweep (first run backfills open cases).
Read-only at CLOSED. REST on /api/chat, /api/portal/client/chat, /api/portal/expert/chat (routes once in
`ChatRoutes`). Live: Ably, one private channel per person, publish never granted. Push: web-push 5.1.2 to
members not present. Never run against a real Ably app. Full suite 1282/0/4 skipped as of phase 1.
**2026-09-28 additions:** `GET me` (`{ kind, id }`, so a client computes "mine" on its own live
events) and reactions now carry `{ kind, id, name }` per reactor (`MessageServiceTest#reactionsCarryWhoReacted`,
`StaffChatControllerTest#meNamesTheCallerInChatTerms`). No staff UI yet (phase 2's staff-app half);
the portal subset of `packages/evalos-chat` is BUILT — see the Unit 58 entry below.

**Unit 58 phases 1–2 (2026-09-27 / 2026-09-28): BUILT.** Phase 1: V70 draft files + comments;
`POST /api/cases/{id}/drafts` (Word + PDF); `CaseDrafts`, `CaseStatusHistory` (was `CaseMilestones`, Unit 76); per-case client routes
(documents, drafts, comments, approve / request changes by version, delivered files gated to
DELIVERED, paid invoices); staff Upload draft + comment thread. Phase 2: `packages/evalos-chat` —
the portal subset (inbox, conversation panel, composer, replies, reactions, unread badge, the Ably
connection, REST catch-up), source-only, imported through a Vite/TS alias (`@evalos/chat`) with
`resolve.dedupe` on `react`/`ably` — no `file:` dependency, no `node_modules` of its own.
**Final-review fixes (2026-09-28):** `markRead` uses whichever of the list's last item and
`conversations[id].lastMessage` is newer, so a reply (never in the top-level list) is marked read
too; `read.moved` for me clears unread only when that watermark matches the conversation's newest
message; reopening an already-loaded conversation now pages it forward the same way a reconnect's
catch-up does (`catchUpOne`, shared), so REST-only — which has no reconnect — sees what was posted
while it was closed; a pending reply is cleared when the conversation changes; the thread
composer's dead reply-cancel "×" no longer renders. Tests: `api.test.ts`, `reducer.test.ts`,
`realtime.test.ts`, `client.test.ts`, `text.test.ts`; frontend suite 72/72. **Not built:** push (phase 4). **Known gaps:** no
component tests (the portals have no Testing Library); never exercised against a real Ably app;
`client-expert`'s `npm run lint` does not scan `packages/evalos-chat`.

**2026-09-28 — Unit 58 PHASE 3 (client portal UI) BUILT.** Case-first: Home (active, then delivered
— `ClientCaseSummary.stepIndex`), `/cases/:caseId` (stepper; Documents, Draft with version tabs,
PDF/Word downloads, per-version comments with page, approve / request changes behind an inline
confirmation; Delivered once delivered; History; `CaseChatPanel`, a Messages tab on phones —
**removed 2026-09-30:** the right column is now a Document checklist with the Draft under it, and
the chat lives only in Conversations, `?c=` opening one; toast and client push land there),
`/conversations` (`ChatInbox` + `ConversationView`), Invoices paid-only; nav Home · Invoices ·
Conversations (unread badge) · My requests; `ChatProvider` in `PortalLayout`. Removed: Documents,
Meetings, DraftReview pages; backend `/case`, `/approve`, `/request-revisions`, case-less
`/documents`, version-less `cases/{id}/approve|request-revisions`, `/meetings` +
`PortalMeetingService` + `GhlCalendarClient.forContact`. Q8 closed (Home is the picker). Draft PDF
downloads, never inline — Q13. Backend 1298/0/0/4; portals 73.

**2026-09-28 — Unit 58 PHASE 4 (push) BUILT — Unit 58 COMPLETE.** `client/public/sw.js` (push →
notification tagged by conversation; click focuses an open tab and routes it by postMessage, since a
reload drops the memory-only token, else opens one — same origin only); `evalos-chat` `core/push.ts`
+ `PushCard` on Conversations (permission only from a click; hidden without VAPID keys); sign-in
returns to the bounced-from page. Staff and expert apps still have no service worker. Portals 76.

**2026-09-28 — Unit 57 PHASE 2 (staff app) BUILT.** `frontend/` gains `ably` and the `@evalos/chat`
alias; `ChatProvider` + `ChatToast` in `AppShell` (not for MARKETING — in no conversation); nav
Conversations (production roles + SALES, unread badge) and `/conversations/:conversationId` (staff
push target); case screen `CaseChat` (Client / Internal / Expert tabs); `public/sw.js`; `PushCard` on
Conversations. Package `client.onIncoming` + `ChatToast`, also in the client portal. Staff suite 134,
portals 77. Left: the expert portal (phase 3's other half).

**2026-09-28 — Unit 57 PHASE 3 (expert portal) BUILT — Unit 57 COMPLETE.** `ExpertChat` beside the case
on `/case` (Expert conversation by case code; `PushCard` in the panel — no nav, so no Messages page);
`expert/public/sw.js`; `createPortalChat(audience)` now in `shared/src/services/portalChat.ts`. Limit:
an expert push opened with no tab open lands on `/case` without the fragment token — closes with Q6.

**2026-09-28 — Q13 built (D51 view first):** `DocumentStore.presignedPdfView` (inline, application/pdf, DRAFT PDFs only), `?view=true` on the client draft file route and staff document route; View PDF first on both screens.

**2026-09-28 — Q11 built (D54):** `AbandonedRequestService` + `GET /api/requests/abandoned` (GM, SALES; caller's brand, GM every brand) + staff `/requests/abandoned` "Unfinished requests".

**2026-09-28 — chat features completed:** staff inbox tabs / open-only filter / search, typing line, participants with presence dots, seen-by, edit and delete of own messages (never for viewers). No backend change. Filters run client-side over the loaded 100.

**2026-09-28 — Unit 59 phase 1 (expert accounts) BUILT.** V72 `expert_account` (bound to `expert.id`) + `expert_credential_token`; `ExpertAccountService`, `/api/portal/auth/expert/{sign-up, forgot-password, sign-in, set-password}` (the first two 204 always); `mintForExpertAccount` = the same party-scoped token; expert portal door, `/set-password`, `/cases`, `/case?caseId=`. Unknown-email: no mail (Q6b closed).

**2026-09-28 — staff-minted expert links REMOVED (Unit 59 complete).** `mintForExpert`, `mintPartyForExpert`, `statusForExpert`, `PortalLinkController`, the portal-links ledger (service, route, dashboard panel) and the expert card's Send-link button are gone; `/case` reads no URL token; `V73` revokes live expert links. Signing in (`mintForExpertAccount`) is the only mint. Backend 1282/0/0/4.

**2026-09-28:** Q6b closed (no mail for unknown emails; the Welcome screen tells experts to use the email they joined with). Status rows fixed: Client Portal COMPLETE, Documents no longer cites Q8.

**2026-09-29 — D56 DONE.** `openLead` skips `upsertOpportunity` when the contact's open deal (`OpportunityMirrorService.linkedFor`, any status — a queued close counts) has a pending outbox push (`SyncOutboxService.isPending`); returns the mirror row, `created = false`. Backend 1284/0/0/4.

**2026-09-29 — D59 edited: read-only expert Payouts (spec 62).** `GET /api/portal/expert/payouts`, `payoutRows`/`ExpertPayoutRow` and the ledger's per-expert finder restored with their four tests; expert app `/payouts` + sidebar link (owed/paid per currency, `payoutTotals`). ENM still settles by hand on the staff screens.

**2026-09-29 — Unit 60 (D55) BUILT, guests excepted.** Spec `context/specs/60-appointments.md`. `V74` re-adds `team_member.ghl_user_id` (unique), linked by email on REFERENCE_MIRROR. `GhlCalendarClient`: cancel (status `cancelled`), notes CRUD, block-slots create/list + event delete, free slots `userId`. Reschedule/cancel/notes require a `meeting` row on that deal (closes a cross-deal write). UI: `MeetingRow` (Cancel, Notes), `BlockedTimeCard`, `BookingForm` slots per member. Guests: GHL has no field → open Q14 (recommend drop).

**2026-09-29 — Unit 61 (D60) BUILT, email excepted.** Spec `context/specs/61-checklist-send.md`. `V75` adds `document_checklist_item.sent_at/sent_by` (existing rows backfilled sent; `V912`/`V952` do the same for the demo seeds). `POST /api/cases/{id}/checklist/send` publishes unsent items and `CHECKLIST_REQUESTED` (intake no longer does); refused when nothing is unsent. CM joins `COORDINATION` and the `/checklists` nav; the board also lists cases with unsent items; the portal hides unsent items and refuses uploads to them.

**2026-09-29 — expert portal sidebar shell.** `expert/src/layouts/ExpertLayout.tsx` wraps `/cases`, `/case`, `/messages` (new): nav Your cases (needs-you count) · Messages (unread), help note, Sign out (full load of `/`), one shared `ChatProvider`, token guard. `/cases`: filter tiles, searchable table, status + needs-you widgets — all from `ExpertCaseSummary`, nothing invented (no payouts, D59; no due dates). `/case`: fact strip, three-column answers. New cases `/new`: `ExpertCaseSummary.offered` (open offer, `openOfferCaseIds`), each offered case has Open case + Your answer (shared `components/Answers.tsx`). Not yet browser-checked.

**Next up (2026-09-29):** see the 'Next up' list at the top of `.claude/implementation-status.md` — D58 four client emails (the checklist one hangs off `CHECKLIST_REQUESTED` from Send), then real-service verification.

**2026-09-29 — Unit 63 (ENM workspace, D61 / D62, D59 edited) BUILT.** Spec `context/specs/63-enm-workspace.md`. `V76`: `PipelinePurpose.EXPERT_HIRING`, `expert.credentials_verified_at`. ENM pipelines derived in `TeamMemberPipelineRepository.ghlIdsFor`; ENM on the board / stage / lead / deal contact + notes gates; `CaseIntakeService` refuses hiring candidates; staff `/hiring` + `/hiring/new`, `HiringActions` on the deal page. `ExpertCaseHistoryService` (`GET /api/experts/{id}/cases`), credentials-verified route, `retakeExpert` (`POST /api/cases/{id}/expert/retake`). Payouts: `confirmByExpert` on `POST /api/portal/expert/payments/{id}/confirm` (staff confirm removed), `GET /api/payouts/summary` + `PayoutSummary` (CSV), labels Pending / Processing / Paid. Notifications `HIRING_PIPELINE_UPDATED`, `PAYOUT_DUE`, `PAYOUT_CONFIRMED`; ENMs get portal declines and overdue signatures. ENM dashboard: hiring-by-stage + payouts-this-month. Candidates are sourced *EvalOS Expert Hiring* in GHL. Suites: backend 1312/0/0/4, staff 139, portals 89; client and expert `tsc -b` clean. Not browser-checked; needs a tagged hiring pipeline in GHL.

**2026-09-29 — payout reports + retake fix (branch `feature/unit-63b-payout-reports`).** Reports weekly / monthly / yearly, grouped by **due date** (the batch's week); `GET /api/payouts/export?period=` CSV of every row (formula-safe `csvField`) beside the totals export. Local run-through found that an accept-then-decline left the case history on Accepted with no retake: `ExpertCaseHistoryService` now reads Rejected / eligibility off the case. Ably key lacks publish capability (40160) — chat works over REST only until fixed. Backend 1315/0/0/4, staff 139.

**2026-09-29 — GM headline + chat names.** *Business won* / *by source* now count sales desks' wins only (marketing nurtures the same leads — adding them double-counted). GM sets the month's target on the tile: `PUT /api/metrics/gm/goal`, `V77` `sales_monthly_goal` (append-only, newest per month wins, `SALES_MONTHLY_GOAL` fallback). Chat: `ConversationView.clientName` (applicant name); inbox is one row per case, `Client name (case code)` + unread, opening the latest conversation; staff Conversations shows Client / Internal / Expert tabs for the open case. Then the one-screen redesign: package `ChatScreen` used by all three apps (search, filter, All/Unread, avatars, ⋮ menus, emoji, paperclip → case documents); client name falls back to `contact_snapshot.full_name`. Chrome-checked staff desktop + expert desktop/phone.

**2026-09-29 — expert Dashboard + Payouts redesign.** `/dashboard` (landing after sign-in): greeting via new `GET /api/portal/expert/me`, range menu, 4 tiles, cases + payouts donuts with 6-month bars, recent activity. `/payouts`: range tiles, earnings trend, status ring, full table (never range-filtered, keeps Confirm received reachable). Desktop top bar: bell (chat unread) + name menu. Backend: `ExpertCaseSummary.signedAt`, `ExpertPayoutRow.dueDate`. SVG charts, no new deps. Chrome-checked.

**2026-09-29 — Unit 64 (remove client requests) SPECCED, then BUILT the same day** (branch `feature/unit-64-remove-client-requests`: `V78`, `CasePortalAccountListener` + `openForCase`, request code / sign-up / staff tabs / Unfinished requests deleted, "My cases"; backend 1288/0/0/4, staff 138, portals 97, Chrome-checked). Spec `context/specs/64-remove-client-requests.md`. Business: no service request from the portal; documents only on a case via the PC/CM checklist; portal account auto-created at case creation (set-password mail), public sign-up removed; `INTAKE` retired; "My requests" → "My cases". Decisions D3a, D3d, D4, D6, D8, D10–D13, D33–D35, D41, D54 edited; i2 closed; specs 43/52/53/55 bannered. Docs and code agree.

**2026-09-30 — Unit 65 (case fee on the offer + Payouts module, D59 edited) BUILT** (branch `feature/unit-65-case-fee-payouts`, spec `context/specs/65-case-fee-and-payouts-module.md`). Built the same day: backend 1333 / staff 149 / portals 100 tests green; the overview uses the shell's period + brand switcher (DateWindow), every register read narrows by brandId, LeftNav links match exactly. Chrome-checked at 1440 on an isolated schema; not at 390, and the expert portal screen not in a browser (its guard via the API). Review fixes the same day: committed money only for the live acceptance (refund / retake / replaced expert), attention list not period-filtered, V80 offer @Version (fee-edit vs accept race → 409), LeftNav exact-match only over a child item. Suites: backend 1339 / staff 151 / portals 100. Restart the backend to apply V79 / V80 / V914. Delivered: `V79` offer fee, fee on assign / reassign / retake, `PATCH /api/cases/{id}/expert/offer/fee`, register / experts / overview / history endpoints, correct-amount removed, portal shows the fee and accept carries it.

**2026-09-30 — Unit 66 (case workspace) BUILT** (spec `66`, branch `feature/unit-66-case-workspace`, frontend only). `CaseHeader` (was `StageActions`): due chip → Deadline dialog (GM/PM, end of day local, reason as note), Upload draft, stage actions + More ▾, next-step line, 12-stage `ProgressStrip` from `caseProgress.ts` (stages before current read as passed even without trail rows). `UploadDraftDialog` + optional note to PM (header, Draft panel, My drafts rows). Draft panel = status + versions + upload (`draftLink` not read). Documents & checklist summary + `ChecklistSheet` (GM/BM/PC/CM). Expert & offer = Unit 65 `OfferFee`, re-read on trail growth. CM gets expert signed/declined/timed-out, not reassign (picker gated). 171 SPA tests; browser-checked at 1440 (PM/CM/PC/ENM); at 390 the header fits and the strip scrolls, but the staff AppShell has no phone layout (sidebar never collapses) and the staff SPA has no dark theme. Next: Unit 67 role queues, Unit 68 GM admin.

**2026-09-30 — Deal screen details BUILT** (branch `feature/deal-details`, no spec: bounded change, design approved in chat). **Bug:** GHL's opportunity *search* sends custom field values as `{id, type, fieldValueString}`, not the documented `{id, fieldValue}`; `CustomFieldValue` read only `fieldValue`, so every mirrored deal's `custom_fields` was `{}` (and a list-valued `fieldValue` failed the page). Now read as a map: `fieldValue`, else any `fieldValue*`, else `value` (contacts). Board service/source were blank for the same reason. **Added:** `V81` (`contact_snapshot.country`, `tags`, `custom_fields`), `ContactSnapshot.syncDetails`, `GhlContactClient` binds them, `model=contact` definitions (`GhlCustomFieldClient.forModel`, per-model absence pass), `GET /api/opportunities/{id}/contact` + `dealFields`/`country`/`tags`/`contactFields` (named, unnamed values dropped), `DealPage`: email/phone/company in the header, everything else (deal fields, then country, tags, contact fields) in an Opportunity details card; Notes moved to the sidebar under Actions, replacing Contact details; no duplicated fields. Live: 767/1,749 live deals with values, 1,466/1,697 contacts with country. Backend 1,345 tests green; frontend build/lint/151 tests green.

**2026-09-30 — Unit 66b (expert opens case documents, D63) BUILT** (spec `66b`, branch `feature/unit-66-case-workspace`, no migration). `ExpertCaseView.documents` = newest `CLIENT_APPROVED` draft (`LETTER`) + client's non-superseded uploads; `GET /api/portal/expert/documents/{id}/url` (view gate, unlisted id 403, inline only for the letter PDF, `EXPORTED` audit). Expert `/case`: View/Download PDF, Download Word, Download per client file. Fixed: letter card read only `draftLink`, so file drafts showed "not ready yet". ENM still refused. Backend expert tests 41 green; portals 100. Not browser-checked (no local data).

**2026-09-30 — Unit 69 (edit every deal field + delete, D66) BUILT** (spec `69`, no migration). `PUT /api/sales/opportunities/{id}` takes name, value, stage, expectedCloseDate, assignedTo, customFields (shared three queued; the rest inline via `GhlWriteClient.updateOpportunityDetails`, then `absorbDetails` on the mirror). `DELETE /api/sales/opportunities/{id}` (SALES): refuses won / pending push, `deleteOpportunity` (404 = done, `AuditAction.DELETED`), `markDeleted` stamps `missing_since`. `ContactView` gains `assignedToId`, `dealFieldValues`. `DealEditDialog`: all fields + inline-confirmed Delete. Tests: SalesDeskServiceTest +4, SalesDeskControllerTest +1; tsc clean. Not browser-checked or run against live GHL. Limits: custom fields cannot be cleared; close date not mirrored.

**2026-09-30 — ENM dashboard 500 fixed.** `resolvedTurnaroundSeconds` is native `extract(epoch from outcome_at - offered_at)::float8` (JPQL `date_part` on a Hibernate-6 duration failed on Postgres). Postgres test added; endpoint verified 200.

**2026-10-02 — staff see an expert's portal account.** Roster column *Portal* and profile fact *Portal account*: Not signed up / Password not set / Active · last in (`ExpertService.PortalAccount` from `expert_account`, brand-scoped batch per page).

**2026-10-02 — Unit 67 built (spec 67):** `RowActions` (select of `actionsFor` → `QuickActionDialog`) on Inbox, My drafts, Expert assignment; CM may reassign an expert; case header names the PM/CM/coordinator (`CaseDetail.team`). Board card still has no buttons.

**2026-10-02 — Unit 68 built (spec 68, V84, D72):** staff create/edit/deactivate/set-password routes + `/admin/staff`; `/admin/pipelines` (purpose — D61's screen); `/admin/sync` (drift + outbox, read-only); `/brands` read-only; `JwtFilter` refuses inactive members per request.

**2026-10-02 — Unit 74 (View and Download on every document, D51 edited) BUILT** (spec `74`, no migration). `DocumentStore.presignedView(key, filename)` serves `inline` with the type forced to PDF / PNG / JPEG from the extension (else 400; Word stays download-only); `view` accepted on staff, expert, client document and client delivered routes. Every document list shows View (when viewable) and Download; staff draft history reads View PDF · Download PDF · Download Word. Read rules and `EXPORTED` audit unchanged. Not browser-checked yet.

**2026-10-02 — Unit 75 (portal sign-in survives a reload, D73) BUILT** (spec `75`, no migration). Token in `sessionStorage`; `signOut` revokes via `POST /api/portal/sign-out` (`PortalAccessService.revoke`); a 401 clears it and returns to sign-in with "Your session has ended". Client portal gains Sign out; expert Sign outs now call `signOut`. Not browser-checked yet.

**2026-10-02 — Draft upload CM-only (D51 edited):** `POST …/drafts` `hasRole('CASE_MANAGER')`, `mayUploadDraft` CM only. `CaseControllerTest` 21 green, `draftRules.test.ts` 8 green.

**2026-10-02 — local demo world V915.** Replaces V905's cases: 30 cases (all 12 stages, all 3 exceptions), 13 experts, 28 demo clients; every client/expert account on `DevPassw0rd!`; team members + GHL mirror kept. Samples in `backend/seed-local-documents/` -> `.local-documents/seed/`. Applies on next backend start.

**2026-10-06 — Unit 76 (client status card, remarks, history; D74) BUILT** (spec `76`, `V85`). `PortalStageProjection.ClientStatus`, `CaseStatusHistory` (replaces `CaseMilestones`), `case_client_remark`, `ClientRemarkService`/`Controller` (`/api/cases/{id}/client-remarks`); `ClientDraftView` has `status` + `history` instead of `milestones`. Client portal: Current status card + History, stepper removed; staff: Update for the client panel, hold reason shown to client. Backend 1423/0/0/4, staff 194, portals 112. Not browser-checked.

**2026-10-06 — Case header opens the opportunity.** Name / card click opens a read-only *Opportunity details* pop-up (`CaseDealDialog`) via `GET /api/opportunities/for-case/{caseId}` (`CaseOpportunityService`; GM/BM/PM/PC/CM, not the ENM). Backend 1427/0/0/4. Not browser-checked.

**2026-10-06 — Brand colours.** Both portals (`shared/src/styles/globals.css`) and all client emails now use the logo's palette: navy `#10264A`, red `#E31B23`, text `#142B4A`, page `#F5F7FA`, Inter/Arial. Emails put the logo in a banner (320x75) over a red stripe in a white 14px card. Supersedes crimson `#C8102E` / navy `#003152` and DM Sans.

**2026-10-07 — Installable apps.** Staff app and both portals have a web app manifest, 192/512 icons and apple-touch-icon so iOS can install them and deliver web push (Home Screen only). Not tested on a device. DevOps must serve the manifest and icons as static files.

**2026-10-07 — Security review.** Won webhook now confirmed with GHL (`GhlOpportunityHandler`). No live secret found in git history; owner must rotate the GHL token and use non-committed `JWT_SECRET` / `EVALOS_FIELD_KEY` in prod. Header token, rate limit, hashed token, rotation runbook not done.

**2026-10-07 — Push to a closed browser.** `refreshPush`/`<PushRefresh>` re-subscribe silently on every signed-in load (key change, expired subscription); `PushSender` uses `Urgency.HIGH` and logs refused sends; opt-in card on both Home pages. Needs `EVALOS_PUSH_VAPID_*` + `EVALOS_PUSH_SUBJECT` on the server and `/sw.js` static. Portal sign-in storage (sessionStorage, D73) unchanged pending the owner's decision. Not device-tested.

**2026-10-07 — D73 amended:** the portal token moved from `sessionStorage` to `localStorage` (closed browser stays signed in; push notification opens signed in). Server expiry 7 days (`PORTAL_PARTY_LINK_TTL`), Sign out revokes, 401 clears. Portals 114 tests.

**2026-10-07 — Case-event push.** `CasePushNotifier` + `CaseEvents.Type.CLIENT_REMARK_ADDED`; `CasePushNotifierTest` (5); backend 1437/0/0/4. Same VAPID prerequisites as chat push. Not device-tested.

**2026-10-07 — GM 'By desk' fixed.** It read the legacy `team_member.ghl_pipeline_id` (unwritten since 44b); now sums each desk over its granted set (`ghlIdsFor`), same as the board. Unit 36 is built and was widened by 44b. Backend 1438/0/0/4.

**2026-10-07 — 'Open' meant every deal.** Board totals (and the GM/desk 'Open deals', 'No movement 7d+') counted won and lost rows; now open only (`isOpen`, `openDeals`). Sweeps healthy, no drift. Column counts still include won/lost cards. Backend 1439/0/0/4, staff 196.
