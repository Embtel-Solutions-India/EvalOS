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
widened only to carry the documents beside the answers (D34).

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
questionnaire still reached Sales; it no longer does, knowingly). `createOpportunity`, on the
pipeline a GM marked `INTAKE`, with **no stage and no assignee** — placement is GHL automation's
job. Service id, correlation key and `SUBMITTED` all ride that one create; `setOpportunityFields`
is off this path. Start and save reach GHL zero times. A failed create **refuses the submit** and
keeps the draft. From there it is Sales': review → won → payment (D10c).

Four GHL write paths, three verbs: `signUp` → `ensureCrmIdentity` (upsertContact), application
**`submit`** (createOpportunity — `start` reaches GHL zero times under D10),
`SalesDeskService.newDeal` (createOpportunity), and
`MarketingLeadService.capture` (**upsertOpportunity** — the one place a repeat enquiry reuses an
open deal).

**Documents** enter at the **case** today; D33 adds the request-stage upload keyed by the **GHL
contact id** (D41 — one id names a contact everywhere; `DocumentStore.clientKey` moved back onto it
on 2026-09-17) and carries it into `case_document` at Handoff A over the same S3 object. **Notifications** are in-app today; D37 makes them in-app **and push**, never mail.

**Conversations do not exist** — no table, no route, no component, anywhere.

**GHL → EvalOS (45d, 2026-09-17).** `contact.created`/`contact.updated` → `contact_snapshot`;
`opportunity.create|created|update|updated|stage_changed|status_changed` → re-read
`forContact` → `absorbForContact`. `opportunity.won` stays Handoff A alone. `MIRROR_DELTA` (15m)
re-reads only pipelines nobody has looked at inside `evalos.ghl.delta-ttl`.
