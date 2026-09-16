# EvalOS — Implementation Status

Verified 2026-09-16 by reading code, the live schema and running the suites. Every status is
backed by a file, endpoint, table or test. **Gap cells re-judged 2026-09-17** against the business
answers recorded as D33–D38 and D19c: three of them were never gaps (Sales case reads, a richer
request status, portal deployment) and now say so.

**Build state (re-verified 2026-09-17 after 45e):** backend `1049 tests, 0 failures, 0 errors, 3 skipped`;
staff SPA `127 tests` and `tsc -b` clean; portals `30 tests` and `tsc -b` clean in both `client/`
and `expert/`. All green.

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
| **Client sign in** | COMPLETE | `ClientAuthController` 5 routes, `ClientAccountService.signIn`, `client_account`, `client_credential_token`, `ClientAccountServiceTest` | — |
| **Client sign up** | COMPLETE | `ClientAccountService.signUp` — **EvalOS rows only, no outbound call by either path** (`signingUpReachesGhlZeroTimes` drives 100 sign-ups and asserts `verifyNoInteractions`). `created_via = SIGNUP`. `SignUp.tsx` | A client who never opens the mail is invisible to Sales until they do — D3a's trade, taken knowingly; `PORTAL_CLEANUP` clears the row after 30d |
| **Mail transport** | COMPLETE | `MailTransport` + `SmtpMailTransport` + `BrevoMailTransport` (`POST /v3/smtp/email`, header `api-key`, text-only body, bounded timeout), chosen by `evalos.mail.transport`; `ClientMailer` owns the wording and writes the `PORTAL_LINK_ISSUED` audit row (subject + brand, **never the link**). `ClientMailerTest` | **`EVALOS_MAIL_BREVO_API_KEY` and `_SENDER_EMAIL` are unset** — prod defaults to `brevo`, so until both are set every client answers `MAIL_UNAVAILABLE`. The sender must be one Brevo has verified |
| **Client → GHL contact** | COMPLETE | `ClientAccountService.ensureCrmIdentity` with `source: "Client Portal"` (D3b), called from `setPassword`, `signIn` and `ClientApplicationService` — **never from an unauthenticated route** (D3d) | GHL takes no `utmSource`/`attributionSource` on a write; `source` is the whole of what provenance can be |
| **Client Portal** | PARTIAL | 11 pages, 12 routes, `/api/portal/client/**` | two-or-more-cases refuses (Q8). *Deployment is no longer listed here — it is DevOps's, D38* |
| **Client account** | COMPLETE | `client_account` (V43, V45, V46, V55), `ClientAccountRepository` | — (44c joined it to `contact_snapshot` and made `ghl_contact_id` unique per brand by partial index; D32) |
| **GHL contact** | COMPLETE | `GhlWriteClient.upsertContact` → `POST /contacts/upsert`; `contact_snapshot` | every stored id from before 2026-09-11 names a contact in the abandoned sub-account |
| **GHL opportunity** | COMPLETE | `upsertOpportunity`, `createOpportunity`, `updateOpportunity`, `moveStage`, `setStatus`; `GhlOpportunityClient`; `ghl_opportunity_cache` | `MarketingLeadService` uses upsert, so a repeat marketing enquiry reuses the open deal |
| **Client request** | COMPLETE (as specced) | `client_application` (V49), `ClientApplicationService`, `ClientApplicationController`, `NewRequest.tsx`, `serviceCatalog.ts` | never exercised — 0 rows locally. **Two statuses is now the answer, not a gap** (D35). What is owed is the document half of submit (D33) |
| **Portal → GHL integration** | PARTIAL | **set-password** upserts the contact and stores the id (moved off sign-up 2026-09-16, D3a); **submit** opens the opportunity (D10, moved off service-pick 2026-09-16) carrying the **service id** (`GHL_OPPORTUNITY_SERVICE_FIELD`), the correlation key and `SUBMITTED` — all on the one create, so `setOpportunityFields` is off this path entirely; spec `52-client-portal-ghl-integration.md` | **EvalOS→GHL only.** The routing workflow is UI work in GHL and is not built; both field ids default blank (the fields are omitted, nothing breaks). **GHL→EvalOS is now routed for the mirror** (45d): `contact.*` and `opportunity.*` update EvalOS rows. What is still one-way is the *portal's own* data — a Sales edit in GHL reaches the mirror, not `client_application` |
| **Service request → Sales review** | PARTIAL | `ApplicationReviewController` `GET /api/opportunities/{id}/application`, `DealApplication.tsx` | **read-only is correct** (D35 — approve/reject/return are GHL stages). The one gap is the **documents** beside the answers on this same read (D34), which waits on Unit 53 |
| **Documents** | PARTIAL | `case_document`, `DocumentStore` (S3 put + presign, **keyed by the GHL contact id** since 2026-09-17 — D41), portal upload/read, staff read, expert letter | **case-scoped only.** Request-stage documents are **Unit 53** (D33, spec `53`); per-case picker missing (Q8); `case_document = 0` rows locally. A contact with no GHL id is refused at upload with a message naming the repair — the exposure D41 accepts |
| **Sales dashboard** | PARTIAL | `PipelineDashboard(audience=sales)`, `OpportunityBoardPage`, `SalesDeskService`, `SalesOpportunityController`, `MeetingsPage` | **SALES reading no case is correct as of 2026-09-17 (D19c)** — their world ends at won, so `ScopePredicate`'s PIPELINE arm returning `cb.disjunction()` is the rule, not a bug. *(This cell previously named it as the gap.)* What remains is the board's own polish |
| **GHL sync engine (Unit 45)** | **COMPLETE — all five slices** | `GhlFailure` (7 classes, `isRetriable()` / `stopsEverything()`), `GhlUnavailableException.failure()`/`status()`, `GhlHttp` honouring `Retry-After` on a 429 by pushing the shared pacer; `sync_drift` (`V56`), `SyncAuditService` + nightly `SYNC_AUDIT` sweep (paged full-list diff, opportunities only), `GET /api/sync/drift` (GM); `sync_outbox` (`V57`/`V58`), `SyncOutboxService` + `SYNC_OUTBOX` drain (2m) with search-before-create on the correlation key, and the portal's previously-swallowed create and submit-marker failures queued; **45d** `GhlMirrorHandler` routing `contact.created/updated` into `contact_snapshot` and six spellings of `opportunity.*` into `OpportunityMirrorService.absorbForContact`, plus the `MIRROR_DELTA` sweep (15m) over `refreshIfStale`; **45e** `FieldOwnership` (GHL owns the assignee and the pipeline, four shared fields, notes never synced), `Opportunity.syncFromGhl` keeping a shared field only while EvalOS holds an edit GHL has not confirmed, and `owner`/`resolution`/`needsAHuman` on `GET /api/sync/drift` | **The ownership rules are largely latent until Unit 46**, which moves the desks off synchronous GHL writes and onto the outbox — today the only rows carrying an unconfirmed local edit are portal-born ones between the create and GHL's acknowledgement. 45e pushes nothing to GHL: "EvalOS wins" is about what the mirror keeps |
| **GHL mirror (Unit 44)** | COMPLETE — all four slices | `pipeline` + `pipeline_stage` (`V50`), `PipelineMirrorService`, `PipelineMirrorSweep` (`PIPELINE_MIRROR`, 1h), `PipelinePurpose`, `GET/PUT /api/ghl/pipelines`; `opportunity` (`V51`), `OpportunityMirrorService`, `client_application.opportunity_id` (`V53`), the correlation custom field on create; `ghl_opportunity_cache` DROPPED (`V52`) along with `CachedOpportunity`, `OpportunityCache` and `GhlOpportunityClient`; `team_member_pipeline` (`V54`) with `PUT`/`DELETE /api/team-members/{id}/pipelines`, `PipelineScope.mine()` returning a set and `ScopePredicate`'s pipeline arm using `IN`; `intake-pipeline-name` retired for `purpose = INTAKE`; `client_account.contact_id` (`V55`) joining the account to its CRM row with D6 enforced by a partial unique index, `ContactSnapshotService` extracted, and set-password creating the contact (closing the prospect gap; it was sign-up until D3a moved it on 2026-09-16) | The `contact_snapshot` → `contact` **rename** is deferred: two seeds write that table and run after every migration, so a rename has nowhere to sit — `context/specs/44-ghl-tier1-mirror.md` §4.1. `OpportunityRepository.SCOPE` is `brandOnly` until 44b lines the pipeline axis up, so the pipeline scope lives in the finder signatures and `OpportunityRepositoryScopeTest` guards it. No sync engine: that is Unit 45 |
| **GM dashboard** | COMPLETE | `GmDashboard.tsx`, `GmOverviewService`, `GET /api/metrics/gm` (`hasRole('GM')`), `evalos.sales.monthly-goal` / `won-lookback-days` | `SALES_MONTHLY_GOAL` is unset (`0`), so the headline shows the amount without a % to goal. *Hot leads* and *Invoice sent* need the business to name the GHL stages; email-campaign stats need a wider PIT scope; social reach has no GHL endpoint at all — see `context/specs/51-gm-dashboard.md` §3 |
| **Marketing dashboard** | PARTIAL | `PipelineDashboard(audience=marketing)`, `MarketingLeadService`, `NewLeadForm` | **the two GHL funnel screens are GONE (2026-09-16).** `/marketing/email` and `/sales/pipeline` were GM-only, so the audience for a marketing funnel could not open one; the business removed them rather than widen the gate. `MarketingController`, `MarketingPipelineService`, `GhlFunnelCache`, `GhlFunnelCacheRepository` and `GhlPipelineClient.countIn` went with them. The `ghl_funnel_cache` **table** is still there and orphaned — the drop has nowhere to live (`V905` clears it, and `MigrationTreeTest` forbids a `db/migration` script numbered ≥900); see `GmOverviewService`'s note. A funnel screen returns as a *new* screen after Unit 25 puts the location on `brand` |
| **Conversations** | NOT IMPLEMENTED | grep across all three apps finds no conversation table, column, route or component | the whole feature; `opportunity_note` is the only message-like thing |
| **Calendar** | PARTIAL | `GhlCalendarClient` (calendars, free-slots, book, reschedule, addNote, forContact), `SalesCalendarController` | see `workflows.md` §6 |
| **Employee-wise booking** | PARTIAL | `assignedUserId` on booking; `GET /api/sales/users` | availability is per *calendar*, not per employee; no column joins a GHL user to a `team_member` |
| **Appointments** | PARTIAL | `meeting` table (V47), `SalesMeetingService`, `BookingForm.tsx`, `Meetings.tsx` | no cancel, no guests, no blocked-off time, no notes UI |
| **Payments (client)** | NOT IMPLEMENTED | `evalos_case.paid` / `paid_at` set by Handoff A; `PortalInvoiceService` reads GHL invoices | deliberate — invoicing is GHL's (D15) |
| **Payments (expert payouts)** | COMPLETE | `payout_ledger`, `payout_payment`, `PayoutService`, `PayoutController`, `PaymentController`, `PayoutBatch.tsx` | live settlement unexercised |
| **Cases** | COMPLETE | `evalos_case`, `CaseLifecycleService`, `CaseTransitions`, 30+ transition routes, `BoardView`, `CaseDetail` | — |
| **Production** | COMPLETE | 12 stages, `exception_state`, `document_checklist_item`, `ChecklistService`, four sweeps | — |
| **Expert workflow** | PARTIAL | `expert`, `expert_case_offer`, `ExpertMatchService`, `ExpertPortalService`, `/api/portal/expert/**`, `ExpertCasePortal.tsx` | **no expert accounts** — a staff-minted link is the whole of access, and whether that changes is **waiting on a stakeholder discussion** (Q6, D23), not on code. Expert cannot open evidence documents. *(Deployment dropped from this cell — D38)* |
| **Notifications** | PARTIAL | `notification` table, 11 `NotificationType`s, `NotificationService`, `NotificationListeners`, `NotificationBell` | **in-app only; push is owed** (D37 — in-app **and** push, and those two only). Needs a subscription table and a delivery step beside the existing write. No email and no SMS is now a decision, not a gap |
| **RBAC** | COMPLETE | `Role` + `Tier`, `ScopePredicate`, `ScopedRepository`, `@PreAuthorize`, `OwnershipGuard`, `navigation.ts` mirror, `DomainInvariantsTest` | — (PIPELINE matching no case is D19c, intended) |
| **Audit logs** | COMPLETE | `audit_event` + append-only trigger, `AuditService`, 16 `AuditAction`s incl. client sign-in, `GET /api/cases/{id}/timeline` | no cross-entity unified timeline |
| **Webhooks (inbound)** | COMPLETE | `InboundWebhookController`, `WebhookGateway` (HMAC + brand token), `WebhookRouter`, `GhlOpportunityHandler`, `GhlMirrorHandler` (45d), `WebhookPayload`, `webhook_event` idempotency | **three event families routed** as of 2026-09-17: `opportunity.won` → Handoff A, `contact.created/updated` → the contact mirror, six spellings of `opportunity.*` → the opportunity mirror. `refund.requested` is the last deferred no-op. `webhook_event = 0` rows locally — none of it has been exercised by a real delivery |
| **Webhooks (outbound)** | NOT IMPLEMENTED | `event/CaseEvents.java` only; no `webhook.outbound` package | Handoff C and invariant 11 |
| **S3 / document storage** | COMPLETE | `DocumentStore.put` + `presignedUrl` (5 min, never stored), SSE per request, 502 with named vars when unconfigured | never exercised against a real bucket |
| **Background jobs** | COMPLETE | **9 sweeps** (`MIRROR_DELTA` added at 45d), `JobLock`, `JobLedger`, `scheduled_job`, `GET /api/jobs/runs`, `JobRunsPage`, `SweepRegistrationTest` | — |
| **Portal cleanup** | COMPLETE | `PortalCleanupSweep` (`PORTAL_CLEANUP`, 24h), gated on `client_account.created_via = 'SIGNUP'` (`V59`) so seeded clients are never eligible: `ClientCredentialTokenRepository.deleteByExpiresAtBefore` one TTL past expiry, and `ClientAccountRepository.deleteAbandonedSignUps` — no password, no GHL contact, no token, no application, no session, older than `evalos.portal.abandoned-sign-up-after` (30d). The other half of D3a: refusing the CRM write bounded the damage, this bounds the tables | **Audit is deliberately not swept** (append-only, invariant), so a sign-up flood still grows `audit_event`. Two whole-table deletes, no batching — see the `ponytail:` note on the sweep |
| **Multi-brand** | PARTIAL | `brand` table, `BrandSwitcher`, brand-scoped everything | one GHL location globally; `/brands` nav entry renders `PlaceholderPage`; Client Portal is single-brand by config |
| **Deployment** | **OUT OF SCOPE HERE** | `docker-compose.yml`, `backend/Dockerfile`, `frontend/Dockerfile`, `.github/workflows/ci.yml` → EC2 | **DevOps owns deployment and edits it (D38, 2026-09-17)** — the portals' absence from compose and CI is not this repository's debt. CI still triggers on `main` only, so nothing since `dee45c6` has been through it: that is a fact to know, not a task here |

## Known operational state (not code)

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
