# EvalOS — Open Decisions

Unresolved only. A resolved item moves to `current-decisions.md` and leaves here.
House rule: every question carries a recommendation.

## Blocking the request lifecycle

_Q2 (request status model), Q3 (Sales approval rules) and Q4 (request documents) were resolved on
2026-09-17 and left for `current-decisions.md` D33–D35. Q7 (portal deployment) left for D38, and
carried-forward item (a) for D19c. Q12 (stage moves across pipelines) was resolved on
2026-09-24 and left for D44. Q10 (conversation sidebar) left for D52 on 2026-09-28. Q8 (a client with several cases) was
settled by Unit 58 phase 3 on 2026-09-28 — Home lists every case and each opens its own page (D51).
On 2026-09-28 the business also answered Q6 (D23, leaving Q6b), Q11 (D54) and Q13 (D51). On 2026-09-29: Q9 → D55, Q1 → D56, §12 b → D57, §12 c → D58, expert payments → D59._

## Blocking the portals

_Nothing open here: Q6b (unknown-email mail) was answered on 2026-09-28 — no mail (D23)._

## Appointments and conversations

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q14 | D55 names **guests** on an appointment, but GHL's appointment API has no guest field (one `contactId`; `users[]` is response-only; extra recipients exist only per calendar in `/calendars/{id}/notifications`). What should "guests" mean? | **Drop guests from EvalOS.** Extra people are added in GHL — the calendar's notification settings or a GHL workflow — so GHL stays the sender (invariant 14). Revisit only if GHL adds an attendee field. Spec `60` §4. |

## Carried forward from `00d` §12, still unresolved

_Item h was settled by Unit 57 (2026-09-26): the client sees the case team by name in the Client
conversation and never shares a conversation with the expert._

| #   | Question                                                   | Recommendation                                                                                                                                      |
| --- | ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| i2  | Do GHL-born leads share the application table?             | Decide with Q5 in Unit 44. A GHL lead has no service.                                                                                               |
| i3  | Does the GM's board span every brand or the selected one?  | The selected brand, consistently.                                                                                                                   |
| j   | Should brand isolation move to Postgres RLS?               | No. Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path.                                        |
| k   | Do the `…FromPortal` method twins collapse?                | Yes, while splitting `CaseLifecycleService`, not before.                                                                                            |
