# Implementation status

**The authoritative file is `.claude/implementation-status.md` — a table with evidence per row.
Check it before claiming anything exists or is missing.**

Build is green: backend 1006 tests, 0 failures, 4 skipped; staff SPA 127 tests plus clean tsc;
portals 30 tests plus clean tsc. All run 2026-09-16.

**The newest work is uncommitted.** Units 40, 43 and 51 and the `00d` audit are 121 changed or
untracked paths on branch `development`, whose HEAD is `dee45c6 fix(42)`. CI runs on `main` only,
so none of it has been through CI.

NOT IMPLEMENTED: conversations, outbound webhooks (Handoff C), expert accounts, the GHL mirror
(Units 44 to 48), request-stage documents, client payments (deliberate — they are GHL's).

REMOVED 2026-09-16, and do not restore it from git: the two GHL funnel screens, `/marketing/email`
and `/sales/pipeline` (Units 26 and 27). Both were GM-only under D19 — one global location, no
brand — so the audience for a marketing funnel could not open one, and the business removed the
screens rather than widen the gate. Gone with them: `MarketingPipelinePage`, `marketingApi`,
`MarketingController`, `MarketingPipelineService`, `GhlFunnelCache`, `GhlFunnelCacheRepository`,
`GhlPipelineClient.countIn` and three config properties. The `ghl_funnel_cache` TABLE survives,
orphaned: the drop has nowhere to live, because `V905` clears the table and `MigrationTreeTest`
forbids a `db/migration` script numbered 900 or above. A funnel screen returns only as a NEW
screen after Unit 25 puts the location on `brand`, and that one can admit Marketing.

BUILT 2026-09-16 (Unit 52, partial): the Client Portal ↔ GHL integration is EvalOS→GHL only.
Sign-up upserts the GHL contact and stores the id; picking a service opens the opportunity carrying
the SERVICE ID as a custom field so a GHL workflow can route it to a pipeline; submitting writes
`SUBMITTED` to a second field through `GhlWriteClient.setOpportunityFields` — custom fields only, so
it structurally cannot undo GHL's routing. Both field ids default BLANK (field omitted, nothing
breaks); the routing WORKFLOW is UI work in GHL and is not built. GHL→EvalOS sync is Units 44–48,
decided 2026-09-16 — `opportunity.update` and `contact.*` are still archived-and-acked, not routed.
Spec: `context/specs/52-client-portal-ghl-integration.md`.

BUILT 2026-09-15 (Unit 51): the GM dashboard — `GmDashboard.tsx` + `GET /api/metrics/gm`
(`hasRole('GM')`, nothing wider) + `GmOverviewService`. GM and Brand Manager no longer share
`RevenueDashboard`. `context/specs/51-gm-dashboard.md` §3 maps every widget on the business's PDF
to its source and says which cannot be computed at all.

PARTIAL and worth knowing: SALES can read no case; a client with two or more cases is refused; notifications are in-app only; the Client and Expert
Portals are in neither `docker-compose.yml` nor CI.

Operational, not code: IE's GHL sub-account was replaced on 2026-09-11 with
`WY6bW2xUCI8Tz8gw7aLJ` and no contacts were migrated. Whether the `opportunity.won` workflow was
recreated **cannot be determined from this repo** — verify it in GHL. If it was not, no case is
created by anything. `evalos.ghl.intake-pipeline-name` defaults to blank, which makes every
request start answer 502.
