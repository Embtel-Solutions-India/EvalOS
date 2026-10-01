# Open decisions

**The authoritative file is `.claude/open-decisions.md`. Every entry carries a recommendation
(house rule). Resolved items move to `.claude/current-decisions.md` and leave that file.**

Unresolved as of 2026-09-17:


**Resolved 2026-09-29:** ~~Q15~~, ~~Q16~~ → D60 (evidence-request item unsent like any other; CM sets status too). **Open: Q14 guests** — D55 names guests, GHL's appointment API has none (one contact; extra recipients only per calendar). Recommend: drop from EvalOS; add people in GHL notification settings / workflows. Spec 60 §4.

**Resolved 2026-09-29:** ~~1~~ → D56, ~~9~~ → D55, ~~§12 b~~ → D57, ~~§12 c~~ → D58, expert payments → D59.

**Resolved 2026-09-28:** ~~6b. unknown-email mail~~ → none (experts sign up only once hired). ~~6. expert sign-up~~ → D23; ~~11. abandoned requests~~ → D54 (retired by Unit 64); ~~13. inline PDF~~ → D51 (view first); outbound webhooks → D53 (dropped). ~~8. several cases~~ → Unit 58 phase 3, Home lists every case (D51). ~~10. conversation sidebar~~ → **D52**: none; GHL conversations stay in GHL.

**Resolved 2026-09-26:** ~~h does the client see who works their case~~ → Unit 57: the case team by name in
the Client conversation; the client never shares a conversation with the expert.

**Resolved 2026-09-25:** ~~g2 does the expert see the answers~~ → moot, no questionnaire (D13, Unit 55).

**Resolved 2026-09-24:** ~~Q12 cross-pipeline stage move~~ → **D44**: refused server-side.

**Resolved 2026-09-17 — do not re-open from an old note:**

- ~~2. request status model~~ / ~~3. Sales approval rules~~ → **D35**: no EvalOS review state at
  all. Two statuses is the answer. Review is a GHL pipeline stage.
- ~~4. request-stage documents~~ → **D33**: uploaded with the request, keyed by contact,
  carried into `case_document` at Handoff A. Unit 53.
- ~~5. merge `contact_snapshot` + `client_account`~~ → **D32** (2026-09-16): two tables, joined.
- ~~7. portal deployment~~ → **D38**: DevOps's, outside this repository.
- ~~00d §12(a) does SALES get case reads~~ → **D19c**: none. Their world ends at won.

Plus eight carried forward from `context/specs/00d-platform-audit-and-alignment.md` section 12.

**If a requirement is ambiguous, add it here — never invent behaviour.**

**Closed 2026-09-29:** carried-forward **i2** (GHL leads and the application table) — Unit 64 removes the table.

**Q18 (2026-10-02):** staff passwords are set by the GM (D72). Mail a staff set-password link instead? Recommended: keep as built — a new mail under invariant 14, and the GM knows every hire.
