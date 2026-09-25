# Workflows

**The authoritative file is `.claude/workflows.md`. It states CURRENT IMPLEMENTATION and TARGET
WORKFLOW separately — never present a target as if it exists.**

Target lifecycle:

```
CLIENT → PORTAL → REQUEST SERVICE → SERVICE DETAILS → DOCUMENT SUBMISSION → REQUEST CREATED
       → SALES REVIEW → OPPORTUNITY → PAYMENT/WON → CASE → PRODUCTION → DELIVERY
```

**One step of it is missing** as of 2026-09-17: **document submission** at request stage (D33,
Unit 53). *Sales review as an EvalOS state is NOT missing and NOT owed* — D35 makes it a GHL
pipeline stage, so read-only on `GET /api/opportunities/{id}/application` is the finished shape,
widened only to carry the documents beside the request (D34). No questionnaire since Unit 55 (D13).

Production, and it is now a stated business rule rather than an accident of the code (D36):
Handoff A creates the case → a **PM** takes it → the PM assigns **Coordinator**, **Case Manager**
and **Expert** → the **CM drafts and uploads** → the **client approves in the portal** → only then
the **expert** downloads, signs and uploads back. `CaseLifecycleService` already implements all of
it. **Sales sees none of this** (D19c).

Client identity (`ClientAccountService`) already matches the target: `identify` answers three ways;
`signIn` creates nothing; and **`signUp` upserts the GHL contact** with `source: "Client Portal"`
(D3d/D3b — it moved to set-password for one morning as D3a and came back the same day, because GHL
sends the mail and `POST /conversations/messages` requires a `contactId`). `/auth/sign-up` is
`permitAll` on the shared 60/min/IP counter, so that write is a stranger's write; what is meant to
hold it down is the route's **own** tighter budget plus a proof-of-human gate — **neither is built
yet** — and `PORTAL_CLEANUP` clearing what a flood leaves behind (D3f, `created_via`). If GHL is
down the client still gets in, and `identify`/`signIn`/`ClientApplicationService` backfill the
contact (D3c).

Client request (`ClientApplicationService`): the opportunity is created **at SUBMIT** (D10,
changed 2026-09-16, third time of asking — it was service-pick, so an abandoned
request still reached Sales; it no longer does, knowingly). The funnel is Service → Review
(documents + send); the questionnaire is gone (Unit 55). `createOpportunity`, on the
pipeline a GM marked `INTAKE`, with **no stage and no assignee** — placement is GHL automation's
job. Service id, correlation key and `SUBMITTED` all ride that one create; `setOpportunityFields`
is off this path. Start and document uploads reach GHL zero times. A failed create **refuses the submit** and
keeps the draft. From there it is Sales': review → won → payment (D10c).

Four GHL write paths, three verbs: `signUp` → `ensureCrmIdentity` (upsertContact), application
**`submit`** (createOpportunity — `start` reaches GHL zero times under D10),
`SalesDeskService.newDeal` (createOpportunity), and
`MarketingLeadService.capture` (**upsertOpportunity** — the one place a repeat enquiry reuses an
open deal).

**Documents** enter at the **case** today; D33 adds the request-stage upload keyed by the **GHL
contact id** (D41 — one id names a contact everywhere; `DocumentStore.clientKey` moved back onto it
on 2026-09-17) and carries it into `case_document` at Handoff A over the same S3 object. **Notifications** are in-app today; D37 makes them in-app **and push**, never mail.

**Conversations do not exist** — no table, no route, no component, anywhere. **Notes** are
synced both ways (Unit 54, built 2026-09-24): pushed once to the GHL contact via the
outbox, GHL notes shown on the deal from the existing `ghl_note` mirror.

**GHL → EvalOS (45d, 2026-09-17).** `contact.created`/`contact.updated` → `contact_snapshot`;
`opportunity.create|created|update|updated|stage_changed|status_changed` → re-read
`forContact` → `absorbForContact`. `opportunity.won` stays Handoff A alone. `MIRROR_DELTA` (15m)
re-reads only pipelines nobody has looked at inside `evalos.ghl.delta-ttl`.

**Contact backfill (2026-09-22).** `ContactSnapshotService.findOrFetch`: mirror first, and only on a
miss `GhlContactClient.byId` → `GET /contacts/{id}` (`contacts.readonly`, already granted), saved
through `findOrCreate` so the email-match and contradiction rules still apply. **Why it had to
exist:** every writer of `contact_snapshot` is an EvalOS-side event (Handoff A, set-password, the
`contact.*` webhook) and NO SWEEP PULLS CONTACTS — `MIRROR_DELTA` refreshes opportunities. A deal
typed straight into GHL therefore carried a `ghl_contact_id` and no contact row, and the deal screen
read "it arrives with the next sync", naming a sync that does not exist. A GHL failure returns empty
and logs rather than throwing, so a blip does not take the whole screen down with the contact card.
It is a backfill, not a mirror: a contact CHANGED in GHL still only updates via the webhook.

**Desk writes (46, 2026-09-17).** Edits — `update`, `moveToStage`, `close`, Marketing's `value` —
are `editLocally` + `enqueue(UPSERT|CLOSE)` and return the local row. Creates — `createDeal`,
`openLead` — still call GHL inline. Boards call GHL **never**. `moveToStage` refuses a stage not
live on the row's own pipeline (Q12 → D44, 2026-09-24).

**Desk edits, as of the 2026-09-18 review pass.** Edit the mirror row, stamp `local_updated_at`
**and record which shared fields were touched** (`locally_edited_fields`), enqueue, answer from the
row. `SYNC_OUTBOX` (2m) sends **only those fields** — sending all four made a rename undo a GHL
workflow's stage move, the mirror's stage being up to one `MIRROR_DELTA` behind. The push's
confirmation is conditional (`confirmPushed`): if the row was edited again during the round trip
nothing is cleared and the drain re-queues, after marking the first row sent so the pending-row
collapse cannot swallow it. **Both creates absorb GHL's reply into the mirror before answering**, or
the next edit of a just-created deal is refused as "not in the mirror yet" for a full sweep.

**DOCUMENT SUBMISSION is no longer the missing step** (Unit 53, 2026-09-18). The client attaches
documents on the request's review step before sending; **submit is never gated on them** (`43` §5).
Sales reads them beside the request on the deal page, on their own route and the same permission
(D34). At Handoff A they follow the request onto the case with no S3 copy and no re-key, through a
`CASE_CREATED` listener that can never fail the case.