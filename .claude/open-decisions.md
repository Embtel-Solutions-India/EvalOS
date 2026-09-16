# EvalOS — Open Decisions

Unresolved only. A resolved item moves to `current-decisions.md` and leaves here.
House rule: every question carries a recommendation.

## Blocking the request lifecycle

_Q2 (request status model), Q3 (Sales approval rules) and Q4 (request documents) were resolved on
2026-09-17 and left for `current-decisions.md` D33–D35. Q7 (portal deployment) left for D38, and
carried-forward item (a) for D19c._

**Q1 — Should `MarketingLeadService` keep `upsertOpportunity`?**
It reuses the one open opportunity per contact per pipeline, so a second enquiry from the same
person overwrites the first lead's name and value. The other three write paths use
`createOpportunity`.
_Recommend:_ keep upsert for a genuine marketing _lead_ capture and document it as the one
exception; switch it if Marketing ever needs two live enquiries from one person.
_Gates:_ nothing today. Decide before Unit 46 moves the desks onto the mirror.

## Blocking the portals

**Q6 — Do experts get accounts?**
Access is a staff-minted link only. `00d` §12d recommends yes.
_Recommend:_ yes, on the Unit 42 pattern — it is built, proven and cheap to repeat.
_Status 2026-09-17:_ **waiting on a stakeholder discussion the business will hold** — not on a
technical answer. Build nothing that assumes accounts until it happens (D23).
_Gates:_ the expert's case list, payouts view and evidence access.

Expert will go to our experts.internationalevaluations.com and can sign in or up according to stakeholder decision.

**Q8 — What does a client with two or more cases see?**
`PortalCaseService.authorized` refuses. `00d` §2b item 3 names the per-case routes and a picker.
_Recommend:_ build the picker; the per-case routes already exist
(`GET /api/portal/client/cases/{caseId}`).
_Gates:_ any repeat client.

## Appointments and conversations

**Q9 — Who owns an appointment, and how is employee availability modelled?**
Availability is per GHL _calendar_; `assignedUserId` is a GHL user id with no link to a
`team_member`. Cancel, guests and blocked-off time do not exist.
_Recommend:_ add `team_member.ghl_user_id` first — it unblocks ownership, employee-wise
availability and assignment in one column — then decide cancel/guests/blocked-time as a unit.
_Gates:_ the GHL-like booking experience.
Answer: completly copy from ghl how they schedule and meeting.

**Q10 — What is the scope of the conversation sidebar, and when?**
Nothing exists. It is tier 3 of the mirror (Unit 47) and has no spec.
_Recommend:_ spec it as its own unit after Unit 45's sync engine; start read-only (list, history,
context) and add sending later, because sending is an invariant-14 question.
_Gates:_ Unit 47.
Answer no coonversation sidebar remove this.

**Q11 — Who, if anyone, chases an abandoned request now that D10 moved the opportunity to
submit?**
D10 changed on 2026-09-16: the deal opens at submit, so a client who picks a service and stops
halfway reaches no salesperson at all. The `client_application` rows are still there with
`status = DRAFT`, and **nothing reads them, sweeps them or reports them** — which is precisely the
lead-loss the old D10 existed to prevent, now accepted deliberately rather than by accident.
_Recommend:_ a staff screen before a sweep — one GM/Sales list of drafts older than 48h, sorted by
age, reading rows EvalOS already has. It needs no GHL write, no new table and no decision about
whose job the chase is, which is the part that is actually unresolved. Resist a job that opens
opportunities for abandoned drafts: that is the reverted D10 wearing a different name.
_Gates:_ none — the data is already there.

## Carried forward from `00d` §12, still unresolved

| #   | Question                                                   | Recommendation                                                                                                                                      |
| --- | ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| b   | Who owns reaching the client now Sales exists?             | Split at Handoff A: before the case the client is Sales's, from creation the Coordinator's with Sales copied.                                       |
| c   | Does EvalOS send client mail beyond the two auth messages? | Yes — four messages, as a unit, with a written invariant-14 amendment: checklist+link, draft ready, expert signing link, delivered. Not the chases. |
| g2  | Does the expert see the client's questionnaire answers?    | A curated subset — field, degree, institution, visa category, stated goal. Never budget, timeline pressure or attribution.                          |
| h   | Does the client see who is working on their case?          | A role and a date, not a name.                                                                                                                      |
| i2  | Do GHL-born leads share the application table?             | Decide with Q5 in Unit 44. A GHL lead has no service and no answers.                                                                                |
| i3  | Does the GM's board span every brand or the selected one?  | The selected brand, consistently.                                                                                                                   |
| j   | Should brand isolation move to Postgres RLS?               | No. Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path.                                        |
| k   | Do the `…FromPortal` method twins collapse?                | Yes, while splitting `CaseLifecycleService`, not before.                                                                                            |
