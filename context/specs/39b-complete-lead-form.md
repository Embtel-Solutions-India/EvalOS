# Unit 39b — The BDE's "Add lead" is GHL's full opportunity form (D64)

**Built 2026-09-30.** Extends Unit 39 (the marketing lead desk). No migration.

## Why

The BDE (`MARKETING`) "Add lead" asked for name, email, phone and a valuation only. The lead was
already an opportunity on the BDE's own pipeline (`PipelineScope.mineForWrite`), but everything
else GHL's own "Add opportunity" form asks — stage, expected close, the intake custom fields — had
to be filled in GHL afterwards, or by Sales later.

## What

- `/marketing/leads/new` (non-ENM) renders `NewDealFields` in `lead` mode: Client (first, last,
  email, phone), Opportunity (name — optional, GHL falls back to the contact's — value, expected
  close, stage of the caller's own pipeline), and **What they need** (the same `INTAKE_FIELD_KEYS`
  as the sales form). The ENM's "Add candidate" keeps the short form (intake is about a client case).
- `POST /api/marketing/leads` gains `stageId`, `expectedCloseDate`, `customFields`.
- `GhlWriteClient.upsertOpportunity` sends `pipelineStageId` and `forecastExpectedCloseDate`
  (both in GHL's upsert body). **GHL's upsert takes no `customFields`**, so `openLead` follows the
  upsert with `setOpportunityFields` (fields-only `PUT`, blanks dropped — a repeat enquiry never
  erases an earlier answer).
- `GET /api/sales/opportunity-fields` and `GET /api/sales/users` are `SALES` or `MARKETING`
  (definitions and colleagues, not client data).
- **Owner** on both forms (`assignedTo`, a GHL user id from the mirrored location users; blank =
  GHL's round-robin), on `POST /api/marketing/leads` and `POST /api/sales/opportunities`.
  Not re-validated server-side: GHL refuses an unknown id, and any live user is a legitimate owner.

## Fix — sales "Add opportunity" answered 502 in production

`createOpportunity` (and `upsertOpportunity`) sent no `locationId`, which GHL's docs mark
required on both. GHL refused the create; `GhlHttp` maps any refusal to `GHL_UNAVAILABLE` (502).
Both bodies now carry `http.locationId()`, as the contact upsert always did. Checked field by field against
GHL's Create / Upsert Opportunity pages (2026-09-30): the create body now matches the docs
exactly (minus `forecastProbability`, deliberately). The upsert docs also list `followers`,
`isRemoveAllFollowers` and `followersActionType` as required; EvalOS sends them as a no-op
(`[]`, `false`, `add`) so a repeat enquiry never strips followers. The docs page shows
`Version: v3`; EvalOS sends `2021-07-28` everywhere (`GHL_API_VERSION`), which the live reads
accept — left unchanged. Confirm in the
production log: the refusal is logged as `GHL refused a request: HTTP <status> ... body=`.

## Still not on the form, each for the sales form's reason

Pipeline (the caller's, never a field), status (forced `open`; `won` fires Handoff A), followers,
forecast probability (GHL derives it), production-state custom fields (EvalOS owns those facts).

## Tests

`GhlWriteClientTest#anOpportunityIsUpsertedOnTheGivenPipeline` (stage + close in the body),
`GhlWriteClientTest#aCreatedOpportunityNamesTheLocationAndTheOwner` (locationId, owner, custom
fields on the create), `MarketingLeadServiceTest#theCompleteFormReachesGhl`.
