# Current decisions

**The authoritative file is `.claude/current-decisions.md` (31 numbered decisions). Read it.**

The ones most often violated from memory:

- A GHL Contact is not an EvalOS ClientAccount. A contact existing is **not** proof of portal
  registration. All three states (no contact and no account; contact only; both) are legal.
- One GHL Contact, many opportunities. A repeat request makes a new **opportunity**, never a
  second contact.
- **Request is not Case.** `client_application` is the request, `evalos_case` is production work,
  and the GHL opportunity is the commercial process between them.
- **The opportunity opens when the client PICKS a service, not when they submit** (D10) — a
  half-finished request is still a lead Sales can ring. Asked on 2026-09-16 to move it to submit;
  reaffirmed, and submit now writes a `SUBMITTED` custom field on the same opportunity instead.
- **EvalOS sends GHL the requested SERVICE ID as an opportunity custom field and nothing else about
  placement** (D10a). A GHL workflow routes the deal to a pipeline from it. Mapping services onto
  pipelines is a business rule and lives in the workflow — the same ruling that deleted
  `hot-stage-name`. No stage, no assignee, no price: EvalOS holds no price list.
- **A case is created only by the `opportunity.won` webhook.** Build-enforced by
  `DomainInvariantsTest`. Never add a second creator.
- Invoicing is GHL's, full stop. EvalOS reads invoices and raises none.
- EvalOS sends **exactly two** emails: set-password and reset-password. Any other mail is a new
  decision.
- Brand-scoped by default. Append-only truth for `audit_event` and `opportunity_note`, enforced by
  database triggers.
- **The one scoping exception is the GHL location, and it costs screens rather than being
  widened.** `evalos.ghl.location-id` is one global sub-account belonging to no brand, so every
  screen over it is GM-only. On 2026-09-16 the two GHL funnel screens were **removed** for exactly
  that reason — their audience was Marketing, who could not open them — instead of admitting a
  brand-locked role to an unattributable figure. `/api/opportunities/board` is the one narrowed
  case: `evalos.ghl.sales-brand` names the owning brand and assignment refuses any other, so its
  brand *is* provable and SALES/MARKETING reach it.
- Experts have **no accounts** — a staff-minted link is the only way in.

Changed a decision? Edit `.claude/current-decisions.md`, then this memory. Never leave a
contradicting note beside the old one.
