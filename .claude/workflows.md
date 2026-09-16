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
                                      → transport cannot reach them → MAIL_UNAVAILABLE
                                        (no transport configured — an address is all Brevo needs)

POST /api/portal/auth/sign-up    { email, firstName?, lastName?, phone? }
  → account already exists?  yes → fall through to identify()   (creates nothing, no GHL call)
                             no  → INSERT client_account (created_via SIGNUP, NO contact — D3d)
                                     NOTHING LEAVES THE JVM on this route
                                 → audit CREATED
                                 → fall through to identify()   → returns a state, NEVER a token
                             The contact is created at set-password, the next sign-in, or the
                             first request that needs one (D3c). Until then it is an EvalOS row
                             PORTAL_CLEANUP can sweep.

POST /api/portal/auth/sign-in    { email, password }
  → verify hash → audit CLIENT_SIGNED_IN | CLIENT_SIGN_IN_REFUSED
  → mint a party-scoped portal_access token  (no contact, no account created)

POST /api/portal/auth/forgot-password  → 204 always
The SET/RESET link is carried by `evalos.mail.transport` (D3e): `smtp` (Spring Mail) or `brevo`
(POST /v3/smtp/email). The `ghl` transport is deleted — it addressed a `contactId` rather than an
address, which is the only thing that ever forced the GHL contact to be created at sign-up.

POST /api/portal/auth/set-password     → spend token, set hash
                                      → ensureCrmIdentity (idempotent; repairs an outage)
                                      → mint token, sign in

POST /api/portal/auth/sign-in         → verify, then ensureCrmIdentity — "login verify and update
                                        if something missing". Idempotent and silent.
```

`ClientAccountService`, `ClientAuthController`.

**The CRM write is off sign-up again (D3d, spec `52` §11).** It was there only while GHL carried
the mail and demanded a `contactId`; Brevo needs an address, so the ordering that holds D3a's
property costs nothing and is back. Two call sites had to go — `signUp` and `issueCredential` —
because sign-up falls through to `identify`, so removing one would have looked fixed and changed
nothing.

**A GHL outage never refuses anything here.** Sign-up stands with no contact, `identify` answers
`MAIL_UNAVAILABLE` (the transport genuinely cannot reach them), and `ensureCrmIdentity` repairs it
at the next sign-in — or at the first request, via `ClientApplicationService` (D3c). Same repair
covers `V45` accounts seeded without an id.

**This already matches the target identity model.** All three states are supported; the upsert
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
                                       NOTHING LEAVES THE JVM — D10
  step 2+ questionnaire           → PUT  /api/portal/applications/{id}   (autosave, jsonb)
                                       still nothing; the whole funnel is EvalOS's own rows
  submit                          → POST /api/portal/applications/{id}/submit
                                       ensure ghl_contact_id (D3c backfill, if it is missing)
                                       open the local opportunity row (correlation key first)
                                       GHL POST /opportunities/  ← createOpportunity, NOT upsert
                                         on the pipeline a GM marked INTAKE
                                         NO stage, NO assignee — GHL automation places it
                                         custom fields: service id, correlation key, SUBMITTED
                                       store ghl_opportunity_id
                                       status → SUBMITTED, submitted_at set
                                       refuses 502 if GHL would not open the deal — the draft
                                         survives and the next attempt retries

Sales reviews, then wins          → GET /api/opportunities/{id}/application
                                       200 + null when the deal did not come from the portal
                                       review → won → payment are GHL's and Sales' (D10c)

GHL marks the opportunity won     → POST /api/webhooks/ghl/{endpointToken}
                                       WebhookGateway → WebhookRouter → GhlOpportunityHandler
                                       → CaseIntakeService → INSERT evalos_case (paid = true)
```

`ClientApplicationService`, `ClientApplicationController`, `ApplicationReviewController`,
`GhlOpportunityHandler`.

**The deal opens at SUBMIT, not at service-pick (D10, changed 2026-09-16, third time of asking).**
It opened at the first screen until then, so that a client who abandoned the questionnaire still
reached a salesperson. A deal on the board is now a finished request and nothing else, which is
what gives Sales' review step something to review. **The cost is stated rather than hidden: an
abandoned questionnaire now reaches nobody** — the `DRAFT` rows are still there and nothing sweeps
them or tells anyone, which is an open decision, not a silent gap.

**Documents are not part of this flow.** `NewRequest.tsx` says so in the UI: *"you can send us
your documents once your case is open."* Every upload route takes a checklist item on a case.

**A GHL outage is swallowed on start and save and refused on submit** — the client keeps a
working draft rather than being told something untrue.

### TARGET WORKFLOW

```
CLIENT → PORTAL → REQUEST SERVICE → SERVICE DETAILS → DOCUMENT SUBMISSION → REQUEST CREATED
       → SALES REVIEW → OPPORTUNITY PROCESS → PAYMENT / WON → CASE → PRODUCTION → DELIVERY
```

**One step of this is missing, and it is one step rather than two as of 2026-09-17.**

- **DOCUMENT SUBMISSION** — needs a request-scoped document table, routes and an S3 prefix keyed by
  the **GHL contact id** (D41), and a carry-forward into `case_document` at Handoff A (D33,
  spec `53`).
  The client uploads with the questionnaire; the documents are the client's, held against the
  person, before any case exists to hold them.
- ~~**SALES REVIEW as a state**~~ — **not owed.** D35: review is a GHL pipeline stage, not an
  EvalOS column. `client_application.status` stays `DRAFT` / `SUBMITTED`. What Sales *is* owed is
  the documents beside the answers on the one opportunity screen (D34).

Everything else in the chain exists.

---

## 3. Contact and opportunity write semantics

### CURRENT IMPLEMENTATION — four create paths, three verbs; every *edit* is queued

**Creates still call GHL inline** (D46):

| Path | Contact | Opportunity | Effect on a repeat client |
|---|---|---|---|
| `ClientAccountService.signUp` | none (D3d) | none | — |
| `ClientApplicationService.submit` | `upsertContact` if missing (D3c) | **`createOpportunity`** | **new opportunity, same contact** |
| `SalesDeskService.createDeal` | `upsertContact` | **`createOpportunity`** | new opportunity; refuses a second open deal unless confirmed |
| `MarketingLeadService.openLead` | `upsertContact` | **`upsertOpportunity`** | **reuses the open opportunity on that pipeline** |

**Edits do not call GHL at all** (D44, Unit 46). `SalesDeskService.update` / `moveToStage` /
`close` and `MarketingLeadService.value` each edit the mirror row, stamp `local_updated_at`, queue
`UPSERT` or `CLOSE`, and answer from the row. `SYNC_OUTBOX` (2m) sends it; a successful push calls
`pushedToGhl()`, which clears the stamp so 45e stops defending an edit GHL now has.

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

**Unchanged — and as of 2026-09-17 this is the stated business lifecycle, not just what the code
happens to do** (D36). Read as staffing: the case is born at Handoff A, a **PM** takes it, and the
PM assigns the **Project Coordinator**, the **Case Manager** and the **Expert**
(`POST /api/cases/{id}/assign-coordinator`, `…/assign-cm` — which names the CM and the expert in
one transaction and writes the expert offer). The **CM drafts and uploads**; the **client sees and
approves** it in the portal (`CLIENT_REVIEW` → `CLIENT_APPROVAL`); **only then** does it reach the
**expert**, who downloads, signs and uploads it back (`EXPERT_SIGNING`, Handoff B).

The open work is visibility, not lifecycle: the expert cannot open evidence documents, and there is
no unified timeline across the request, the opportunity and the case. **Sales is not on that list**
— they read no case by design (D19c).

---

## 5. Documents

### CURRENT IMPLEMENTATION

```
Client Portal /documents
  GET  /api/portal/client/documents            checklist + this client's uploads
  POST /api/portal/client/documents?checklistItemId=…
        authorize the case → verify the item is on it
        → S3 key built from brand + the GHL contact id + a fresh document uuid (D41)
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
**Request** does not exist; documents enter at the Case today.

**Decided 2026-09-17 (D33, D41):** the client uploads **at questionnaire submit**, and the S3 key is
keyed by the **GHL contact id** — one id names a contact everywhere, and the documents belong to the
person rather than to a case that has not been won yet. Sales reads them on their own route and tab
on the same opportunity (D34); Handoff A carries them forward into `case_document` over the same S3
object, so Production starts holding what Sales already read. Spec `53`.
The per-case picker (Q8) is separate and still open.

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
| **mirror** | GHL → EvalOS | `contact.*` and `opportunity.*` webhooks update `contact_snapshot` and `opportunity`; `MIRROR_DELTA` (15m) is the floor under them | code complete (45d, 2026-09-17) |
| **B** | EvalOS → Expert | staff mints a portal link; expert signs | code complete |
| **C** | EvalOS → GHL / client | outbound dispatcher | **not implemented** |
