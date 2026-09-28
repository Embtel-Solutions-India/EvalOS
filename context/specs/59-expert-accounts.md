# Unit 59 — Expert accounts

**Decided 2026-09-28 by the business (D23, closing Q6):** an expert signs up with their email;
EvalOS checks it against the expert database; a known email gets a set-password email, an unknown
one gets a different email whose wording the business will supply (Q6b). **Status: phase 1 BUILT
2026-09-28; phase 2 (retiring staff-minted expert links) not built.**

## 0. What this is modelled on

The client account flow (Unit 42): `ClientAccountService`, `/api/portal/auth/*`,
`authService.ts`, SignIn / SetPassword. **A password is a second way to obtain the existing
credential, not a second kind of session**: signing in mints the same party-scoped expert
`portal_access` row `mintPartyForExpert` already mints, so `PortalTokenFilter`, `ExpertPortalService`
and the expert chat routes consume it unchanged. The authorisation model does not move.

## 1. Decisions inside this unit

| # | Question | Answer (recommendation taken) |
|---|---|---|
| 1 | What is the account bound to? | **`expert.id`**, one account per roster row (`unique (expert_id)`). The email is used only to *find* the roster row at sign-up; sign-in finds the account through the roster row's email in the portal's brand, then mints for the bound `expert_id`. A later email change on the roster row moves the sign-in address with it. |
| 2 | Brand | **On the token, from config** — `evalos.portal.expert-brand` (defaults to `client-brand`). The portal deployment serves one brand, as the client portal does. |
| 3 | An expert on two brands' panels | **Two roster rows, so two accounts, one per brand's portal deployment.** Nothing crosses brands: `mintForParty`'s rule (minting one brand's link must not reach the other's) holds because each account mints in its own brand. |
| 4 | Several cases after sign-in | **A case list** (`GET /api/portal/expert/cases`, already built) at `/cases`; each opens `/case?caseId=`. The five case actions (`accept`, `request-evidence`, `decline`, `letter`, `signed-letter`) and `GET /case` gain an **optional `caseId`**: with it, `authorized(principal, caseId)` (expert and brand checked); without it, today's behaviour, so every staff-minted link already in an inbox keeps working. |
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
panel). A staff-minted link (`/case#token`) still works. The token stays memory-only, as in the
client portal.

## 5. Phase 2 — not in this change

D23: staff-minted expert links and the token-only `/case` retire **together, once the replacement
exists**. That removal touches `mintForExpert` / `mintPartyForExpert`, four staff screens and every
link already in an expert's inbox, so it is its own change: remove the staff mint buttons, drop
the token-only routes and the no-`caseId` action branch, and make `caseId` required.

## 6. Tests

`ExpertAccountServiceTest` (known / unknown / has-password mail, identical answers, sign-in
refusals, link single use and brand), `ExpertAuthControllerTest` (routes open, 204 both ways),
the per-case action path in `ExpertPortalServiceTest`; `MigrationTreeTest` keeps passing.
