# Unit 64b — Case-opened emails (D65)

**Built 2026-09-30.** Extends Unit 64 (the portal account is born with the case). No migration.

## Why

Unit 64 sent the generic "Set your password" mail when a case opened. Three gaps:

1. It named nothing — no case, no service, no next step.
2. Its link lived `credential-ttl` (30 minutes) although the client never asked for it; most
   clients opening it later hit a dead link.
3. A client who already had a password got nothing when their next case opened.

## What

`CASE_CREATED` (after commit) → `CasePortalAccountListener` reads the case (code, service) →
`ClientAccountService.openForCase(contact, caseCode, service)`:

| Account state | Mail | Template | Link |
|---|---|---|---|
| new, or existing without a password | **Your case has started — set your password** | `mail/case-started.html` | set-password, **7 days** (`CASE_LINK_TTL`), fresh token every case |
| existing with a password | **Your new case has started** | `mail/case-signin.html` | the portal's sign-in page, no credential |
| no email / no GHL id / other contact | none; the case is flagged (unchanged) | — | — |

- Both name the service (`CREDENTIAL_EVALUATION` → "Credential evaluation") and the case code, and
  carry a written plain-text part.
- The case-started link skips `issueCredential`'s cooldown: that bounds an unauthenticated route;
  this runs once per case (the webhook gateway dedupes redeliveries by event id), and an
  outstanding 30-minute sign-in link must not swallow the case mail.
- A failed case-started mail flags the case `MAIL_UNAVAILABLE` (unchanged). A failed sign-in mail
  is only logged: the client can already reach the case.
- The sender is `no-reply@`, so neither mail says "reply"; both point at the support address.
- The generic set / reset password mails are unchanged and still serve sign-in and forgot-password.

## Tests

`MailTemplatesTest` (no surviving placeholder, both parts, escaping, `theCaseMailsNameTheCase`),
`ClientAccountServiceTest` (case-started names the case and its token lives 7 days; a client with a
password gets the sign-in mail and no token), `CasePortalAccountListenerTest#theServiceIsNamedInWords`.
