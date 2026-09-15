# EvalOS — Workflows

**CURRENT IMPLEMENTATION** is what the code does today, with the file that does it.
**TARGET WORKFLOW** is where it is going. They are never mixed.

---

## 1. Client identity

### CURRENT IMPLEMENTATION

```
POST /api/portal/auth/identify   { email }
  → account found?  no  → UNKNOWN            (nothing is sent, nothing is created)
                    yes + password  → PASSWORD_SET
                    yes + no password → mail a SET link → NO_PASSWORD
                                      → mail unconfigured → MAIL_UNAVAILABLE

POST /api/portal/auth/sign-up    { email, firstName?, lastName?, phone? }
  → account already exists?  yes → fall through to identify()   (creates nothing)
                             no  → GHL POST /contacts/upsert    (match email, then phone)
                                 → INSERT client_account (ghl_contact_id linked)
                                 → audit CREATED
                                 → fall through to identify()   → returns a state, NEVER a token

POST /api/portal/auth/sign-in    { email, password }
  → verify hash → audit CLIENT_SIGNED_IN | CLIENT_SIGN_IN_REFUSED
  → mint a party-scoped portal_access token  (no contact, no account created)

POST /api/portal/auth/forgot-password  → 204 always
POST /api/portal/auth/set-password     → spend token, set hash, sign in
```

`ClientAccountService`, `ClientAuthController`. GHL unreachable refuses the signup with 502
rather than creating a local-only account.

**This already matches the target identity model.** All three states are supported; signup
reuses an existing GHL contact and never duplicates it; sign-in creates nothing.

### TARGET WORKFLOW

Two schema-level gaps remain (see `data-model.md` → REQUIRED FUTURE MODEL):
`client_account.ghl_contact_id` should be unique per brand, and `client_account` should be joined
to or merged with `contact_snapshot` so one person is one row.

---

## 2. Request to case

### CURRENT IMPLEMENTATION

```
Client Portal /requests/new
  step 1  pick a service          → POST /api/portal/applications
                                       INSERT client_application (status DRAFT)
                                       GHL POST /opportunities/  ← createOpportunity, NOT upsert
                                         on the pipeline named by evalos.ghl.intake-pipeline-name
                                         NO stage, NO assignee — GHL automation places it
                                       store ghl_opportunity_id
  step 2+ questionnaire           → PUT  /api/portal/applications/{id}   (autosave, jsonb)
  submit                          → POST /api/portal/applications/{id}/submit
                                       status → SUBMITTED, submitted_at set
                                       refuses 502 if there is still no opportunity

Sales                             → GET /api/opportunities/{id}/application
                                       200 + null when the deal did not come from the portal

GHL marks the opportunity won     → POST /api/webhooks/ghl/{endpointToken}
                                       WebhookGateway → WebhookRouter → GhlOpportunityHandler
                                       → CaseIntakeService → INSERT evalos_case (paid = true)
```

`ClientApplicationService`, `ClientApplicationController`, `ApplicationReviewController`,
`GhlOpportunityHandler`.

**Documents are not part of this flow.** `NewRequest.tsx` says so in the UI: *"you can send us
your documents once your case is open."* Every upload route takes a checklist item on a case.

**A GHL outage is swallowed on start and save and refused on submit** — the client keeps a
working draft rather than being told something untrue.

### TARGET WORKFLOW

```
CLIENT → PORTAL → REQUEST SERVICE → SERVICE DETAILS → DOCUMENT SUBMISSION → REQUEST CREATED
       → SALES REVIEW → OPPORTUNITY PROCESS → PAYMENT / WON → CASE → PRODUCTION → DELIVERY
```

Two steps of this are missing today:

- **DOCUMENT SUBMISSION** — needs a request-scoped document table, routes and S3 prefix.
- **SALES REVIEW as a state** — Sales can *read* the application but cannot approve, reject or
  return it; `client_application.status` has only DRAFT and SUBMITTED.

Everything else in the chain exists.

---

## 3. Contact and opportunity write semantics

### CURRENT IMPLEMENTATION — four paths, three different verbs

| Path | Contact | Opportunity | Effect on a repeat client |
|---|---|---|---|
| `ClientAccountService.signUp` | `upsertContact` | none | reuses the contact |
| `ClientApplicationService.start` | — (uses the account's) | **`createOpportunity`** | **new opportunity, same contact** |
| `SalesDeskService.newDeal` | `upsertContact` | **`createOpportunity`** | new opportunity; refuses a second open deal unless confirmed |
| `MarketingLeadService.capture` | `upsertContact` | **`upsertOpportunity`** | **reuses the open opportunity on that pipeline** |

`upsertOpportunity` means one open opportunity per contact per pipeline. It is correct for a
marketing lead and would be wrong for a second sale.

### TARGET WORKFLOW

One contact, many opportunities. **Three of the four paths already do this.**
`MarketingLeadService` is the divergence — deliberate and documented, but it is the one place a
second enquiry from the same person overwrites the first lead's name and value instead of opening
a new one. Whether that stays is `open-decisions.md` → Q1.

---

## 4. Production lifecycle

### CURRENT IMPLEMENTATION

```
DOC_COLLECTION ─ coordinator chases the checklist; client uploads to S3
 → PM_REVIEW ─ PM writes strategy notes, assigns an expert
 → DRAFT_IN_PROGRESS → DRAFT_REVIEW ─ PM approves or returns
 → READY_TO_SEND → CLIENT_REVIEW ─ client approves or requests revisions in the portal
 → CLIENT_APPROVAL → EXPERT_SIGNING ─ expert accepts / declines / signs via a staff-minted link
 → FINAL_QC ─ PM passes or fails
 → READY_TO_DELIVER → DELIVERED → CLOSED
```

Orthogonal `exception_state` for holds and refunds. Every transition is a `POST /api/cases/{id}/…`
route, goes through `CaseLifecycleService`, and writes an audit row.

Four sweeps run over this: `DOC_CHASE`, `DOC_ESCALATION`, `EXPERT_SIGN`, `STAGE_SLA`.

### TARGET WORKFLOW

Unchanged. The open work is visibility, not lifecycle: Sales and the expert cannot see case
status, and there is no unified timeline across the request, the opportunity and the case.

---

## 5. Documents

### CURRENT IMPLEMENTATION

```
Client Portal /documents
  GET  /api/portal/client/documents            checklist + this client's uploads
  POST /api/portal/client/documents?checklistItemId=…
        authorize the case → verify the item is on it
        → S3 key built from brand + contact_snapshot.id + a fresh document uuid
        → PUT to S3 → INSERT case_document (CLIENT_UPLOAD, versioned)
        → checklist item → UPLOADED → audit
  GET  /api/portal/client/documents/{id}/url   5-minute presigned read, never stored, audited

Staff     GET /api/cases/{id}/documents, …/{documentId}/url
Expert    GET /api/portal/expert/letter, POST /api/portal/expert/signed-letter
```

`PortalCaseService`, `DocumentStore`. A client with no case sees an empty screen, not a refusal.
A client with two or more cases is **refused** — the per-case routes and picker do not exist.

### TARGET WORKFLOW

`Client Portal → S3 → Request → Sales → Case → Production → Expert`. The first hop into a
**Request** does not exist; documents enter at the Case. Add the per-case picker and the
request-scoped store together.

---

## 6. Calendar and appointments

### CURRENT IMPLEMENTATION

```
GET  /api/sales/calendars                        list GHL calendars
GET  /api/sales/calendars/{id}/slots             free slots for a date range and timezone
POST /api/sales/opportunities/{id}/meetings      book
PUT  /api/sales/opportunities/{id}/meetings/{a}  reschedule
GET  /api/sales/meetings                         the salesperson's diary
GET  /api/portal/client/meetings                 the client's own appointments
```

Booking sends: calendar, contact, start, end, title, description, `assignedUserId`,
`meetingLocationType`, `address`, `appointmentStatus=confirmed`, optional
`ignoreFreeSlotValidation`. Mirrored into `meeting` (`GhlCalendarClient`, `SalesMeetingService`).

### Against the target booking experience

| Capability | State |
|---|---|
| Calendar, title, description, date, available slots, timezone | **IMPLEMENTED** |
| Contact, meeting location, create, view, reschedule | **IMPLEMENTED** |
| Team member on the appointment | **PARTIAL** — `assignedUserId` is a GHL user id; no column joins a GHL user to a `team_member` |
| Employee-wise availability | **PARTIAL** — availability is per *calendar*, not per employee |
| Account vs calendar timezone | **PARTIAL** — one timezone is passed to free-slots; no account default is stored |
| Guests | **MISSING** |
| Internal notes | **PARTIAL** — `GhlCalendarClient.addNote` exists; no route or screen calls it |
| Cancel | **MISSING** |
| Blocked-off time | **MISSING** |

---

## 7. Conversations

### CURRENT IMPLEMENTATION

**Nothing.** No table, no column, no endpoint, no component. The only message-like feature is
`opportunity_note` — append-only staff prose against a GHL opportunity, rendered by `DealNotes.tsx`.

### TARGET WORKFLOW

A custom EvalOS conversation sidebar backed by GHL: list, search, unread, assignment, history,
SMS / email / WhatsApp / social, attachments, internal comments, calls, and contact / opportunity /
request / appointment context. This is tier 3 of the mirror (Unit 47) and has no spec of its own.

---

## 8. Handoffs

| | Direction | Trigger | State |
|---|---|---|---|
| **A** | GHL → EvalOS | `opportunity.won` webhook creates the case | code complete |
| **B** | EvalOS → Expert | staff mints a portal link; expert signs | code complete |
| **C** | EvalOS → GHL / client | outbound dispatcher | **not implemented** |
