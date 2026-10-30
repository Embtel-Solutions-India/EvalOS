# Open decisions

**The authoritative file is `.claude/open-decisions.md`. Every entry carries a recommendation
(house rule). Resolved items move to `.claude/current-decisions.md` and leave that file.**

Unresolved as of 2026-10-30:

- **Q14 guests** — D55 names guests, GHL's appointment API has none (one contact; extra recipients only per calendar). Recommend: drop from EvalOS; add people in GHL notification settings / workflows. Spec 60 §4.
- **Q19 lead source** — ~675 opportunities have no source; no GHL write approved. Recommend: fill them in GHL from the script's CSV, make Source required there; approve `--apply` only if nobody will do 172 by hand.
- **Q26** Admin-created roles? Recommend: not free-form; roles are an enum bound to scope tiers, so it needs a permission-model spec first.
- **Q27** mail + portal branding is IE-only constants. Recommend: move site/logo/support email/display name onto `brand` before XP clients get mail.
- **Q28** GHL source labels and the `America/Los_Angeles` business clock. Recommend: leave until a second brand/office needs different ones.
- **Q29** Marketing "Replies" on the GM Team card has no data. Recommend: count a GHL "Replied" stage if Marketing has one; don't mirror conversations for one column.
- **Q31** D30 "no AI" vs the client terms' "AI-assisted tools" (`PolicySummary.tsx`, `TermsGate.tsx`). Recommend: remove the AI wording (bump the policy version) unless AI is planned.
- **Q32** `team_member_pipeline` has no `brand_id`. Recommend: add it via Flyway, backfilled from `pipeline`, composite FK.
- **Q33** D77 `dealOf` falls back to the contact's newest deal even if open or lost. Recommend: won deals only, else change nothing and flag.
- **Q34** checklist remove deletes items already sent or marked MISSING/INCORRECT. Recommend: unsent `REQUIRED` items only.
- **Q35** `/pm-notes` is dead (`PmNotesPage`, `LeftNav` icon, `GET /api/cases/pm-notes`; no role reaches it). Recommend: delete.
- **Q36** emails customised by service type (user, 2026-10-29); today only the service name varies. Recommend: per-service intro lines in `MailTemplates` keyed by `ServiceType`.
- Carried forward from `00d` §12: **j** Postgres RLS? No — composite FKs + the `findById` test. **k** collapse the `…FromPortal` twins? Yes, while splitting `CaseLifecycleService`.

**Resolved — do not re-open from an old note:**

- 2026-10-30: **Q18** staff set-password mail — premise gone (the Admin sets staff passwords, D78), no staff mail is built. **i3** GM board brands — answered by code: "All brands" spans every brand, the switcher narrows (`CaseBoardController`).
- 2026-10-29: **Q30** teams → D20, there are none.
- 2026-10-15: Q23 → D78 (since 2026-10-29 the Admin **and** the GM set pipeline purpose; won stages are the GM's); Q24 one brand, selling-brand-only drain stays; Q25 `POST /board/refresh` deleted.
- 2026-10-09: Q20–Q22 → D79–D81.
- 2026-09-30: Q17 expert offer mail → D67.
- 2026-09-29: Q15, Q16 → D60; Q1 → D56, Q9 → D55, §12 b → D57, §12 c → D58, expert payments → D59; i2 closed (Unit 64 removed the application table).
- 2026-09-28: Q6 → D23, Q6b no mail, Q11 → D54, Q13 → D51, Q8 → Unit 58 phase 3, Q10 → D52 (no GHL conversation sidebar), outbound webhooks → D53.
- 2026-09-26: h → Unit 57 (client sees the case team by name, never shares a conversation with the expert).
- 2026-09-25: g2 → moot (D13, Unit 55).
- 2026-09-24: Q12 → D44 (cross-pipeline stage move refused server-side).
- 2026-09-17: Q2 / Q3 → D35 (no EvalOS review state); Q7 → D38; §12 a → D19c (Sales reads no case).
- 2026-09-16: Q5 → **D32** — `client_account` and `contact_snapshot` stay two tables, joined by `client_account.contact_id` (`V55`); the merge was not taken.

**If a requirement is ambiguous, add it here — never invent behaviour.**
