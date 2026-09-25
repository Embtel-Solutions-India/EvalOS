# Open decisions

**The authoritative file is `.claude/open-decisions.md`. Every entry carries a recommendation
(house rule). Resolved items move to `.claude/current-decisions.md` and leave that file.**

Unresolved as of 2026-09-17:

1. Does `MarketingLeadService` keep `upsertOpportunity`?
6. Do experts get accounts? — **waiting on a stakeholder discussion the business will hold**, not
   on a technical answer. Build nothing that assumes accounts (D23).
8. What does a client with two or more cases see? (the per-case picker)
9. Who owns an appointment, and how is employee availability modelled?
10. What is the scope of the conversation sidebar, and when? (Unit 47)
11. Who chases an abandoned `DRAFT` request now D10 moved the opportunity to submit?

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
