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
                                        (no relay or no sender configured — an address is all
                                         any transport needs to reach a person)

There is NO sign-up (Unit 64, 2026-09-29). The account is opened with the case:

CASE_CREATED (after commit) → CasePortalAccountListener  (portal brand only, new transaction, never throws)
  → ClientAccountService.openForCase(case contact)
      no email / no GHL id              → no account; case FLAGGED
      account for this GHL contact      → no password: mail a SET link (REMINDED) · password: nothing
      account for the email, unlinked   → link it to the contact (LINKED), then as above
      account for the email, other contact → never relinked; case FLAGGED (OTHER_CONTACT)
      none                              → INSERT client_account (created_via CASE, linked) → mail a SET link

POST /api/portal/auth/forgot-password  → 204 always
The SET/RESET link leaves over **SMTP, and the provider is configuration** (D3e, 2026-09-18):
`spring.mail.host/port/username/password` + `EVALOS_MAIL_FROM` reach Brevo, Resend, Mailgun,
Postmark or SES without a build. Both vendor-specific transports are deleted — `ghl` (it addressed
a `contactId` rather than an address, the only thing that ever forced the GHL contact to be created
at sign-up) and `brevo` (`POST /v3/smtp/email`).

POST /api/portal/auth/set-password     → spend token, set hash
                                      → ensureCrmIdentity (idempotent; repairs an outage)
                                      → mint token, sign in

POST /api/portal/auth/sign-in { email, password }
                                      → verify hash → audit CLIENT_SIGNED_IN | CLIENT_SIGN_IN_REFUSED
                                      → mint a party-scoped portal_access token (no account created)
                                      → an account with no contact link queues one
                                        (sync_outbox CLIENT_ACCOUNT UPSERT, enqueueIfAbsent, same tx)
                                        — no GHL call on sign-in since 2026-10-15
SYNC_OUTBOX drain                     → linkCrmIdentity: upsert contact, link; GHL failure retries/dead-letters
```

`ClientAccountService`, `ClientAuthController`, `CasePortalAccountListener`.

**No unauthenticated route creates an account or a CRM row (D3a).** `identify` answers `UNKNOWN`
with "your portal account opens when your first case starts". An account opened for a case is born
linked to its contact, so no GHL call is needed there.

**A GHL outage never refuses anything here.** `identify` answers `MAIL_UNAVAILABLE` when the
transport cannot reach someone, and a missing contact link is repaired from the next sign-in — queued, then
linked by the outbox drain (D3c). Same repair covers `V45` accounts seeded without an id.

**This already matches the target identity model.** All three states are supported; the upsert
reuses an existing GHL contact and never duplicates it; sign-in creates nothing.

**First sign-in (Unit 72, D71):** after any client or expert sign-in, `TermsGate` asks
`GET …/{client|expert}/terms`; while `required`, the portal shows only the acceptance screen (the
three policies, a checkbox, Accept) and `POST …/terms` records the version. Asked once per account
and policy version; a case-scoped link is never asked.

### TARGET WORKFLOW

**Built as CURRENT above (Unit 64, 2026-09-29): the account is born with the case.** No public sign-up.

```
opportunity.won → GHL confirms a won deal for the contact (D9) → Handoff A → CASE_CREATED (after commit)
  → case brand is the portal brand, contact has email + GHL id?
      no  → flag the case, no account
      yes → account for (brand, email)?
              none                       → create (created_via CASE, linked to the contact) → case-started mail (D65)
              linked to this contact     → no password: case-started mail · password set: new-case sign-in mail
              unlinked                   → link, then as above
              linked to another contact  → flag the case, never relink
sign-in, unknown email → "your account opens when your first case starts"
```

Two schema-level gaps remain (see `data-model.md` → REQUIRED FUTURE MODEL):
`client_account.ghl_contact_id` should be unique per brand, and `client_account` should be joined
to or merged with `contact_snapshot` so one person is one row. Spec `64` §2–§3.

---

## 2. Deal to case (no client request since Unit 64)

### CURRENT IMPLEMENTATION

```
A deal starts in GHL               form · call · Sales (`SalesDeskService.createDeal`) · Marketing
                                    (`MarketingLeadService.openLead`). The portal opens none.
Sales works it                     GHL pipeline stages; review → won → payment are GHL's (D11)
Won in EvalOS (D70)                Won button, or a move to the Won stage (board or picker):
                                   Sales writes a required note for production: a deal note
                                   flagged `handoff` (synced to GHL), shown on the case

"New case" (GM / Brand Manager / PM; a GHL opportunity or contact id, D9 2026-10-30)
                                  → POST /api/cases/from-ghl → GhlOpportunityHandler.openFromGhl
                                       (deal must be won in GHL; contact, amount, service read from GHL)
                                  → the same CaseIntakeService path as below
"Cancel case" (GM / Brand Manager / PM; reason required, never delivered)
                                  → POST /api/cases/{id}/cancel → CLOSED, exception CANCELLED,
                                       pending payouts voided, chat read-only, client reads "Closed"
GHL marks the opportunity won     → POST /api/webhooks/ghl/{endpointToken}
                                       WebhookGateway → WebhookRouter → GhlOpportunityHandler
                                       → CaseIntakeService → INSERT evalos_case (paid = true)
                                       service type from customData.serviceType, else the won
                                       deal's Service Requested (D77), else Credential evaluation
                                  → CASE_CREATED (after commit)
                                       → chat conversations (Unit 57)
                                       → the client's portal account + case-opened mail (§1, D65:
                                         set-password link valid 7 days, or sign-in if set)
                                       → CaseServiceTypeListener: the deal's Service Requested (below)
PC / CM send the checklist (D60)  → the client uploads on the case (§5)
```

`GhlOpportunityHandler`, `CaseIntakeService`, `CasePortalAccountListener`.

**Service type from the deal (D77, 2026-10-30).** GHL's won webhook carries no service, so a case can
open on the fallback, Credential evaluation. Three things correct it, all through `CaseServiceTypeService`:

```
CASE_CREATED (after commit)  → CaseServiceTypeListener: deal not in the mirror → GHL byId → absorb;
                                then reconcile (new tx; a failure only logs — the sweep is the floor)
SERVICE_TYPE_RECONCILE (5m)  → mirror only; reconcile every case still on the fallback type,
                                in DOC_COLLECTION, whose checklist is all untouched
PUT /api/cases/{id}/service-type  (CaseServiceTypeController; GM / Brand Manager / PM; the pencil on
                                the case header) → any open case, any type; refused on CLOSED or same type
```

The deal (`dealOf`, brand-matched): the case's `ghl_opportunity_id`; with none, the case contact's deals
in the mirror — the latest **won** one, else the most recently changed or created. The label maps by a
static table (unmapped labels leave the type alone). A change keeps every item that was sent, uploaded
or reviewed (anything not "REQUIRED and unsent"), deletes untouched items the new type's template lacks,
adds the template's missing ones as REQUIRED, and audits `serviceType` before → after.

**Removing a checklist item (2026-10-30).** `DELETE /api/cases/{id}/checklist/{itemId}` (GM, Brand
Manager, PC, CM — the checklist's own roles) → `ChecklistService.removeItem`: refused for an item the
client UPLOADED or that was APPROVED, and for the case's last item; otherwise deleted and audited
(`DELETED`, "Required document removed: …"). A sent, MISSING or INCORRECT item may still be removed.

**Removed by Unit 64 (2026-09-29):** the portal request (`/requests/new`, `client_application`),
its documents (`application_document`), the opportunity EvalOS opened at submit on the `INTAKE`
pipeline with the service and submitted fields, the confirmation mail, Sales' Application and
Request documents tabs, and the Unfinished requests screen. `V78` dropped both tables. Spec `64`.

### TARGET WORKFLOW

Matches CURRENT above (Unit 64 built 2026-09-29).

---

## 3. Contact and opportunity write semantics

### CURRENT IMPLEMENTATION — two create paths, two verbs; every *edit* is queued

**Creates still call GHL inline** (D46):

| Path | Contact | Opportunity | Effect on a repeat client |
|---|---|---|---|
| `SalesDeskService.createDeal` | `upsertContact` | **`createOpportunity`** | new opportunity; refuses a second open deal unless confirmed |
| `MarketingLeadService.openLead` | `upsertContact` | **`upsertOpportunity`** (+ stage, close), then `setOpportunityFields` for intake (D64) | **reuses the open opportunity on that pipeline** |

**Edits do not call GHL at all** (D44, Unit 46). `SalesDeskService.update` / `moveToStage` /
`close` and `MarketingLeadService.value` each edit the mirror row, stamp `local_updated_at` **and
record which of the four shared fields they touched** (`locally_edited_fields`, `V63`), queue
`UPSERT` or `CLOSE`, and answer from the row. `SYNC_OUTBOX` (2m) sends it. `moveToStage` first
refuses a stage that is not a live `pipeline_stage` of the row's own pipeline (Q12, 2026-09-24),
so the merged board strip cannot turn a drag into a pipeline move.

**Sales edits every field, and deletes (Unit 69, D66, 2026-09-30).** `PUT
/api/sales/opportunities/{id}` also takes `stageId`, `expectedCloseDate`, `assignedTo` and
`customFields`. Name / value / stage keep the queued path above; close date, owner and custom
fields go to GHL **inline** first (`GhlWriteClient.updateOpportunityDetails`, no pipeline, no
status), then `OpportunityMirrorService.absorbDetails` writes owner + fields onto the row.
`DELETE /api/sales/opportunities/{id}` refuses a won deal and one with a pending outbox push,
calls `GhlWriteClient.deleteOpportunity` (404 = done, audited `DELETED`) and stamps the row
`missing_since` (`markDeleted`) — never a row delete. UI: `DealEditDialog` ("Edit or delete").

**The push carries the edited fields only.** `updateOpportunity` omits a null from the body, so an
unedited field is left alone in GHL rather than overwritten by whatever the mirror happens to hold
— which is what made a rename undo a GHL workflow's stage move, the mirror's stage being up to one
`MIRROR_DELTA` behind. A row with nothing outstanding sends nothing: GHL answers 422 to an empty
body, and "they already agree" is success, not a retry.

**The confirmation is conditional.** `OpportunityRepository.confirmPushed(opportunityId, seen)`
clears `local_updated_at` and `locally_edited_fields` only while the stamp is still the one the push
carried. A zero row-count means the row was edited again during the round trip, so nothing is
cleared and the drain re-queues — after marking the first row sent, because a pending row is what
the outbox collapses onto.

**Both creates write the mirror before answering** (`absorbCreated`), from GHL's own reply. Without
it the very next edit of a just-created deal was refused as "not in the mirror yet" for up to a
full `MIRROR_DELTA`.

`upsertOpportunity` means one open opportunity per contact per pipeline. It is correct for a
marketing lead and would be wrong for a second sale. **D56:** when that open deal has a push still
waiting in `sync_outbox`, `openLead` does not upsert at all — the queued edit wins and the desk
gets the mirror row back with `created = false`.

### TARGET WORKFLOW

One contact, many opportunities. **One of the two create paths already does this** (`SalesDeskService.createDeal`).
`MarketingLeadService` is the divergence — deliberate and documented, but it is the one place a
second enquiry from the same person overwrites the first lead's name and value instead of opening
a new one. It stays (D56), except that a queued edit is never overwritten.

---

## 4. Production lifecycle

### CURRENT IMPLEMENTATION

```
DOC_COLLECTION ─ the PC or CM builds the checklist and SENDS it (Unit 61, D60: unsent items
                  are not in the portal; later additions wait for the next Send); PC/CM chase;
                  client uploads to S3 against each sent item
 → PM_REVIEW ─ PM writes strategy notes, assigns the CM (assign-cm, CM only — Unit 73)
 → DRAFT_IN_PROGRESS → DRAFT_REVIEW ─ CM uploads the draft as Word, PDF or both — at least one (POST …/drafts); PM approves or returns;
                  once a draft exists, PM / CM / ENM / GM offer the expert an amount (PATCH …/expert, stage kept)
 → READY_TO_SEND → CLIENT_REVIEW ─ client comments, then approves or requests changes on that version
 → CLIENT_APPROVAL → EXPERT_SIGNING ─ expert accepts / declines / signs, signed in (Unit 59)
 → FINAL_QC ─ PM passes or fails
 → READY_TO_DELIVER → DELIVERED → CLOSED
```

Orthogonal `exception_state` for holds and refunds. Every transition is a `POST /api/cases/{id}/…`
route, goes through `CaseLifecycleService`, and writes an audit row.

**Draft versions (Unit 58, D51).** `SUBMIT_DRAFT` puts both files in S3 (after the transition check,
before the row) and stores them on the new DRAFT version. The PM's ruling stamps `RETURNED` /
`PM_APPROVED`; the client's answer stamps `CLIENT_APPROVED` / `CHANGES_REQUESTED`. `CaseDrafts`
decides which version is in client review (latest `PM_APPROVED` while the client answer is
`PENDING`), which the client may see (that one, plus approved and sent-back versions) and owns the
comment threads.

**Expert evidence request (Unit 15; reach widened 2026-10-01).** In `EXPERT_SIGNING` the expert asks
from their portal → a REQUIRED, unsent checklist item + `ON_HOLD_AWAITING_CLIENT` + audit note (the
request text) → `EXPERT_EVIDENCE_REQUESTED` notifies the brand's Coordinators **and the case's CM and
PM**. The case stays on the Coordinator's **Doc checklists** board for the whole hold (labelled
"Expert asked for more evidence"), not only while the item is unsent; the Coordinator sends the item
and may **chase** — the chase is allowed in `DOC_COLLECTION` or while held for the client. The case
page shows the request to everyone on the case (`caseRules.expertEvidenceRequest`) until **Resume**
(Coordinator / PM); then the expert can sign.

Five sweeps run over this: `DOC_CHASE`, `DOC_ESCALATION`, `EXPERT_SIGN`, `STAGE_SLA`, and
`SERVICE_TYPE_RECONCILE` (§2).

**Staffing (Unit 73, 2026-10-02).** The case is born at Handoff A, a **PM** takes it, and the PM
assigns the **Project Coordinator** (`POST /api/cases/{id}/assign-coordinator`, GM / Brand Manager /
PM) and, at PM Review, the **Case Manager** (`…/assign-cm`, GM / PM — the CM only; no expert, no
offer). **Once the CM has uploaded a draft** the PM, CM, ENM or GM offers the **expert** an amount from
the case's More menu ("Assign / Change expert", `PATCH …/expert` → `changeExpert`, stage kept,
`DRAFT_IN_PROGRESS` … `CLIENT_APPROVAL`; a CM at the standard fee only): any open offer SUPERSEDED,
the old expert link revoked, a new offer opened, and `EXPERT_ASSIGNED` sends the offer mail and adds
the expert to the Expert chat. Refused before a draft and from `EXPERT_SIGNING` on (decline /
timed-out → reassign). The PM (or GM) may change the CM at any stage ("Assign / Change case manager",
`PATCH …/case-manager`). Once a slot is filled its action reads "Change …". The PC and the CM are each
notified when they are put on a case (`CASE_ASSIGNED`), and the timeline row names who was assigned.
The expert opens the client's current files and the approved draft on their case (D63, Unit 66b).

### TARGET WORKFLOW

The lifecycle and staffing above **are** the stated business lifecycle (D36, 2026-09-17): the CM
drafts, the client approves, only then the expert signs (Handoff B). The open work is visibility,
not lifecycle: there is no unified timeline across the opportunity and the case. **Sales is not on
that list** — they read no case by design (D19c).

---

## 5. Documents

### CURRENT IMPLEMENTATION

```
Client Portal /cases/:caseId (Unit 58 — every route names its case; the case-less ones are gone)
  POST /api/portal/client/cases/{id}/documents?checklistItemId=…
        authorize the case → verify the item is on it AND sent (Unit 61)
        → S3 key built from brand + the GHL contact id + a fresh document uuid (D41)
        → PUT to S3 → INSERT case_document (CLIENT_UPLOAD, versioned)
        → checklist item → UPLOADED → audit
  GET  /api/portal/client/cases/{id}                    view + step, stepIndex, status (current + latest remark), history (dated status changes)
  GET  /api/portal/client/cases/{id}/documents          POST …/documents?checklistItemId=  GET …/documents/{doc}/url
  GET  /api/portal/client/cases/{id}/drafts             client-visible versions only
  GET  …/drafts/{draft}/files/{docx|pdf}/url            GET|POST …/drafts/{draft}/comments
  POST …/drafts/{draft}/approve                         POST …/drafts/{draft}/request-changes
  GET  /api/portal/client/cases/{id}/delivered          + …/delivered/{doc}/url — 404 before DELIVERED
  GET  /api/portal/client/invoices?status=paid

Staff     GET /api/cases/{id}/documents, …/{documentId}/url[?pdf=true]
          POST /api/cases/{id}/drafts (docx and/or pdf)    GET|POST /api/cases/{id}/drafts/{draft}/comments
Expert    GET /api/portal/expert/letter, POST /api/portal/expert/signed-letter
```

`PortalCaseService`, `CaseDrafts`, `CaseStatusHistory`, `DocumentStore`. The portal UI over them is
Unit 58 phase 3: Home lists every case, and each case page holds its documents, drafts, delivered
files, history and Client conversation.

**Live updates (Unit 70, D68, 2026-10-01).** A committed document, checklist or draft change shows on
every open screen of that case, staff and portal, without a reload: `case.changed` over Ably, then a
background REST re-read (staff `lib/live.ts`, portals `LiveInvalidate`). The GM hears every brand:
their token names each brand's channel.

### TARGET WORKFLOW

`Case → checklist sent (D60) → Client Portal upload → S3 (keyed by GHL contact id, D41) → Production → Expert`.

Matches CURRENT: documents enter **only at the Case**, against a sent checklist item. The
request-document upload, Sales' Request documents tab and the carry-forward at Handoff A were
removed by Unit 64 (2026-09-29).

---

## 6. Calendar and appointments

### CURRENT IMPLEMENTATION

```
GET  /api/sales/calendars                        list GHL calendars
GET  /api/sales/calendars/{id}/slots             free slots for a date range and timezone
POST /api/sales/opportunities/{id}/meetings      book
PUT  /api/sales/opportunities/{id}/meetings/{a}  reschedule
PUT  /api/sales/opportunities/{id}/meetings/{a}/cancel       cancel (GHL status `cancelled`)
GET|POST /api/sales/opportunities/{id}/meetings/{a}/notes    internal notes, live from GHL
PUT|DELETE /api/sales/opportunities/{id}/meetings/{a}/notes/{n}
GET|POST /api/sales/blocked-time, DELETE /api/sales/blocked-time/{e}   the caller's own blocks
GET  /api/sales/meetings                         the salesperson's diary
```

**Unit 60:** every per-meeting route requires a `meeting` row for that appointment **on that
deal** (spec `60` §1.2). Blocked time is keyed by the caller's `team_member.ghl_user_id`; removing
one is checked against the caller's own blocks first, because GHL's only delete is the generic
event delete.

Booking sends: calendar, contact, start, end, title, description, `assignedUserId`,
`meetingLocationType`, `address`, `appointmentStatus=confirmed`, optional
`ignoreFreeSlotValidation`. Mirrored into `meeting` (`GhlCalendarClient`, `SalesMeetingService`).

Capability by capability:

| Capability | State |
|---|---|
| Calendar, title, description, date, available slots, timezone | **IMPLEMENTED** — the calendar **list** is mirrored (Unit 47); **slots stay live and must** (D48) |
| Contact, meeting location, create, view, reschedule | **IMPLEMENTED** |
| Team member on the appointment | **IMPLEMENTED** — `assignedUserId`; `team_member.ghl_user_id` (`V74`) links staff by email |
| Employee-wise availability | **IMPLEMENTED** — free slots take the chosen member's `userId` |
| Account vs calendar timezone | **PARTIAL** — one timezone is passed to free-slots; no account default is stored |
| Guests | **NOT AVAILABLE IN GHL** — the appointment API takes one contact and no attendees; `open-decisions.md` Q14 |
| Internal notes | **IMPLEMENTED** — list / add / delete on the diary row (edit route exists, no UI) |
| Cancel | **IMPLEMENTED** — GHL status `cancelled`, so GHL notifies |
| Blocked-off time | **IMPLEMENTED** — the caller's own, on the Meetings screen |

### TARGET WORKFLOW

The booking experience in the table above, with three gaps: an account default timezone beside the
calendar's; guests, which GHL cannot hold (Q14 recommends dropping them); and a UI for the existing
note-edit route.

---

## 7. Conversations

### CURRENT IMPLEMENTATION

**Case chat, backend only (Unit 57 phase 1, 2026-09-26; spec `57-case-chat.md`, D50).** Every case
gets three conversations — Client, Internal, Expert — created at `CASE_CREATED` by the after-commit
`ChatLifecycleListener`. Membership is computed by `ChatMembership` from the case team, the deal
pipeline's Sales, the brand's ENMs, the client's portal account and the expert's open or accepted
offer, and follows every event in `ChatLifecycleListener.MOVES_THE_CHAT` (including the new
`CASE_MANAGER_REASSIGNED`). `CHAT_RECONCILE` (hourly) repairs whatever an event misses and, on its
first run, backfills every case not yet closed. At `CLOSED` all three become read-only. Messages go
through REST on `/api/chat`, `/api/portal/client/chat` and `/api/portal/expert/chat`; after commit
`ChatFanout` publishes each change into every current member's private Ably channel, and
`ChatPushNotifier` sends a web push to members who are not present on theirs. **Screens:** staff
`/conversations` (`ConversationsPage`) and the case page's chat (`CaseChat`); Client Portal
`/conversations` and the case page's Client conversation; Expert Portal `/messages`.
`opportunity_note` (Unit 54a) is unchanged and separate.

**The GM takes part (Unit 77, D75, 2026-10-08).** A GM who is not a member may write in any open
conversation of any brand (`ChatAccessLevel.PARTICIPANT`); the post is labelled `GM`
(`MessageView.authorRole`), creates no member row and no read position, and the GM has no unread. A
closed case stays read-only and the Brand Manager still only reads. Because the GM is in no member
list, `ChatFanout` never reaches the GM's own channel: a non-member listens on the conversation's
own `chat:view:{brand}:{conversation}` channel while it is open (`Realtime.watch`, driven by
`ConversationView.access` and `.brandId`).

**Notes, both ways (Unit 54, 2026-09-24).** A note written on a deal is queued and pushed
once by `SYNC_OUTBOX` to the deal's GHL **contact** (≤2m), carrying an author/deal trailer; GHL's
notes, already mirrored into `ghl_note` by `MIRROR_DELTA`, show on the deal beside EvalOS's with an
origin badge (≤5m), filed by GHL's own `relations` — a note on the contact alone shows on every
deal of that contact. **An EvalOS note's author may edit or delete it** (Unit 54a): the change is
queued and the drain overwrites (`PUT`) or deletes (`DELETE`) the GHL copy; GHL notes are changed in
GHL only. Spec `54-two-way-note-sync.md`.

### TARGET WORKFLOW

As CURRENT. **No GHL conversation sidebar (D52, 2026-09-28).** GHL conversations stay in GHL; EvalOS's only
messaging is the case chat (Unit 57, spec `57-case-chat.md`).

---

## 8. Handoffs

### CURRENT IMPLEMENTATION

| | Direction | Trigger | State |
|---|---|---|---|
| **A** | GHL → EvalOS | `opportunity.won` webhook creates the case; second door, same class: "New case" `POST /api/cases/from-ghl` for a deal won in GHL (D9, 2026-10-30) | code complete |
| **mirror** | GHL → EvalOS | `contact.*` and `opportunity.*` webhooks update `contact_snapshot` and `opportunity`; `MIRROR_DELTA` (15m) is the floor under them | code complete (45d, 2026-09-17) |
| **contact backfill** | GHL → EvalOS | the deal screen reads `GET /contacts/{id}` when `contact_snapshot` has never seen the person, and keeps the row — country, tags and custom field values included (2026-09-30; `CONTACT_MIRROR` fills them for every contact) | code complete (2026-09-22) |
| **push** | EvalOS → GHL | a desk edit queues `sync_outbox`; the drain (2m) reads GHL's copy first and sends only the fields GHL has not changed since — both changed → the newer edit wins (spec 86); a dead push re-queues itself after 1h when the cause was timing or GHL, max 3 times a week | code complete (2026-10-28) |
| **B** | EvalOS → Expert | the offer (`EXPERT_ASSIGNED`) mails the expert; they sign in to their own portal account, accept, sign and upload (staff-minted links removed, `V73`) | code complete |
| **C** | EvalOS → GHL / client | — | **dropped (D53)** |

**The contact backfill exists because every other writer of `contact_snapshot` is an EvalOS-side
event.** Handoff A writes one when a deal is won, the portal writes one at set-password, and 45d's
`contact.created`/`contact.updated` webhook writes one when GHL tells us something changed. None of
those fires for a contact that already existed in GHL before EvalOS met it, and **no sweep pulled
contacts** then — `MIRROR_DELTA` refreshes opportunities; `CONTACT_MIRROR` came later. So a deal a salesperson typed into GHL arrived
in the mirror carrying a `ghl_contact_id` and nothing else, and the deal screen said *"no contact on
this deal yet — it arrives with the next sync"* indefinitely, which was a sentence about a sync that
was never going to run.

`ContactSnapshotService.findOrFetch` closes it: **the mirror is still the source** and a row already
held is returned without touching GHL — which keeps the screen working with the sync off — but a
miss reads `GET /contacts/{contactId}` once and saves the result through `findOrCreate`, so the
email-match and contradiction rules still apply and the second open is a mirror read again. A GHL
failure returns empty and logs rather than throwing: an upstream blip must not take the notes, the
questionnaire and the actions down with the contact card. Scope `contacts.readonly`, already granted
— `GhlCalendarClient` uses it for a contact's appointments.

### TARGET WORKFLOW

As CURRENT. Handoff C stays dropped (D53).

## Request documents (Unit 53) — removed by Unit 64 (2026-09-29)

### CURRENT IMPLEMENTATION

The pre-case upload, Sales' tab and the carry-forward at Handoff A are gone, and `V78` dropped
`application_document`. Client documents enter only on a case (§5). Spec `64`.

### TARGET WORKFLOW

None — not coming back.

## Expert portal sign-in (Unit 59, 2026-09-28)

### CURRENT IMPLEMENTATION

```
Expert Portal /  → POST /api/portal/auth/expert/sign-up | …/forgot-password   roster match in the portal's brand
                    → set / reset mail; else nothing (by decision, Q6b); 204 always
POST /api/portal/auth/expert/sign-in → party-scoped expert token
/set-password#token → POST …/set-password → party-scoped expert token
/cases → /case?caseId=                     expert actions take ?caseId
```
**There is no other way in:** staff-minted expert links were removed and live ones revoked (`V73`).

### TARGET WORKFLOW

As CURRENT.

## 9. Expert lifecycle — the ENM (Unit 63, D61 / D62 / D59)

### CURRENT IMPLEMENTATION

```
GHL "Expert Hiring" pipeline (GM tags it EXPERT_HIRING)
  New Lead → Meeting Scheduled → Meeting Done → In Process → Onboarded | Dropped   (stages are GHL's)
    ENM: /hiring board (drag = stage move → mirror + outbox → GHL), /hiring/new (lead upsert)
    GHL-side move or new candidate → mirror absorb → HIRING_PIPELINE_UPDATED to the brand's ENMs
    won on this pipeline → CaseIntakeService refuses: never a case
  Onboarded → "Add to expert database" → /experts create form, pre-filled → expert row
Directory: credentials verified (stamp + audit) · fee · availability · workload · quality
  · case history (offered / accepted / submitted / delivered / rejected / reassigned)
Rejected (declined / timed out) while EXPERT_DECLINED_REMATCHING + expert AVAILABLE
  → "Offer again" (GM / PM / ENM / CM) → same rematch transition → CLIENT_APPROVAL → CM sends again
PM / CM / ENM / GM offers the case with a fee (PATCH …/expert; blank = standard fee; retake keeps the
  declined fee; CM: standard fee only) and a required note to the expert (D69; a retake re-sends the last one)
  → fee editable while the offer is open by GM / PM / PC / ENM (PATCH …/expert/offer/fee, OfferFeeController;
    audited, before → after) — the PC edits the fee but does not make the offer
  → the expert sees "Fee for this case" and accepts it (a changed or missing fee → 409) → final
Delivered → payout PENDING at the accepted fee (a missing amount can be set once) → PAYOUT_DUE to ENMs
  → ENM records the transfer → PAID, shown "Processing"
  → expert presses "Confirm received" in the portal → CONFIRMED, shown "Paid" → PAYOUT_CONFIRMED to the recorder
ENM pays weekly from Payment batch (/payouts/pay: one week's due payouts, one transfer per expert)
Payouts module: Overview (/payouts: Committed / Pending / Processing / Paid per currency on the
  shell's period and brand, needs attention, the weekly / monthly / yearly Summary and its two CSVs)
  · Cases (/payouts/cases: fee, status, done, per-offer log, CSV) · Experts (/payouts/experts)
```

### TARGET WORKFLOW

The same. Not built by choice: booking or follow-ups from a hiring deal, an outreach log, a payout
approval step or failed state, disputes, any payout adjustment (bonus, deduction, advance), a change
to the amount after acceptance.

---

## 10. Email — what EvalOS sends, and when

### CURRENT IMPLEMENTATION

All mail is `ClientMailer` → SMTP (D3e), copy in `MailTemplates`. Each send is after commit and never
throws; a skipped or failed send is logged, the screen the link opens is the record.

```
CLIENT  (to the case contact's linked client_account; portal brand only)
  case created          CasePortalAccountListener → ClientAccountService.openForCase
                          no password → case-started mail with a set-password link (7 days)
                          password set → case-started sign-in mail
  checklist Send        CHECKLIST_REQUESTED → "Documents needed" (count of sent, incomplete items)
  manual Chase          CHECKLIST_CHASED (ChecklistService.chase) → reminder, same count
                          — the DOC_CHASE sweep sends none (an in-app prompt to the Coordinators)
  draft to client       DRAFT_READY_FOR_CLIENT → "Your draft is ready to review"
  delivered             CASE_DELIVERED → "Your final report is ready"
EXPERT  (to the case's expert; portal brand only)
  offer                 EXPERT_ASSIGNED → "A new case is offered to you", with the open offer's fee if priced
  sent for signing      EXPERT_SENT_FOR_SIGNING → "A letter is ready for your signature"
AUTH
  client identify       account with no password → SET link (ClientAccountService)
  client forgot-password → RESET link; 204 always (a live unspent link is not re-sent)
  expert sign-up / forgot-password → set / reset link on a roster match (ExpertAccountService); 204 always
ADMIN
  Settings → test mail  SettingsAdminService.testMail → the signed-in Admin's own address
```

`CaseMailListener` (case progress, D58; offer, D67), `CasePortalAccountListener` (D65),
`ClientAccountService`, `ExpertAccountService`, `SettingsAdminService`.

**Skipped, no mail:** a case outside the portal brand (`evalos.portal.client-brand`); no client account
linked to the case contact; an expert with no email; Send or Chase with zero outstanding sent documents;
mail unconfigured (no relay or no sender) or switched off by the Admin (`MAIL_ENABLED`, D83). Only the
service's **name** varies by service type (`open-decisions.md` Q36).

### TARGET WORKFLOW

As CURRENT. Any new client or expert mail is its own decision (invariant 14).
