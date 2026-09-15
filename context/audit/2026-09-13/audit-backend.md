# EvalOS backend — implementation-quality audit

Scope: `backend/src/main/java/com/ie/evalos` (294 files), `backend/src/main/resources`,
`backend/src/test`. Read at 2026-09-13 on branch `development` @ `dee45c6`.
Serena MCP was down; everything below was read with Glob/Grep/Read/Bash.

Test suite: `./mvnw -q -o test` — **exit 0. 966 tests, 0 failures, 0 errors, 4 skipped,
93 test classes.** The build is green; the findings below are things the suite does not
assert.

---

## API inventory

### Staff chain (`SecurityConfig`, order 2 — bearer JWT, `anyRequest().authenticated()`)

| Route | Auth | File |
|---|---|---|
| `POST /api/auth/login` | permitAll | `web/AuthController.java:52` |
| `GET /api/me` | authenticated | `web/AuthController.java:60` |
| `GET /api/health` | permitAll | `web/HealthController.java:18` |
| `GET /api/brands` | `GM` | `web/BrandController.java:41` |
| `GET /api/cases` | authenticated (scope = query) | `web/CaseController.java:364` |
| `GET /api/cases/pm-notes` | authenticated | `web/CaseController.java:550` |
| `GET /api/cases/{id}` | authenticated | `web/CaseController.java:375` |
| `GET /api/cases/{id}/documents` | authenticated | `web/CaseController.java:493` |
| `GET /api/cases/{id}/documents/{documentId}/url` | authenticated | `web/CaseController.java:524` |
| `PATCH /api/cases/{id}/strategy-notes` | GM, PM | `web/CaseController.java:385` |
| `PATCH /api/cases/{id}/case-manager` | GM, PM | `web/CaseController.java:400` |
| `PATCH /api/cases/{id}/deadline` | GM, PM | `web/CaseController.java:413` |
| `PATCH /api/cases/{id}/intake-facts` | GM, PM, CM | `web/CaseController.java:428` |
| `POST /api/cases/{id}/flag` | GM, CM | `web/CaseController.java:442` |
| `POST /api/cases/{id}/notes` | authenticated | `web/CaseController.java:462` |
| `POST /api/cases/{id}/assign-pm` | GM, BM, PM | `web/CaseController.java:571` |
| `POST /api/cases/{id}/assign-cm` | GM, PM | `web/CaseController.java:577` |
| `POST /api/cases/{id}/assign-coordinator` | GM, BM, PM | `web/CaseController.java:588` |
| `POST /api/cases/{id}/docs-complete` | GM, BM, PC, PM | `web/CaseController.java:605` |
| `POST /api/cases/{id}/draft/submit` | GM, CM | `web/CaseController.java:618` |
| `POST /api/cases/{id}/draft/pm-approve` | PM only | `web/CaseController.java:639` |
| `POST /api/cases/{id}/draft/pm-return` | PM only | `web/CaseController.java:654` |
| `POST /api/cases/{id}/draft/send-to-client` | GM, PC | `web/CaseController.java:660` |
| `POST /api/cases/{id}/draft/client-approve` | GM, PC | `web/CaseController.java:667` |
| `POST /api/cases/{id}/draft/client-revisions` | GM, PC | `web/CaseController.java:674` |
| `POST /api/cases/{id}/expert/signed` | GM, PM, ENM, CM | `web/CaseController.java:684` |
| `POST /api/cases/{id}/expert/declined` | GM, PM, ENM, CM | `web/CaseController.java:693` |
| `POST /api/cases/{id}/expert/timed-out` | GM, BM, PM, CM | `web/CaseController.java:711` |
| `POST /api/cases/{id}/reassign-expert` | GM, PM, ENM, CM | `web/CaseController.java:719` |
| `POST /api/cases/{id}/qc-fail` | GM, PM | `web/CaseController.java:733` |
| `POST /api/cases/{id}/send-to-expert` | GM, PM, CM | `web/CaseController.java:746` |
| `POST /api/cases/{id}/qc-approve` | GM, PM | `web/CaseController.java:752` |
| `POST /api/cases/{id}/deliver` | GM, PC | `web/CaseController.java:760` |
| `POST /api/cases/{id}/close` | GM, PC | `web/CaseController.java:766` |
| `POST /api/cases/{id}/hold` | GM, PC, PM | `web/CaseController.java:774` |
| `POST /api/cases/{id}/resume` | GM, PC, PM | `web/CaseController.java:780` |
| `POST /api/cases/{id}/refund/request` | **authenticated, any role** | `web/CaseController.java:787` |
| `POST /api/cases/{id}/refund/approve` | GM | `web/CaseController.java:792` |
| `POST /api/cases/{id}/refund/deny` | GM | `web/CaseController.java:798` |
| `GET /api/cases/board` | authenticated | `web/CaseBoardController.java:156` |
| `GET /api/cases/{id}/timeline` | authenticated | `web/CaseTimelineController.java:36` |
| `GET /api/cases/{id}/checklist` | authenticated | `web/ChecklistController.java:161` |
| `PATCH /api/cases/{id}/checklist/{itemId}` | COORDINATION | `web/ChecklistController.java:166` |
| `POST /api/cases/{id}/checklist/items` | COORDINATION | `web/ChecklistController.java:174` |
| `POST /api/cases/{id}/chase` | COORDINATION | `web/ChecklistController.java:191` |
| `GET /api/checklists/board` | COORDINATION | `web/ChecklistController.java:149` |
| `GET /api/cases/{id}/expert-shortlist` | GM, BM, PM | `web/ExpertShortlistController.java:99` |
| `GET /api/cases/{id}/portal-link` | MAY_MINT | `web/PortalLinkController.java:77` |
| `POST /api/cases/{id}/portal-link?party=` | MAY_MINT | `web/PortalLinkController.java:98` |
| `GET /api/experts` (picker) | GM, BM, PM, ENM | `web/ExpertPickerController.java:78` |
| `GET /api/experts/roster` | ROSTER_READ | `web/ExpertController.java:267` |
| `GET /api/experts/availability-board` | ROSTER_READ | `web/ExpertController.java:285` |
| `GET /api/experts/{id}` | ROSTER_READ | `web/ExpertController.java:294` |
| `POST /api/experts` | ROSTER_WRITE | `web/ExpertController.java:309` |
| `PATCH /api/experts/{id}` | ROSTER_WRITE | `web/ExpertController.java:317` |
| `PATCH /api/experts/{id}/availability` | ROSTER_WRITE | `web/ExpertController.java:329` |
| `PATCH /api/experts/{id}/performance-flags` | ROSTER_WRITE | `web/ExpertController.java:347` |
| `PUT /api/experts/{id}/payment-detail` | ROSTER_WRITE | `web/ExpertController.java:361` |
| `POST /api/experts/import/validate` | ROSTER_WRITE | `web/ExpertController.java:377` |
| `POST /api/experts/import` | ROSTER_WRITE | `web/ExpertController.java:392` |
| `GET /api/payouts` | PAYOUTS | `web/PayoutController.java:72` |
| `GET /api/payouts/batch` | PAYOUTS | `web/PayoutController.java:88` |
| `GET /api/payouts/{id}` | PAYOUTS | `web/PayoutController.java:82` |
| `PATCH /api/payouts/{id}` | PAYOUTS | `web/PayoutController.java:96` |
| `POST /api/payouts/settle` | PAYOUTS | `web/PayoutController.java:104` |
| `GET /api/payments?expertId=` | PAYOUTS | `web/PaymentController.java:51` |
| `GET /api/payments/{id}` | PAYOUTS | `web/PaymentController.java:57` |
| `PATCH /api/payments/{id}` | PAYOUTS | `web/PaymentController.java:64` |
| `POST /api/payments/{id}/confirm` | PAYOUTS | `web/PaymentController.java:73` |
| `GET /api/metrics/pm` | GM, BM, PM | `web/MetricsController.java:83` |
| `GET /api/metrics/coordinator` | GM, BM, PC | `web/MetricsController.java:103` |
| `GET /api/metrics/case-manager` | GM, CM | `web/MetricsController.java:117` |
| `GET /api/metrics/portal-links` | GM, BM, PM, PC, CM | `web/MetricsController.java:150` |
| `GET /api/metrics/expert-network` | GM, BM, PM, ENM | `web/MetricsController.java:156` |
| `GET /api/metrics/revenue` | GM, BM, PM | `web/MetricsController.java:169` |
| `GET /api/metrics/drafts` | GM, BM, PM | `web/MetricsController.java:183` |
| `GET /api/metrics/nav` | authenticated | `web/MetricsController.java:197` |
| `GET /api/notifications?page&size` | authenticated (self) | `web/NotificationController.java:56` |
| `GET /api/notifications/unread-count` | authenticated | `web/NotificationController.java:64` |
| `POST /api/notifications/{id}/read` | authenticated | `web/NotificationController.java:69` |
| `POST /api/notifications/read-all` | authenticated | `web/NotificationController.java:75` |
| `GET /api/team-members` | GM, BM | `web/TeamMemberController.java:49` |
| `GET /api/team-members/assignable?role=` | GM, BM, PM | `web/TeamMemberController.java:66` |
| `PUT /api/team-members/{id}/ghl-pipeline` | GM | `web/TeamMemberController.java:90` |
| `GET /api/ghl/pipelines` | GM | `web/GhlPipelineController.java:55` |
| `GET /api/marketing/ads-pipeline` | GM | `web/MarketingController.java:71` |
| `GET /api/marketing/email-pipeline` | GM | `web/MarketingController.java:93` |
| `GET /api/marketing/sales-pipeline` | GM | `web/MarketingController.java:124` |
| `POST /api/marketing/leads` | MARKETING | `web/MarketingLeadController.java:59` |
| `PUT /api/marketing/leads/{opportunityId}` | MARKETING | `web/MarketingLeadController.java:66` |
| `GET /api/opportunities/board` | SALES, MARKETING, GM | `web/OpportunityBoardController.java:38` |
| `GET /api/opportunities/{opportunityId}/notes` | SALES, MARKETING | `web/OpportunityNoteController.java:56` |
| `POST /api/opportunities/{opportunityId}/notes` | SALES, MARKETING | `web/OpportunityNoteController.java:62` |
| `GET /api/sales/calendars` | SALES | `web/SalesCalendarController.java:40` |
| `PUT /api/sales/opportunities/{id}` | SALES | `web/SalesDeskController.java:75` |
| `PUT /api/sales/opportunities/{id}/stage` | SALES | `web/SalesDeskController.java:82` |
| `PUT /api/sales/opportunities/{id}/status` | SALES | `web/SalesDeskController.java:97` |
| `POST /api/sales/opportunities/{id}/follow-ups` | SALES | `web/SalesDeskController.java:108` |
| `POST /api/sales/opportunities/{id}/meetings` | SALES | `web/SalesDeskController.java:125` |
| `PUT /api/sales/opportunities/{id}/meetings/{appointmentId}` | SALES | `web/SalesDeskController.java:133` |
| `GET /api/jobs/runs` | GM | `web/JobAdminController.java:41` |
| `GET /api/jobs/sweeps` | GM | `web/JobAdminController.java:47` |
| `POST /api/jobs/{jobType}/run` | GM | `web/JobAdminController.java:60` |
| `POST /api/webhooks/ghl/{endpointToken}` | permitAll (token in path) | `webhook/InboundWebhookController.java:39` |

### Portal chain (`PortalSecurityConfig`, order 1 — `X-Portal-Token`, no roles)

| Route | Auth | File |
|---|---|---|
| `POST /api/portal/auth/identify` | permitAll | `web/ClientAuthController.java:383` |
| `POST /api/portal/auth/sign-in` | permitAll | `web/ClientAuthController.java:388` |
| `POST /api/portal/auth/forgot-password` | permitAll (204 always) | `web/ClientAuthController.java:397` |
| `POST /api/portal/auth/set-password` | permitAll | `web/ClientAuthController.java:403` |
| `GET /api/portal/client/invoices` | CLIENT token, party-scoped only | `web/ClientPortalController.java:79` |
| `GET /api/portal/client/meetings` | CLIENT token, party-scoped only | `web/ClientPortalController.java:95` |
| `GET /api/portal/client/case` | CLIENT token | `web/ClientPortalController.java:101` |
| `GET /api/portal/client/cases` | CLIENT token, party-scoped only | `web/ClientPortalController.java:113` |
| `GET /api/portal/client/cases/{caseId}` | CLIENT token | `web/ClientPortalController.java:126` |
| `POST /api/portal/client/approve` | CLIENT token | `web/ClientPortalController.java:141` |
| `POST /api/portal/client/cases/{caseId}/approve` | CLIENT token | `web/ClientPortalController.java:235` |
| `POST /api/portal/client/request-revisions` | CLIENT token | `web/ClientPortalController.java:216` |
| `POST /api/portal/client/cases/{caseId}/request-revisions` | CLIENT token | `web/ClientPortalController.java:240` |
| `GET /api/portal/client/documents` | CLIENT token | `web/ClientPortalController.java:153` |
| `GET /api/portal/client/documents/{documentId}/url` | CLIENT token | `web/ClientPortalController.java:166` |
| `POST /api/portal/client/documents` | CLIENT token | `web/ClientPortalController.java:191` |
| `GET /api/portal/expert/case` | EXPERT token | `web/ExpertPortalController.java:69` |
| `GET /api/portal/expert/cases` | EXPERT token, party-scoped only | `web/ExpertPortalController.java:75` |
| `GET /api/portal/expert/cases/{caseId}` | EXPERT token | `web/ExpertPortalController.java:86` |
| `GET /api/portal/expert/payouts` | EXPERT token | `web/ExpertPortalController.java:99` |
| `POST /api/portal/expert/accept` | EXPERT token | `web/ExpertPortalController.java:110` |
| `POST /api/portal/expert/request-evidence` | EXPERT token | `web/ExpertPortalController.java:116` |
| `POST /api/portal/expert/decline` | EXPERT token | `web/ExpertPortalController.java:123` |
| `GET /api/portal/expert/letter` | EXPERT token | `web/ExpertPortalController.java:129` |
| `POST /api/portal/expert/signed-letter` | EXPERT token | `web/ExpertPortalController.java:146` |

**129 routes across 28 controllers.** *(Corrected 2026-09-13: this line originally read "105
routes across 27 controllers" and undercounted. The table above was always complete — verified
against `grep -rhoE "@(Get|Post|Put|Patch|Delete)Mapping" web/ webhook/` → 129 across 28 files.
The inventory is right; only this summary was wrong.)*

---

## Findings

### Correctness

**C1. The client document upload is dead for every token the sign-in door mints. — P0**
- **What.** `PortalCaseService.upload` resolves the case as `cases.findById(principal.caseId())`
  instead of going through the class's own `authorized(principal)`. Every other method on the
  class goes through `authorized`. Since Unit 42, a client sign-in mints a **party- or
  account-scoped** `PortalAccess`, whose `case_id` is **null** by construction
  (`PortalPrincipal.isPartyScoped()` is defined as `caseId == null`,
  `security/PortalPrincipal.java:62`). `SimpleJpaRepository.findById(null)` asserts non-null
  and throws `IllegalArgumentException`, which the catch-all advice turns into
  **500 INTERNAL_ERROR**.
- **Evidence.** `service/PortalCaseService.java:345`; mint path
  `service/PortalAccessService.java:436-456`; controller `web/ClientPortalController.java:191`.
  No test covers it — `web/ClientPortalTest.java:300` stubs `portal.upload(...)` with Mockito
  and `service/PortalCaseServiceTest.java` has no upload test at all.
- **Impact.** No client who signed in through Unit 42 can upload a document. This is the whole
  point of the documents screen.
- **Decision.** MODIFY — replace line 345 with `Case subject = authorized(principal);` (and add
  a `upload(principal, caseId, ...)` overload, see C2). **Priority P0.**

**C2. `documents`, `documentUrl` and `upload` have no per-case variant, so a client with two
cases cannot reach their documents at all. — P1**
- **What.** `authorized(principal)` throws `AmbiguousCaseException` (409 `SAY_WHICH_CASE`)
  whenever a party token resolves to ≠1 case. `approve` and `requestRevisions` were given
  `/cases/{caseId}/…` variants for exactly this reason (`web/ClientPortalController.java:163-179`
  documents the fix); the three document routes were not.
- **Evidence.** `service/PortalCaseService.java:267, 301, 459-473`;
  `web/ClientPortalController.java:153, 166, 191` — no `{caseId}` in any of the three.
- **Impact.** A repeat client (two services = two cases, which `V15` explicitly permits) gets a
  409 on the documents tab and can never upload. A client with zero cases (self-signup,
  account-scoped token) gets "This link has no cases behind it".
- **Decision.** ADD the three `/cases/{caseId}/documents…` routes and route them through
  `authorized(principal, caseId)`. **Priority P1.**

**C3. No optimistic locking anywhere — concurrent transitions on one case silently lose. — P1**
- **What.** `ScopedEntity` has `@Id`, `brand_id`, `created_at` and **no `@Version`**. Only
  `GhlFunnelCache` has one. `CaseLifecycleService.apply` reads, checks
  `CaseTransitions.target(subject, action)`, mutates and saves with no `SELECT … FOR UPDATE`
  and no version column, so two staff acting at once (PM approve + PM return; two Accept
  clicks; deliver + hold) both pass the guard and the second write wins.
- **Evidence.** `domain/ScopedEntity.java:27-57`; `service/CaseLifecycleService.java:1189-1215`;
  `grep -rn "@Version" domain/` returns one hit, `domain/GhlFunnelCache.java:95`.
- **Impact.** A stage can be written twice with two audit rows and one surviving state; the
  audit trail then disagrees with the case. Money is protected — `attachToPayment` puts the
  precondition in the `WHERE` (`repository/PayoutLedgerRepository.java:87-98`) — but the case
  machine is not.
- **Decision.** ADD `@Version private long version` to `ScopedEntity` (+ one migration adding
  the column to each table), and map `OptimisticLockingFailureException` to a 409 in
  `ApiExceptionHandler`. **Priority P1.**

**C4. `PortalCaseService.upload` skips the brand check every other path applies. — P1**
- **What.** `byId(principal, caseId)` asserts `subject.getBrandId().equals(principal.brandId())`
  (`service/PortalCaseService.java:507`). `upload` does not — it loads by id and writes into
  the case. Today the id comes from the token so the brand always matches; the moment C1/C2 is
  fixed by passing a path variable, this becomes an exploitable cross-brand write if the fix
  does not route through `authorized`.
- **Evidence.** `service/PortalCaseService.java:345-374` vs `504-511`.
- **Decision.** MODIFY — same one-line fix as C1 closes it. **Priority P1** (fix together).

**C5. Webhook `accept()` is not transactional and races on first delivery. — P2**
- **What.** `WebhookGateway.accept` is a plain method; each `webhookEvents.save` opens its own
  transaction. Two simultaneous deliveries of the same event both miss `alreadySeen`, both
  insert, and one hits `V13`'s unique key → the loser answers 500 and GHL redelivers. The
  business invariant is still safe (`V15`'s partial unique index is what actually stops a
  second case), so this is noise, not corruption.
- **Evidence.** `webhook/WebhookGateway.java:89-144`; `V13__webhook_event_brand_scoped_key.sql`;
  `V15__one_open_case_per_contact_service.sql`.
- **Decision.** KEEP, and note it — making `accept` `@Transactional` would *break* the
  error-recording path at line 137-140, which relies on committing outside the handler's
  rollback. **Priority P2.**

**C6. `setPassword` spends one credential token and leaves the sibling live. — P2**
- **What.** A client can hold both a `SET` and a `RESET` token (different `purpose`, both
  admitted by `findFirstByClientAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter`). Spending
  one marks only that row used; the other stays usable for the rest of its TTL and can set the
  password again.
- **Evidence.** `service/ClientAccountService.java:266-298` (only `credential.markUsed`);
  `repository/ClientCredentialTokenRepository.java:47`.
- **Impact.** A 30-minute window in which a stale mailbox link still rewrites the password after
  the client has set one. Small, but it is the one place a mailbox link is a credential.
- **Decision.** MODIFY — mark every unused token for that account used inside the same
  transaction. **Priority P2.**

**C7. `EVALOS_PORTAL_CLIENT_BRAND` unset degrades to "no client can ever sign in", silently. — P2**
- **What.** `evalos.portal.client-brand` defaults to empty (`application.yml:105`) and is
  injected as `UUID brandId` (`service/ClientAccountService.java:100`). Spring converts an
  empty string to `null`, so every finder runs with `brandId = null`, matches nothing, and
  `identify` answers `UNKNOWN` for every real client. No warning is logged. Contrast
  `OpportunityBoardService`, which fails the boot on a malformed brand UUID
  (`service/OpportunityBoardService.java:97-107`), and `GhlHttp`/`DocumentStore`/`ClientMailer`,
  which all log a warning at boot.
- **Decision.** ADD a boot-time warning (or a fail-fast, matching `OpportunityBoardService`).
  **Priority P2.**

---

### Security

Ranked by exploitability. The security posture here is genuinely strong: two fully separate
filter chains, `FilterRegistrationBean(enabled=false)` keeping `JwtFilter` off the global
servlet chain (`security/SecurityConfig.java:77-82`), SHA-256 token storage with
`MessageDigest.isEqual` (`service/PortalAccessService.java:558-568`), 256-bit `SecureRandom`
tokens, BCrypt, presigned URLs forced to `Content-Disposition: attachment`
(`integration/DocumentStore.java:171`), content sniffing on upload
(`web/ClientPortalController.java:144`), named CORS origins with `allowCredentials(false)`
(`security/PortalSecurityConfig.java:53-69`), no secrets in any committed yaml
(`config/ConfigSecretsTest`). I found **no IDOR**: every portal path either takes no id, or
matches the id against the credential before reading (`PortalCaseService.authorized(p, caseId)`
at `:483-498`, `ExpertPortalService.authorized(p, caseId)` at `:566-580`).

**S1. Client password guessing has no per-account throttle and no lockout. — P1**
- **What.** `PortalTokenFilter.overLimit` keys the 60/min budget on `request.getRemoteAddr()`
  — a *caller*, not an *account*. Nothing counts failures per `client_account`. With
  `forward-headers-strategy: none` (the default) and any distributed source, a single account's
  password is attackable at (N sources × 60)/min. `evalos.portal.rate-limit-per-minute`'s own
  comment says precisely this ("it limits a *caller*, not a *token*").
- **Evidence.** `security/PortalTokenFilter.java:110-117`; `application.yml:143-152`;
  `service/ClientAccountService.java:218-236` (no attempt counter).
- **Impact.** BCrypt at default strength is the only real brake. `CLIENT_SIGN_IN_REFUSED` *is*
  audited (`:225-227`) but nothing reads it — there is no alert, no lockout, no exponential
  backoff.
- **Decision.** ADD a per-account failure counter (a column on `client_account` plus a
  lock-until, or a bounded in-memory map keyed by account id, same shape as the IP limiter).
  **Priority P1.**

**S2. The portal rate limiter is per-JVM and per-instance. — P2**
- **What.** `ConcurrentHashMap` + a fixed minute window, cleared on roll. Two app instances each
  allow the full budget; a restart resets it. The class comment marks this as a deliberate
  `ponytail:` ceiling for single-instance deployment.
- **Evidence.** `security/PortalTokenFilter.java:65, 51-63`.
- **Decision.** KEEP (documented ceiling), upgrade to Redis/gateway when the app goes
  multi-instance. **Priority P2.**

**S3. `POST /api/cases/{id}/refund/request` is open to every authenticated staff role. — P2**
- **What.** No `@PreAuthorize`. The scoped load in `CaseLifecycleService.load` keeps it inside
  the caller's brand/team/assignment, so it is not a cross-tenant hole — but a Coordinator or
  ENM can flip any case they can see into `REFUND_REQUESTED`, which `CaseTransitions` then
  treats as an exception state that **blocks every other transition** until a GM rules on it
  (`service/CaseTransitions.java:210-212`).
- **Evidence.** `web/CaseController.java:786-790`.
- **Impact.** A denial-of-progress on any visible case, by any staff role, one POST.
- **Decision.** MODIFY — the javadoc says "any authenticated staff member — and, from Unit 18,
  GHL's refund webhook", but Unit 18 was removed (`V33__drop_unit_13_18_20.sql`) and nothing
  else calls it. Narrow it to GM/BM/PM/PC. **Priority P2.**

**S4. `identify` is a deliberate email-enumeration oracle. — P2 (accepted)**
- **What.** Three-way answer reveals whether an address has an account and whether it has a
  password. The service javadoc argues the trade explicitly and points at the per-IP limiter as
  the containment.
- **Evidence.** `service/ClientAccountService.java:115-148`.
- **Decision.** KEEP — it is a written decision, not an oversight. Worth re-reading if S1 is
  not fixed, because the two compound.

**S5. `signIn` leaks account existence through timing. — P2**
- **What.** An unknown email throws before any BCrypt call; a known one pays ~100ms of hashing.
- **Evidence.** `service/ClientAccountService.java:219-229`.
- **Impact.** Nil in practice given S4 already reveals it by design. Listed for completeness.
- **Decision.** KEEP while S4 stands. **Priority P2.**

**S6. Recipient email addresses are written to WARN/ERROR logs. — P2**
- **Evidence.** `service/ClientMailer.java:94, 111` (`"'{}' to {} was not sent"`).
- **Impact.** PII in log aggregation. `DocumentStore.clientKey` goes out of its way to keep
  addresses out of S3 keys for exactly this reason (`integration/DocumentStore.java:191-193`);
  the mailer does not hold the same line.
- **Decision.** MODIFY — log the `client_account` id, not the address. **Priority P2.**

**S7. The webhook endpoint has no signature and no replay window. — P2 (accepted)**
- **What.** The per-brand path token is the whole credential; the body is unsigned. Documented
  at `webhook/InboundWebhookController.java:19-22` as forced by GHL's Custom Webhook action.
  Replay protection is the `event_id`/`webhook_id` key, falling back to a SHA-256 of the body
  (`webhook/WebhookGateway.java:177-185`) — so a genuinely-distinct byte-identical redelivery
  is swallowed, and there is no time bound on the archive, so a captured payload replays as a
  duplicate ack rather than a second case. That is the safe direction.
- **Decision.** KEEP. **Priority P2.**

---

### Brand scoping

I checked every method on all 21 repositories. **There is no unscoped scoped-entity read that
is a bug.** Every exception is deliberate, documented in the repository's own javadoc, and in
most cases the scope is carried structurally (as a required parameter) rather than by
predicate. This is the strongest part of the codebase.

Unscoped-by-design, all justified:

| Method | File | Why it is not a bug |
|---|---|---|
| `BrandRepository.findByActiveTrueOrderByNameAsc` | `repository/BrandRepository.java:20` | GM-only brand switcher; `web/BrandController.java:42` gates it |
| `BrandRepository.findByWebhookEndpointTokenAndActiveTrue` | `:23` | The token *is* the brand resolution (Handoff A) |
| `TeamMemberRepository.findByEmailIgnoreCaseAndActiveTrue` | `repository/TeamMemberRepository.java:23` | Login precedes tenancy |
| `TeamMemberRepository.findByGhlPipelineIdAndActiveTrue` | `:45` | `uq_team_member_pipeline` is globally unique; GM-only caller |
| `TeamMemberRepository.findByActiveTrueAndRole` | `:71` | GM pool; narrowed to one role |
| `PortalAccessRepository.findByTokenHash` | `repository/PortalAccessRepository.java:41` | The credential is the scope; brand read off the row |
| `ClientCredentialTokenRepository.findByTokenHash` | `repository/ClientCredentialTokenRepository.java:32` | Same; a brand parameter here would be caller-controlled |
| `PortalAccessRepository.findByClientAccountIdOrderByCreatedAtDesc` | `:86` | Account id already implies one brand (`V43`) |
| `CaseRepository.countCasesPerExpert` | `repository/CaseRepository.java:129` | Aggregates over already-scoped expert ids; asserted in `LocalPostgresIntegrationTest` |
| `CaseRepository.findAllAtStageForSweep` / `findActiveForSweep` | `:177, :186` | Sweeps have no `TenantContext` |
| `GhlFunnelCacheRepository` (whole) | `repository/GhlFunnelCacheRepository.java:24` | Rows belong to a GHL location, not a brand; GM-only route |
| `ScheduledJobRepository` (whole) | `repository/ScheduledJobRepository.java:19` | No brand column; GM-only routes |
| `CachedOpportunityRepository` (whole) | `repository/CachedOpportunityRepository.java:28` | Scoped by required `ghlPipelineId` parameter; `CachedOpportunityRepositoryScopeTest` fails the build on an unscoped finder |
| `WebhookEventRepository.findBySourceAndBrandIdAndExternalId` | `repository/WebhookEventRepository.java:28` | Brand *is* in the key |

**B1. Three finders are "scoped by convention", and the convention is a javadoc. — P2**
- **What.** `DocumentChecklistItemRepository.findByCaseId`,
  `ExpertCaseOfferRepository.findByCaseIdAndOutcome` / `findByCaseIdOrderByOfferedAtDesc`,
  `PortalAccessRepository.findByCaseIdAndAudienceOrderByCreatedAtDesc`, and
  `PayoutLedgerRepository.findByCaseIdAndStatus` / `findByPaymentId` take an id and no brand.
  Each carries "do not call it with an id that arrived from a request". The batched siblings
  were already converted (`findByBrandIdInAndCaseIdIn`, `findCaseActionScoped`) precisely
  because "a comment is not a scope" — these are the leftovers.
- **Evidence.** `repository/DocumentChecklistItemRepository.java:29`;
  `repository/ExpertCaseOfferRepository.java:46, 61`; `repository/PortalAccessRepository.java:52`;
  `repository/PayoutLedgerRepository.java:33, 111`.
- **Impact.** No current caller violates it (I traced all of them). It is a live trap for the
  next one.
- **Decision.** REFACTOR — add `brandId` to each signature, the way the other two were fixed.
  **Priority P2.**

**B2. `findScoped(ctx)` on an unbounded table is a scope, not a limit. — see P1 in Performance**
- The scoping is correct; the read volume is not. See P1/P2 below.

---

### Performance

**P1. Eight read models load the entire scoped case table on every request. — P1**
- **What.** `CaseLifecycleService.list(null, null, null)` returns every case the caller may see,
  recomputes SLA on each row in Java (`withCurrentSla`), and hands the whole list to the caller,
  which then filters and aggregates in memory. Eight services do this:
  - `service/PmMetricsService.java:102`
  - `service/CoordinatorMetricsService.java:72`
  - `service/CaseManagerMetricsService.java:148`
  - `service/DraftReviewService.java:136`
  - `service/RevenueMetricsService.java:66`
  - `service/NavBadgeService.java:63`
  - `service/CaseBoardService.java:60`
  - `service/PortalLinkLedgerService.java:105`
- **Evidence.** `service/CaseLifecycleService.java:179-187`.
- **Impact.** `GET /api/metrics/nav` is on every page load and scans the whole brand's cases. At
  the stated volume (50-100 cases/brand/month) this is fine for a year or two and then is not.
  The SLA recompute-in-Java is the reason it cannot be pushed to SQL as written.
- **Decision.** REDESIGN when case volume crosses ~10k: the SLA column is already maintained by
  `StageSlaSweep` every 15 minutes, so the recompute is belt-and-braces and the filters could go
  to SQL. **Priority P1** to track, not to fix today.

**P2. `PortalLinkLedgerService` is a textbook N+1. — P1**
- **What.** For every open case × every `PortalAudience` value, one
  `findByCaseIdAndAudienceOrderByCreatedAtDesc`. 2 queries per case, on top of P1's full scan.
- **Evidence.** `service/PortalLinkLedgerService.java:112-117` (nested loop) calling `:128`.
- **Decision.** REFACTOR — one `findByBrandIdInAndCaseIdIn`-shaped batch read, exactly as
  `ChecklistService.board` already does it (`service/ChecklistService.java:150-156`).
  **Priority P1.**

**P3. `PayoutService` reads the whole ledger to answer three screens. — P1**
- **What.** `batch`, `list` and `history` each do `payouts.findScoped(ctx)` — every payout row
  in the brand, ever — then filter by week / status / expert in a stream. `history` does it
  twice (payments *and* payouts).
- **Evidence.** `service/PayoutService.java:322, 372, 378, 438`.
- **Impact.** Unlike cases, payout rows never close: this grows monotonically forever, and
  `GET /api/payouts` has no pagination.
- **Decision.** REFACTOR — the filters are all expressible as Specifications on
  `ScopedRepository`, which already extends `JpaSpecificationExecutor`. **Priority P1.**

**P4. `ExpertPortalService.payoutRows` is 2N+1. — P2**
- **What.** Per payout row: `cases.findById` for the case code and `payments.findById` for the
  settlement date.
- **Evidence.** `service/ExpertPortalService.java:310-318`.
- **Decision.** REFACTOR to two batched `findAllById` lookups, the pattern `PayoutService
  .expertNames/caseCodes` already uses (`service/PayoutService.java:579-602`). **Priority P2.**

**P5. `GET /api/opportunities/board` makes an uncached GHL call on every load. — P1**
- **What.** `draw()` calls `pipelines.pipelines()` unconditionally, on every request, to resolve
  stage names. That is one GHL round trip per board load *regardless of the cache*, plus a full
  pipeline refill when the 2-minute TTL has expired. The opportunity cache absorbs the second;
  nothing absorbs the first.
- **Evidence.** `service/OpportunityBoardService.java:184` → `integration/GhlPipelineClient.java:128`.
- **Impact.** Against a 100-req/10s-per-location budget shared with the marketing funnels and
  the sales desk, a room of open boards spends the budget on a list of stage names that changes
  monthly.
- **Decision.** ADD a TTL cache on `pipelines()` (the same `cache-ttl` the funnels use).
  **Priority P1.**

**P6. `GhlHttp.pace()` sleeps on the request thread, serialising every GHL caller. — P1**
- **What.** One global 110ms spacing, claimed under `synchronized(this)` and slept outside it.
  Correct as a limiter, but the wait is charged to a Tomcat request thread. N concurrent
  GHL-backed requests make the Nth wait N×110ms *before* its own 10s read timeout starts.
- **Evidence.** `integration/GhlHttp.java:226-246`; timeout at `application.yml:240`.
- **Impact.** With 20 in-flight GHL requests the tail waits 2.2s in `Thread.sleep` holding a
  worker. `MarketingPipelineService` already refills off-thread; the board, the sales desk, the
  portal invoices and the portal meetings all sit on the request path.
  `GET /api/portal/client/invoices` and `/meetings` are **synchronous external calls on a
  client-facing path** with no cache at all.
- **Decision.** MODIFY — bound the pace wait (refuse with 503 rather than queue past N), and
  cache `PortalInvoiceService`/`PortalMeetingService` per contact for a short TTL.
  **Priority P1.**

**P7. `GET /api/cases`, `/api/cases/board`, `/api/payouts`, `/api/payments`,
`/api/metrics/*`, `/api/experts/availability-board`, `/api/jobs/runs` return unbounded lists.
— P2**
- Only `/api/experts/roster` (clamped, `web/ExpertController.java:279`) and
  `/api/notifications` (clamped in the service) paginate. `/api/jobs/runs` is bounded by
  `findTop50ByOrderByStartedAtDesc`.
- **Decision.** ADD pagination to `/api/payouts` and `/api/cases` first — those two grow without
  bound. **Priority P2.**

**P8. In-memory filtering that should be SQL. — P2**
- `ExpertService.readable()` reads the whole scoped roster then filters by brand, search, tag,
  letter type, availability and tier in Java, and pages the result in memory
  (`service/ExpertService.java:485-488`). Documented as acceptable at "tens of rows".
  `ExpertMatchService.java:238` and `ExpertNetworkMetricsService.java:138` do the same.
- **Decision.** KEEP at current scale; revisit with the roster. **Priority P2.**

**P9. `JobLock` holds a Hikari connection for the whole sweep. — P2**
- Session-scoped advisory lock, so this is inherent, and it is documented
  (`job/JobLock.java:167-175`). Four sweeps = four connections pinned whenever they overlap,
  on top of each item's own transaction.
- **Decision.** KEEP; size the pool with this in mind. **Priority P2.**

---

### Duplication / Dead code

**D1. `OpportunityCache.evict` has no caller. — P2**
- Its own javadoc says "Nothing calls this yet" (`service/OpportunityCache.java:221`). It is
  the only caller of `CachedOpportunityRepository.evict` (`repository/CachedOpportunityRepository.java:69`).
- **Decision.** REMOVE both, or wire the inbound webhook to it. **Priority P2.**

**D2. Half of `ClientAccount` is unwritten. — P2**
- `linkGhlContact`, `setFirstName`, `setLastName`, `setPhone`, `setCountry`, `getFirstName`,
  `getLastName`, `getPhone`, `getCountry`, `getLastSignInAt` have no caller in `src/main`
  (`domain/ClientAccount.java:510-552`). The matching columns `first_name`, `last_name`, `phone`,
  `country` (`V43__client_account.sql`) are written by nothing — `V45` seeds only `email` and
  `ghl_contact_id`. They are Unit 43 scaffolding.
- **Decision.** KEEP the columns (Unit 43 lands next), REMOVE the unused accessors. **P2.**

**D3. `ClientCredentialToken.getPurpose`, `getExpiresAt`, `getUsedAt` have no caller. — P2**
- `domain/ClientCredentialToken.java:621-631`. `isUsable` is the only thing read.
- **Decision.** REMOVE. **Priority P2.**

**D4. Dead expert columns, correctly unmapped but still in the schema. — P2**
- `expert.current_active_count`, `total_cases_completed` (`V7`) are not mapped in
  `domain/Expert.java:89` and never written. `avg_response_hours` (`domain/Expert.java:86`) and
  `total_payments_pending` (`:111`) **are** mapped, have public getters, and are written by
  nothing — four services document them as permanently dead
  (`service/ExpertLoadService.java:17`, `ExpertMatchService.java:341`,
  `ExpertNetworkMetricsService.java:111`, `repository/PayoutLedgerRepository.java:57`).
- **Impact.** `getTotalPaymentsPending()` returns a hardcoded zero and looks like an answer.
- **Decision.** REMOVE the two mapped fields and their getters; drop all four columns in a
  migration. **Priority P2.**

**D5. Portal authorization logic is duplicated between the two portal services. — P2**
- `authorized(principal)`, `authorized(principal, caseId)` and `partyCases(principal)` exist
  twice, in `service/PortalCaseService.java:459-532` and
  `service/ExpertPortalService.java:544-587`, with the same shape and different party columns.
  C1 is exactly the bug that a second copy invites — one of them forgot to use it.
- **Decision.** REFACTOR into one `PortalCaseResolver` parameterised by how the party maps to
  cases. **Priority P2.**

**D6. Small copy-pasted helpers. — P2**
- `blankToNull` in `service/CaseIntakeService.java:208` and `service/PayoutService.java:620`;
  `trimmed` in `service/ExpertService.java:522`; trailing-slash trimming in
  `service/PortalAccessService.java:115` and inline at `service/ClientAccountService.java:111`.
- **Decision.** REFACTOR into `common/` (or KEEP — this is genuinely marginal). **Priority P2.**

**D7. Leftovers from removed units are already gone. — no action**
- I found **no** residue of Unit 18 (outbound dispatcher), Unit 20 (AI), Unit 29 (sales desk) or
  Unit 21 (uploads). `V30__drop_sales_executive.sql`, `V33__drop_unit_13_18_20.sql` and
  `V34__drop_drive_link.sql` did the schema work; `PortalAccessService.expertUrl` documents the
  deleted client-link arm (`:606-612`) and `application.yml:120-125` documents the deleted
  `base-url`. Migrations V1–V46 are contiguous with no orphans, and `MigrationTreeTest` guards
  the tree. The only stale *reference* is the javadoc on
  `web/CaseController.java:786` still citing Unit 18 (see S3).

---

### Error handling & observability

**E1. `ApiExceptionHandler` is comprehensive and correctly layered. — KEEP**
- 13 handlers: validation, unreadable body, bad parameter, upload-too-large, auth, ambiguous
  case (409 `SAY_WHICH_CASE`), illegal transition (409), webhook rejection (source's own
  status), document store (502), GHL (502), forbidden (403 with a fixed message that reveals
  nothing), no-such-route (404), catch-all (500). The `NoResourceFoundException` handler
  exists because its absence made every typo'd path an alertable 500
  (`common/ApiExceptionHandler.java:184-198`) — that is the level of care throughout.
- **Decision.** KEEP. One gap: no `OptimisticLockingFailureException` handler, because there is
  no optimistic locking (C3).

**E2. A failing GHL call is a clean 502 on every path, and **nothing retries**. — P2**
- `GhlHttp.call` catches only `RestClientException` — "a `RuntimeException` from anywhere else
  is our bug and is left to propagate" (`integration/GhlHttp.java:189-215`). The upstream status
  is in the message and the response body is logged server-side. Writes deliberately do not
  retry, for lack of an idempotency key (`:58-59`).
- **Impact.** A GHL outage takes out `/api/opportunities/board`, `/api/sales/*`,
  `/api/marketing/*`, `/api/ghl/pipelines`, and **the client's invoices and meetings tabs** —
  all as 502s. The rest of EvalOS keeps working, which is the design.
- **Decision.** KEEP the no-retry rule. ADD a short read cache in front of the two portal calls
  (see P6) so a GHL blip does not blank a client's billing page.

**E3. Failures are logged but nothing surfaces them. — P1**
- `webhook_event.error` records a failed handler (`webhook/WebhookGateway.java:137`),
  `scheduled_job` records `FAILED` with the message (`job/SweepRunner.java:340`), and
  `AuditAction.CLIENT_SIGN_IN_REFUSED` records a bad password. `GET /api/jobs/runs` and
  `/api/jobs/sweeps` expose the sweep ledger to a GM. **There is no screen, no metric and no
  alert for a failed webhook or a run of refused sign-ins**, and no Actuator endpoint beyond
  `health` (`application.yml:287-298`).
- **Decision.** ADD a GM-visible webhook-failure list, and expose `metrics`/`prometheus`.
  **Priority P1.**

**E4. Logging quality is high. — KEEP**
- No token, secret, or password appears in any log line I found. `GhlHttp` logs the token's
  *length* only, and says why (`integration/GhlHttp.java:105-117`). `JwtFilter` logs the
  rejection reason and never the token (`security/JwtFilter.java:57`). The one lapse is S6.

---

### Tests

`./mvnw -q -o test` → **exit 0, 966 tests, 0 failures, 4 skipped, 93 classes.** Runtime was
well under the session budget. Coverage of the *decisions* is unusually good: there are tests
asserting that scope declarations exist (`DomainInvariantsTest`), that no unscoped finder is
added to `CachedOpportunityRepository` (`CachedOpportunityRepositoryScopeTest`), that config
carries no secrets (`ConfigSecretsTest`), that the GHL verb list stays closed (`GhlHttpTest`),
that `payment_detail` never serializes (`ExpertPortalServiceTest`), and that a `Segment` is not
an access key (`SegmentIsNotAnAccessKeyTest`).

**T1. Critical paths with no test class. — P1**

| Untested | File | Why it matters |
|---|---|---|
| `WebhookGateway` | `webhook/WebhookGateway.java` | Idempotency, brand resolution, the retry-vs-duplicate distinction, the SHA-256 fallback key. `InboundWebhookTest` covers the controller, not the gateway's dedupe logic. |
| `GhlOpportunityHandler` | `webhook/GhlOpportunityHandler.java` | Handoff A's payload binding, the `full_name` reconstruction, the `@Positive` amount, the default service type |
| `RefundService` | `service/RefundService.java` | Voiding pending payouts on approve, and `isRevenueRecognized` — invariant 5 |
| `CaseTransitions` | `service/CaseTransitions.java` | **The state machine table itself.** Transitions are exercised through `CaseLifecycleServiceTest`, but the table's own guards (`REQUIRES_EXCEPTION`, the one-exception-at-a-time rule at `:210`) have no direct test |
| `SlaCalculator` | `service/SlaCalculator.java` | The RAG status every board is painted from |
| `JwtService` | `security/JwtService.java` | Issue/verify round trip, the pre-Unit-36 null-pipeline path at `:85-88`, the 32-byte key floor |
| `JobLock` | `job/JobLock.java` | The advisory lock. `SweepRunnerTest` mocks it |
| `PipelineScope` | `service/PipelineScope.java` | The fail-closed check in front of every sales/marketing write |
| `DocumentStore` | `integration/DocumentStore.java` | Key construction (`clientKey`/`caseKey`) is a security boundary and is pure-static — trivially testable |
| `RecipientResolver`, `NavBadgeService`, `CoordinatorMetricsService`, `CaseDetailService`, `ChecklistTemplates` | — | Read models, lower stakes |

**T2. The portal upload path is tested only through a Mockito stub. — P0 (this is how C1 survived)**
- `web/ClientPortalTest.java:300` stubs `portal.upload(...)`, so the controller test proves the
  sniffing and the wiring and nothing about the service. `service/PortalCaseServiceTest.java`
  has no upload test. A single test calling `upload` with a party-scoped `PortalPrincipal`
  would have caught C1.
- **Decision.** ADD. **Priority P0** (ship with the C1 fix).

**T3. No concurrency tests anywhere.** Consistent with C3 — nothing asserts that two
simultaneous transitions on one case behave. `LocalPostgresIntegrationTest` does exercise the
real partial unique indexes (and its
`anAccountScopedPortalTokenIsLegalAndStillOnlyOneLives` case is what found the
`saveAndFlush` ordering bug documented at `service/PortalAccessService.java:491-504`) — that is
the right instinct, applied to tokens but not to cases. **Priority P2.**

---

### API surface

**A1. `@PreAuthorize` is absent from the case reads by design, and the design is sound.**
Every read gate is the scoped load (`CaseLifecycleService.load` → `findScoped`), so a role
widening cannot widen a read. This is stated at `web/CaseController.java:47` and
`web/CaseBoardController.java:37-50`. KEEP.

**A2. Every endpoint returns a DTO except two. — P2**
- `GET /api/jobs/runs` returns `List<ScheduledJob>` — the JPA entity
  (`web/JobAdminController.java:43`).
- `GET /api/portal/expert/payouts` returns a hand-built whitelist record, correctly — but
  `GET /api/portal/client/invoices` and `/meetings` return
  `GhlInvoiceClient.ClientInvoice` / `GhlCalendarClient.ClientMeeting`, integration-layer
  records, straight to the browser (`web/ClientPortalController.java:80, 96`).
- **Decision.** MODIFY `/api/jobs/runs` to a DTO. The GHL records are narrow projections
  already, so KEEP those. **Priority P2.**

**A3. Route-convention inconsistencies. — P2**
- `PUT /api/experts/{id}/payment-detail` is the only `PUT` among `PATCH`es on the same
  controller (`web/ExpertController.java:361`).
- `ExpertShortlistController` has no class-level `@RequestMapping` and declares its full path on
  the method (`web/ExpertShortlistController.java:99`); every other controller uses a class
  prefix.
- `ChecklistController` maps `/api` and spells out two different prefixes on its methods
  (`/checklists/board` and `/cases/{id}/…`), so `/api/cases/**` is served by three controllers.
- `AuthController` maps `/api` and serves `/auth/login` plus `/me`.
- **Decision.** KEEP (cosmetic), or REFACTOR in one pass. **Priority P2.**

**A4. No endpoint exists without a caller** that I could identify from the backend side — the
frontend is out of scope for this audit, so "no caller" claims would be speculation. The one
*service method* with no caller is D1.

---

## Things safe to delete today

All of these are unreferenced in `src/main` and `src/test`; deleting them cannot change
behaviour.

1. `service/OpportunityCache.java` — the `evict(String)` method (`:224-227`) **and** its only
   dependency `repository/CachedOpportunityRepository.java:67-69` (`evict`). Its own javadoc
   says nothing calls it.
2. `domain/ClientAccount.java` — `linkGhlContact` (`:510`), `setFirstName` (`:526`),
   `setLastName` (`:534`), `setPhone` (`:542`), `setCountry` (`:550`), `getFirstName` (`:522`),
   `getLastName` (`:530`), `getPhone` (`:538`), `getCountry` (`:546`), `getLastSignInAt` (`:518`).
   *(Keep the fields and columns — Unit 43 needs them. Delete only the accessors.)*
3. `domain/ClientCredentialToken.java` — `getPurpose` (`:621`), `getExpiresAt` (`:625`),
   `getUsedAt` (`:629`).
4. `domain/Expert.java` — the `avgResponseHours` field (`:86-87`) and its getter (`:376`); the
   `totalPaymentsPending` field (`:111-112`) and its getter (`:415`). Both are permanently
   null/zero and four services say so in writing. A follow-up migration should drop
   `expert.avg_response_hours`, `expert.total_payments_pending`, `expert.current_active_count`
   and `expert.total_cases_completed`.
5. `service/PortalAccessService.java` — **nothing.** `mintPartyForExpert` and `statusForExpert`
   both have live callers in `web/PortalLinkController.java`.

Not safe to delete, despite looking it: `ScopePredicate.Fields.brandAndPipeline` (used by
`OpportunityNoteRepository`), `PortalAccessService.MintedLink` (expert path), every `SCOPE`
constant (`DomainInvariantsTest` requires them).

---

## Do-not-break list

Production behaviour an upgrade must preserve. Each of these is load-bearing and most were
bought with a bug.

1. **The two filter chains never read each other's credentials.** `PortalSecurityConfig` is
   `@Order(1)` on `/api/portal/**` and contains no `JwtFilter`; `PortalTokenFilter` is *not* a
   bean so Boot cannot auto-register it; `jwtFilterIsChainOnly`
   (`security/SecurityConfig.java:77-82`) disables `JwtFilter`'s servlet registration. Removing
   any one of the three re-opens it.
2. **`PortalAccessService.retire` uses `saveAndFlush`, and the flush is required.** Hibernate
   runs inserts before updates at flush, so a plain `save` makes the second mint collide with
   the row it just superseded on `V23`/`V38`/`V44`'s partial unique indexes. Reproduced in
   `LocalPostgresIntegrationTest.anAccountScopedPortalTokenIsLegalAndStillOnlyOneLives`
   (`service/PortalAccessService.java:491-512`).
3. **`signIn` is `@Transactional(noRollbackFor = InvalidRequestException.class)`.** Without it
   the `CLIENT_SIGN_IN_REFUSED` audit row rolls back with the throw and a failed sign-in leaves
   no trace (`service/ClientAccountService.java:217`).
4. **`issueCredential` sends the mail *before* it writes the token row.** Save-then-send leaves
   an unspent token that the cooldown then reads as "a link is on its way", suppressing the
   retry for a full TTL (`service/ClientAccountService.java:175-194`).
5. **`identify` and `forgotPassword` are deliberately NOT `@Transactional`.** A transaction
   there holds a Hikari connection across a 15s SMTP conversation on an unauthenticated route
   at 60 req/min/IP (`service/ClientAccountService.java:127-135`).
6. **`forgot-password` answers 204 for known and unknown addresses alike, including on a mail
   outage.** `ClientMailer` returns `false` rather than throwing precisely so a `MailException`
   cannot make a known address answer 500 while an unknown one answers 204
   (`service/ClientMailer.java:106-113`).
7. **One GHL pacer, shared.** `GhlHttp` exists because the 100-req/10s limit is per *location*
   and a limiter is per *object*; two clients with their own pacers are each legal and together
   over. `GhlHttpTest` pins the shared-pacer property across two instances
   (`integration/GhlHttp.java:23-35`).
8. **GHL writes do not retry.** No idempotency key exists, so a blind retry turns one
   opportunity into two (`integration/GhlHttp.java:58-59`).
9. **`CaseRepository.findScoped`'s `dueBefore` filter admits undated cases.**
   `deadline <= :dueBefore` alone drops every null-deadline case from every window, and intake
   leaves it null whenever GHL sends no date (`repository/CaseRepository.java:147-158`).
10. **The SLA status is recomputed on read, not trusted from the column.** A case goes overdue
    with nothing writing to it, so a SQL filter on `sla_status` would miss exactly the rows a
    board asks for (`service/CaseLifecycleService.java:174-187`).
11. **`attachToPayment` puts its precondition in the `WHERE` and the caller asserts the row
    count.** A read-then-save is a check-then-act two settlements can both win
    (`repository/PayoutLedgerRepository.java:87-98`, asserted at `service/PayoutService.java:229-236`).
12. **`weekStart` is the single window function for payouts.** A second, parallel window
    computation with an inclusive bound would put a boundary draft in two weeks — i.e. payable
    twice (`service/PayoutService.java:291-294`).
13. **Presigned URLs are `Content-Disposition: attachment`, always, and never stored.** That is
    the half of gap G14 that closes the path rather than the file: a malicious HTML/SVG that got
    past the sniffer has no origin to run in (`integration/DocumentStore.java:152-172`).
14. **S3 keys are `brand/client/<ghlContactId>/<documentId>` — brand-first, no email, no
    filename.** Adding the brand prefix later is an object migration, not a code change
    (`integration/DocumentStore.java:183-212`).
15. **The expert attestation sentence is composed on the server from `expert.full_name`.**
    Taking the name from the request proved only that the caller could spell their own claim
    twice (`service/ExpertPortalService.java:393-425`).
16. **`revokeExpertLink` fires at all four ends of an expert's involvement.** Without it a
    rematch leaves the outgoing expert holding a live 30-day credential that can upload the
    deliverable under their name (`service/CaseLifecycleService.java:1232-1243`).
17. **`ScopePredicate`'s `PIPELINE` arm `return`s `cb.disjunction()` rather than skipping.** A
    pipeline-scoped principal carrying no pipeline must match nothing, not their whole brand
    (`service/ScopePredicate.java:119-130`).
18. **`ScopedEntity.@PrePersist` refuses a null `brand_id`.** It is the last line of defence:
    a row that reaches the database unscoped can never be scoped afterwards
    (`domain/ScopedEntity.java:48-53`).
19. **`AuditEventRepository` extends the bare `Repository` marker, not `JpaRepository`.** It is
    what makes the trail append-only by construction (`repository/AuditEventRepository.java:24`).
20. **`server.forward-headers-strategy` defaults to `none`.** With nothing in front of the app,
    honouring `X-Forwarded-For` lets any caller present a fresh address per request and walk
    past the portal rate limit (`application.yml:276-285`).
21. **`evalos.portal.allowed-origins` is empty by default and never `*`.** The portal chain is
    credentialed; an environment that forgets it gets a loud failing preflight rather than a
    silent open one (`security/PortalSecurityConfig.java:36-52`).
22. **`spring.servlet.multipart.file-size-threshold == max-file-size`.** Keeping them equal is
    what buys "never spools to disk" (invariant 14); `max-request-size` must stay strictly
    larger or a file at exactly the cap is rejected for one form field
    (`application.yml:38-60`).
23. **`ClientAccountService.setPassword` re-checks `brandId` against the account.** It is the
    one method that reaches an account through the token's unscoped foreign key
    (`service/ClientAccountService.java:276-284`).
