# Unit 59 — Expert accounts

**Decided 2026-09-28 by the business (D23, closing Q6):** an expert signs up with their email;
EvalOS checks it against the expert database; a known email gets a set-password email, an unknown
one gets a different email whose wording the business will supply (Q6b). **Status: BUILT
2026-09-28.**

**The flow is the only way in (business, 2026-09-28):** an expert is hired → staff add them to the
expert database → the expert signs up → gets the set-password link → sets a password → signs in
from then on. **Staff-minted expert links are removed in this unit**, not in a later phase: there
is no second door. What was "phase 2" is §5 below, done in the same change.

## 0. What this is modelled on

The client account flow (Unit 42): `ClientAccountService`, `/api/portal/auth/*`,
`authService.ts`, SignIn / SetPassword. **A password is a second way to obtain the existing
credential, not a second kind of session**: signing in mints a party-scoped expert
`portal_access` row, so `PortalTokenFilter`, `ExpertPortalService` and the expert chat routes
consume it unchanged. **Signing in is the only thing that mints one** (§5).

## 1. Decisions inside this unit

| # | Question | Answer (recommendation taken) |
|---|---|---|
| 1 | What is the account bound to? | **`expert.id`**, one account per roster row (`unique (expert_id)`). The email is used only to *find* the roster row at sign-up; sign-in finds the account through the roster row's email in the portal's brand, then mints for the bound `expert_id`. A later email change on the roster row moves the sign-in address with it. |
| 2 | Brand | **On the token, from config** — `evalos.portal.expert-brand` (defaults to `client-brand`). The portal deployment serves one brand, as the client portal does. |
| 3 | An expert on two brands' panels | **Two roster rows, so two accounts, one per brand's portal deployment.** Nothing crosses brands: `mintForParty`'s rule (minting one brand's link must not reach the other's) holds because each account mints in its own brand. |
| 4 | Several cases after sign-in | **A case list** (`GET /api/portal/expert/cases`, already built) at `/cases`; each opens `/case?caseId=`. The five case actions (`accept`, `request-evidence`, `decline`, `letter`, `signed-letter`) and `GET /case` gain an **optional `caseId`**: with it, `authorized(principal, caseId)` (expert and brand checked); without it, the expert's only case if they have exactly one. |
| 5 | Sign-up vs. forgot password | **One act, two doors.** Both send "a link for this address": a known expert with no password gets *set password*, one with a password gets *reset password*. The screen says the same thing either way. |
| 6 | Unknown email | **Sends nothing until Q6b's wording arrives** — one marked hook in `ExpertAccountService.sendLink`. The HTTP answer is identical (204) whether or not the email is on the roster, so the form cannot probe the panel. |
| 7 | Mail | The Unit 52 channel (`ClientMailer`, SMTP) and the existing set / reset templates, whose copy is account-neutral. Audited as `EXPERT`, not `CLIENT`. |

## 2. Data — migration `V72` (`V71` stays reserved for the answers drop)

- **`expert_account`** — `id`, `brand_id` → brand, `expert_id` → expert (**unique**),
  `password_hash` (null until set), `created_at`, `last_sign_in_at`.
- **`expert_credential_token`** — `id`, `brand_id`, `expert_account_id` → expert_account,
  `token_hash` (unique, SHA-256 of the mailed token), `purpose` (`SET` / `RESET`), `expires_at`,
  `used_at`, `created_at`. Same shape as `client_credential_token`; single use, `credential-ttl`.

## 3. Backend

| Route (permitAll, per-IP limited like the client's) | Does |
|---|---|
| `POST /api/portal/auth/expert/sign-up` `{email}` | known expert → set / reset mail (account created on first use); unknown → nothing (Q6b hook). **204 always** |
| `POST /api/portal/auth/expert/forgot-password` `{email}` | the same call |
| `POST /api/portal/auth/expert/sign-in` `{email, password}` | 200 `{token, expiresAt}` or **one** 400 for every refusal |
| `POST /api/portal/auth/expert/set-password` `{token, password ≥ 8}` | spends the link, sets the hash, signs in |

`ExpertAccountService`, `PortalAccessService.mintForExpertAccount` (retires the expert's previous
party token in the brand, like `mintPartyForExpert`), audit on sign-in, refused sign-in and
password set. Rate limiting is `PortalTokenFilter`'s per-IP limiter on `/api/portal/**`.

## 4. Expert portal

`/` is the door (sign in; "first time here, or forgot your password?" sends the link),
`/set-password#token`, `/cases` (the list), `/case?caseId=` (the existing page, plus the case chat
panel). There is no token in the URL any more (§5). The token stays memory-only, as in the
client portal.

## 5. Staff-minted links removed (in this unit, 2026-09-28)

- **Backend:** `PortalAccessService.mintForExpert`, `mintPartyForExpert` and `statusForExpert` are
  deleted, with `PortalLinkController` (`/api/cases/{id}/portal-link`). The **portal-links ledger**
  (`PortalLinkLedgerService`, `GET /api/metrics/portal-links`) goes too: it reported on
  staff-minted links, and none are minted for anyone any more (clients sign in, Unit 42).
- **Data:** a Flyway migration revokes every live expert `portal_access` row, so a link already in
  an inbox stops working rather than living out its TTL. An expert signs in instead.
- **Staff app:** the expert card's "copy portal link" is replaced by a line saying the expert signs
  in at the expert portal with their roster email; the ledger panel leaves the PM, Coordinator and
  Case Manager dashboards.
- **Expert portal:** `/case` no longer reads a token from the URL fragment; no token → the door.

## 6. Tests

`ExpertAccountServiceTest` (known / unknown / has-password mail, identical answers, sign-in
refusals, link single use and brand), `ExpertAuthControllerTest` (routes open, 204 both ways),
the per-case action path in `ExpertPortalServiceTest`; `MigrationTreeTest` keeps passing.
