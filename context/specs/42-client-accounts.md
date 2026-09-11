# Unit 42 — Client accounts and the sign-in door

> **Status:** specced 2026-09-11, not built.
> **Depends on:** 34 (the portal frontend and its token), 35 (the party-scoped credential), 30 (S3).
> **Followed by:** Unit 43, which is the Get Started funnel that creates these accounts.

> **This unit reverses a decision that was taken in writing, so it is reversed in writing.**
> `34-portal-frontend-wiring.md` **D1** refused accounts rather than deferring them:
>
> > *"The alternative, if the business wants self-service signup: a third Spring Security chain
> > with real accounts. That is a genuinely larger thing — credential storage, rotation, reset,
> > lockout — and reset requires a mail channel EvalOS does not have (invariant 14). It is a
> > business decision with an infrastructure bill attached... **Do not drift into it; take it in
> > writing.**"*
>
> The business decision was taken on **2026-09-11**: clients sign in. This spec is the writing.
> Two of D1's four objections were right and are answered below; two were wrong and are corrected.

---

## 1. What changes, in one paragraph

The Client Portal gains a front door. A client arriving from the website lands on a welcome
screen with two choices — **Sign in** or **Get started** — instead of a dashboard that only
opens if somebody pasted a link into their inbox. Signing in takes an email first, looks it up,
and gives one of three different answers rather than one "wrong password". Passwords are set
and reset through a single-use link EvalOS sends over SMTP, which it has never had before.
Underneath, the credential the portal already uses is **unchanged**: a successful password
check mints the same party-scoped `PortalAccess` token Unit 35 built, so every screen behind
the door keeps working without knowing an account exists.

---

## 2. Two of D1's objections were right, and two were wrong

**Right, and answered:**

- *"Reset requires a mail channel EvalOS does not have."* Correct. §4 gives it one, and
  invariant 14 is amended rather than worked around.
- *"It reverses the passwordless-via-a-GHL-delivered-link line in four documents."* Correct.
  §8 lists them and they are edited, not annotated.

**Wrong, and this is why the unit is one unit and not three:**

- *"A third Spring Security chain."* **There is no third chain.** Sign-in is not a new
  credential — it is a new *way to obtain the existing one*. `POST /auth/sign-in` verifies a
  bcrypt hash and then calls `PortalAccessService.mintForParty`, returning the same token the
  staff mint button returns. `PortalTokenFilter` is untouched. See §6.
- *"Credential storage, rotation, reset, lockout."* Storage is one bcrypt column
  (`SecurityConfig:61` already has the encoder). Lockout is the per-IP limiter already running
  in `PortalTokenFilter:79` over `/api/portal/**`, which these routes sit under. Rotation of a
  client password is not a thing this business needs and is not built.

---

## 3. The UX: email first, three answers

**The entry** (`/welcome`) is two cards — *Start a new evaluation* and *Sign in* — plus a
quieter third line, **"Opened a link we sent you?"**. That third line is not decoration: every
`portal_access` link already in a client's inbox must keep working on the day this ships, and a
welcome screen that only offers a password is a screen that tells an existing client their
working link is wrong.

**Sign-in asks for the email alone**, then decides. `POST /api/portal/auth/identify` answers
one of three, and each is a different screen:

| Answer | Means | What the client sees |
| --- | --- | --- |
| `PASSWORD_SET` | account exists, password set | the password field reveals **in place**, no navigation, plus *Forgot password?* |
| `NO_PASSWORD` | account exists, never set a password | *"You're in our system, but haven't set a password yet. We've emailed you a link to set one."* — and the mail is sent by this call |
| `UNKNOWN` | no account for that email | *"We couldn't find that email"* + a button into Get Started **carrying the email forward** |

**`NO_PASSWORD` is the whole reason the email comes first.** Every client who has ever had a
case is seeded into this table by §7 with a null password. A combined email-and-password form
tells all of them "wrong password", which is false and unactionable. Email-first tells them the
truth and fixes it in the same breath.

**This endpoint reveals whether an email is known, and that is a decision, not an oversight.**
It is email enumeration. It is accepted here because the three-way answer above *is* the
feature — hiding it makes the common case (existing client, no password) indistinguishable
from a typo, and a client who cannot tell those apart contacts support, which is the cost this
unit exists to remove. What contains it: the per-IP limiter in `PortalTokenFilter` already
covers this path, so enumeration is throttled to the same budget as token guessing.

**`/forgot-password` does NOT differentiate.** It always answers *"If that email is in our
system, we've sent a reset link."* There is no UX gain from differentiating on a screen the
client reached by already believing they have an account, so the leak buys nothing and is not
taken.

**Setting a password** happens at `/set-password#<token>` — the fragment, not the query string,
for the reason `portal.ts:tokenFromFragment` already documents: a fragment is never sent to a
server and lands in no access log. Single-use, 30-minute expiry, stored as a SHA-256 exactly
as `PortalAccess` stores its own.

---

## 4. The mail channel — EvalOS gains SMTP

`spring-boot-starter-mail` against the business's existing mailbox. Decided 2026-09-11 over
Amazon SES, and the choice is less locked-in than it looks: **SES also exposes a plain SMTP
endpoint**, so moving to it later is four properties and no code.

```
evalos.mail.from                          ${EVALOS_MAIL_FROM:}
spring.mail.host / port / username / password    — environment only, never a committed default
```

`ConfigSecretsTest` fails the build if `spring.mail.password` carries a default in a shared
profile, on the same rule that already guards `GHL_API_TOKEN`.

**One class, `ClientMailer`, and exactly two messages**: *set your password* and *reset your
password*. Nothing else. **This is the boundary and it is the one most likely to erode** — the
first feature that wants "just a quick status email to the client" is a different decision and
gets argued in its own unit. A blank `evalos.mail.from` degrades `NO_PASSWORD` to *"please
contact us"* rather than failing the boot: the same reasoning that gave `GHL_API_TOKEN` an
empty default rather than a fatal one.

---

## 5. Data model

Two tables here, one migration. The third (`client_application`) belongs to Unit 43.

```sql
client_account
  id uuid pk, brand_id uuid not null,
  email citext not null, password_hash text null,
  ghl_contact_id text null,              -- a LINK, not the identity
  first_name, last_name, phone, country,
  created_at, last_login_at,
  unique (brand_id, email)

client_credential_token
  id uuid pk, client_account_id uuid not null,
  token_hash text not null, purpose text not null,   -- SET | RESET
  expires_at timestamptz not null, used_at timestamptz null
```

**`password_hash IS NULL` is the `NO_PASSWORD` state.** Not a status enum beside it — a second
column saying what the first column already says is a second thing to keep in step.

**`ghl_contact_id` is nullable and demoted to a link column, and that is the independence
decision made concrete.** Invariant 7 says GHL's contact id is the canonical client identity.
After this unit it remains the canonical identity *in GHL*; it stops being the identity EvalOS
signs a client in with. A client whose GHL contact is deleted, or whose CRM was replaced, still
signs in and still sees their cases and documents. §8 carries the amendment.

---

## 6. Auth: no new security chain

```
POST /api/portal/auth/identify         { email }              -> { state }
POST /api/portal/auth/sign-in          { email, password }    -> { token, expiresAt }
POST /api/portal/auth/forgot-password  { email }              -> 204 always
POST /api/portal/auth/set-password     { token, password }    -> { token, expiresAt }
```

All four are `permitAll` on the **existing** portal chain — `PortalSecurityConfig:96`'s
`anyRequest().authenticated()` gains one `requestMatchers("/api/portal/auth/**").permitAll()`
above it. That is the entire security config diff.

`sign-in` verifies the hash and then mints the party-scoped `PortalAccess` Unit 35 already
built, so what comes back is **the same token shape the staff mint button produces** and every
existing screen consumes it unchanged. `PortalAccessService` gains one overload minting by
`ghlContactId` directly rather than resolving it from a case, because a client signing in has
no case in hand — and a client with no GHL contact at all (§5) mints against their
`client_account` id, which is the second place independence shows up.

**A password check is an audit event.** `recordPortalEvent` with `actor_type = CLIENT`
(invariant 13 already has the vocabulary). Failures are audited too: an unaudited failed
sign-in is the one event a support conversation actually needs.

---

## 7. Seeding the clients you already have

Every existing client is inserted into `client_account` from `contact_snapshot`, one row per
distinct `(brand_id, email)`, with `password_hash` null. They land in `NO_PASSWORD` on their
first sign-in attempt and set a password from the emailed link.

**Two things about `contact_snapshot` that the migration has to survive, because neither is
obvious from the column list:**

- **`email` is nullable.** A contact that arrived without one is skipped — there is nothing to
  sign in with, and a row with a null login is a row that can never be used.
- **`email` is not unique, and the entity's own javadoc says so**: *"contacts sharing an email
  would otherwise let the second silently take over"*. So `SELECT DISTINCT` is load-bearing
  here, not tidiness — a naive insert violates `unique (brand_id, email)` and fails the
  migration. Where two snapshots share an address they collapse into **one** account, which is
  correct: it is one person with one inbox, and the cases are found through the party link,
  not through the snapshot row.

**`ghl_contact_id` IS copied from `contact_snapshot`. An earlier draft of this spec said to seed
it NULL, and that was wrong — corrected 2026-09-12, during implementation.**

The original reasoning: IE runs a fresh sub-account as of 2026-09-11
(`WY6bW2xUCI8Tz8gw7aLJ`, replacing `kBumF0uUOmMBB5bneYjx`) with no contact migration, so every
`ghl_contact_id` EvalOS holds names a contact **that does not exist in the new location** — and a
column that looks authoritative and 404s is worse than a null.

**That is true of the column's GHL job and irrelevant to its EvalOS job, which is the one that
matters here.** `PortalCaseService.authorized()` resolves a party token to its cases *through*
that id, and **fails closed when it is null**. Seeding null would therefore let every existing
client sign in successfully and then see **no cases at all** — a `ForbiddenException` on every
one. That is far worse than the degradation the null was avoiding.

**The two jobs fail independently, and only one of them is broken by the CRM swap:**

| The id's job | Where | After the swap |
| --- | --- | --- |
| join key from a client to their cases | `contact_snapshot`, entirely inside EvalOS | **works** |
| lookup key for invoices and meetings | live calls to GHL | **404s** |

So the invoice and meeting screens are empty for pre-cutover clients, and that is correct and
contained — those are live GHL lookups for a contact GHL no longer has, and they answer empty.
**Cases, documents, drafts and sign-in all keep working**, because they are EvalOS-owned. That
asymmetry is the argument for `00c` arriving as live evidence rather than as a prediction.

**No new condition is introduced by copying it forward:** `contact_snapshot` already holds
exactly these ids, resolving in EvalOS and 404ing in GHL, and has since the cutover.

---

## 8. Invariant impact

**Invariant 14 — amended.** *"EvalOS hosts no files and sends no email"* becomes *"EvalOS hosts
no files, and sends email for exactly one purpose: proving control of a client's own address."*
The hosting half is untouched and still a test. What is explicitly **not** licensed: status
mail, marketing mail, notification mail, or any message a client did not initiate by trying to
sign in. `00b` §2's ruling — *"what would break it is EvalOS composing and dispatching a
message itself"* — is now partly spent, deliberately, and the remaining line is drawn at
authentication.

**Invariant 7 — first clause amended a second time.** Unit 39 gave EvalOS permission to *ask
GHL to change* contact data. This unit goes further for one entity: `client_account` is an
**EvalOS-owned record** whose `ghl_contact_id` is a nullable link. The three-identifier rule
survives verbatim and is still load-bearing. What changes is that a client's *ability to sign
in* no longer depends on GHL holding a row.

**Invariant 8 — untouched, and checked.** This unit creates no case. `client_account` is not a
case, is not case-shaped, and `DomainInvariantsTest` still refuses any dependency on
`CaseIntakeService` outside `GhlOpportunityHandler`.

**Invariant 1 — held.** `client_account` is brand-scoped and the unique key is
`(brand_id, email)`. The portal resolves its brand from `evalos.portal.client-brand`, one value.
A second brand is a second portal deployment.

**That value is its own, and is deliberately NOT `evalos.ghl.sales-brand`.** Unit 36's
`sales-brand` exists to say *which brand's staff may hold the `SALES` and `MARKETING` roles* —
a staff-authorization ceiling on the normal security chain. **Nothing in Units 42 and 43 touches
that role model**, because nothing in them is a staff surface: the whole of both units lives on
the portal chain, where the credential names a client and there are no roles at all. Reusing
`sales-brand` here would tie the portal's brand to a staff-role setting that has no reason to
move with it, and would import a dependency on machinery this work does not use.

**Documents to edit, not annotate:** `architecture.md` (invariants 7 and 14, and the
Notifications row of the stack table), `CLAUDE.md`, `00-build-plan.md` (its "no mail server —
still an open decision" note is now closed), `context/process-automation.md` (same), and
`34-portal-frontend-wiring.md` D1, which gets a "superseded by Unit 42" header rather than a
rewrite — the refusal was correct on the evidence it had.

---

## 9. What this unit deliberately does not do

- **No Get Started funnel.** That is Unit 43. This unit ships the door; 43 ships the room.
- **No email change, no account deletion, no profile screen.** Each is a real feature and none
  is on the path from "a client cannot sign in" to "a client can".
- **No 2FA, no TOTP, no SSO.** Considered and refused: an emailed OTP is not a channel-free
  option, it *is* the same mail channel with more typing and identical security; and TOTP asks
  a credential-evaluation client to enrol an authenticator app.
- **No staff screen for client accounts.** Support unblocks a client with the mint button that
  already exists.

---

## 10. Acceptance criteria

1. A client with a seeded account and no password gets `NO_PASSWORD`, receives mail, sets a
   password from the link, and lands on `/dashboard` signed in.
2. The set-password link is refused the second time it is used, and after 30 minutes.
3. `/forgot-password` answers 204 identically for a known and an unknown email, asserted on
   both the status and the response body.
4. A successful sign-in returns a token that `PortalTokenFilter` accepts on
   `GET /api/portal/cases` with no other change.
5. A `portal_access` link minted before this unit still opens the portal.
6. Sign-in success and failure both write an audit row with `actor_type = CLIENT`.
7. `ConfigSecretsTest` fails if `spring.mail.password` gains a default in a shared profile.
8. A `client_account` with a null `ghl_contact_id` signs in and reaches `/documents`.
