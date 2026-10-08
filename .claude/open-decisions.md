# EvalOS — Open Decisions

Unresolved only. A resolved item moves to `current-decisions.md` and leaves here.
House rule: every question carries a recommendation.

## Blocking the request lifecycle

_Q2 (request status model), Q3 (Sales approval rules) and Q4 (request documents) were resolved on
2026-09-17 and left for `current-decisions.md` D33–D35. Q7 (portal deployment) left for D38, and
carried-forward item (a) for D19c. Q12 (stage moves across pipelines) was resolved on
2026-09-24 and left for D44. Q10 (conversation sidebar) left for D52 on 2026-09-28. Q8 (a client with several cases) was
settled by Unit 58 phase 3 on 2026-09-28 — Home lists every case and each opens its own page (D51).
On 2026-09-28 the business also answered Q6 (D23, leaving Q6b), Q11 (D54) and Q13 (D51). On 2026-09-29: Q9 → D55, Q1 → D56, §12 b → D57, §12 c → D58, expert payments → D59. Q15 and Q16 (the checklist under D60) closed the same day into D60._

## Blocking the portals

_Nothing open here: Q6b (unknown-email mail) was answered on 2026-09-28 — no mail (D23)._

## Appointments and conversations

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q14 | D55 names **guests** on an appointment, but GHL's appointment API has no guest field (one `contactId`; `users[]` is response-only; extra recipients exist only per calendar in `/calendars/{id}/notifications`). What should "guests" mean? | **Drop guests from EvalOS.** Extra people are added in GHL — the calendar's notification settings or a GHL workflow — so GHL stays the sender (invariant 14). Revisit only if GHL adds an attendee field. Spec `60` §4. |

## Staff accounts

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q18 | Staff passwords are set by the GM and handed over (D72, Unit 68). Should EvalOS instead mail a staff member a set-password link, as it does clients and experts? | **Keep it as built.** A staff credential mail is a new message under invariant 14, and the GM already knows every hire personally. Revisit if staff numbers grow past what one person hands over. |

## Sales attribution

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q19 | ~675 opportunities have no source in GHL and no source on their contact. Who fills them, and may EvalOS write `Lead Source` back to GHL (`scripts/source-backfill-dryrun.py --apply`, 172 won deals)? **No GHL write is approved as of 2026-10-07.** | **Fill them in GHL** (bulk edit from the script's CSV), and make Source required where opportunities are created there. Approve the `--apply` write only if a person would rather not do 172 by hand. |

## Client and expert mail

_Q17 (expert offer mail) was answered on 2026-09-30 — yes, D67, built with Unit 64c._

## Carried forward from `00d` §12, still unresolved

_Item h was settled by Unit 57 (2026-09-26): the client sees the case team by name in the Client
conversation and never shares a conversation with the expert. Item i2 closed on 2026-09-29: Unit 64
removes the application table, so no lead shares it (D8)._

| #   | Question                                                   | Recommendation                                                                                                                                      |
| --- | ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| i3  | Does the GM's board span every brand or the selected one?  | The selected brand, consistently.                                                                                                                   |
| j   | Should brand isolation move to Postgres RLS?               | No. Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path.                                        |
| k   | Do the `…FromPortal` method twins collapse?                | Yes, while splitting `CaseLifecycleService`, not before.                                                                                            |

## PM dashboard analytics (spec 79)

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q20 | What is a "blocked" case? No `blocked` state exists; `exception_state` has hold-awaiting-client, expert-declined-rematching and refund-requested. | **Blocked = `exception_state != NONE`.** It is the only recorded off-path state; do not infer blocks from missing documents until a rule is stated. |
| Q21 | First-pass QC rate needs a per-case QC outcome (pass / returned). None is stored. | **Show "Not tracked yet".** If wanted, add an append-only QC decision row written by the existing Final QC action — a separate decision, not part of this UI work. |
| Q22 | The brief asks for "cases requiring reassignment". No such state exists. | **Show blocked-with-`EXPERT_DECLINED_REMATCHING` only**, labelled as that, and add nothing else. |
