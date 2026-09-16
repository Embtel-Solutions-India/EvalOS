# Current decisions

**The authoritative file is `.claude/current-decisions.md` (31 numbered decisions). Read it.**

The ones most often violated from memory:

- A GHL Contact is not an EvalOS ClientAccount. A contact existing is **not** proof of portal
  registration. All three states (no contact and no account; contact only; both) are legal.
- One GHL Contact, many opportunities. A repeat request makes a new **opportunity**, never a
  second contact.
- **The GHL contact is created AT SIGN-UP** (D3d) — it briefly moved to set-password (D3a,
  2026-09-16 morning) and came back the same day because **GHL sends the mail and
  `POST /conversations/messages` requires a `contactId`**: there is no contact-less send. D3a's
  *property* still holds (a stranger must not drive unbounded CRM writes); what holds it is now the
  route's own gate + `PORTAL_CLEANUP`, not the ordering. **The gate is NOT built yet** — the route
  is still on the shared 60/min/IP counter.
- **`PORTAL_CLEANUP` only ever deletes `created_via = 'SIGNUP'` rows** (D3f, V59). The earlier
  predicate also matched every V45-seeded client and would have deleted the seeded backlog after
  30 days. Column defaults to `SEED` — a writer that forgets gets a row that survives.
- **`identify` repairs a missing `ghl_contact_id` before asking whether mail can reach them.**
  Without it, an account with no contact is unmailable, so it can never get a password, so it can
  never reach any other repair point. The repair is best-effort: a failed write is logged, never a
  500 on an unauthenticated route.
- **A GHL outage never refuses a sign-up, a set-password or a sign-in.** The account stands with no
  contact, `identify` answers `MAIL_UNAVAILABLE`, and `ensureCrmIdentity` repairs it at the next
  sign-in or the first request (D3c).
- **Mail is swappable** (D3e): `MailTransport` + `evalos.mail.transport` = `smtp` | `ghl`. Brevo is
  a new class and a changed variable. Ask `canReach`, not `isConfigured` — the GHL transport is
  configured yet cannot address a client with no linked contact.
- **A portal contact carries `source: "Client Portal"`** (D3b). GHL accepts no `utmSource` or
  `attributionSource` on a write — it fills those from its own form tracking, which an API caller
  never passes through — so the documented `source` string is the whole of what provenance can be.
  Every `upsertContact` caller names itself.
- **Request is not Case.** `client_application` is the request, `evalos_case` is production work,
  and the GHL opportunity is the commercial process between them.
- **The opportunity opens when the client SUBMITS** (D10) — changed 2026-09-16 on the second
  asking, having been reaffirmed against the first. It opened at service-pick so a half-finished
  request still reached Sales; it no longer does, and that cost is accepted rather than overlooked.
  The `SUBMITTED` custom field rides the create now, not a follow-up call. A failed create refuses
  the submit and keeps the draft. Then: Sales review → won → payment (D10c).
- **Abandoned portal rows are swept** (`PORTAL_CLEANUP`, daily). Expired `client_credential_token`
  rows go one TTL past expiry; `client_account` rows with no password, no GHL contact, no token, no
  application and no session go after `abandoned-sign-up-after` (30d). **Audit is never swept** —
  append-only by invariant, so a flood still grows `audit_event` and that is the one place growth
  is the feature.
- **GHL's pipelines and stages are MIRRORED rows now** (Unit 44a, `V50`), keyed on GHL's own ids.
  GHL owns every column except `pipeline.purpose`, which EvalOS owns and a sweep never writes. Rows
  are never deleted — `missing_since` instead. Nothing guesses a purpose from a pipeline's name.
- **A SALES/MARKETING member holds a SET of pipelines** (`team_member_pipeline`, Unit 44b), assigned
  by MIRROR id and never by a pasted GHL string. The one-owner rule is gone — Case Delivery is a
  pipeline nobody owns. `PipelineScope.mine()` returns a list; a CREATE uses `mineForWrite()`, which
  refuses rather than guessing when a desk holds several.
- **The client's request lands on the pipeline marked `INTAKE`**, not one matched by name.
  `evalos.ghl.intake-pipeline-name` is retired: a rename in GHL used to stop every request silently.
- **EvalOS sends GHL the requested SERVICE ID as an opportunity custom field and nothing else about
  placement** (D10a). A GHL workflow routes the deal to a pipeline from it. Mapping services onto
  pipelines is a business rule and lives in the workflow — the same ruling that deleted
  `hot-stage-name`. No stage, no assignee, no price: EvalOS holds no price list.
- **A case is created only by the `opportunity.won` webhook.** Build-enforced by
  `DomainInvariantsTest`. Never add a second creator.
- Invoicing is GHL's, full stop. EvalOS reads invoices and raises none.
- EvalOS sends **exactly two** emails: set-password and reset-password. Any other mail is a new
  decision.
- Brand-scoped by default. Append-only truth for `audit_event` and `opportunity_note`, enforced by
  database triggers.
- **The one scoping exception is the GHL location, and it costs screens rather than being
  widened.** `evalos.ghl.location-id` is one global sub-account belonging to no brand, so every
  screen over it is GM-only. On 2026-09-16 the two GHL funnel screens were **removed** for exactly
  that reason — their audience was Marketing, who could not open them — instead of admitting a
  brand-locked role to an unattributable figure. `/api/opportunities/board` is the one narrowed
  case: `evalos.ghl.sales-brand` names the owning brand and assignment refuses any other, so its
  brand *is* provable and SALES/MARKETING reach it.
- Experts have **no accounts** — a staff-minted link is the only way in.

Changed a decision? Edit `.claude/current-decisions.md`, then this memory. Never leave a
contradicting note beside the old one.

**D32 (2026-09-16).** `client_account` and `contact_snapshot` stay **two tables, JOINED** — not
merged. `V55` adds `client_account.contact_id`, a real FK, backfilled on `ghl_contact_id` within
the brand and set at sign-up; null stays legal. **D6 is now schema-enforced** by a partial unique
index, where `ghl_contact_id` had been nullable AND not unique, so nothing stopped two accounts
naming one contact. The `contact_snapshot` → `contact` **rename is deferred for a mechanical
reason**: two seeds write that table (`V905`, `V951`), both 900+, both running after every
`db/migration` script, and `MigrationTreeTest` forbids a migration in that range while editing an
applied seed is a checksum mismatch that refuses the boot. Do it when the seed tree is rebaselined.

