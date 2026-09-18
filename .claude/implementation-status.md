# EvalOS — Implementation Status

Verified 2026-09-16 by reading code, the live schema and running the suites. Every status is
backed by a file, endpoint, table or test. **Gap cells re-judged 2026-09-17** against the business
answers recorded as D33–D38 and D19c: three of them were never gaps (Sales case reads, a richer
request status, portal deployment) and now say so.

**Build state (backend re-verified 2026-09-18 after the review pass below, on a `clean` build; the rest 2026-09-17, in the browser against the live location):** backend `1112 tests, 0 failures, 0 errors, 4 skipped` (the 4 are opt-in live checks, `SmtpMailTransportLiveTest` among them); the GM board draws **1,460 deals, synced**, and a Sales desk draws its own pipeline's four;
staff SPA `127 tests`, `oxlint` and `tsc -b` clean; portals `30 tests` and `tsc -b` clean in both `client/`
and `expert/`. All green.

> **Use `mvnw clean test`, not `mvnw test`, when a signature has changed.** The VS Code Java
> extension compiles into the same `target/classes`, and its error-tolerant output — methods whose
> body is `throw new Error("Unresolved compilation problem")` — survives an incremental Maven build.
> It reports **green** while running stale bytecode, and Mockito's inline mock maker fails on those
> classes with "Byte Buddy could not instrument all classes within the mock's type hierarchy",
> which reads like a Mockito problem and is not. Found the hard way on 2026-09-18.

> **It was RED when re-checked, and the cause is worth keeping.**
> `GmOverviewServiceTest.countsOnlyCasesAlreadyPastTheirPromisedDateAsLate` set a delivery date
> with the **real** `Instant.now()` while the window it is counted against comes from a **fixed**
> clock (2026-09-15, exclusive end at midnight opening the 16th). It passed on the day it was
> written and failed on every run after — a time bomb, not a regression; no production code was
> wrong. Fixed by using `CLOCK.instant()` for the delivery date. **The asymmetry that allowed it
> is still there:** `GmOverviewService.evaluation` takes its window from an injected clock but
> reads lateness from its own `Instant.now()`, which no caller can inject, so lateness cannot be
> tested at a fixed point in time.
(Use `tsc -b`, never a bare `tsc --noEmit`: every `tsconfig.json` here is `files: []` with project
references, so `--noEmit` typechecks nothing and exits 0.)

> **Working-tree state, 2026-09-16.** The tree is **clean** — everything named below is
> committed. HEAD is `e2d18bb feat(45c)` on `development`. *(This paragraph previously read
> "uncommitted: 121 changed or untracked paths … HEAD is `dee45c6 fix(42)`", which was true when
> written and stopped being true once Units 51, 52, 44a–44d and 45a–45c landed.)* **CI still runs
> on pushes to `main` only, so none of it has been through CI** — that half is unchanged.

| Domain | Status | Evidence | Gap |
|---|---|---|---|
| **Authentication (staff)** | COMPLETE | `AuthController`, `JwtService`, `JwtFilter`, `SecurityConfig`, `POST /api/auth/login`, `GET /api/me` | — |
| **Client sign in** | COMPLETE | `ClientAuthController` 5 routes, `ClientAccountService.signIn`, `client_account`, `client_credential_token`, `ClientAccountServiceTest`. **Auth screens reworked 2026-09-17** (`SignIn`, `SignUp`, `SetPassword`, `Welcome`): a permanent *Create an account* link instead of one reachable only via `UNKNOWN`; `NO_PASSWORD` / `MAIL_UNAVAILABLE` / no-token each end in a control instead of a sentence; `MAIL_UNAVAILABLE` states outright that **no mail was sent**; password rules shown before the first submit (`PASSWORD_REQUIREMENTS`, beside the validator) | **`SUPPORT_EMAIL` is `support@internationalevaluations.com` and was chosen, not confirmed** — the two `MAIL_UNAVAILABLE` screens now `mailto:` it, so if that mailbox does not exist the one state with no in-portal recovery is still a dead end |
| **Client sign up** | COMPLETE | `ClientAccountService.signUp` — **EvalOS rows only, no outbound call by either path** (`signingUpReachesGhlZeroTimes` drives 100 sign-ups and asserts `verifyNoInteractions`). `created_via = SIGNUP`. `SignUp.tsx` | A client who never opens the mail is invisible to Sales until they do — D3a's trade, taken knowingly; `PORTAL_CLEANUP` clears the row after 30d |
| **Mail transport** | COMPLETE | **SMTP only, as of 2026-09-18 (D3e).** `MailTransport` + `SmtpMailTransport` (Spring Mail, 587 + STARTTLS, three 5s timeouts), reaching whichever provider `spring.mail.host/port/username/password` names — Brevo, Resend, Mailgun, Postmark or SES, tabulated over `spring.mail` in `application.yml`. `isConfigured()` now requires **both** a relay and a sender, so a deployment that sets one and forgets the other answers `MAIL_UNAVAILABLE` instead of failing one send at a time. `ClientMailer` owns the wording and the `PORTAL_LINK_ISSUED` audit row (subject + brand, **never the link**) — **wrapped in a catch as of 2026-09-18**: `recordPortalEvent` is `@Transactional` and no caller of `issueCredential` is, so a transient database error escaped as a 500 for a *known* address while an unknown one answered 204, which is the enumeration oracle this class exists to close, and it unwound before the credential row was saved. `ClientMailerTest` (the seam, with fakes), `SmtpMailTransportTest` (4), and `SmtpMailTransportLiveTest` (opt-in `MAIL_LIVE_TEST=true`, sends one real mail — the only check that proves a live credential, a *verified* sender and the deploy's egress, each of which fails as a swallowed false in production). **`BrevoMailTransport` and its live test are deleted**, with the whole `evalos.mail.brevo.*` block; prod's `transport` default flipped `brevo` → `smtp` | **Unset in every environment — `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD` and `EVALOS_MAIL_FROM` are all blank**, so every client still answers `MAIL_UNAVAILABLE` until a provider's SMTP credentials are put in `.env` (local) and the deploy's environment. **The Brevo API-key blocker is retired, not solved**: that account's transactional sending is still suspended after the 2026-09-17 hard bounce, and SMTP on the same account would hit the same suspension — a different provider, or Brevo clearing it, is the way through. **The key that sat in `application-local.yml` is disclosed and must be rotated or deleted.** Two environment failures still look identical to a bad password from inside the app: a host that blocks outbound 587, and a provider with an authorised-IP list this deploy is not on — `SmtpMailTransportLiveTest` run *from the deployed host* is what separates them |
| **Client → GHL contact** | COMPLETE | `ClientAccountService.ensureCrmIdentity` with `source: "Client Portal"` (D3b), called from `setPassword`, `signIn` and `ClientApplicationService` — **never from an unauthenticated route** (D3d) | GHL takes no `utmSource`/`attributionSource` on a write; `source` is the whole of what provenance can be |
| **Client Portal** | PARTIAL | 11 pages, 12 routes, `/api/portal/client/**` | two-or-more-cases refuses (Q8). *Deployment is no longer listed here — it is DevOps's, D38* |
| **Client account** | COMPLETE | `client_account` (V43, V45, V46, V55), `ClientAccountRepository` | — (44c joined it to `contact_snapshot` and made `ghl_contact_id` unique per brand by partial index; D32) |
| **GHL contact** | COMPLETE | `GhlWriteClient.upsertContact` → `POST /contacts/upsert`; `contact_snapshot` | every stored id from before 2026-09-11 names a contact in the abandoned sub-account |
| **GHL opportunity** | COMPLETE | `upsertOpportunity`, `createOpportunity`, `updateOpportunity`, `moveStage`, `setStatus`; `GhlOpportunityClient`; `ghl_opportunity_cache` | `MarketingLeadService` uses upsert, so a repeat marketing enquiry reuses the open deal |
| **Client request** | COMPLETE (as specced) | `client_application` (V49), `ClientApplicationService`, `ClientApplicationController`, `NewRequest.tsx`, `serviceCatalog.ts` | never exercised — 0 rows locally. **Two statuses is now the answer, not a gap** (D35). What is owed is the document half of submit (D33) |
| **Portal → GHL integration** | PARTIAL | **set-password** upserts the contact and stores the id (moved off sign-up 2026-09-16, D3a); **submit** opens the opportunity (D10, moved off service-pick 2026-09-16) carrying the **service id** (`GHL_OPPORTUNITY_SERVICE_FIELD`), the correlation key and `SUBMITTED` — all on the one create, so `setOpportunityFields` is off this path entirely; spec `52-client-portal-ghl-integration.md` | **EvalOS→GHL only.** The routing workflow is UI work in GHL and is not built; both field ids default blank (the fields are omitted, nothing breaks). **GHL→EvalOS is now routed for the mirror** (45d): `contact.*` and `opportunity.*` update EvalOS rows. What is still one-way is the *portal's own* data — a Sales edit in GHL reaches the mirror, not `client_application` |
| **Service request → Sales review** | COMPLETE | `ApplicationReviewController` `GET /api/opportunities/{id}/application` + `ApplicationDocumentController` `GET /api/opportunities/{id}/documents`, `DealApplication.tsx` and `DealDocuments.tsx` side by side on the deal page | **read-only is correct** (D35 — approve/reject/return are GHL stages). The documents gap D34 named is closed by Unit 53 (2026-09-18): its own route and its own panel, on the same permission, because Sales reaches both by already being able to open the opportunity |
| **Documents** | PARTIAL | `case_document`, `DocumentStore` (S3 put + presign, **keyed by the GHL contact id** since 2026-09-17 — D41), portal upload/read, staff read, expert letter. **Request-stage documents BUILT 2026-09-18 (Unit 53, `V65`)**: `application_document`, `ApplicationDocumentService`, `POST`/`GET`/`DELETE /api/portal/applications/{id}/documents`, `GET /api/opportunities/{id}/documents` + `/{d}/url` for Sales, `RequestDocuments` on the portal's review step and `DealDocuments` on the deal page, and `RequestDocumentCarryForward` putting them on the case at Handoff A | **Still no per-case picker** (Q8); `case_document = 0` rows locally. A contact with no GHL id is refused at upload with a message naming the repair — the exposure D41 accepts. **The carry-forward has never run against a real `opportunity.won`**, like everything else on that path |
| **Sales dashboard** | COMPLETE | `PipelineDashboard(audience=sales)`, `OpportunityBoardPage`, `SalesDeskService`, `SalesOpportunityController`, `MeetingsPage`; **`/opportunities/new` (SALES) and `/marketing/leads/new` (MARKETING) are sidebar entries as of 2026-09-17** — they were a button and an inline form on the board; **Unit 46**: the board makes no GHL request and every edit is a local write plus a queued push | **SALES reading no case is correct (D19c)** — their world ends at won. A *create* still calls GHL inline and always will until custom fields are mirrored (D46, Unit 47) |
| **GHL sync engine (Unit 45)** | **COMPLETE — all five slices** | `GhlFailure` (7 classes, `isRetriable()` / `stopsEverything()`), `GhlUnavailableException.failure()`/`status()`, `GhlHttp` honouring `Retry-After` on a 429 by pushing the shared pacer; `sync_drift` (`V56`), `SyncAuditService` + nightly `SYNC_AUDIT` sweep (paged full-list diff, opportunities only), `GET /api/sync/drift` (GM); `sync_outbox` (`V57`/`V58`), `SyncOutboxService` + `SYNC_OUTBOX` drain (2m) with search-before-create on the correlation key, and the portal's previously-swallowed create and submit-marker failures queued; **45d** `GhlMirrorHandler` routing `contact.created/updated` into `contact_snapshot` and six spellings of `opportunity.*` into `OpportunityMirrorService.absorbForContact`, plus the `MIRROR_DELTA` sweep (15m) over `refreshIfStale`; **45e** `FieldOwnership` (GHL owns the assignee and the pipeline, four shared fields, notes never synced), `Opportunity.syncFromGhl` keeping a shared field only while EvalOS holds an edit GHL has not confirmed, and `owner`/`resolution`/`needsAHuman` on `GET /api/sync/drift` | — **Unit 46 landed the same day and the ownership rules stopped being latent**: every desk edit now stamps `local_updated_at`, so 45e is what defends it until the drain confirms. 45e itself still pushes nothing; the outbox does |
| **GHL mirror (Unit 44)** | COMPLETE — all four slices | `pipeline` + `pipeline_stage` (`V50`), `PipelineMirrorService`, `PipelineMirrorSweep` (`PIPELINE_MIRROR`, 1h), `PipelinePurpose`, `GET/PUT /api/ghl/pipelines`; `opportunity` (`V51`), `OpportunityMirrorService`, `client_application.opportunity_id` (`V53`), the correlation custom field on create; `ghl_opportunity_cache` DROPPED (`V52`) along with `CachedOpportunity`, `OpportunityCache` and `GhlOpportunityClient`; `team_member_pipeline` (`V54`) with `PUT`/`DELETE /api/team-members/{id}/pipelines`, `PipelineScope.mine()` returning a set and `ScopePredicate`'s pipeline arm using `IN` — **a revoke stamps `revoked_at` rather than deleting, as of `V64` (2026-09-18)**, because `backfillFromLegacyColumn` runs after every `PIPELINE_MIRROR` pass and was resurrecting revoked grants within the sweep interval, past the role check, the selling-brand check and the audit event; clearing `team_member.ghl_pipeline_id` instead is forbidden by `V39`'s `team_member_pipeline_matches_role`, which requires a SALES/MARKETING row to hold one; `intake-pipeline-name` retired for `purpose = INTAKE`; `client_account.contact_id` (`V55`) joining the account to its CRM row with D6 enforced by a partial unique index, `ContactSnapshotService` extracted, and set-password creating the contact (closing the prospect gap; it was sign-up until D3a moved it on 2026-09-16) | The `contact_snapshot` → `contact` **rename** is deferred: two seeds write that table and run after every migration, so a rename has nowhere to sit — `context/specs/44-ghl-tier1-mirror.md` §4.1. `OpportunityRepository.SCOPE` is `brandOnly` until 44b lines the pipeline axis up, so the pipeline scope lives in the finder signatures and `OpportunityRepositoryScopeTest` guards it. No sync engine: that is Unit 45 |
| **GM dashboard** | COMPLETE | `GmDashboard.tsx`, `GmOverviewService`, `GET /api/metrics/gm` (`hasRole('GM')`), `evalos.sales.monthly-goal` / `won-lookback-days` | `SALES_MONTHLY_GOAL` is unset (`0`), so the headline shows the amount without a % to goal. *Hot leads* and *Invoice sent* need the business to name the GHL stages; email-campaign stats need a wider PIT scope; social reach has no GHL endpoint at all — see `context/specs/51-gm-dashboard.md` §3 |
| **Marketing dashboard** | PARTIAL | `PipelineDashboard(audience=marketing)`, `MarketingLeadService`, `NewLeadForm` | **the two GHL funnel screens are GONE (2026-09-16).** `/marketing/email` and `/sales/pipeline` were GM-only, so the audience for a marketing funnel could not open one; the business removed them rather than widen the gate. `MarketingController`, `MarketingPipelineService`, `GhlFunnelCache`, `GhlFunnelCacheRepository` and `GhlPipelineClient.countIn` went with them. The `ghl_funnel_cache` **table** is still there and orphaned — the drop has nowhere to live (`V905` clears it, and `MigrationTreeTest` forbids a `db/migration` script numbered ≥900); see `GmOverviewService`'s note. A funnel screen returns as a *new* screen after Unit 25 puts the location on `brand` |
| **Conversations** | NOT IMPLEMENTED | grep across all three apps finds no conversation table, column, route or component | the whole feature; `opportunity_note` is the only message-like thing |
| **Employee-wise booking** | PARTIAL | `assignedUserId` on booking; `GET /api/sales/users` | availability is per *calendar*, not per employee; no column joins a GHL user to a `team_member` |
| **Appointments** | PARTIAL | `meeting` table (V47), `SalesMeetingService`, `BookingForm.tsx`, `Meetings.tsx` | no cancel, no guests, no blocked-off time, no notes UI |
| **Payments (client)** | NOT IMPLEMENTED | `evalos_case.paid` / `paid_at` set by Handoff A; `PortalInvoiceService` reads GHL invoices | deliberate — invoicing is GHL's (D15) |
| **Payments (expert payouts)** | COMPLETE | `payout_ledger`, `payout_payment`, `PayoutService`, `PayoutController`, `PaymentController`, `PayoutBatch.tsx` | live settlement unexercised |
| **Cases** | COMPLETE | `evalos_case`, `CaseLifecycleService`, `CaseTransitions`, 30+ transition routes, `BoardView`, `CaseDetail` | — |
| **Production** | COMPLETE | 12 stages, `exception_state`, `document_checklist_item`, `ChecklistService`, four sweeps | — |
| **Expert workflow** | PARTIAL | `expert`, `expert_case_offer`, `ExpertMatchService`, `ExpertPortalService`, `/api/portal/expert/**`, `ExpertCasePortal.tsx`; the expert portal's `/` is a **holding page** as of 2026-09-18 (`expert/src/pages/Welcome.tsx`) — the app had no `/` at all, so the bare origin answered 404 and read as a broken portal | **The stakeholder decision came back on 2026-09-18: experts sign in like clients and staff-minted links are retired** (D23 edited). **The process is not designed**, and that is what gates the code — an expert is on the roster before they could sign in, their access is party-scoped rather than account-scoped, and one person may sit on two brands' panels, so the Unit 42 flow is a starting point and not a template (Q6, with a recommendation). **Nothing is removed yet, deliberately:** `mintForExpert`/`mintForParty` are still wired into four staff screens and every link already in an inbox points at `/case`, so minting and that route retire together in one change. Expert still cannot open evidence documents. *(Deployment dropped from this cell — D38)* |
| **Notifications** | PARTIAL | `notification` table, 11 `NotificationType`s, `NotificationService`, `NotificationListeners`, `NotificationBell` | **in-app only; push is owed** (D37 — in-app **and** push, and those two only). Needs a subscription table and a delivery step beside the existing write. No email and no SMS is now a decision, not a gap |
| **RBAC** | COMPLETE | `Role` + `Tier`, `ScopePredicate`, `ScopedRepository`, `@PreAuthorize`, `OwnershipGuard`, `navigation.ts` mirror, `DomainInvariantsTest` | — (PIPELINE matching no case is D19c, intended) |
| **Audit logs** | COMPLETE | `audit_event` + append-only trigger, `AuditService`, 16 `AuditAction`s incl. client sign-in, `GET /api/cases/{id}/timeline` | no cross-entity unified timeline |
| **Webhooks (inbound)** | COMPLETE | `InboundWebhookController`, `WebhookGateway` (HMAC + brand token), `WebhookRouter`, `GhlOpportunityHandler`, `GhlMirrorHandler` (45d), `WebhookPayload`, `webhook_event` idempotency | **three event families routed** as of 2026-09-17: `opportunity.won` → Handoff A, `contact.created/updated` → the contact mirror, six spellings of `opportunity.*` → the opportunity mirror. `refund.requested` is the last deferred no-op. `webhook_event = 0` rows locally — none of it has been exercised by a real delivery |
| **Webhooks (outbound)** | NOT IMPLEMENTED | `event/CaseEvents.java` only; no `webhook.outbound` package | Handoff C and invariant 11 |
| **S3 / document storage** | COMPLETE | `DocumentStore.put` + `presignedUrl` (5 min, never stored), SSE per request, 502 with named vars when unconfigured. **Local development writes to a DIRECTORY, not a bucket, as of 2026-09-19** — `evalos.s3.local-dir` (`EVALOS_S3_LOCAL_DIR` in `.env`, which `launch.json` loads as real environment variables) with `LocalDocumentController` serving reads back on a five-minute capability token and `Content-Disposition: attachment`, mirroring a presign. `DocumentStoreLocalModeTest` pins the round trip, the expiry, the traversal guard and the profile refusal. The `evalos.s3.endpoint` override survives for a real S3-compatible store and stays blank in every shared profile (`DocumentStoreEndpointTest`) | **never exercised against a real bucket.** The local mode replaced MinIO because MinIO needed a container running before any document route worked at all — **`docker-compose.local.yml` was never added and is referenced nowhere now**; a directory needs nothing. **Production is untouched**: it takes bucket and region from `application.yml`, `local-dir` is blank there and in prod, `DocumentStore` refuses to start if it is set outside the `local` profile, and `LocalDocumentController` is `@ConditionalOnProperty` so the unauthenticated route is not mapped at all |
| **Background jobs** | COMPLETE | **10 sweeps** (`MIRROR_DELTA` added at 45d), `JobLock`, `JobLedger`, `scheduled_job`, `GET /api/jobs/runs`, `JobRunsPage`, `SweepRegistrationTest` | — |
| **Portal cleanup** | COMPLETE | `PortalCleanupSweep` (`PORTAL_CLEANUP`, 24h), gated on `client_account.created_via = 'SIGNUP'` (`V59`) so seeded clients are never eligible: `ClientCredentialTokenRepository.deleteByExpiresAtBefore` one TTL past expiry, and `ClientAccountRepository.deleteAbandonedSignUps` — no password, no GHL contact, no token, no application, no session, older than `evalos.portal.abandoned-sign-up-after` (30d). The other half of D3a: refusing the CRM write bounded the damage, this bounds the tables | **Audit is deliberately not swept** (append-only, invariant), so a sign-up flood still grows `audit_event`. Two whole-table deletes, no batching — see the `ponytail:` note on the sweep |
| **Desks on the mirror (Unit 46)** | COMPLETE | `OpportunityBoardService` with no `refreshIfStale` and no `board-cache-ttl`; `Opportunity.editedLocally`; `OpportunityMirrorService.editLocally` / `absorbCreated`; `SalesDeskService.update`/`moveToStage`/`close` and `MarketingLeadService.value` queueing `UPSERT`/`CLOSE`; `MIRROR_DELTA` at **5m**; `POST /api/opportunities/board/refresh` plus the board's sync stamp and "Sync delayed" banner; spec `46-desks-on-the-mirror.md`. **Four review fixes, 2026-09-18:** the `UPSERT` sends only the fields the desk edited (`locally_edited_fields`, `V63`) instead of all four, so a rename no longer re-sends a stale stage over a GHL workflow's card move; the confirmation is `OpportunityRepository.confirmPushed` — a conditional statement — instead of `save(row)` on an entity read before the round trip, which lost any edit that landed during it; both creates now `absorbCreated` from GHL's reply, so the next edit is not refused as "not in the mirror yet"; and Refresh reads pipeline *structure* only when the mirror holds none | **A won deal reaches GHL on the next drain (≤2m), so Handoff A's case arrives later than it used to** — the trade D44 names. Creates, the contact upsert and GHL tasks still call GHL inline (D46). **A GM's Refresh still fans a live deal read over every live pipeline synchronously** — bounded on repeat by the 30s floor, not on the first press; the upgrade named in the code is a 202 from the job runner |
| **Reference mirror (Unit 47 + 47b)** | COMPLETE | `ghl_custom_field` / `ghl_calendar` / `ghl_user` (`V60`), `GhlReference` + three repositories, `ReferenceMirrorService`, `REFERENCE_MIRROR` sweep (1h), and `/sales/opportunity-fields`, `/sales/calendars`, `/sales/users` reading the mirror; spec `47-reference-mirror.md` **47b added what 47 cut** (`V62`): custom field **values** on `opportunity`, `ghl_note`, `ghl_tag`, and read-back for tasks and appointments — all on the opportunity search's `getNotes`/`getTasks`/`getCalendarEvents`, for **zero extra requests**. **Free slots are the one thing still never mirrored** (D48). A task EvalOS never created is not invented; tags are read, never written. **2026-09-18:** all four absorbs fall back to GHL's id when GHL sends no name — `name` is `NOT NULL`, so one nameless row used to fail its whole list inside `guarded()` and leave `refreshIfEmpty` re-running the failing read on every booking-form request |
| **Calendar** | PARTIAL | `GhlCalendarClient` (calendars, free-slots, book, reschedule, addNote, forContact), `SalesCalendarController`; the calendar **list** is mirrored at 47 | see `workflows.md` §6; free slots stay live by design (D48) |
| **Multi-brand** | PARTIAL | `brand` table, `BrandSwitcher`, brand-scoped everything | one GHL location globally; `/brands` nav entry renders `PlaceholderPage`; Client Portal is single-brand by config |
| **Deployment** | **OUT OF SCOPE HERE** | `docker-compose.yml`, `backend/Dockerfile`, `frontend/Dockerfile`, `.github/workflows/ci.yml` → EC2 | **DevOps owns deployment and edits it (D38, 2026-09-17)** — the portals' absence from compose and CI is not this repository's debt. CI still triggers on `main` only, so nothing since `dee45c6` has been through it: that is a fact to know, not a task here |

## Known operational state (not code)

- **The six IE desk logins are `sales-1..3` and `bde-1..3`** (`@evalos.local` locally), password
  **`DevPassw0rd!`** — the same seeded throwaway as every other local login, kept on the business's
  instruction 2026-09-19 and verified against each stored hash rather than read off a comment.
  Sales hold the three service pipelines (Evaluation & Translational, PERM + Immigration, Expert
  Opinion Letter + RFE); BDE hold the three BDE pipelines. **BDE is `MARKETING`, not `SALES`**, and
  in this system that is behaviour rather than a label — MARKETING opens a *lead* (`openLead`, an
  upsert on contact + pipeline) and SALES opens a *deal* (a true create) with meetings, follow-ups
  and close.
  `V911` exists because five of the six had been renamed **by hand in a local database** while the
  seeds still said `sales.attorney.ie@evalos.local` — so a fresh checkout came up with names
  nobody had been told to use. It renames by id, not by address, so it converges either way.
  **`docs/seed-desks.sql` is the production script** (an operator script, not a migration: who
  works at a company is not schema). **It carries the same published password, which is the one
  thing about it to think twice over** — `DevPassw0rd!`'s hash is committed in `V908`, its
  plaintext is in that file's comments, and both are in every clone and the whole git history.
  On a laptop that costs nothing; on an environment holding client data it is a published
  credential, not a weak one.

- **`npm run dev` in the portals workspace served the client app on the expert portal's port,
  fixed 2026-09-18.** `dev` was an alias for `cd client && vite`, and neither vite config set
  `strictPort` — so Vite silently incremented past a busy 5174 and a second `npm run dev` came up
  on **5175**, which is the expert portal's. The expert app looked broken while nothing about it
  was wrong: two distinct apps, two distinct configs, and the wrong one answering. Both configs now
  set `strictPort: true` so a collision stops instead of resolving itself, and `dev` refuses with a
  message naming `dev:client` (5174) and `dev:expert` (5175) rather than quietly picking one. The
  two are separate subdomains in production, so a dev setup that blurs them is the wrong shape to
  develop against.
- **The client questionnaire's autosave was answering 403 from the CORS preflight, fixed
  2026-09-18.** `PortalSecurityConfig.portalCors` allowed `GET, POST, OPTIONS`, and
  `PUT /api/portal/applications/{id}` — the autosave, and the **only** non-GET/POST route on the
  whole portal API — has existed since Unit 43. The portal is a separate origin, so the PUT was
  preflighted; `DefaultCorsProcessor` answers a refused preflight with a bare **403 and a
  plain-text body**, which the client cannot read an error message out of, so it showed its
  fallback ("We could not save your answers.") to a client who had just finished the form and
  pressed Review. Nothing was unauthorised and nothing logged an error — the verb was simply not
  on a list. `ClientApplicationRoutesTest` now asserts the preflight for every method the chain
  serves, and that a verb it does not serve is still refused, so `*` is not the fix and a new
  verb fails there rather than in a browser.
- **Document routes are exercisable on a laptop as of 2026-09-19 — MinIO, in its own compose
  file.** `DocumentStore` gained an optional `evalos.s3.endpoint`; when it is set it also switches
  to path-style addressing, and **the presigner is overridden as well as the client** (a client
  pointed at MinIO with a presigner still pointed at AWS uploads happily and then hands out URLs
  that 404 — the upload looks fine and only the read breaks, hours later). `docker-compose.local.yml`
  runs MinIO and creates the bucket; it is a **separate file from `docker-compose.yml`, which is the
  deployment and DevOps's (D38)**. Credentials come from `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`
  in the environment, never from a profile — `ConfigSecretsTest` forbids a credential default and
  that rule is not bent for a worthless one. `application-local.yml` now names a **local** bucket
  again, which is only safe because the endpoint is localhost; `DocumentStoreEndpointTest` pins the
  two together and pins the endpoint blank everywhere else. **UNVERIFIED END TO END: Docker Desktop
  was not running on this machine, so no object has actually been written to MinIO.** The code
  compiles and the config guards pass; the first real upload is still the first real upload.
- **GHL WRITES ARE STUBBED ON A LAPTOP as of 2026-09-19, and writing for real is opt-in.**
  `evalos.ghl.write-mode` defaults to `stub` in `application-local.yml` and `live` everywhere else;
  `StubGhlWriteClient` answers all nine write verbs locally and sends nothing. **Reads are
  unaffected** — the mirror still fills from the live location, because that is where the
  pipelines, stages and custom fields are and a faked read would stop catching the shape mismatches
  live reads catch (Unit 47b's `notes` envelope was exactly that). This exists because
  `location-id` names the **business's real CRM**: exercising the portal's intake flow created real
  opportunities a salesperson then had to find and delete, so the flow mostly went unexercised
  instead. `StubGhlWriteClient` refuses to start outside the `local` profile — a deployment holding
  it would accept every desk edit, request and close, report success and send none of it, diverging
  from the CRM permanently with no failed request anywhere to notice. Set
  `EVALOS_GHL_WRITE_MODE=live` in `.env` for a write you actually want to land.
- **`evalos.ghl.intake-pipeline-name`'s successor bit a fresh environment on 2026-09-19.** All
  twelve mirrored pipelines were `UNASSIGNED`, so `POST /api/portal/applications/{id}/submit`
  answered **400** saying *"we could not reach our systems, please try again in a moment"* while
  GHL was perfectly reachable and retrying could never work — and the log called it "queued for
  retry" while queueing nothing, because the outbox needs a local opportunity id and there was
  none. `intakePipeline()` now resolves **outside** the outage catch and logs at ERROR with the
  actionable sentence, and the catch only claims a queue when it made one
  (`ClientApplicationServiceTest`, two tests, one asserting GHL is never called).
- **Documents are stored on local disk in development as of 2026-09-19, never in a bucket.**
  `EVALOS_S3_LOCAL_DIR` in `.env` points `DocumentStore` at a directory and
  `LocalDocumentController` serves reads on a five-minute token. **MinIO was dropped**: it needed a
  container running before a document route worked, and `docker-compose.local.yml` was never
  written. Three guards keep the mode out of a deployment — blank in `application.yml` and
  `application-prod.yml`, a startup refusal outside the `local` profile, and
  `@ConditionalOnProperty` on the route. **The profile check reads `acceptsProfiles`, not
  `getActiveProfiles`**: `spring.profiles.default: local` means a run with nothing activated IS
  local while the active list is empty, and getting that wrong refused every integration test in
  the suite before it was caught.
- **Unit 53 (request documents) was BUILT 2026-09-18**, closing the one step `workflows.md` §2 had
  always named and Unit 43 deferred. Three things about it worth knowing without reading the spec:
  the S3 object is **never copied and never re-keyed** at Handoff A — the key is the contact's
  prefix, so the case row points at the object the client already uploaded, which is the whole
  payoff of `53` §1 keying by the person; the carry-forward is an **event listener on
  `CASE_CREATED` rather than a call inside `CaseIntakeService`**, a deliberate deviation from
  `53` §4 that buys the isolation §4 itself demands ("never fails the case") structurally rather
  than by remembering a try/catch, since `opportunity.won` is the only door into a case
  (invariant 8); and **submit is still never gated on documents** (`43` §5, unchanged) — the
  portal copy says "if you have them to hand" and means it.
- **Adding `DELETE` for the document routes broke the portal CORS test, which is the point.**
  `ClientApplicationRoutesTest`'s preflight assertion failed the moment the route was added, one
  commit before a client would have found it as a bare 403 — exactly the failure the PUT autosave
  shipped with. `PortalSecurityConfig` now allows GET/POST/PUT/DELETE/OPTIONS, and PATCH is the
  verb the negative assertion uses because nothing under `/api/portal/**` serves it.
- **Four review findings on the staff SPA are OPEN, reverted on request 2026-09-18.** They were
  fixed and then rolled back to `9a1f3e7` because the change was not wanted; `frontend/src` is
  byte-identical to that commit. They are recorded here rather than dropped, because each one is
  reproducible today:
  (1) **the deal page's Contact panel hangs on "Loading…" for ever** when the contact is not
  mirrored — `OpportunityContactController.read` answers 200 with `null` on purpose and
  `useMetrics` derives its state from `data === null`, so the "No contact on this deal yet" copy at
  `DealPage.tsx:54` is unreachable; the fix belongs in `useMetrics` (a settled flag), not DealPage.
  (2) **a failed Refresh is silent** — `OpportunityBoardPage.syncNow` has `try/finally` with no
  `catch`, so `reload()` never runs, nothing changes on screen and the promise rejects unhandled,
  against a 15s axios timeout on a live GHL sweep.
  (3) **a GM opening any deal gets an access error under an unusable "Add note" box** —
  `navigation.ts:441` grants `DEAL_DETAIL_PATH` to GM while `DealPage` renders `DealNotes`
  unconditionally and `OpportunityNoteController` is `@PreAuthorize("hasAnyRole('SALES','MARKETING')")`
  on both verbs. **Gating the panel was explicitly declined**, so if this is closed it should be
  closed on the server side or by widening the controller, not by hiding the panel.
  (4) **a matched existing deal reads as a new lead** — `NewLeadPage` navigates away `onOpened`,
  unmounting the form before `NewLeadForm.tsx:83` can show "That contact already had an open
  deal…", so two marketers can quietly work one deal.
- **A review pass on 2026-09-18 fixed six server-side defects in Units 45d–47b and the mail
  refactor**, none of which any test caught. Three are in the rows above; the three not otherwise
  recorded: `OpportunityMirrorService.absorb` stamped the pipeline as freshly synced **before**
  absorbing anything, so a mid-loop failure left a half-absorbed mirror advertising itself as
  current with the TTL suppressing the retry (the stamp is now last, and the decorative
  `@Transactional` that never applied — the only caller is in the same class — is gone with a note
  saying why one transaction over five figures of rows would be worse); `ContactSnapshot.syncFromGhl`
  assigned `fullName`/`email`/`phone`/`company` unconditionally, so a GHL Custom Webhook mapping
  only `contact_id` and `phone` **blanked the client's name and nulled their email** (blank now
  means "leave it alone", as the other five fields already did); and `application-local.yml`
  defaulted `EVALOS_S3_BUCKET`/`REGION` to the live bucket and region under a comment still promising
  "Blank on a laptop, deliberately", so any developer with ambient AWS credentials wrote test
  uploads into production's own `brandId/client/…` prefixes.
- **Two findings from that pass were rejected, and the reasons are the record.** The board's
  "Sync delayed" banner does fire at the boundary of every healthy cycle — but
  `board-stale-after` is **5m by a business decision on 2026-09-17, over a proposed 15**, and
  `application.yml` already names the blink as the accepted cost. The defect was the *comment* in
  `OpportunityBoardService.draw` claiming "three missed passes at the 5-minute cadence", which is
  15m and the opposite of the configured default; the comment was corrected and the value left
  alone. Separately, `syncNow` was reported as holding a pooled connection across a GHL round trip
  inside `@Transactional`: it carries no such annotation, and neither does its class.

- **Two bugs found only by running it, both fixed 2026-09-17.** (1) `refreshIfStale` decided
  freshness from `max(opportunity.synced_at)` while `absorb` stamped the **pipeline**, so a pipeline
  with recent deals looked fresh, was skipped, and never got the stamp the board reads — every board
  said "never synced" while showing deals. One question, two sources. The derived finder is deleted.
  (2) **Unit 47b's `notes` binding was wrong and broke every opportunity read**: GHL sends
  `{"notes": [...], "total": N}`, an object, not an array — Jackson refused the whole page, so the
  mirror absorbed nothing and every screen read "GHL did not answer". The operation contract lists
  field *names*, not *shapes*; only the live endpoint settles that.
- **`WY6bW2xUCI8Tz8gw7aLJ` is the final GHL location** (confirmed by the business 2026-09-17), and
  the abandoned sub-account's id has been purged from every live config and spec. **Three separate
  causes of "no pipelines, never synced" were found and fixed the same day**: `sales-brand` was
  blank (so every mirror no-opped), the board's age was derived from opportunity rows (so an empty
  pipeline could never report a sync), and the GM's union read the column Unit 44b replaced. Desk
  assignments now backfill on each `PIPELINE_MIRROR` pass, because `V910` runs before any pipeline
  exists and can only ever insert zero rows.
- **IE's GHL sub-account was replaced on 2026-09-11** with `WY6bW2xUCI8Tz8gw7aLJ`. No contact or
  opportunity migration; the old account is abandoned.
- The `opportunity.won` **GHL workflow EXISTS in the new account** — confirmed by the business
  on 2026-09-16. *(This bullet previously said it "cannot be determined from this repository" and
  that Handoff A might be dead; that was true of the repo and is now answered from outside it.
  Nothing in code proves it, and nothing can — the proof is one real won opportunity producing a
  case, which has **not yet been observed**.)*
  **What a firing must carry**, so a test one is not wasted: `POST /api/webhooks/ghl/{brand
  .webhook_endpoint_token}` — the token is the whole credential, there is no signature step —
  with `event_type: opportunity.won`, `contact_id` (the one field that cannot be missing) and
  `full_name` at the top level in **snake_case**, plus an optional `customData` key in
  **camelCase** whose own fields are snake_case (`service_type`, `opportunity_id`, `amount`).
  All three of those are optional; `amount` is `@Positive` **where present**, so a deal valued at
  **0 is refused** while an absent value is accepted.
  **2026-09-17:** the business confirms the webhook is **built in the new location** and a dummy
  opportunity to fire it is coming. Until one arrives, Handoff A remains code-complete and
  unobserved.
- **`evalos.ghl.intake-pipeline-name` no longer exists** — Unit 44b retired it for
  `pipeline.purpose = INTAKE`, because matching by name meant a rename in GHL silently stopped
  every request reaching Sales. The blocking condition moved rather than going away: a GM must
  mark exactly one mirrored pipeline through **`PUT /api/ghl/pipelines/{id}/purpose`**, and
  **zero (the default on a fresh deployment) and two or more are both refused** — 502 with a
  message naming the fix, since guessing would file a client's request onto a pipeline nobody
  chose. Run the `PIPELINE_MIRROR` sweep first if the pipeline is new. *(This line previously
  named the retired property and its blank default.)*
- `SALES_MONTHLY_GOAL` defaults to `0`; the GM headline tile shows the won amount with
  *"No monthly goal set"* until the business supplies it.
