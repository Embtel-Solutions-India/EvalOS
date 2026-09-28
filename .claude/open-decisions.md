# EvalOS — Open Decisions

Unresolved only. A resolved item moves to `current-decisions.md` and leaves here.
House rule: every question carries a recommendation.

## Blocking the request lifecycle

_Q2 (request status model), Q3 (Sales approval rules) and Q4 (request documents) were resolved on
2026-09-17 and left for `current-decisions.md` D33–D35. Q7 (portal deployment) left for D38, and
carried-forward item (a) for D19c. Q12 (stage moves across pipelines) was resolved on
2026-09-24 and left for D44. Q10 (conversation sidebar) left for D52 on 2026-09-28. Q8 (a client with several cases) was
settled by Unit 58 phase 3 on 2026-09-28 — Home lists every case and each opens its own page (D51).
On 2026-09-28 the business also answered Q6 (D23, leaving Q6b), Q11 (D54) and Q13 (D51)._

**Q1 — Should `MarketingLeadService` keep `upsertOpportunity`?**
It reuses the one open opportunity per contact per pipeline, so a second enquiry from the same
person overwrites the first lead's name and value. The other three write paths use
`createOpportunity`.
_Recommend:_ keep upsert for a genuine marketing _lead_ capture and document it as the one
exception; switch it if Marketing ever needs two live enquiries from one person.
_Gates:_ **the gate moved on 2026-09-17.** It read "decide before Unit 46 moves the desks onto
the mirror". Unit 46 moved the desks' _edits_ and left _creates_ calling GHL inline (D46) — and
upsert lives in `openLead`, which is a create. Nothing in 46 turned on the answer, so the question
is now gated on whichever unit moves creates onto the queue.

## Blocking the portals

**Q6b — What does an expert sign-up with an unknown email receive?**
Q6 is decided (D23): a known email gets a set-password mail. The business will supply the wording
for the other case.
_Recommend:_ a short "we could not find you on our expert panel — reply to <panel address> to
join" mail, sent through the same channel so both cases cost the same and look the same on screen.
_Gates:_ only that mail; the path sends nothing until the text arrives.

## Appointments and conversations

**Q9 — Who owns an appointment, and how is employee availability modelled?**
Availability is per GHL _calendar_; `assignedUserId` is a GHL user id with no link to a
`team_member`. Cancel, guests and blocked-off time do not exist.
_Recommend:_ add `team_member.ghl_user_id` first — it unblocks ownership, employee-wise
availability and assignment in one column — then decide cancel/guests/blocked-time as a unit.
_Gates:_ the GHL-like booking experience.
Answer: completly copy from ghl how they schedule and meeting.

## Carried forward from `00d` §12, still unresolved

_Item h was settled by Unit 57 (2026-09-26): the client sees the case team by name in the Client
conversation and never shares a conversation with the expert._

| #   | Question                                                   | Recommendation                                                                                                                                      |
| --- | ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| b   | Who owns reaching the client now Sales exists?             | Split at Handoff A: before the case the client is Sales's, from creation the Coordinator's with Sales copied.                                       |
| c   | Does EvalOS send client mail beyond the two auth messages? | Yes — four messages, as a unit, with a written invariant-14 amendment: checklist+link, draft ready, expert signing link, delivered. Not the chases. |
| i2  | Do GHL-born leads share the application table?             | Decide with Q5 in Unit 44. A GHL lead has no service.                                                                                               |
| i3  | Does the GM's board span every brand or the selected one?  | The selected brand, consistently.                                                                                                                   |
| j   | Should brand isolation move to Postgres RLS?               | No. Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path.                                        |
| k   | Do the `…FromPortal` method twins collapse?                | Yes, while splitting `CaseLifecycleService`, not before.                                                                                            |
