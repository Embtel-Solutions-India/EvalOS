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
- Brand is resolved from the authenticated principal for staff, and from the
  per-brand endpoint token for inbound webhooks — never from a client-supplied
  body field.
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
- Client-facing messages are delivered by GHL off domain events. EvalOS sends no
  email itself.

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
  log line, and webhook payload. It is the only encrypted field (payouts are
  manual; no bank/card processing).

## Files & Storage

- EvalOS hosts no files. Persist a Google Drive **link** on the case, never the
  document bytes. Signed letters live in Dropbox Sign; reference them, don't copy
  them. The redacted CV is generated on demand, not stored in a database blob.
- There is no S3/object-storage dependency. Do not add one.

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
- `integration/` — GHL and Dropbox Sign clients.
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
