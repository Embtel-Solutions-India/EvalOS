# EvalOS — Open Decisions

Unresolved only. A resolved item moves to `current-decisions.md` and leaves here.
House rule: every question carries a recommendation.

## Resolved earlier (the request lifecycle is gone, Unit 64)

_Q20–Q22 (PM dashboard: blocked, first-pass QC, reassignment) were answered on 2026-10-09 and left for D79–D81._

_Q2 (request status model), Q3 (Sales approval rules) and Q4 (request documents) were resolved on
2026-09-17 and left for `current-decisions.md` D33–D35. Q7 (portal deployment) left for D38, and
carried-forward item (a) for D19c. Q12 (stage moves across pipelines) was resolved on
2026-09-24 and left for D44. Q10 (conversation sidebar) left for D52 on 2026-09-28. Q8 (a client with several cases) was
settled by Unit 58 phase 3 on 2026-09-28 — Home lists every case and each opens its own page (D51).
Q30 (teams) closed on 2026-10-29 into D20: there are no teams.
On 2026-09-28 the business also answered Q6 (D23, leaving Q6b), Q11 (D54) and Q13 (D51). On 2026-09-29: Q9 → D55, Q1 → D56, §12 b → D57, §12 c → D58, expert payments → D59. Q15 and Q16 (the checklist under D60) closed the same day into D60._

## Blocking the portals

_Nothing open here: Q6b (unknown-email mail) was answered on 2026-09-28 — no mail (D23)._

## Appointments and conversations

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q14 | D55 names **guests** on an appointment, but GHL's appointment API has no guest field (one `contactId`; `users[]` is response-only; extra recipients exist only per calendar in `/calendars/{id}/notifications`). What should "guests" mean? | **Drop guests from EvalOS.** Extra people are added in GHL — the calendar's notification settings or a GHL workflow — so GHL stays the sender (invariant 14). Revisit only if GHL adds an attendee field. Spec `60` §4. |

## Staff accounts

_Q18 (mail staff a set-password link?) closed on 2026-10-30: its premise is gone — the Administrator, not
the GM, sets staff passwords (D78) — and its recommendation, no staff mail, is what is built._

## Sales attribution

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q19 | ~675 opportunities have no source in GHL and no source on their contact. Who fills them, and may EvalOS write `Lead Source` back to GHL (`scripts/source-backfill-dryrun.py --apply`, 172 won deals)? **No GHL write is approved as of 2026-10-07.** | **Fill them in GHL** (bulk edit from the script's CSV), and make Source required where opportunities are created there. Approve the `--apply` write only if a person would rather not do 172 by hand. |

## Client and expert mail

_Q17 (expert offer mail) was answered on 2026-09-30 — yes, D67, built with Unit 64c._

## Raised by the ponytail-debt upgrades (2026-10-15)

_Q23–Q25 were all answered on 2026-10-15. Q23: the Admin was view-only on Pipelines (since 2026-10-29 it also sets purpose — D78); the GM sets purpose and won
stages, so both changes are audited with the GM's brand (D78 edited). Q24: one brand only for now, so the
selling-brand-only outbox drain stays; a per-brand drain comes with Unit 25. Q25: the unused all-pipelines
`POST /board/refresh` was deleted._

## Raised by the hard-coded-values audit (2026-10-28)

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q26 | Should an Admin be able to **create new roles**? Today the nine roles are a Java enum (`Role`), each bound to a scope tier, and every route names its roles in `@PreAuthorize` and in `navigation.ts`. An Admin can give a person a role, never make one. | **Not as free-form roles.** A role's tier *is* its data access, so a role made at runtime would need a permission model (a role → tier + a set of named permissions) and every route checked against permissions instead of role names. That is a project of its own. If new roles are expected, spec that first; until then a new role is a code change plus a migration, which is safe and audited. |
| Q27 | Client and expert emails, and the portal's logo and support address, are **International Evaluations' only** (`MailTemplates.SITE_URL/LOGO_URL/SUPPORT_EMAIL`, `authService.SUPPORT_EMAIL`, `Logo.tsx`). An XpertsPortal client would get IE branding. | **Move them onto `brand`** (site URL, logo URL, support email, display name) in one migration, and read them per case's brand — before XpertsPortal clients get mail. Not urgent while one brand sells (Q24). |
| Q28 | Two smaller fixed values: the source labels written into GHL (`"EvalOS Sales Desk"` / `"EvalOS Marketing Desk"`, `GhlWriteClient`) and the business clock `America/Los_Angeles` (`BusinessCalendar.ZONE`, `meetingTime.MEETING_TZ`). | **Leave both until a second brand or office needs different ones**, then make the timezone a brand setting (SLA clock and diary together) and the labels derive from the role's display name. |
| Q29 | The GM's Team card should show Marketing **Replies** per member (2026-10-28 request). EvalOS mirrors no GHL conversation and no pipeline has a "replied" stage, so there is nothing to count. What is a reply? | **A GHL stage, if Marketing has one** (e.g. "Replied" / "Contacted"): count open deals in it per member, like Hot — no new sync. Mirroring GHL conversations to count inbound messages is a much larger read and should not be built for one column. |

## Raised by the docs sync (2026-10-30)

| #   | Question | Recommendation |
| --- | -------- | -------------- |
| Q31 | D30 says no AI anywhere, yet the client terms (D71) tell clients "We use AI-assisted tools to organise and collect your documents and to review them" and make them acknowledge "the AI assistance described above" (`client-expert/shared/src/legal/PolicySummary.tsx`, `TermsGate.tsx`). Which is true? | **Remove the AI wording from the terms** (and bump the policy version so clients re-accept) unless AI is actually planned — then D30 is edited first. A client must not accept a practice the system does not have. |
| Q32 | `team_member_pipeline` has no `brand_id` (`V54`); its brand is only reachable through `pipeline`. Every other scoped table carries one. | **Add `brand_id` via Flyway**, backfilled from `pipeline.brand_id`, NOT NULL, with a composite FK so a grant cannot cross brands. |
| Q33 | D77's `dealOf`: a case with no `ghl_opportunity_id` takes the contact's latest **won** deal, but with no won deal it falls back to their newest deal even if it is open or lost, and reads its Service Requested. | **Won deals only**; with none, change nothing and flag the case for the PM (the "Change service type" pencil stays the fix). |
| Q34 | Checklist remove (`ChecklistService.removeItem`) refuses UPLOADED / APPROVED items and the last item, but deletes items already **sent** to the client or marked MISSING / INCORRECT — the client saw them. | **Unsent items only** (`REQUIRED` and not sent); a sent item the case no longer needs is marked by status, not deleted. |
| Q35 | `/pm-notes` is dead: `PmNotesPage` is routed in `App.tsx` and has a `LeftNav` icon, but `navigation.test.ts` asserts no role may reach it; `GET /api/cases/pm-notes` serves only it. | **Delete all three** (page, icon, route) and the test line. |
| Q36 | Should client and expert emails be customised by service type? (raised by the user 2026-10-29). Today only the service **name** varies (`MailTemplates`, `serviceLabel`). | **Per-service intro lines in `MailTemplates`, keyed by `ServiceType`**, falling back to today's generic line — still static text, no editor, no new mail. |

## Carried forward from `00d` §12, still unresolved

_Item h was settled by Unit 57 (2026-09-26): the client sees the case team by name in the Client
conversation and never shares a conversation with the expert. Item i2 closed on 2026-09-29: Unit 64
removes the application table, so no lead shares it (D8). Item i3 (does the GM's board span every
brand?) is answered by the code, matching its recommendation: the board shows the brand the shell's
switcher selects — "All brands" (no `brandId`) spans every brand the caller reads, a chosen brand
narrows it, and a brand the caller cannot read yields an empty board (`CaseBoardController.board`,
`CaseBoardService.forCaller`, `BrandSwitcher`)._

| #   | Question                                                   | Recommendation                                                                                                                                      |
| --- | ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| j   | Should brand isolation move to Postgres RLS?               | No. Composite FKs plus a test forbidding `findById` on a `ScopedRepository` outside a token-authorised path.                                        |
| k   | Do the `…FromPortal` method twins collapse?                | Yes, while splitting `CaseLifecycleService`, not before.                                                                                            |
