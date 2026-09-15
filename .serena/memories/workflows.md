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
`signUp` upserts the GHL contact and never duplicates it; `signIn` creates nothing.

Client request (`ClientApplicationService`): the opportunity is created at the **first screen**,
with `createOpportunity`, on `evalos.ghl.intake-pipeline-name`, with **no stage and no assignee** —
placement is GHL automation's job. Submit changes status only.

Four GHL write paths, three verbs: `signUp` (upsertContact), application `start`
(createOpportunity), `SalesDeskService.newDeal` (createOpportunity), and
`MarketingLeadService.capture` (**upsertOpportunity** — the one place a repeat enquiry reuses an
open deal).

**Conversations do not exist** — no table, no route, no component, anywhere.
