# Unit 52 — The Client Portal ↔ GHL integration

**Status: PARTIALLY BUILT (2026-09-16).** §4 is built and tested. §5 is the delta, and most of it
is **Units 44–48**, not this unit — decided 2026-09-16, see §6.

The requirement, as the business stated it:

> When a client signs up, EvalOS fires an API to create or find/link the corresponding GHL Contact
> and stores the GHL Contact ID in EvalOS. When the client submits a service request, EvalOS
> creates a basic GHL Opportunity containing only the essential request/client data, while the
> detailed questionnaire, uploaded documents, and request information remain owned by EvalOS. A GHL
> workflow triggered by opportunity creation assigns the opportunity to the appropriate pipeline
> based on the requested service. Both EvalOS and GHL stay synchronized so Sales can see and manage
> the Contact and Opportunity from either platform, while EvalOS provides the complete business
> context — Request, Questionnaire, Documents, Payments and Case — without making GHL the primary
> source of business data.

---

## 1. The truth model this rests on, restated

Unchanged from `00b` §1.3 and still correct:

| Record | Owner | EvalOS holds |
|---|---|---|
| Contact | **GHL** | `client_account.ghl_contact_id`, `contact_snapshot` |
| Opportunity | **GHL** | `client_application.ghl_opportunity_id` |
| Request + questionnaire | **EvalOS** | `client_application` (`V49`) |
| Documents | **EvalOS** | `case_document`, S3 object keys |
| Invoices / payments | **GHL** (D15) | read-only, `PortalInvoiceService` |
| Case | **EvalOS** | `evalos_case`, born only of `opportunity.won` (invariant 8) |

**"Without making GHL the primary source of business data" is already true and is enforced rather
than intended.** Nothing in `client_application` has a GHL equivalent — a service id from EvalOS's
own catalog, and a questionnaire whose questions depend on it — and `CachedOpportunity`'s class
comment makes it a build-time rule that no EvalOS screen may show a value that exists only in a GHL
cache.

---

## 2. Status against each clause of the requirement

| Clause | State |
|---|---|
| Sign-up creates or finds the GHL Contact | ✅ **built** — `ClientAccountService.signUp` → `GhlWriteClient.upsertContact` |
| The GHL Contact ID is stored in EvalOS | ✅ **built** — `client_account.ghl_contact_id`, set by `linkGhlContact` |
| A basic GHL Opportunity carrying the essential data | ✅ **built (§4.1)** — contact, name, and now the requested service |
| Questionnaire / documents / request stay EvalOS's | ✅ **built** — and structurally, see §1 |
| A GHL workflow routes it to a pipeline by service | ⚙️ **EvalOS's half is built (§4.1); the workflow is yours to build in GHL (§4.3)** |
| GHL learns the request was submitted | ✅ **built (§4.2)** |
| Both platforms stay synchronized | ❌ **Units 44–48**, see §5.1 and §6 |
| Sales manages from either platform | ◐ EvalOS→GHL works; GHL→EvalOS is §5.1 |

---

## 3. One decision the requirement collided with, and how it was resolved

The requirement says the opportunity is created **when the client submits**. It is created when
they **pick a service** — decision **D10**, taken deliberately on 2026-09-15 because the
questionnaire is the longest part of the funnel and therefore exactly where people stop, so *a lead
who abandons halfway must already be on a salesperson's board*.

**Resolved 2026-09-16 in favour of D10, with a submit signal added.** The opportunity still opens
at service-pick, so no lead is lost; submitting now writes a custom field on the same opportunity,
which is a clean second workflow trigger. That satisfies what the requirement is *for* — GHL
learning that a real request arrived — without reversing a decision taken to stop losing leads.

---

## 4. What was built

### 4.1 The requested service goes to GHL as an opportunity custom field

`evalos.ghl.opportunity-service-field` (`GHL_OPPORTUNITY_SERVICE_FIELD`) names a GHL opportunity
custom field id. `ClientApplicationService` sets it to the **service id** — `academic_evaluation`,
`eb1a_expert_opinion_letter`, … — when it creates the opportunity.

**This is the whole of EvalOS's part in the routing, and the narrowness is deliberate.** A GHL
workflow can branch on a custom field; it cannot branch on the free text in the opportunity's
*name*, which is where the service appeared before and nowhere else. So EvalOS states the fact it
owns — which service was asked for — and GHL decides which pipeline that belongs on.

- **The id, not the display name.** The name is written for a human reading a board and is reworded
  whenever the catalog is; the id is the stable slug a workflow condition can be written against.
- **Mapping services to pipelines lives in the workflow, not here.** Eighteen services against four
  sales pipelines is a business rule. A copy in EvalOS goes stale the first time somebody reworks
  the routing in GHL — which is exactly why `hot-stage-name` was removed from this class on the
  business's instruction, and this unit does not bring it back.
- **No price is sent.** EvalOS holds no price list; what the work is worth is Sales' to set. A zero
  would be a priced deal worth nothing rather than an unpriced one, and would land in the GM
  dashboard's won figures as exactly that.
- **Blank omits the field and nothing else changes.** An environment that has not created the field
  yet still opens the deal — losing the lead over an unconfigured field would defeat D10.

### 4.2 Submitting the request tells GHL

`evalos.ghl.opportunity-submitted-field` (`GHL_OPPORTUNITY_SUBMITTED_FIELD`) names a second custom
field. On submit, EvalOS writes the constant `SUBMITTED` into it.

Why it exists: because of D10, a deal on the board does not distinguish somebody browsing from a
finished request, and Sales would have to open EvalOS to tell them apart. This field is the
difference, and it is a second workflow trigger for GHL to do whatever the business decides with.

- **Through `GhlWriteClient.setOpportunityFields`, which sends custom fields and nothing else.**
  By submit time GHL's workflow has very probably moved the opportunity onto a service-specific
  pipeline. `PUT /opportunities/{id}` accepts a `pipelineId` and treats the pipeline as a mutable
  field, so any update path carrying one could **undo GHL's own routing**. That method structurally
  cannot: there is no pipeline, stage or name in the body for a future edit to smuggle in.
- **A failure here does not fail the submit**, which is the opposite of the rule for a missing
  opportunity. That rule exists because an application Sales cannot *see* reads to the client as
  "sent" and to the business as nothing at all. Here Sales can already see the deal — only the
  marker is missing — so refusing would throw away a completed questionnaire over a flag.
- `ponytail:` swallowed and logged with no retry, so a GHL outage at that exact moment loses the
  marker permanently. The fix is not a retry loop — it is **Unit 45's outbox**, where every
  EvalOS→GHL write is meant to end up.

### 4.3 What you have to build in GHL

EvalOS cannot create a workflow through the API; this is UI work in the sub-account
(`WY6bW2xUCI8Tz8gw7aLJ`).

1. Create two **Opportunity** custom fields under Settings → Custom Fields: one for the service
   (text), one for the request status (text or a picklist containing `SUBMITTED`).
2. Copy their **ids** into `GHL_OPPORTUNITY_SERVICE_FIELD` and `GHL_OPPORTUNITY_SUBMITTED_FIELD`.
   `GET /api/sales/opportunity-fields` — which the sales desk already calls — lists them.
3. Build a workflow triggered on **opportunity created**, filtered to the intake pipeline, with
   branches on the service field that move the deal to the right pipeline. A GHL *Update
   Opportunity* action can change the pipeline: `PUT /opportunities/{id}` treats the pipeline as a
   mutable field and the opportunity **keeps its id**, which is what makes the EvalOS link and the
   note stream survive the move.
4. Optionally, a second workflow on the submitted field changing.

**Verify step 3 in the GHL workflow builder before promising it.** The API-side capability is
proven (`GhlWriteClient.moveStage` relies on it); that the workflow builder exposes a
pipeline-changing action in this sub-account is a UI fact this repository cannot check.

**Also outstanding and not this unit's:** `GHL_INTAKE_PIPELINE_NAME` defaults to blank, which makes
every request-start answer 502, and `00d` records that the `opportunity.won` workflow was never
recreated in the new sub-account — so **no case is created by anything** today. Both are Phase 0
runbook items and both must be done for this flow to work end to end.

---

## 5. The delta that remains

### 5.1 GHL → EvalOS is one webhook wide

The only inbound event handled is `opportunity.won`, which creates a case. `WebhookRouter` archives
and acks `contact.created`, `contact.updated` and `refund.requested` without routing them, and
`opportunity.update` is not recognised at all. So today:

- A salesperson moving a deal, pricing it or reassigning it in GHL leaves **no trace in EvalOS**.
- A contact edited in GHL does not reach `client_account` or `contact_snapshot`.
- "Manage from either platform" is true in one direction only.

### 5.2 `client_application` has no link to the case

`evalos_case.ghl_opportunity_id` and `client_application.ghl_opportunity_id` hold the same value
when a request is won, so the join exists — but nothing reads it, and there is no
request → case navigation on any screen. That is what "EvalOS provides the complete business
context" needs and does not yet have.

### 5.3 No document is attached to a request

`case_document` is case-scoped. A client uploading during intake has nowhere to put the file until
a case exists, which `00d` §2.2 already records.

---

## 6. Decision — how far the sync goes

**Units 44–48, the full mirror. Decided 2026-09-16.**

Not this unit, and not a webhook bolted on to it. The programme is already specced in
`context/specs/00c-ghl-independence-programme.md` and amended by `00d` §6:

| Unit | What it lands |
|---|---|
| 44 | Tier-1 mirror tables + the correlation custom field + `pipeline` / `pipeline_stage` + `team_member_pipeline`; merges `contact_snapshot` and `client_account` |
| 45 | The outbox (partial-unique on `entity_id`, not payload), per-field ownership, `sync_drift`, paged diff audit, error classification, a sync-status surface |
| 46 | The desks move onto the mirror |
| 47 | Cut to what 46 actually reads |
| 48 | The switch, exercised in staging |

**This unit hands three requirements to that programme**, and they should be read as acceptance
criteria for it rather than as gaps here:

- §5.1 — inbound `opportunity.*` and `contact.*` become the sync engine's input, not one-off handlers.
- §4.2's lost-marker `ponytail:` — Unit 45's outbox is its fix.
- §5.2 — the request → case join is a mirror-era read.

`00d` §6.1 is the part to read first: **at-least-once webhook delivery over a non-idempotent create
produces duplicates**, which is the failure this whole flow is most exposed to, and its correlation
field is pulled forward into Unit 44 for exactly that reason.

---

## 7. Acceptance

- [x] Sign-up upserts a GHL contact and stores its id; all three states (no contact, contact only,
      contact + account) remain legal.
- [x] Picking a service opens a GHL opportunity carrying the contact, a human-readable name and the
      **service id** as a custom field.
- [x] No stage, no assignee and no monetary value are sent — GHL owns placement and Sales owns price.
- [x] Submitting writes `SUBMITTED` to the submitted field through a call that cannot carry a
      pipeline, so it cannot undo GHL's routing.
- [x] A GHL outage at submit leaves the request submitted in EvalOS and logs the missed marker.
- [x] Both fields blank leaves every existing behaviour unchanged.
- [ ] A GHL workflow routes by the service field — **yours to build (§4.3)**.
- [ ] GHL → EvalOS sync — **Units 44–48 (§6)**.
