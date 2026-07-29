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

1. Every case is auto-created in EvalOS from a GHL "contact created" webhook — the
   manual sales-to-PM email handoff is eliminated (0 manual handoffs). The case
   starts unpaid; payment is recorded on it, and nothing reaches an expert before
   then.
2. Every case is a structured, brand-scoped record with a stage, an owner, and
   per-stage timestamps — replacing the case-tracking Google Sheet.
3. Each brand's experts live in a structured database maintained by that brand's
   Expert Network Manager via **sheet upload + CRUD**, with rule-based match
   scoring (ranked top-3 shortlist) — replacing the expert Google Sheet.
4. Expert sign-off happens in a portal via Dropbox Sign, with every
   request-evidence / decline loop tracked on the case timeline — replacing
   email draft loops.
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

Scope tiers (attribute-based): **All** (GM) · **Brand** (Brand Manager) ·
**Team** (PM) · **Self** (Coordinator, Case Manager). The ENM is a supply-side
axis: it sees cases that *need an expert* (field only, not client identity or
content) and manages the expert roster, but not case content.

## Core User Flow

The case lifecycle, from EvalOS's point of view (EvalOS owns stages 3–7 of the
8-stage business pipeline; GHL owns 1–2 and 8):

1. **Contact created in GHL** → GHL fires the contact webhook to that brand's
   dedicated endpoint (Handoff A). EvalOS creates an **unpaid** case at **Document
   Collection**, tags it with the brand, syncs a read-only contact snapshot from
   GHL, opens the service-specific document checklist, drops the case in the brand
   pool (unassigned), and raises a `NEW_LEAD` alert to the GM/Brand Manager.
1b. **Payment recorded.** A GM or Brand Manager marks the case paid (amount +
   invoice ref); that raises the `NEW_CASE_IN_POOL` alert that says a PM is
   needed. Documents may be collected before this, but the case cannot leave
   Document Collection unpaid — everything past it engages an expert.
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
   (case returns to rematching). The expert signs via Dropbox Sign within the
   SLA; on timeout the case auto-prompts reassignment.
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

### Client Portal (draft review)
- Passwordless access via a link delivered through GHL. The client sees the
  drafted letter, approves or requests revisions, and a read receipt is recorded.
  Source-document upload is not in the client portal — documents are collected
  in Drive.

### Expert Portal
- Separate external access. Expert sees only assigned cases with draft, stated
  goal (initial petition vs. RFE), and evidence pack in one place.
- Accept / request-evidence / decline-with-reason / sign electronically via
  Dropbox Sign. Every request-evidence loop tracked on the case timeline.

### Payout Ledger (manual)
- Payout entry auto-created (status Pending) when a case reaches Delivered, tied
  to case + invoice. The responsible team member fills a manual form to mark it
  Paid/Confirmed (amount, method, reference, date). No payment-platform
  integration. An optional expert `payment_detail` field is encrypted at rest.

### Notifications (no mail server)
- Staff: in-app notification center. Clients: messages delegated to GHL
  (email/SMS/WhatsApp on event triggers). Experts: Dropbox Sign issues the
  signing request; status shown in the expert portal (portal-only nudges).

### Dashboards
- Money-in vs. delivered (open liability / refund exposure), cycle time by
  stage, expert utilization & acceptance rate, review capture — per brand, and
  cross-brand for the GM. Built off precomputed read models.

### Integrations / Handoffs
- Handoff A (inbound): GHL contact webhook (per-brand endpoint) → create unpaid
  case; a GM/Brand Manager records payment on it separately.
- Handoff B (internal): client-approved → expert portal + Dropbox Sign.
- Handoff C (outbound): Delivered → GHL review/referral trigger + payout entry.
- Dropbox Sign for e-signature. Google Drive for document links.

## Scope

### In Scope (EvalOS — back of house)
- Multi-brand tenancy and brand-scoped access control.
- Case production pipeline and the 8-stage state machine (stages 3–7).
- Expert database (ENM sheet upload + CRUD), rule-based match scoring, redacted
  CV generation.
- Client draft-review portal and expert portal + electronic sign-off (Dropbox
  Sign).
- Document checklist tracking (Drive links; files not re-hosted).
- Manual payout ledger.
- Production/expert/delivery/finance dashboards (production-side roles).
- In-app staff notifications.
- The three handoffs and the suppression sync back to GHL.

### Out of Scope (owned by GHL, or deferred)
- Marketing, social, ads, ad attribution, nurture/cold email, SEO.
- Sales pipeline, lead qualification, proposals, quoting; sales/marketing user
  roles and (by default) their dashboards — those stay in GHL.
- Invoicing and payment collection; the payment processor integration itself
  (EvalOS listens to GHL's webhook, not to Stripe/Razorpay directly).
- Any object/file storage of documents — EvalOS stores Drive links and Dropbox
  Sign references, not the files.
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
4. An expert can access an assigned case with draft + evidence + goal and
   complete sign-off via Dropbox Sign.
5. On **Delivered**, a payout ledger entry (Pending) is created and Handoff C
   posts to GHL to start the review sequence.
6. The dashboard shows collected-but-undelivered value as open liability and
   cycle time by stage, correctly scoped per brand (all brands for the GM).
7. Every state transition on every object writes an append-only audit entry, and
   no query ever returns another brand's data.
