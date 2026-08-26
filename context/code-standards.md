# EvalOS — Code Standards

## General

- Keep classes small and single-purpose. One service owns one domain concern.
- Fix root causes; do not layer workarounds on top of a broken abstraction.
- Do not mix unrelated concerns in one controller, service, or component.
- Business rules live in `service`, never in controllers or JPA entities.

## Multi-Tenancy (non-negotiable)

- Every scoped read filters by `brand_id`. A finder/query that can return rows
  without a brand predicate is a defect — scope it at the repository/service
  layer (Specifications / Hibernate filters), not in the UI.
- Layer the finer scopes on top of brand: **team** (PM) and **assignee** (Case
  Manager / expert / client). The GM is the only role allowed cross-brand reads.
- Brand is resolved from the authenticated principal for staff, from the
  per-brand endpoint token for inbound webhooks, and from the **portal token's own
  row** for a client or expert on a link-based surface (Unit 14) — never from a
  client-supplied body field. Three sources, one rule: the most authoritative
  signal the surface has.
- **A link-based portal caller is not a staff caller with a narrower scope.** Their
  token names one case, so it *is* the scope: no `ScopePredicate`, no synthetic
  `TenantContext`, no predicate that could fail open. Do not manufacture a staff
  principal for a non-staff caller — a role tier widened later would silently widen
  what they can read. What they see is a **whitelist projection**, not a staff DTO
  with fields removed.
- **A Spring `Filter` belonging to one security chain must not be a `@Component` or
  a `@Bean`.** Spring Boot auto-registers `Filter` beans as global servlet filters,
  so an annotated portal filter would run on staff routes too. Construct it inside
  the chain that owns it (see `security/PortalSecurityConfig`).
- Never let one brand's identifiers leak into another brand's response, event,
  or notification.

## Java

- Java 21. Prefer immutability: `record` types for DTOs and value objects,
  `final` fields, constructor injection only — no field `@Autowired`.
- No raw nulls across boundaries. Return `Optional<T>` from repositories/finders
  and handle absence explicitly; never let a stray null become a 500.
- Model the domain with enums and types, not loose strings: `Stage`,
  `PayoutStatus`, `ServiceType`, `VisaCategory`, `Role`. No stringly-typed state.
- Keep entities and DTOs separate. Never accept or return a JPA entity directly
  from a controller — map to a DTO.

## Spring / Web

- Controllers are thin: `@Valid` the request DTO → authorize (role + brand +
  ownership) → call a service → return a DTO in the standard envelope. No logic
  in the controller.
- One responsibility per endpoint. Do not fold multiple state transitions into a
  single endpoint.
- Centralized error handling with `@RestControllerAdvice`. Services throw typed
  domain exceptions; the advice maps them to consistent HTTP responses.
- Guard endpoints with method security (`@PreAuthorize`) for roles, and verify
  brand + ownership in the service for row-level access.
- Webhook controllers verify the shared secret / signature before deserializing
  the body. An unverified payload is dropped, never processed.

## Webhooks

- **Inbound**: verify signature → resolve brand (per-brand endpoint) →
  deduplicate on source event / invoice id → archive raw payload → route to a
  service → fast ack. No business logic in the receiver; slow work goes to a
  `job`. A duplicate event must produce no second side effect.
- **Outbound**: publish a domain event from the service layer; the dispatcher
  delivers it. Sign every payload (HMAC over body + timestamp), retry with
  backoff, dead-letter after N attempts, and write a delivery-log row per attempt.
- Outbound payloads carry only brand/case/contact/attribution references — never
  the `payment_detail` field, never role-restricted internal notes. Add fields
  via the event catalog in `architecture.md`, not ad hoc.
- Do not call an external partner's API inline from a request handler for a
  lifecycle side effect; emit a domain event and let the dispatcher deliver it.
- Client-facing messages are delivered by GHL off domain events — see
  *Client- and expert-facing messages* below for the marker convention and the
  open channel decision.

## Persistence (JPA / PostgreSQL)

- Entities live in `domain`, repositories in `repository` (extend
  `JpaRepository`). Entities hold mapping + column constraints only.
- Transactions are declared at the `service` layer with `@Transactional`.
- Every scoped entity has a non-null `brand_id` column and foreign key to Brand.
- Add explicit compound indexes for scoped/queried columns:
  `(brand_id, team_id, assigned_to, stage)`, `(brand_id, deadline)`,
  `(brand_id, sla_status)`, `(brand_id, expert_id)`.
- Avoid N+1: use fetch joins or entity graphs for known read paths.
- Every schema change is a new Flyway migration under
  `backend/src/main/resources/db/migration`. Never edit an applied migration.
- The audit table is append-only: its repository exposes save/find only — no
  update or delete method anywhere. Enforce with a DB grant where possible. This
  applies to every object's audit entries, not just cases.
- Contact snapshots synced from GHL are read-only and brand-tagged. No code path
  updates a synced contact field.
- The expert `payment_detail` is persisted only through the field-level
  encryption `AttributeConverter` in `common`. It is excluded from every DTO,
  log line, and webhook payload. It is **currently** the only encrypted field
  (payouts are manual; no bank/card processing).
  - **SIGNED OFF 2026-08-26: a second encrypted column is permitted, and there is
    exactly one way to add it.** Extract the AES-GCM out of `PaymentDetailConverter`
    into a single `common/EncryptedStringConverter`, and leave `PaymentDetailConverter`
    as a thin subclass of it. This was option 1 of four in
    `context/specs/25-ghl-oauth-connection.md`; the others are rejected below.
  - **This grants a narrow, named exception to the protected-file rule** in
    `ai-workflow-rules.md` — *that one extraction only*, and only with the expert
    path's behaviour unchanged (same key, same AES-256-GCM, same fresh 12-byte IV per
    write, same authenticated failure on a tampered column, and `PaymentDetailConverter`
    keeps its type and its call sites). Anything else touching that file still needs its
    own sign-off.
  - **Why not the alternatives.** A second converter duplicating ~60 lines of AES-GCM
    (option 2) means two crypto implementations, and the second is the one nobody
    re-reads. A separate key for OAuth tokens (option 3) buys blast-radius isolation at
    the cost of one more secret every environment must not forget — more operational
    surface than the threat warrants for an internal tool. Not encrypting at all
    (option 4) is refused outright: a refresh token is a live credential to a
    third-party system holding customer data.
  - **Do the extraction when Unit 25 is built, not before.** Nothing needs a generic
    converter until there is a second column to put in it, and a shared abstraction
    with one implementation is the thing this codebase deletes. The decision is
    unblocked; the code is not owed yet.
  - The rule that does **not** move: a credential that never has to be replayed is
    **hashed, not encrypted** (portal tokens). Encryption is only for what must be
    recovered — which is exactly why a refresh token cannot be hashed.

## Files & Storage

- EvalOS hosts no files. Persist a Google Drive **link or file id** on the case, never
  the document bytes — including the signed letter, which is filed into the case's own
  Drive folder by the expert's upload. The redacted CV is generated on demand, not
  stored in a database blob.
- There is no S3/object-storage dependency. Do not add one.
- **An upload streams; it never lands.** Where EvalOS accepts a file (Unit 21),
  pass the request's `InputStream` straight to the Drive client via
  `InputStreamContent` — no byte array, no temp file, no upload directory. Buffering
  the whole file both breaks "hosts no files" and puts an attacker-sized allocation
  on the heap.
- **Validate an accepted file by content, not by claim.** Sniff the leading bytes
  against an **allowlist** (never a denylist of extensions); enforce the size cap
  before streaming; reject empty files; generate the stored filename yourself and
  treat the client's as untrusted data — no separators, no traversal, never echoed
  into HTML. Rate-limit **per portal token** — note the existing `PortalTokenFilter`
  limiter keys on the client address, not the token, so this is a second key rather
  than something already handled.
- **A portal credential travels in the `X-Portal-Token` header only** — never a path
  segment or query parameter, both of which land in access logs, `Referer` headers and
  browser history, and would break the reason CSRF is disabled on that chain.

## Background jobs

- Sweeps live in `job`, are `@Scheduled`, and take a **Postgres advisory lock on
  their job type** before doing anything. Two instances exist for a few seconds in
  every rolling deploy, and a double-fired sweep double-messages a client silently.
- **Idempotency comes from the data**, never from a "already ran" row: derive it from
  the audit trail or the notification rows the action itself writes. A sweep must be
  safe to run twice, because `POST /api/jobs/{type}/run` exists.
- **One transaction per item.** One poisoned case must not abort the sweep; record
  the run `FAILED` with the error and let the next tick retry.
- **A sweep prompts and publishes; it never transitions a case.** Every state change
  goes through the owning service's transition, fired by a person.
- Sweeps have no authenticated caller, so `ScopePredicate` does not apply: read
  brand-wide deliberately, and write through `AuditService.recordSystemEvent` with
  the brand taken from the row.
- Queue work is the `webhook_delivery` outbox claimed `FOR UPDATE SKIP LOCKED`.
  **Do not add a message broker** — the only cross-process work is retrying one
  webhook.

## Client- and expert-facing messages

- Client-facing messages are delivered by GHL off domain events; an expert is reached
  by a scoped portal link. **EvalOS sends no email** (invariant 14).
- That channel decision is **under review** for the touchpoints listed in
  `context/process-automation.md`. Wherever code will sit for one of them, leave a
  marker comment naming the touchpoint so the decision is greppable:

  ```java
  // email: T5 draft ready for client — channel undecided (GHL vs EvalOS mail).
  // See context/process-automation.md, outward touchpoints.
  ```

- Until it is decided, do not add a mail dependency.

## One home per fact

- Every fact has exactly one authority: SLA budgets in `SlaCalculator`, business
  hours in `BusinessCalendar`, legal transitions in `CaseTransitions`,
  trigger→recipient in `NotificationListeners.ROUTES`, scope in `ScopePredicate`,
  money visibility in `CaseController.SEES_DEAL_VALUE`, RAG tokens in
  `ui-context.md`.
- Docs and comments **cite** those; they never restate a threshold as though it were
  the source. A second copy of a number is a second thing that can be wrong, and the
  copy is always the one that goes stale.
- Prefer deriving over storing. `expert.current_active_count`,
  `total_cases_completed` and `total_payments_pending` are columns nothing has ever
  written — the standing example of why a counter is a liability. `ExpertLoadService`
  is the pattern to copy.

## Validation

- Use Bean Validation (`@Valid` + constraints) on every inbound request DTO and
  webhook payload. Parse-then-trust: validate before any logic runs.

## Styling (frontend)

- Use the CSS custom-property tokens defined in `ui-context.md`. No hardcoded hex
  in components.
- The three status tokens (`--status-red/amber/green`) are for RAG status only.
  Use `--accent-primary` for decorative/brand color.
- Follow the border-radius scale in `ui-context.md`.
- Use tabular figures (`--font-num`) for all numeric, currency, date, and ID
  columns.

## Frontend HTTP

- **One shared axios instance** (`lib/api.ts`), imported everywhere. Never call
  `axios` directly and never create a per-feature instance.
- **One exception, and only for this reason:** a surface that must hold no staff
  session gets its own instance, because importing the shared one drags in the
  module that reads and writes the staff token. `features/client-portal/portalApi.ts`
  is that case (Unit 14) — one credential, one header, nothing persisted. A new
  instance needs a reason of that kind, not convenience.

## Response Shapes

- Every API response uses one consistent envelope for success and error.
- Never leak internal or sensitive fields (`payment_detail`, notes not meant for
  a role, another brand's data) into a response the requesting principal isn't
  authorized to see. Projection is enforced by the DTO + scoping, not the client.

## File Organization

Backend (`backend/src/main/java/com/ie/evalos/`):
- `web/` — REST controllers (thin) + DTOs.
- `service/` — domain logic (case lifecycle, matching, payouts, QC, scoping).
- `domain/` — JPA entities + enums.
- `repository/` — Spring Data JPA repositories + scoping filters.
- `integration/` — GHL and Google Drive clients.
- `webhook/` — inbound webhook controllers + secret verification + brand resolve.
- `event/` — domain events + outbound dispatcher.
- `job/` — `@Scheduled` / `@Async` workers (backed by `scheduled_job`).
- `notification/` — in-app staff notification center.
- `security/` — Spring Security config, JWT, RBAC/ABAC, ownership, portal chains.
- `common/` — encryption converter, error types, response envelope, helpers.
- `config/` — configuration/beans.

Frontend (`frontend/src/`):
- `components/ui/` — generated headless components (protected).
- `features/` — feature UIs (board, case detail, dashboards, client portal,
  expert portal).
- `lib/` — API client, hooks, shared utilities.
