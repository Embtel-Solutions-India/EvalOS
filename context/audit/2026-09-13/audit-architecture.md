# EvalOS — Independent Architecture Audit

**Lens:** domain model, boundaries, ownership, monolith fitness, multi-tenancy, invariants, mirror readiness, ENM.
**Date:** 2026-09-13. **Branch:** `development` @ `dee45c6`.
**Method:** read the JPA entities, all 46 Flyway migrations, every repository interface, the scoping mechanism, the `event/` and `job/` packages, and the three programme specs. Docs were used only to state the *claim*; the code was used to state the *fact*.

---

## Inventory

### Real tables (20 — that is all of them). `backend/src/main/resources/db/migration/V1..V46`

| Table | Entity | Scoped? | Notes |
|---|---|---|---|
| `brand` | `domain/Brand.java:16` | n/a (root) | `webhook_endpoint_token`, `currency`, `payout_term_days`. **No `ghl_location_id` column** — see F9. |
| `team_member` | `domain/TeamMember.java:23` | `brand_id` nullable (GM) | **Not** a `ScopedEntity`. Carries `ghl_pipeline_id` (`:61`), `segment`, `reports_to`. |
| `evalos_case` | `domain/Case.java:25` | `ScopedEntity` + `team_id` + 4 assignee cols | **53 columns.** The god-entity. |
| `contact_snapshot` | `domain/ContactSnapshot.java:29` | `ScopedEntity` | GHL-owned mirror. `ghl_contact_id` nullable, `email` nullable and **not unique**. |
| `client_account` | `domain/ClientAccount.java:32` | `ScopedEntity` | Unit 42. EvalOS-owned identity. `ghl_contact_id` nullable *link*. |
| `client_credential_token` | `domain/ClientCredentialToken.java:24` | `ScopedEntity` | set/reset password; `purpose`, `used_at`. |
| `portal_access` | `domain/PortalAccess.java:42` | `ScopedEntity` | Four-way polymorph: `case_id`, `expert_id`, `ghl_contact_id`, `client_account_id`, all nullable. |
| `expert` | `domain/Expert.java:32` | `ScopedEntity` | ~50 columns incl. encrypted `payment_detail` (`:146`). |
| `expert_case_offer` | `domain/ExpertCaseOffer.java:30` | `ScopedEntity` | offer/outcome append record. |
| `case_document` | `domain/CaseDocument.java:32` | `ScopedEntity` | `kind` ∈ {DRAFT, CLIENT_UPLOAD, SIGNED_LETTER}, `version`, `object_key`. |
| `document_checklist_item` | `domain/DocumentChecklistItem.java:18` | `ScopedEntity` | label + status only. |
| `payout_ledger` | `domain/PayoutLedger.java:23` | `ScopedEntity` | `case_id`, `expert_id`, `payment_id`. |
| `payout_payment` | `domain/PayoutPayment.java:26` | `ScopedEntity` | batch settlement. |
| `notification` | `domain/Notification.java:18` | `ScopedEntity` | in-app only. |
| `audit_event` | `domain/AuditEvent.java:33` | `brand_id` **nullable**, deliberately not `ScopedEntity` | append-only; `V10` trigger blocks UPDATE. |
| `webhook_event` | `domain/WebhookEvent.java:34` | `brand_id` nullable | inbound archive + dedupe. |
| `scheduled_job` | `domain/ScheduledJob.java:31` | **none** | job-run ledger, cross-brand by design. |
| `opportunity_note` | `domain/OpportunityNote.java:37` | `brand_id` + `ghl_pipeline_id` hand-rolled | **not** a `ScopedEntity`; append-only via `updatable=false` on every column. |
| `ghl_opportunity_cache` | `domain/CachedOpportunity.java:34` | **no `brand_id` at all** (`V40` decision) | GHL's id is the PK. |
| `ghl_funnel_cache` | `domain/GhlFunnelCache.java:47` | **no `brand_id`** | GM-only aggregate cache. |

### Entities the target names that **do not exist as tables**

`Opportunity` (only a droppable cache), `Pipeline`, `Stage` (a Java enum `domain/Stage.java` — 12 EvalOS *production* stages, unrelated to GHL stages, which are opaque `stage_id` strings), `Payment`/`Invoice` (a `boolean paid` + `deal_value` + an unused `invoice_ref` text column), `Draft` (a `draft_link` string + `draft_version_count` int + `case_document(kind=DRAFT)`), `Approval` (two enum columns on `Case`), `Task` (nothing — `GhlWriteClient.createFollowUp` writes a task **into GHL** and keeps no EvalOS row), `Note` on a case (written into `audit_event` as `NOTE_ADDED`), `Questionnaire`, `Lead`/`Candidate`/`ExpertApplication`.

### Packages

`common`(10) `domain`(55) `event`(1) `integration`(9) `job`(11) `notification`(3) `repository`(21) `security`(9) `service`(50) `web`(27) `webhook`(5). Tests: 93 files.

### Key mechanism files

- **Scoping:** `repository/ScopedRepository.java:26`, `service/ScopePredicate.java:82`, `service/OwnershipGuard.java:19`, `security/TenantContext.java:14`, `domain/ScopedEntity.java:46` (`@PrePersist` brand guard), `service/PipelineScope.java:22`.
- **GHL:** `integration/GhlHttp.java:60` (one `RestClient`, **one `locationId`**), `GhlWriteClient.java:47`, `GhlOpportunityClient`, `GhlPipelineClient`, `GhlInvoiceClient`, `GhlCalendarClient`.
- **Events:** `event/CaseEvents.java` (one class, ~30 enum types) → `notification/NotificationListeners.java:150` (the only `@EventListener` in the codebase).
- **Jobs:** `job/JobLock.java:116` (`pg_try_advisory_lock`), `job/SweepRunner.java:64`, 4 sweeps.
- **Invariant tests:** `backend/src/test/java/com/ie/evalos/domain/DomainInvariantsTest.java` (8 tests), `integration/GhlHttpTest.java`, `repository/CachedOpportunityRepositoryScopeTest.java`, `config/MigrationTreeTest.java`, `config/ConfigSecretsTest.java`.

---

## Findings

### F1 — `Case` is the bridge for the operational half only. The commercial half does not reach it. — REDESIGN / P0

**What exists.** From the operational end the chain is real and FK-backed:
`evalos_case.expert_id → expert(id)` · `case_document.case_id → evalos_case(id)` · `payout_ledger.case_id → evalos_case(id)` · `payout_ledger.payment_id → payout_payment(id)` · `expert_case_offer.case_id`. **case → expert → draft → approval → payout traverses today.**

From the commercial end it does not:

- **client → case** goes `client_account.ghl_contact_id` (text, nullable, no FK) → `contact_snapshot.ghl_contact_id` (text, nullable, **not unique**) → `contact_snapshot.id` → `evalos_case.contact_id`. Two string hops through an identifier GHL owns. `service/PortalCaseService.java:525-529` is the entire join, and it fails closed on null. `V45__seed_client_accounts.sql` copies `ghl_contact_id` forward *specifically because* the join would otherwise return an empty case list — the migration comment admits this is the only link there is.
- **opportunity → case** is `evalos_case.ghl_opportunity_id` (text, nullable, no FK; uniqueness only from the partial index in `V24__case_ghl_opportunity.sql`). The other side of that join is `ghl_opportunity_cache`, which is **truncatable by design** (`V40` comment). The link exists but has no durable counterparty inside EvalOS.
- **payment → case does not exist at all.** There is no payment row. `evalos_case.paid` (bool) + `paid_at` + `deal_value` is the entire record of money in. `invoice_ref` is written once at intake (`service/CaseIntakeService.java:318`) and **read by nothing** — grep returns only the setter and two "we don't show this to the portal" javadoc mentions. `service/PortalInvoiceService.java:61` fetches invoices live from GHL **by contact id, not by case**, and its own javadoc states it cannot narrow to one case because GHL offers no such filter.

**What the target needs.** One continuous Lead → … → Completion chain with the Case as the bridge, navigable in one query.
**The gap.** Three of the seven hops are string-keyed on an identifier owned by a system that was replaced two days ago, and one hop (payment) has no representation at all. A "show me this client's money, deals and cases" screen cannot be written today without calling GHL.
**Decision: REDESIGN** — `contact`, `opportunity` and `payment` become first-class EvalOS rows with UUID PKs and real FKs. `00c` §2 already specifies the first two; **payment is in no spec.**
**Dependencies:** Unit 44. **Priority: P0.**

### F2 — `CachedOpportunity` has no `brand_id` and uses GHL's id as its primary key. Both block the mirror, and the write model blocks it harder. — REMOVE / P0

**What exists.** `domain/CachedOpportunity.java:37-39`: `@Id @Column("ghl_opportunity_id") String`. `V40__ghl_opportunity_cache.sql` states "Deliberately NO brand_id … this is a decision rather than an omission", justified by the single-brand ceiling. `repository/CachedOpportunityRepository.java` extends plain `JpaRepository<CachedOpportunity, String>`; **all six of its methods are pipeline-keyed and none is brand-scoped** — correct only while one brand sells.

**What the target needs.** `00c` §2a: EvalOS mints its own `id` and keeps `ghl_id` beside it, because a portal-born opportunity exists before GHL has seen it.

**The gap — four blockers, not one:**

1. GHL's id as PK means a portal-born row **cannot exist**, and when GHL later assigns an id the row's identity would change, taking every FK with it.
2. No `brand_id` means a second brand's opportunities are indistinguishable from the first's. The single-brand ceiling is load-bearing *schema*, not a config setting.
3. **`OpportunityCache.replace()` (`service/OpportunityCache.java:88`) is a wholesale delete-and-reinsert per pipeline.** Any row carrying local state — a `sync_state`, an outbox reference, a portal-born row with no `ghl_id` — is destroyed on every board refresh. This is the deepest blocker and is not mentioned anywhere: the current write model is "truth lives elsewhere, replace everything"; the mirror's is the exact opposite.
4. **Going further than the known problem:** `PipelineScope.requireMine()` (`service/PipelineScope.java:63`) **authorises writes against the cache**. A droppable table is the authorisation oracle for every Sales and Marketing mutation. Its javadoc calls the false-negative "the correct direction" — true for a cache, but it means access control now has a TTL, and truncating the table denies every desk.

**Decision: REMOVE**, replaced by `opportunity` + `pipeline` + `pipeline_stage` per `00c` §2. **Dependencies:** Unit 44. **Priority: P0.**

### F3 — Notes are two unrelated mechanisms, and neither is "shared case context". — ADD / P0

**What exists.**

- **Opportunity notes:** a real table (`opportunity_note`), keyed on `ghl_opportunity_id`, brand + pipeline stamped from the principal at write time (`service/OpportunityNoteService.java:77`), append-only via `updatable=false`. Readable **only** by the one `Tier.PIPELINE` role that owns that pipeline (`PipelineScope.requireMine`).
- **Case notes:** **not a table.** `CaseLifecycleService.addNote` (`:394`) writes an `audit_event` with `AuditAction.NOTE_ADDED` and the text in `after_snapshot`; `CaseTimelineService` renders them back out of the audit trail.
- Nothing joins the two. One is keyed on a GHL string, the other on `(object_type='case', object_id=uuid)`.

**What the target needs.** "Notes/activities are shared opportunity/case context, visible across roles subject to permissions — so nobody has to ask a colleague for status."

**The gap.** (a) A PM cannot see what Sales said about the deal that became their case, and Sales cannot see production progress — no join key, no overlapping reader role. (b) Notes-in-audit conflates "somebody said something" with "the system recorded a transition", so one table is both the compliance log and the collaboration surface, and one retention policy has to serve both. (c) `Tier.PIPELINE` matches **nothing** outside its own pipeline — `ScopePredicate.java:131-136` returns `cb.disjunction()` — so opportunity notes are *structurally* unreadable by production roles. This is not a missing `@PreAuthorize`; it is the scope model itself.

**Decision: ADD** one `note` table keyed polymorphically on `(subject_type, subject_id)` with subject ∈ {opportunity, case, contact, expert}, plus a visibility axis that is **not** the reader's scope tier.
**Dependencies:** F1, F2 (needs a durable `opportunity.id` to key on). **Priority: P0** — the most-cited requirement in the target statement and the least built.

### F4 — Multi-tenancy is structural at write, by-convention at read, and absent across foreign keys. — MODIFY / P0

**Three layers, assessed honestly:**

1. **Write (strong).** `ScopedEntity.@PrePersist` (`domain/ScopedEntity.java:46`) refuses any scoped row with a null brand; `brand_id` is `updatable=false`; `DomainInvariantsTest.aScopedRowWithNoBrandIsRefusedBeforeItIsWritten` pins it; `OwnershipGuard.assertCanAct` checks brand before mutation. This half is real.
2. **Read (by convention).** `ScopedRepository.findScoped` is the only scoped read, and its own javadoc names the hole: *"The inherited `findAll()` and `findById()` are not scoped — Spring Data provides them and they cannot be removed."* **No test forbids a service calling `findById`**, and services do: `service/PortalCaseService.java:303, :345, :348, :505` (with a documented token-scope justification). `CaseLifecycleService` scopes on entry, then loads children by `case_id` alone.
3. **Referential (absent).** `grep -iE "references|foreign key"` across all 46 migrations returns **no composite FK carrying `brand_id`** — every child FK is single-column (`case_id → evalos_case(id)`, `expert_id → expert(id)`). Nothing at the database level prevents `case_document(brand_id=A, case_id=<a brand-B case>)`. The guarantee rests entirely on ~50 service classes remembering.

**Repository methods with no brand predicate — the full list over EvalOS rows:**

| Method | File:line | Verdict |
|---|---|---|
| `findByCaseIdAndKindOrderByVersionDesc`, `findFirstByCaseIdAndKind…` | `repository/CaseDocumentRepository.java:35,43` | unscoped |
| `findByCaseId`, `isChecklistComplete` | `repository/DocumentChecklistItemRepository.java:29,54` | unscoped |
| `findByCaseIdAndOutcome`, `findByCaseIdOrderByOfferedAtDesc` | `repository/ExpertCaseOfferRepository.java:46,61` | unscoped |
| `findByCaseIdAndStatus`, `findByPaymentId` | `repository/PayoutLedgerRepository.java:33,111` | **unscoped — money** |
| `findByCaseIdAndAudienceOrderByCreatedAtDesc`, `findByClientAccountIdOrderByCreatedAtDesc` | `repository/PortalAccessRepository.java:52,86` | **unscoped — credentials** |
| `findByRecipientId…`, `countByRecipientIdAndReadFalse`, `findByIdAndRecipientId`, `existsByCaseIdAndType`, `markAllReadFor` | `repository/NotificationRepository.java:40,43,50,53,68` | unscoped (recipient implies a brand, does not assert one) |
| `findByObjectTypeAndObjectIdOrderByCreatedAtAsc` | `repository/AuditEventRepository.java:28` | unscoped |
| `findByGhlOpportunityIdOrderByCreatedAtDesc` | `repository/OpportunityNoteRepository.java:34` | unscoped by design, javadoc'd; caller composes the Specification |
| *every method* | `repository/CachedOpportunityRepository.java` | **no brand column exists** (F2) |
| *every method* | `repository/GhlFunnelCacheRepository.java` | documented invariant-1 exception |
| `findByEmailIgnoreCaseAndActiveTrue`, `findByGhlPipelineIdAndActiveTrue`, `findByActiveTrueAndRole` | `repository/TeamMemberRepository.java:23,45,71` | login / cross-brand admin — justified |
| `findAllAtStageForSweep`, `findActiveForSweep`, `countCasesPerExpert` | `repository/CaseRepository.java:178,187,129` | sweeps, cross-brand by design |
| `findByTokenHash` | `PortalAccessRepository:41`, `ClientCredentialTokenRepository:32` | the token *is* the key — correct |

**Assessment.** ~18 unscoped finders. Most are child-of-a-scoped-parent and defensible *individually*; collectively the brand boundary is held by discipline. `DomainInvariantsTest:96` checks every `ScopedEntity` *has* a repository declaring scope fields — it does **not** check that anything uses it.

**Decision: MODIFY** — add `(brand_id, id)` composite unique keys on parents and composite FKs on children, so the database refuses a cross-brand child. One migration; converts the whole defect class from "a reviewer must notice" to "the insert fails".
**Dependencies:** none. **Priority: P0** — cheapest structural win in this audit.

### F5 — `CaseLifecycleService` is a god class, and it is the only place production logic lives. — REFACTOR / P1

`service/CaseLifecycleService.java`: **1280 lines, 12 injected collaborators** (`:134-151`), **~40 public `@Transactional` methods** spanning assignment (`assignPm`, `assignCoordinator`, `assignCaseManager`, `reassignCaseManager`), notes, intake facts, deadlines, documents (`readUrl`, `versionsOf`), the whole draft cycle, the whole expert cycle, **and a duplicated portal-vs-staff variant of six operations** (`clientApproveDraft`/`clientApproveDraftFromPortal`, `expertSigned`/`expertSignedFromPortal`, `expertDeclined`/`…FromPortal`, `clientRequestRevisions`/`…FromPortal`, plus `expertAcceptedFromPortal`, `expertRequestEvidenceFromPortal`).

That twinning is the real smell: the pair differs only in *how the subject was authorised* (scoped load vs portal token), so an authorisation decision has been pushed into the domain method and every operation now exists twice.

**Credit where due:** layering is otherwise correct. **Zero `@Transactional` in `web/`**; only `AuthController` and `ExpertPickerController` touch a repository directly; `open-in-view: false`. The mass is all in one service, not smeared into controllers.

**Decision: REFACTOR** — split by lifecycle phase (intake/assignment, drafting, expert, delivery) and collapse the `FromPortal` twins by passing an already-authorised `Case`. Not urgent on its own, but Sales/Marketing/ENM growth lands next door.
**Dependencies:** none. **Priority: P1.**

### F6 — The event bus is synchronous, in-process, single-consumer, and half-dead. — MODIFY / P1

`event/CaseEvents.java` declares ~30 event types. Grep finds exactly **one** consumer: `notification/NotificationListeners.java:150`, a plain `@EventListener` — **not** `@TransactionalEventListener`. So a listener throw rolls back the business transaction, and no listener ever runs after commit. Several declared types are documented as **published by nothing** (`CASE_PAID`: *"Dead: nothing publishes this, and nothing should subscribe to it"*). The outbound dispatcher that was the second consumer left with Unit 18.

**The gap.** `00c` §4d brings back a durable outbox for GHL pushes. An in-process synchronous publisher with one consumer cannot carry that — and the sync engine will be the highest-volume event producer in the system.
**Decision: MODIFY** — move to `@TransactionalEventListener(AFTER_COMMIT)` now (one line per listener; removes a real rollback coupling), and build the outbox as a table + sweep per `00c` §4d, **not** as a third listener. Delete the dead enum constants whose wire names are not persisted on existing rows.
**Dependencies:** Unit 45. **Priority: P1.**

### F7 — The job mechanism is sound and fits the sync engine unchanged. — KEEP / P2

`job/JobLock.java:116` uses session-scoped `pg_try_advisory_lock(hashtext(?))` with an explicit unlock (`:126`) — correctly **not** the `_xact_` variant. `SweepRunner` records every run to `scheduled_job` with seen/acted/error counts. Four sweeps, none of which transitions a case (grep confirms no `EXPERT_TIMED_OUT` call site in `job/`). Failure recovery is per-item: a failing item is counted and the sweep continues.

One real gap: `scheduled_job` has no `brand_id` and the sweeps read cross-brand (`CaseRepository.findActiveForSweep`). Correct for a sweep; it means the job ledger cannot answer "what ran for this brand". Acceptable.
**Decision: KEEP.** `00c` §4a's delta sweep and nightly audit fit this shape as-is. **Priority: P2.**

### F8 — Invariant enforcement: what is real, what is only a test, what is only prose.

| # | Invariant | Actually enforced by | Verdict |
|---|---|---|---|
| 1 | brand isolation | `ScopedEntity.@PrePersist` (write), `ScopePredicate` (read, *if called*), 2 tests | **Partly structural.** Reads rely on convention — F4. |
| 2 | one custody / EvalOS runs no sales-invoicing | `GhlHttpTest` (verb list closed; every write caller reaches `AuditService`), `DomainInvariantsTest.onlyTheGhlOpportunityHandlerCanCreateACase` | **Test-only.** Both halves are structural source tests; nothing in the runtime. |
| 3 | role/brand/ownership before every mutation | `OwnershipGuard` + `@PreAuthorize` | **Enforced**, per-call-site. |
| 4 | `payment_detail` never readable | `PaymentDetailConverter`, no read endpoint, `ExpertImportService` refuses the mapping | **Enforced, genuinely well.** |
| 5 | paid ∧ delivered = revenue | `RefundService.isRevenueRecognized` (`:84`) — one static method, one read path | **Enforced as claimed.** |
| 6 | controllers thin, no long work in web | zero `@Transactional` in `web/`; sweeps live in `job/` | **Enforced.** The `MarketingPipelineService` background-thread exception is real and documented. |
| 7 | `ghl_contact_id` is the canonical client identity | **nothing in code** | **Dying.** `ClientAccount` already made it a nullable link; `00c` Unit 44 rewrites it whole. It survives only as the join key in `PortalCaseService:528` — and the sub-account replacement made every stored value name a dead contact. Prose, not enforcement. |
| 8 | a case is born only of a won opportunity | `DomainInvariantsTest:123` | **Test-only, and load-bearing.** The client SPA's mock intake funnel is the standing pressure on it. |
| 9 | migrations never edited in place | `config/MigrationTreeTest` | **Test-enforced.** |
| 10 | webhooks brand-resolved, deduped, archived | `WebhookGateway` + `webhook_event` unique key (`V13`) | **Structurally enforced.** |
| 11 | outbound signed / retried / dead-lettered | **nothing — the mechanism was deleted with Unit 18** | **DEAD.** Returns narrowed at Unit 45. |
| 12 | webhook transport carries no business logic | package shape only | **Convention.** No test. |
| 13 | append-only audit | `V10` DB trigger + `AuditEventRepository` exposes no save + `DomainInvariantsTest:188` | **Strongest invariant in the system** — three levels. |
| 14 | hosts no files / sends one kind of mail | no blob column, `DocumentStore` streams, `ClientMailer` has exactly two methods | **Enforced by absence.** The "never spools to disk" property rests on `file-size-threshold == max-file-size` in `application.yml` — a config line, not a test. |
| 15 | no AI anywhere | `DomainInvariantsTest:157`; no model dependency in the build | **Enforced.** |

**Summary: 1 dead (11), 1 dying (7), 4 test-only (2, 8, 9, 15), 1 convention-only (12), the rest genuinely enforced.** The pattern is healthy. Note, though, that the two invariants protecting the *most expensive* thing — 2 (the GHL boundary) and 8 (the door into custody) — are held by structural source tests alone.

### F9 — The single-brand ceiling is not a setting. It is schema and transport. — REDESIGN / P0

**What exists.** `GhlHttp` holds **one** `locationId` field injected from `evalos.ghl.location-id` (`integration/GhlHttp.java:80`), and `GhlWriteClient.upsertContact` puts `http.locationId()` straight into the request body (`:84`). `brand` has **no** `ghl_location_id` column — checked against every migration. `ghl_opportunity_cache` and `ghl_funnel_cache` have no `brand_id`. `evalos.ghl.sales-brand` names the one brand permitted to hold a pipeline at all.

**The gap.** Multi-brand is the product's first stated property (`CLAUDE.md`: "multi-brand credential-evaluation business… International Evaluations and XpertsPortal"), and every GHL-touching surface is hard-wired to one location **at the transport layer**. This is not "Unit 25 adds a column": `GhlHttp` must become per-brand, and so must the rate limiter — which is per-*location* by GHL's own rule, so a shared pacer across two locations is both wrong and, if merged, invisibly wrong.
**Decision: REDESIGN** — `brand.ghl_location_id` + `brand.ghl_token` (encrypted, same treatment as `payment_detail`), and a `GhlHttp` resolved per brand with a per-location pacer. `00c` §5 schedules the *consequence* at Unit 44; the transport change is upstream of it.
**Dependencies:** F2. **Priority: P0** — on the mirror's critical path whether or not a second brand is imminent.

### F10 — Data ownership: three contradictions and one duplicated truth already shipped. — MODIFY / P1

| Entity | System of record **today** | Target says | Contradiction |
|---|---|---|---|
| Contact identity | GHL (`ghl_contact_id`) | EvalOS `contact.id`, `ghl_id` a link (`00c` §2a) | **Duplicated truth, live now.** `contact_snapshot` (GHL-owned, read-through) and `client_account` (EvalOS-owned: `first_name`, `last_name`, `phone`, `email`) both hold the client's name and phone, with **no FK between them** and no rule about which wins. `client_account.email` is `updatable=false` while `contact_snapshot.email` syncs from GHL — so the two diverge silently and permanently. |
| Opportunity | GHL | EvalOS mirror, two-way | `CachedOpportunity` is explicitly not-truth (`V40`) yet `PipelineScope` authorises writes from it — it is already load-bearing truth for access control (F2.4). |
| Pipeline / stage | GHL (opaque strings) | mirrored, same ids both sides | No table. `team_member.ghl_pipeline_id` is a free string with **no referential integrity and no validation**; `TeamMemberRepository.findByGhlPipelineIdAndActiveTrue` trusts it. A typo silently grants an empty desk and is indistinguishable from a fail-closed scope. |
| Invoice / payment | GHL | GHL until Unit 49 | Consistent, but `evalos_case.invoice_ref` is dead weight (F1) — drop it or wire it. |
| Case, Expert, Payout, Document, Checklist, Portal access | EvalOS | EvalOS | **Clean.** No contradiction anywhere. |
| Note | split (F3) | shared | Contradiction — F3. |

**Decision: MODIFY** — make one record the client, with `client_account` reduced to credentials and `contact_snapshot` folded into the mirror's `contact` at Unit 44. **Dependencies:** Unit 44. **Priority: P1.**

### F11 — The dead-contact blast radius is wider than the docs state, and one branch hard-breaks the new signup path. — MODIFY / P0

`CLAUDE.md` and `00c` §1 say every stored `ghl_contact_id` names a contact that no longer exists. Tracing the actual call sites (`grep ghlContactId service/ web/`), the consequences split three ways:

- **Degrades silently to empty (accepted and documented):** `PortalInvoiceService:61`, `PortalMeetingService:66` — call GHL with a dead id, get nothing back.
- **Still works (a local join on a stale string):** `PortalCaseService:528` — `client_account.ghl_contact_id → contact_snapshot.ghl_contact_id → cases`. Both sides are EvalOS rows holding the same dead string, so the join holds. This is precisely why `V45` copied the id forward instead of seeding null.
- **Hard-breaks, and is named nowhere:** `PortalCaseService.upload` (`:355-363`) builds the S3 object key as `DocumentStore.clientKey(brandId, ghlContactId, documentId)` behind `requireState(ghlContactId != null, …)`. **A client whose account has no GHL contact id cannot upload a document at all** — it throws. Every *new* client created after the cutover (signup, or a client with no GHL contact) is in exactly that state. The new-client front door Units 42/43 just opened leads to a portal where document upload fails.

Compounding it: the S3 key is namespaced by an identifier GHL owns and has now demonstrated it can revoke. Documents uploaded before 2026-09-11 live under keys naming contacts that no longer exist, so any future re-keying is a bulk object copy, not a migration.

**Decision: MODIFY** — key client documents on `case_id` (or `client_account.id`); both are EvalOS-owned and never reissued. **Dependencies:** none — independent of the mirror. **Priority: P0** — it blocks Unit 43.

### F12 — Expert Network Management is a roster and a dashboard, not a domain. — ADD / P1

**What exists.** `Expert` (~50 columns: credentials, fields, tiers, quality score, availability, fee, `recruitment_source`, `date_onboarded`, `agreement_status` ∈ {SENT, SIGNED, EXPIRED}), `ExpertCaseOffer`, `PayoutLedger`, `PayoutPayment`, `ExpertMatchService` (4-factor arithmetic ranking, no model), `ExpertLoadService`, `ExpertImportService` (709 lines, sheet upload), `ExpertNetworkMetricsService` (roster health, field coverage, onboarding count vs target, acceptance rate, decline counts, turnaround median), `ExpertPortalService`, and three screens: `/experts`, `/payouts`, `ExpertNetworkDashboard`.

**What does not exist — no hiring or onboarding pipeline of any kind:**

- **No candidate or application entity.** `Expert` rows are created directly (`ExpertService.create`, `:295`) or bulk-imported. There is no pre-expert state.
- **No stage model.** `Availability` (AVAILABLE / AT_CAPACITY / INACTIVE / ON_LEAVE) is **capacity, not lifecycle**. There is no SOURCED → CONTACTED → SCREENING → AGREEMENT → ACTIVE.
- `AgreementStatus` is the only lifecycle-shaped field: three values, **no transition guard**. Compare `service/CaseTransitions.java` (225 lines), a real state machine for cases.
- `ExpertNetworkMetricsService.Onboarding(thisMonth, target)` counts `date_onboarded` inside a window — a KPI over a timestamp with no funnel behind it to explain the number.
- `Role.EXPERT_NETWORK_MANAGER` is `Tier.SUPPLY`, which reads the **whole brand** and has **no assignee axis** (`domain/Role.java:44-55`, and `ScopePredicate`'s `SELF` arm explicitly notes that an entity with no assignment column is left brand-wide). There is no notion of "my candidates".

**The gap.** The target names ENM as a peer of Production, Sales and Marketing. Sales and Marketing got four units (37–40) with pipelines, boards, notes and desks. ENM got a table and a dashboard.
**Decision: ADD** — an expert lifecycle (`expert.pipeline_stage` + an append-only `expert_stage_history`) reusing the `CaseTransitions` machinery, plus an assignee column on `Expert` so `Tier.SUPPLY` can narrow below brand.
**Dependencies:** F3 (notes on an expert), F5 (the transition service to copy).
**Priority: P1** — and note loudly: **nothing in `00-build-plan.md`, `00b` or `00c` schedules any of this.** It is the largest unscheduled gap between the code and the stated target.

### F13 — `PortalAccess` is a four-way polymorph, reshaped five times, and now half-dead. — REFACTOR / P2

`domain/PortalAccess.java:42` carries `case_id`, `expert_id`, `ghl_contact_id` **and** `client_account_id`, all nullable, accumulated across `V21, V23, V37, V38, V44` — five migrations reshaping one table, each adding a nullable discriminator without removing the last. Meanwhile commit `5db92bf` ("one client portal, one home — delete the client link system") removed the client-link surface, yet `web/PortalLinkController.java`, `service/PortalLinkLedgerService.java` (212 lines) and `frontend/src/features/dashboards/PortalLinkLedger.tsx` all still exist.
**Decision: REFACTOR** — split the expert token from the client session now that clients have accounts, and confirm whether the link ledger is dead code. **Priority: P2.**

### F14 — Three GHL funnel screens, one component, one location, GM-only. — KEEP / P2

`/marketing/google-ads`, `/marketing/email` and `/sales/pipeline` are three routes onto one `MarketingPipelinePage` (`frontend/src/App.tsx:61-67`), all reading `evalos.ghl.location-id`, all GM-only under invariant 1's exception. Cheap, correctly reasoned, and it closes when F9 does. **KEEP.**

---

## Critical path

Everything else waits on these, in this order:

1. **F11 — re-key client documents off `ghl_contact_id`.** Independent of everything and currently shipping broken: a post-cutover client cannot upload. One change to `DocumentStore.clientKey` + `PortalCaseService:355`. Do it first because it is small and it blocks Unit 43.
2. **F4 — composite `(brand_id, …)` FKs.** One migration. Converts the entire unscoped-query class from review discipline into a constraint violation. It must land **before** the mirror adds four tables and eighteen more finders, or the new tables inherit the same convention.
3. **F9 — `brand.ghl_location_id` + per-brand `GhlHttp`.** The mirror cannot carry `brand_id` on its rows — which `00c` §5 requires, and which is what closes invariant 1's exception — until EvalOS can attribute a location to a brand. This is the true prerequisite of Unit 44, upstream of it rather than part of it.
4. **F2 + F1 — the tier-1 mirror (Unit 44).** `pipeline`, `pipeline_stage`, `contact`, `opportunity` with EvalOS UUID PKs and `ghl_id` beside them; delete `CachedOpportunity`; move `PipelineScope` off the cache onto `opportunity.pipeline_id`. **Reconcile `contact_snapshot` with `client_account` in the same migration** (F10) — doing it separately leaves a third client-identity table.
5. **F3 — the unified note stream.** Needs a durable `opportunity.id` from (4). This is the requirement the target statement leans hardest on and has the least code behind it.
6. **F6 — `AFTER_COMMIT` listeners, then the outbox (Unit 45).** The listener change can land immediately and should; the outbox needs (4).
7. **F12 — the ENM pipeline.** Unblocked once (5) exists. **It needs a unit number before it needs code.**
8. **F5 — split `CaseLifecycleService`.** Do it while adding the ENM transitions, not as its own project.

---

## Open questions

**Q1. Do `contact_snapshot` and `client_account` merge at Unit 44, or does the mirror add a third `contact` table?**
`00c` §2 specifies a `contact` table. Adding it without resolving the other two leaves three rows per client.
**Recommendation: merge into the mirror's `contact`, with `client_account` reduced to credentials only (`contact_id`, `password_hash`, `last_sign_in_at`).** One record per person, one nullable `ghl_id`. Do it inside Unit 44's migration, not after it.

**Q2. Does `payment` become an EvalOS entity before Unit 49?**
Today money is a boolean and a decimal on `Case`, and `PortalInvoiceService` cannot answer "what did this client pay for *this case*".
**Recommendation: yes — add a read-only `payment` mirror at Unit 44 (tier 4 of `00c` §2c, pulled forward), sourced from GHL invoices and keyed to `opportunity_id`.** It does not reverse invariant 2 (EvalOS still raises nothing), and it is what makes the Case a bridge across the commercial/operational seam rather than a wall.

**Q3. Does ENM get a pipeline, and at what unit?**
Nothing schedules it. The target names it as a peer of Sales and Marketing, which got four units each.
**Recommendation: schedule it as Unit 50 — after the switch (48), alongside invoicing (49).** Reuse `CaseTransitions` and the `note` table from F3; the expensive parts will already exist, so the unit is a stage column, a history table and a board.

**Q4. Should brand isolation move to Postgres RLS?**
F4's composite FKs fix cross-brand *writes*; unscoped *reads* through `findById` remain possible.
**Recommendation: no — composite FKs plus an ArchUnit-style test forbidding `findById` on a `ScopedRepository` outside a token-authorised path.** RLS needs a per-request session variable threaded through Hikari, interacts badly with Flyway and with the cross-brand sweeps that legitimately exist, and buys less than the FKs do. Revisit only if a second tenant *class* (not brand) appears.

**Q5. Do the `…FromPortal` method twins in `CaseLifecycleService` collapse?**
Six operations exist twice, differing only in how the subject was authorised.
**Recommendation: yes — the authorisation belongs in the controller or filter, and the domain method should take an already-authorised `Case`.** Do it as part of F5, not before; it touches every portal test.

**Q6. Is `PortalLinkLedgerService` (212 lines) live after "delete the client link system"?**
**Recommendation: assume dead and delete it**, after confirming `PortalLinkController` has no remaining caller in `client-expert/`. A ledger for a system that was removed is exactly the code that gets re-wired by accident.
