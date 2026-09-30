# Unit 64c — Case progress emails (D58)

**Specced and built 2026-09-30.** D58 decided these four messages on 2026-09-29 and nothing
specced them. No migration.

## The four messages

Each hangs off a case event that is **already published**, once per transition, after commit.

| # | Event (publisher) | To | Subject | Button → |
|---|---|---|---|---|
| 1 | `CHECKLIST_REQUESTED` (`ChecklistService.send`, D60) | client | *Documents needed for your case* — "we need **N** documents" | client portal `/cases/{id}` |
| 2 | `DRAFT_READY_FOR_CLIENT` | client | *Your draft is ready to review* | client portal `/cases/{id}` |
| 3 | `EXPERT_SENT_FOR_SIGNING` (starts the 24h signing budget) | the case's expert | *A letter is ready for your signature* | expert portal `/case?caseId={id}` |
| 4 | `CASE_DELIVERED` | client | *Your final report is ready* | client portal `/cases/{id}` |
| 5 | `EXPERT_ASSIGNED` (every offer: assign, rematch, retake) — **D67** | the case's expert | *A new case is offered to you* + the offered fee | expert portal `/case?caseId={id}` |

Every message names the service and the case code, and has a written plain-text part.
**No credential is in any of them**: the button is a deep link, the portal bounces to sign-in and
returns to it (Unit 58 phase 4). A client or expert with no password yet uses the sign-in page's
set-password flow; the copy says so.

## Rules

- **`CaseMailListener`**, after commit, like `CasePortalAccountListener`: reads in a read-only
  transaction, **sends outside it**, never throws. A failed send is logged, not flagged: each
  screen already shows the same fact, so the mail is a nudge, not the record.
- **Portal brand only** (D7): a case in any other brand has no portal to link to.
- **Recipients.** Client: the `client_account` for (case brand, case contact); none → skip.
  Expert: `Case.expertId` → `expert.email` in the case's brand; none → skip.
- **Checklist count** = items on the case that are sent and not complete
  (`ChecklistItemStatus.isComplete`). Zero → no mail.
- **One template**, `mail/case-update.html` (heading, intro, service + case box, button, note).
  The four differ only in words, so four HTML files would be four copies of one layout.
- **Not the chases** (D58): `CHECKLIST_REMINDER` stays GHL's.
- Sender is `no-reply@`: no "reply to this"; the support address is in the footer.

## The offer mail (D67, closed Q17 the same day)

Not in D58; the business said yes. The fee is the **open** offer to the case's own expert (a stray
concurrent offer is ignored), formatted `USD 250.00` in the brand's currency, and left out when
unpriced. No answer deadline is written: none is enforced on an offer, so the mail promises none.

## Tests

`MailTemplatesTest` (all four render, no placeholder left, escaping, count in copy),
`CaseMailListenerTest` (each event → its recipient and link; the offer's fee; other brand / no account / no expert /
zero count → nothing; a failed send does not throw).
