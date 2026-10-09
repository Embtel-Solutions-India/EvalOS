# Spec 83 — ENM "Add candidate" on GHL's opportunity form

**Status:** designed and built 2026-10-09 at the user's instruction ("1" — GHL's Add opportunity form on the
hiring pipeline). Not reviewed before the build.

## Why
The ENM's Add candidate form asked four fields (name, email, phone). The BDE's Add lead is GHL's full
opportunity form. The ENM's is the same upsert on their hiring pipeline, so it can be the same form.

## What
- `NewDealFields` gains `candidate`: stage picker (the hiring pipeline's stages, read from the board), owner
  (GHL users), expected close, no deal value, and the location's opportunity custom fields.
- Candidate fields = an allowlist, matched by name: Current Title, Primary Field of Expertise and Category
  Applying (the "Join as evaluator" website fields), then Lead Source, both "How did you hear about us?" fields
  and Message. Every other opportunity field is about a client's case and is left off. Read live from the
  mirror; a field added in GHL later must be added to `CANDIDATE_FIELDS` to show.
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
