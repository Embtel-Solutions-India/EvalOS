# EvalOS — Open Decisions

Unresolved only. A resolved item moves to `current-decisions.md` and leaves here.
House rule: every question carries a recommendation.

## Blocking the request lifecycle

**Q1 — Should `MarketingLeadService` keep `upsertOpportunity`?**
It reuses the one open opportunity per contact per pipeline, so a second enquiry from the same
person overwrites the first lead's name and value. The other three write paths use
`createOpportunity`.
*Recommend:* keep upsert for a genuine marketing *lead* capture and document it as the one
exception; switch it if Marketing ever needs two live enquiries from one person.
*Gates:* nothing today. Decide before Unit 46 moves the desks onto the mirror.

**Q2 — What is the request status model?**
`client_application.status` has two values. Sales can read an application but cannot act on it.
A "SALES REVIEW" step in the target lifecycle needs states.
*Recommend:* `DRAFT → SUBMITTED → IN_REVIEW → ACCEPTED | RETURNED | REJECTED`, with `RETURNED`
reopening editing for the client. Keep the state on the application; the opportunity stage stays
GHL's.
*Gates:* the Sales review screen, and any request-scoped document work.

**Q3 — What are the Sales approval rules?**
Who may move a request out of review, whether an approval is required before an opportunity can
be marked won, and whether a rejection closes the opportunity.
*Recommend:* any SALES member whose pipeline owns the opportunity; approval is advisory, not a
gate on winning — Handoff A must stay the single door and must not acquire a second precondition.
*Gates:* Q2.

**Q4 — Which documents are required at request stage, and where do they live?**
Nothing attaches a file to a request today, and Unit 43 deliberately deferred it
(`43` §5: *"a missing document is a thing Sales chases, not a wall the funnel puts in front of a
lead"*). The target lifecycle puts DOCUMENT SUBMISSION before REQUEST CREATED.
*Recommend:* make them **optional at request stage** — the same posture, with a place to put them.
One table (`application_document`) mirroring `case_document`'s shape, its own S3 prefix keyed by
`client_account_id`, and a carry-forward into `case_document` when the case is created.
Do not gate submit on completeness.
*Gates:* the request-document unit.

## Blocking the portals

**Q6 — Do experts get accounts?**
Access is a staff-minted link only. `00d` §12d recommends yes.
*Recommend:* yes, on the Unit 42 pattern — it is built, proven and cheap to repeat.
*Gates:* the expert's case list, payouts view and evidence access.

**Q7 — Are the Client and Expert Portals deployed by this repository?**
Neither has a Dockerfile, a compose service or a CI job. Something must be serving them, or
nothing is.
*Recommend:* add both to `docker-compose.yml` and CI on the `frontend/` pattern, or record where
they are actually deployed from.
*Gates:* any client-facing release.

**Q8 — What does a client with two or more cases see?**
`PortalCaseService.authorized` refuses. `00d` §2b item 3 names the per-case routes and a picker.
*Recommend:* build the picker; the per-case routes already exist
(`GET /api/portal/client/cases/{caseId}`).
*Gates:* any repeat client.

## Appointments and conversations

**Q9 — Who owns an appointment, and how is employee availability modelled?**
Availability is per GHL *calendar*; `assignedUserId` is a GHL user id with no link to a
`team_member`. Cancel, guests and blocked-off time do not exist.
*Recommend:* add `team_member.ghl_user_id` first — it unblocks ownership, employee-wise
availability and assignment in one column — then decide cancel/guests/blocked-time as a unit.
*Gates:* the GHL-like booking experience.

**Q10 — What is the scope of the conversation sidebar, and when?**
Nothing exists. It is tier 3 of the mirror (Unit 47) and has no spec.
*Recommend:* spec it as its own unit after Unit 45's sync engine; start read-only (list, history,
context) and add sending later, because sending is an invariant-14 question.
*Gates:* Unit 47.

**Q11 — Who, if anyone, chases an abandoned request now that D10 moved the opportunity to
submit?**
D10 changed on 2026-09-16: the deal opens at submit, so a client who picks a service and stops
halfway reaches no salesperson at all. The `client_application` rows are still there with
`status = DRAFT`, and **nothing reads them, sweeps them or reports them** — which is precisely the
lead-loss the old D10 existed to prevent, now accepted deliberately rather than by accident.
*Recommend:* a staff screen before a sweep — one GM/Sales list of drafts older than 48h, sorted by
age, reading rows EvalOS already has. It needs no GHL write, no new table and no decision about
whose job the chase is, which is the part that is actually unresolved. Resist a job that opens
opportunities for abandoned drafts: that is the reverted D10 wearing a different name.
*Gates:* none — the data is already there.

## Carried forward from `00d` §12, still unresolved

| # | Question | Recommendation |
|---|---|---|
| a | Does SALES get case reads, and how much? | Exactly the client's projection, scoped by opportunity-in-my-pipeline. Never the draft, notes, expert or checklist detail. |
| b | Who owns reaching the client now Sales exists? | Split at Handoff A: before the case the client is Sales's, from creation the Coordinator's with Sales copied. |
| c | Does EvalOS send client mail beyond the two auth messages? | Yes — four messages, as a unit, with a written invariant-14 amendment: checklist+link, draft ready, expert signing link, delivered. Not the chases. |
| g2 | Does the expert see the client's questionnaire answers? | A curated subset — field, degree, institution, visa category, stated goal. Never budget, timeline pressure or attribution. |
| h | Does the client see who is working on their case? | A role and a date, not a name. |
| i2 | Do GHL-born leads share the application table? | Decide with Q5 in Unit 44. A GHL lead has no service and no answers. |
| i3 | Does the GM's board span every brand or the selected one? | The selected brand, consistently. |
| j | Should brand isolation move to Postgres RLS? | No. Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path. |
| k | Do the `…FromPortal` method twins collapse? | Yes, while splitting `CaseLifecycleService`, not before. |
