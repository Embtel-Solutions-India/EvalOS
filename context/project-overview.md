# EvalOS — Project Overview

## Overview

EvalOS is the internal production CRM ("back of house") for a **multi-brand**
credential-evaluation business — brands include **International Evaluations** and
**XpertsPortal** — that produces two signed deliverables (credential evaluations
and expert opinion letters) by matching paying customers (immigration attorneys,
employers, staffing firms, individuals) with US university professors and
industry experts. GoHighLevel (GHL) stays the "front of house" and owns lead
capture, nurture, quoting, invoicing, payment collection, and post-delivery
review campaigns. EvalOS takes over the instant a contact lands in GHL: it turns
each enquiry into a structured, brand-scoped case, records the payment against
it, drives it through production,
routes it to an expert for electronic sign-off, delivers the signed letter, and
records the expert payout. It replaces three fragile tools that hold the truth
today — a case-tracking Google Sheet, an expert Google Sheet, and a WhatsApp
group for payout details — with one shared, multi-brand system.

Every brand is a tenant of the same system: one shared database, row-level
tenancy by `brand_id`, and access scoped so a brand's staff only ever see their
own brand. The GM sees across all brands.

## Goals

1. Every case is auto-created in EvalOS from a GHL **won-opportunity** webhook — the
   manual sales-to-PM email handoff is eliminated (0 manual handoffs). GHL has
   already collected by then, so the case starts **paid**; nobody records payment by
   hand, and nothing reaches an expert unpaid.
2. Every case is a structured, brand-scoped record with a stage, an owner, and
   per-stage timestamps — replacing the case-tracking Google Sheet.
3. Each brand's experts live in a structured database maintained by that brand's
   Expert Network Manager via **sheet upload + CRUD**, with rule-based match
   scoring (ranked top-3 shortlist) — replacing the expert Google Sheet.
4. Expert sign-off happens in a portal — the expert downloads the letter, signs it
   in whatever tool they already use, and uploads it back — with every
   request-evidence / decline loop tracked on the case timeline, replacing
   email draft loops. **No e-signature provider**: a scanned wet signature is the
   norm for this document, and the provenance record is a hash pair plus the
   expert's own attestation.
5. Expert payouts run off a **manual payout ledger** tied to cases and invoices,
   filled in by the responsible team member — replacing payout details posted in
   a WhatsApp group. No payment-platform integration.
6. On "Delivered," EvalOS fires Handoff C back to GHL to start the review
   sequence — instrumenting the #2 health metric (the Google review).
7. Dashboards report money-in vs. delivered (collected-but-undelivered shown as
   open liability), cycle time by stage, expert utilization/acceptance rate, and
   review capture rate — per brand and, for the GM, across all brands.

## Roles

Production hierarchy: **GM** (all brands) → **Brand Manager** (one brand) →
**Project Manager** (a team's cases) → { **Project Coordinator**, **Case
Manager**, **Expert Network Manager** }. Sales & Marketing roles live in GHL and
report to the Brand Manager; EvalOS does not build sales/marketing user roles.
There is no "Head of Evaluations" role and no intern tier in v1.

**Reading the CRM build spec against this:** where that document says "Head of Eval"
— the day-3 document escalation, the unassigned-after-4h alert, the revenue
confirmation, the "Head of Eval dashboard" — **read GM**. That is a decision, not a
gap: the GM is already the cross-brand oversight role, so the sixth role does the
job without a seventh existing. The build spec's intern restrictions are recorded as
a deferred open question in `progress-tracker.md`; nothing is designed for them.

Scope tiers (attribute-based): **All** (GM) · **Brand** (Brand Manager) ·
**Team** (PM) · **Self** (Coordinator, Case Manager). The ENM is a supply-side
axis: it sees cases that *need an expert* (field only, not client identity or
content) and manages the expert roster, but not case content.

## Core User Flow

The case lifecycle, from EvalOS's point of view (EvalOS owns stages 3–7 of the
8-stage business pipeline; GHL owns 1–2 and 8):

1. **Opportunity marked Won in GHL** → GHL fires the won-opportunity webhook to
   that brand's dedicated endpoint (Handoff A). Because GHL has already invoiced
   and collected, that single event is both the reason the case exists and the
   proof it was paid. EvalOS creates a **paid** case at **Document Collection**,
   tags it with the brand, records the opportunity's amount and id, syncs a
   read-only contact snapshot from GHL, opens the service-specific document
   checklist, drops the case in the brand pool (unassigned), and raises the
   `NEW_CASE_IN_POOL` alert to the **PM and Project Coordinator** — a PM is needed.

   Nobody in EvalOS records payment by hand; GHL is the only source of that fact.
   Leads stay in GHL, so EvalOS never sees a case before the money is in.
2. **Document collection.** The Project Coordinator tracks required vs. uploaded
   documents (files live in Google Drive; the case holds the Drive link) and
   chases the client via GHL. When the package is complete, the Coordinator
   marks docs complete and the case moves to the PM.
3. **Expert selection & assignment.** The PM reads the documents, writes strategy
   notes, and selects the best available expert (a rule-based match engine
   surfaces the top 3; the PM confirms). The PM assigns the case to a Case
   Manager with the notes attached.
4. **Drafting & client review.** The Case Manager builds the draft and submits it
   to the PM. PM approves or returns with inline comments (loop). On approval,
   the client reviews it in the **client draft-review portal** (link delivered
   via GHL) and approves or requests revisions (loop). Read receipts are tracked.
5. **Expert sign-off.** On client approval, the Case Manager sends the final
   letter to the assigned expert (Handoff B). The expert accepts, requests
   missing evidence (which auto-opens a client task), or declines with a reason
   (case returns to rematching). The expert downloads the letter, signs it, and
   uploads the signed PDF back within the SLA; on timeout the case auto-prompts
   reassignment.
6. **Final QC.** The PM runs the QC checklist on the signed letter and marks the
   case **Delivered**.
7. **Delivery return (Handoff C).** "Delivered" fires an outbound call to GHL to
   start the review sequence and referral track, and creates the payout ledger
   entry (status Pending) at the same moment.
8. **Close & retention.** The Coordinator confirms client receipt and closes the
   case. Retention/win-back runs in GHL.

## Features

### Case Production Pipeline
- Structured, brand-scoped case record: client, attorney/company, service type,
  visa category, Google Drive link, invoice reference, campaign attribution
  (inherited from GHL), stage, owner, per-stage timestamps, SLA status.
- 8-stage canonical pipeline; EvalOS owns stages 3–7 via the internal state
  machine `DOC_COLLECTION → EXPERT_ASSIGNMENT → DRAFT_GENERATION →
  EXPERT_SIGNING → FINAL_DELIVERY → CLOSED`, plus exception states (On Hold —
  Awaiting Client, Expert Declined — Rematching, Refund Requested).
- Pool intake then assignment: new cases land in the brand pool; GM/Brand
  Manager assigns to a PM; PM assigns to a Case Manager.
- Role-based, brand-scoped queues. Append-only, non-editable audit trail on
  every case (and every object).

### Expert Database & Matching Engine
- Brand-scoped expert profiles maintained by the brand's ENM via **bulk sheet
  upload (CSV/XLSX) + full CRUD**: field tags, letter types, institution,
  tier, availability, quality score, turnaround/decline history.
- Rule-based match scoring → ranked shortlist (field match, letter-type
  experience, acceptance rate, current load). Assist mode: humans confirm.
  (AI-enhanced suggestion/anomaly detection is a later phase.)
- Template-generated redacted CV (name/institution/contact stripped); full
  profile releases on payment.

### Client Portal (draft review) — built in Unit 14
- Passwordless access via a link delivered through GHL. The client sees the
  drafted letter, approves or requests revisions, and a read receipt is recorded.
- **Source-document upload is in the portal, from Unit 21.** This line used to say
  it was not. The client uploads against their own checklist, one file per required
  item, and the bytes stream straight through to the case's Drive folder — so the
  documents still live in Drive, but the client gets them there through EvalOS
  instead of outside it. The Coordinator reviews what arrives and flags anything
  missing or incorrect. **No AI review** — that is a human step by decision.
- **The link is minted in EvalOS and, for now, sent by hand.** Whether GHL can
  deliver it on an EvalOS event is still open, so staff copy it off the case page;
  Unit 18 dispatches it automatically if the answer is yes. Nothing else changes.
- **The client sees a whitelist, and their approval is recorded as theirs.** The
  view is their name, the service, the case reference, the draft, the version, the
  approval state and the anonymous expert profile — no money, no notes, no expert
  identity, no assignments, and never the documents folder. Their approval writes an
  audit entry naming *the client*, which is what makes Handoff B their act rather
  than a staff member's transcription of it.
- **Two read facts, not one:** whether the client has ever opened the draft
  (stamped once) and when they last looked (moved on every visit).

### Expert Portal
- Separate external access. Expert sees only assigned cases with draft, stated
  goal (initial petition vs. RFE), and evidence pack in one place.
- Accept / request-evidence / decline-with-reason, then **download the letter and
  upload it back signed**. Every request-evidence loop tracked on the case timeline.

### Payout Ledger (manual)
- Payout entry auto-created (status Pending) when a case reaches Delivered, tied
  to case + expert, prefilled from the expert's standard fee. No payment-platform
  integration. An optional expert `payment_detail` field is encrypted at rest.
- **The expert charges per draft and is paid weekly** (Unit 16b). One transfer covers
  every draft settled that week and carries one reference, so a **payment is its own
  record** and the payout rows point at it: the ENM ticks the week's drafts, sends the
  money outside EvalOS, and records amount, method, reference, date and notes once.
  The payment's amount must equal the sum of the rows it settles.
- **The ENM records payouts**, alongside the GM and Brand Manager. The ENM sends the
  transfer, so the ENM records it; every record names who did it and writes an audit
  entry. This is a decision taken, not an assumption — Unit 16 had restricted it to
  GM/Brand Manager and flagged the widening as the business's call.
- **No retainers.** Experts are not paid a standing weekly rate and are not stationed
  on client accounts. Considered and rejected in 16b; it would also have put client
  identity on the ENM's screens, which the role does not get (see *Roles*).

### Notifications (no mail server)
- Staff: in-app notification center. Clients: messages delegated to GHL
  (email/SMS/WhatsApp on event triggers). Experts: a scoped portal link shared by the
  Case Manager; status shown in the expert portal (portal-only nudges).
- **The client half is an open decision.** Every client- and expert-facing touchpoint
  is listed in `context/process-automation.md` with its channel marked *pending*:
  GHL delivers them (today's design) or EvalOS sends mail itself, which would reverse
  the no-mail-server rule and bring in deliverability, bounces, unsubscribe and a
  suppression list. Nothing is built either way. Settled already: the expert signing
  retention and reviews (GHL). **The expert's signing link is not settled** — the signature provider
  used to email it, and since there is no provider the Case Manager sends it by hand until the channel
  is decided.

### Dashboards
- Money-in vs. delivered (open liability / refund exposure), cycle time by
  stage, expert utilization & acceptance rate, review capture — per brand, and
  cross-brand for the GM. Built off precomputed read models.

### Integrations / Handoffs
- Handoff A (inbound): GHL won-opportunity webhook (per-brand endpoint) → create a
  paid case in the PM/Coordinator pool. GHL is the only source of the payment fact.
- Handoff B (internal): client-approved → expert portal → signed letter uploaded back.
- Handoff C (outbound): Delivered → GHL review/referral trigger + payout entry.
- **Google Drive is the only external system in the production path** — document
  links, and the folder the signed letter is filed into. No e-signature provider.

## Scope

### In Scope (EvalOS — back of house)
- Multi-brand tenancy and brand-scoped access control.
- Case production pipeline and the 8-stage state machine (stages 3–7).
- Expert database (ENM sheet upload + CRUD), rule-based match scoring, redacted
  CV generation.
- Client draft-review portal (with document upload) and expert portal (with signed-letter
  upload).
- Document checklist tracking (Drive links; files not re-hosted).
- Manual payout ledger.
- Production/expert/delivery/finance dashboards (production-side roles).
- In-app staff notifications.
- The three handoffs and the suppression sync back to GHL.
- **Marketing *readings*, GM-only (Units 24 and 26)**: GHL's Google Ads funnel and
  its email marketing funnel, stage by stage — deals, value and source. Windows onto
  GHL, not a marketing feature: nothing is created, moved, priced, sent, or written
  back, and nothing is stored. They are the only screens in EvalOS that are not
  brand-scoped, because the single GHL location they read has no mapping to a brand.
  **Two screens, one decision**: a further *reading* of a pipeline in that location is
  the same question already answered; anything that would make EvalOS *run* marketing
  is not.

### Out of Scope (owned by GHL, or deferred)
- Marketing, social, ads, ad attribution, nurture/cold email, SEO. **Running any of
  them.** Units 24 and 26 read two GHL funnels onto GM screens and that is the whole
  of it — no campaign, audience, spend, creative or attribution feature is in scope,
  now or later, and there is no write path back into a GHL pipeline. Note what the
  second screen did and did not settle: reading **another pipeline** in the location
  EvalOS already reads is decided; *sending* an email from EvalOS is still out, and
  the email funnel being visible here changes nothing about that.
- Sales pipeline, lead qualification, proposals, quoting; sales/marketing user
  roles. Their **dashboards** stay in GHL too, with the exception above: the open
  question that defaulted to "GHL-native" is now answered as *read-only GM funnel
  views in EvalOS over pipelines in the one configured location, everything else in
  GHL*.
- Invoicing and payment collection; the payment processor integration itself
  (EvalOS listens to GHL's webhook, not to Stripe/Razorpay directly).
- Any object/file storage of documents — EvalOS stores Drive file ids and links, not
  the files. Uploads (client documents, the signed letter) **stream through** to Drive
  and are never persisted here.
- Any payment-platform / disbursement rail — the payout ledger is manual.
- Delivery of the review/retention email sequences — GHL runs them.
- Jurisdiction-specific data-protection features (GDPR/CCPA) — out of scope v1.

## Success Criteria

1. A GHL payment webhook on a brand's endpoint creates a brand-tagged case at
   **Document Collection** with a synced read-only contact snapshot and an
   opened document checklist, drops it in the brand pool, and notifies the
   GM/Brand Manager — with no manual entry, and idempotently.
2. A Brand Manager can assign a pooled case to a PM; the PM can assign it to a
   Case Manager and pick an expert from the ranked shortlist; a Case Manager
   sees only their own docket, within their brand.
3. A client can open the draft-review portal via a GHL-delivered link, approve
   or request revisions, and the read receipt is recorded.
4. An expert can access an assigned case with draft + evidence + goal, and complete
   sign-off by uploading the signed letter back through their portal.
5. On **Delivered**, a payout ledger entry (Pending) is created and Handoff C
   posts to GHL to start the review sequence.
6. The dashboard shows collected-but-undelivered value as open liability and
   cycle time by stage, correctly scoped per brand (all brands for the GM).
7. Every state transition on every object writes an append-only audit entry, and
   no query ever returns another brand's data.
