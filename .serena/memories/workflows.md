# Workflows

**The authoritative file is `.claude/workflows.md`. It states CURRENT IMPLEMENTATION and TARGET
WORKFLOW separately — never present a target as if it exists.**

Target lifecycle:

```
CLIENT → PORTAL → REQUEST SERVICE → SERVICE DETAILS → DOCUMENT SUBMISSION → REQUEST CREATED
       → SALES REVIEW → OPPORTUNITY → PAYMENT/WON → CASE → PRODUCTION → DELIVERY
```

Two steps of it are **missing today**: document submission at request stage, and Sales review as a
state (Sales can read the application but cannot act on it).

Client identity (`ClientAccountService`) already matches the target: `identify` answers three ways;
`signIn` creates nothing; and **`signUp` writes EvalOS rows only — it makes NO outbound call**.
The GHL contact is created by `setPassword` → `ensureCrmIdentity`, with `source: "Client Portal"`
(D3a/D3b, 2026-09-16). `/auth/sign-up` is `permitAll` behind one per-IP counter, so a CRM write
there was a stranger's write: junk contacts for Sales, and GHL's shared 100-req/10s location
budget spent, which 502s every GHL-backed staff screen. If GHL is down at set-password the client
still gets in, and `ClientApplicationService` backfills the contact at their first request (D3c).

Client request (`ClientApplicationService`): the opportunity is created **at SUBMIT** (D10,
changed 2026-09-16, third time of asking — it was service-pick, so an abandoned
questionnaire still reached Sales; it no longer does, knowingly). `createOpportunity`, on the
pipeline a GM marked `INTAKE`, with **no stage and no assignee** — placement is GHL automation's
job. Service id, correlation key and `SUBMITTED` all ride that one create; `setOpportunityFields`
is off this path. Start and save reach GHL zero times. A failed create **refuses the submit** and
keeps the draft. From there it is Sales': review → won → payment (D10c).

Four GHL write paths, three verbs: `setPassword` → `ensureCrmIdentity` (upsertContact — was
`signUp` until D3a), application `start`
(createOpportunity), `SalesDeskService.newDeal` (createOpportunity), and
`MarketingLeadService.capture` (**upsertOpportunity** — the one place a repeat enquiry reuses an
open deal).

**Conversations do not exist** — no table, no route, no component, anywhere.
