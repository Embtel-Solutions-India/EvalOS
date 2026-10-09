# Spec 83 — ENM "Add candidate" on GHL's opportunity form

**Status:** designed and built 2026-10-09 at the user's instruction ("1" — GHL's Add opportunity form on the
hiring pipeline). Not reviewed before the build.

## Why
The ENM's Add candidate form asked four fields (name, email, phone). The BDE's Add lead is GHL's full
opportunity form. The ENM's is the same upsert on their hiring pipeline, so it can be the same form.

## What
- `NewDealFields` gains `candidate`: stage picker (the hiring pipeline's stages, read from the board), owner
  (GHL users), expected close, no deal value, and the location's opportunity custom fields.
- Candidate fields = **every** opportunity custom field GHL's Add opportunity form shows on the hiring pipeline
  (Expert Network (Professors): New Lead, Meeting Scheduled, Meeting Done, In Process, Onboarded, Dropped), in
  GHL's order, **except** what GHL fills itself (Opportunity Id, Created on) and what EvalOS owns (Assigned
  Expert, Draft Link, SLA Status, Docs Received Date, Actual Won Date), matched by name. That includes the
  evaluator fields (Current Title, Primary Field of Expertise, Category Applying, How did you hear about us).
  Read live from the mirror; fields added in GHL later show after the known ones.
- Backend: no new route. `POST /api/marketing/leads` already allows the ENM and already carries stage, owner,
  expected close and custom fields. `GET /api/sales/opportunity-fields` and `GET /api/sales/users` now also allow
  `EXPERT_NETWORK_MANAGER` (definitions and colleague names, not client data).
- Removed the short `NewLeadForm`; `NewLeadPage` uses `NewDealFields` for both desks. Styled on theme tokens.

## Not carried (needs a GHL write change, not built)
GHL's form also has Business name, Source, Tags, Followers, Status and Value. The upsert here sends none of
them: source is set by the desk, status is forced to open, and a candidate has no value (Unit 63).

## Not verified / limits
- No browser check, and no GHL read: which custom fields the hiring pipeline's form really has is unknown from
  here. If a field shows that should not, add its name to `PRODUCTION_FIELD_NAMES` or its key to a hiring list.
- No route test for the two widened reads (none existed for them).
