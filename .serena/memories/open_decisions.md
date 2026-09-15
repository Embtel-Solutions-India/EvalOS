# Open decisions

**The authoritative file is `.claude/open-decisions.md`. Every entry carries a recommendation
(house rule). Resolved items move to `.claude/current-decisions.md` and leave that file.**

Unresolved as of 2026-09-16:

1. Does `MarketingLeadService` keep `upsertOpportunity`?
2. What is the request status model? (two values today)
3. What are the Sales approval rules?
4. Which documents are required at request stage, and where do they live?
5. Do `contact_snapshot` and `client_account` merge?
6. Do experts get accounts?
7. Are the Client and Expert Portals deployed by this repo?
8. What does a client with two or more cases see?
9. Who owns an appointment, and how is employee availability modelled?
10. What is the scope of the conversation sidebar, and when?

Plus nine carried forward from `context/specs/00d-platform-audit-and-alignment.md` section 12.

**If a requirement is ambiguous, add it here — never invent behaviour.**
