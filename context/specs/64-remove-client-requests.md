# Unit 64 — The client no longer requests a service from the portal

**Decided 2026-09-29 by the business.** **Status: SPECCED 2026-09-29, not built.** The docs run
ahead of the code on purpose until this unit lands (see `implementation-status.md` → Next up).

Supersedes **`43-client-intake-funnel.md`** (the whole funnel), **`53-request-documents.md`** (the
whole unit), **`55-remove-questionnaire.md`** (its pending `answers` drop becomes this unit's table
drop), and the sign-up parts of **`52-client-portal-ghl-integration.md`** (§4, §8–§11). Edits
decisions D2, D3a, D3c, D3d, D4, D6, D8, D10, D10a, D10b, D10c, D11, D12, D13, D33, D34, D35, D41,
D54, and closes carried-forward item i2.

## 0. Today, before this unit

- A client signs up at `/signup`, sets a password, and **requests a service** in the portal: pick a
  service (`/requests/new`), attach documents, send. Send opens a GHL opportunity on the `INTAKE`
  pipeline with the requested-service and submitted custom fields (D10, D10a, D10b, D12) and mails a
  confirmation.
- Sales reads that request on the deal page: an **Application** tab and a **Request documents**
  tab (D34). A GM / Sales screen lists **unfinished requests** (D54).
- When the deal is won, Handoff A creates the case (D9) and `RequestDocumentCarryForward` copies the
  request documents onto it as `case_document` rows over the same S3 objects (D33).
- **Handoff A never reads the request otherwise**: the service type comes from the webhook's
  `customData.serviceType` (`GhlOpportunityHandler`), not from the application.
- Public sign-up is the **only** runtime path that creates a `client_account`. Handoff A creates a
  case and a `contact_snapshot` and no account.

## 1. The decision

1. **The client does not request a service in the portal.** Every deal starts in GHL — a form, a
   call, Sales or Marketing — and becomes a case only when it is won (D9 and invariant 8 unchanged).
2. **The client's documents are uploaded only on a case**, against the checklist the PC or the CM
   sends (D60, Unit 61, unchanged). There is no pre-case upload.
3. **EvalOS creates the client's portal account when their case is created**, and emails the
   set-password link. **Public sign-up is removed.** Sign-in, set-password and reset stay.
4. **The `INTAKE` pipeline purpose is retired.** Nothing reads it once submit is gone.
5. **"My requests" becomes "My cases"**: a list of the client's cases, nothing else.

## 2. What is added: a portal account at case creation

`CasePortalAccountListener` (new, `service/`), on `CASE_CREATED`, **after commit**
(`@TransactionalEventListener(phase = AFTER_COMMIT)`: the case must exist, and mail must never go
out for a case that rolled back).

| # | Situation | What happens |
|---|---|---|
| 1 | The case's brand is not `evalos.portal.client-brand` | Nothing. The portal is single-brand (D7). |
| 2 | The case's contact has no email | No account. Audit `FLAGGED` on the case: "no portal account — the contact has no email". |
| 3 | The case's contact has no `ghl_contact_id` | No account, same flag. The portal finds cases through that id (`PortalCaseService.partyCases`), so an account without it would show nothing. |
| 4 | No account for (brand, email) | Create one: `created_via = 'CASE'`, names from the contact, `linkContact(contact.id)`, `linkGhlContact(contact.ghlContactId)`. Then issue a **SET** credential and send the existing set-password mail. |
| 5 | An account exists, **not linked** to a contact | Link it to this contact, then as row 6 or 7. |
| 6 | An account exists, linked to **this** contact, no password | Issue a fresh SET link (a new case is a reason to remind). |
| 7 | An account exists, linked to this contact, password set | Nothing. The new case is on their Home at the next sign-in; D58's emails tell them about it. |
| 8 | An account exists, linked to a **different** contact | **Do not relink.** Audit `FLAGGED`: "portal account for this email belongs to another contact". Relinking would show one person's cases to another. |
| 9 | Anything throws (mail down, a race on the unique email) | Logged and audited `FLAGGED`, **never rethrown**. The case stands; the client can still recover through sign-in (§3). |

- **Mail**: the existing set-password message, unchanged. D29 still says exactly two
  authentication messages; this adds a trigger, not a message.
- **Idempotent**: a redelivered webhook finds the account (rows 6–7) and creates nothing.
- `client_account.created_via` gains `'CASE'` (`V78`). `PORTAL_CLEANUP` deletes only abandoned
  `SIGNUP` rows, so a `CASE` account is never swept.
- The portal-side "a staff-created account" value `'STAFF'` stays in the CHECK, still unused.

## 3. Sign-in without sign-up

- `/signup` (client portal) and `POST /api/portal/auth/sign-up` are **removed**, with
  `ClientAccountService.signUp`, its per-IP gate and its proof-of-human check.
- `identify` is unchanged, so it is the recovery path: an email with an account and no password
  gets a fresh SET link; an unknown email is told **"We couldn't find that email. Your portal account
  opens when your first case starts — check the email we sent you, or contact us."**
- D3a / D3d's hazard (a `permitAll` route that writes accounts and CRM rows) **leaves with the
  route**: no unauthenticated path creates an account any more. `ensureCrmIdentity` stays (it is
  still called at set-password and sign-in) but its "first request" backfill wording (D3c) goes.
- Old `SIGNUP` accounts keep working. `PORTAL_CLEANUP` still removes abandoned ones, and loses its
  `NOT EXISTS (client_application …)` clause because the table is gone.

## 4. What is removed

**Backend**
- `web/ClientApplicationController` (`/api/portal/applications/**`), `web/ApplicationReviewController`
  (`GET /api/opportunities/{id}/application`), `web/ApplicationDocumentController`
  (`/api/opportunities/{id}/documents/**`), `web/AbandonedRequestController` (`/api/requests/abandoned`).
- `service/ClientApplicationService`, `ApplicationDocumentService`, `AbandonedRequestService`,
  `RequestDocumentCarryForward`; `ClientMailer.sendRequestSubmitted`, `MailTemplates.requestSubmitted`,
  `resources/mail/request-submitted.html`.
- `domain/ClientApplication`, `domain/ApplicationDocument`, their repositories;
  `PipelinePurpose.INTAKE`.
- `OpportunityBoardService`: the card's service fallback from `application.serviceName` and the
  `ClientApplicationRepository` it injected. The card shows GHL's service field or nothing.
- `PortalSecurityConfig`: DELETE leaves the portal CORS methods (its only user was the
  request-document delete).
- Config: `evalos.ghl.opportunity-service-field`, `opportunity-submitted-field`, the stale "Unit 43
  intake funnel" comment, and the sign-up gate's settings, in `application.yml`, `-local`, `-prod`.
  **`opportunity-correlation-field` stays** (the sync outbox uses it).

**Database — `V78__remove_client_requests.sql`**
- `DROP TABLE application_document; DROP TABLE client_application;` (in that order; nothing else
  references them). The unmapped `answers` column (Unit 55's pending drop) goes with the table.
- `UPDATE pipeline SET purpose = 'UNASSIGNED' WHERE purpose = 'INTAKE';` then recreate
  `pipeline_purpose_check` without `'INTAKE'`.
- Recreate the `client_account.created_via` CHECK with `'CASE'`.
- No seed tree has rows in either table, so no seed change is needed.

**Client portal (`client-expert/client`)**
- Routes `/requests/new` and `/signup` (both redirect to `/`); `pages/requests/NewRequest.tsx`;
  `components/intake/*`; `constants/serviceCatalog.ts`; `types/intake.ts`;
  `services/applicationService.ts`; `pages/auth/SignUp.tsx`.
- `pages/requests/Requests.tsx` → **`pages/cases/MyCases.tsx`** at `/cases` (cases only; `/requests`
  redirects), nav "My requests" → **"My cases"**.
- Dashboard: the "Continue your request" card and the "Request a service" empty state go. The empty
  state reads **"Your cases appear here once our team starts one."**
- Welcome and sign-in copy stop offering sign-up.

**Staff app (`frontend`)**
- `features/requests/*` (Unfinished requests page, `abandoned.ts` + its test), its route and its
  nav entry.
- `DealApplication.tsx`, `DealDocuments.tsx`, their tabs on `DealPage`, and `fetchApplication`,
  `fetchRequestDocuments`, `requestDocumentUrl` and their types in `opportunityApi.ts`.

## 5. What stays exactly as it is

- Handoff A and `CaseIntakeService` (D9, invariant 8). The service type still comes from the
  webhook's `customData.serviceType`.
- Case documents: the checklist, Send (D60), the portal upload against a sent item, drafts (D51).
- Documents already carried from a request onto a case: they are ordinary `case_document` rows over
  their S3 objects and are untouched.
- Sign-in, set-password, reset, `ensureCrmIdentity`, the correlation field, the sync outbox.

## 6. What is genuinely lost

- **A client can no longer start a deal themselves.** Every lead reaches EvalOS through GHL. A
  prospect who finds the portal first has nothing to do there until Sales wins a deal with them.
- **Deals opened by the portal before this unit, not yet won**, keep their GHL opportunity and still
  become cases when won. **Their request documents do not carry forward.** The files stay in S3 under
  the contact's prefix, but no row points at them. The client uploads them again against the case
  checklist.
- **A contact with no email in GHL gets no portal account** (§2 row 2). The flag on the case is how
  staff learn to fix the contact in GHL. A redelivered or later case then creates the account.
- Sales loses the Application and Request documents tabs, and the GM loses the Unfinished requests
  screen. Both only ever showed portal-born deals.

## 7. Tests

- **New** `CasePortalAccountListenerTest`: one test per row of §2's table, plus that a failure never
  propagates (row 9) and that nothing is sent before commit.
- **New** `MigrationTreeTest`-style check that `V78` applies on the local Postgres
  (`LocalPostgresIntegrationTest`): the tables are gone, `INTAKE` is refused, `'CASE'` is accepted.
- **Deleted** `ClientApplicationServiceTest`, `ClientApplicationRoutesTest`, `AbandonedRequestServiceTest`,
  `AbandonedRequestControllerTest`, `RequestDocumentCarryForwardTest`, the sign-up tests in
  `ClientAccountServiceTest` / the auth route tests, `abandoned.test.ts`.
- **Edited** `DomainInvariantsTest` (the two repositories leave SCOPE), `OpportunityBoardServiceTest`,
  `MailTemplatesTest`, `PortalCleanupSweepTest`, `PipelineMirrorServiceTest` (`INTAKE`).

## 8. Build order

1. `V78` and the backend removals, with `CasePortalAccountListener`; backend suite green.
2. Staff app removals; suite green.
3. Client portal: routes, "My cases", dashboard and auth copy; suite and `tsc -b` green.
4. Status table, memories, and the banners on specs 43, 52, 53, 55.
