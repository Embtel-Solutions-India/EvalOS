# Current decisions

**The authoritative file is `.claude/current-decisions.md` (49 numbered decisions). Read it.**

The ones most often violated from memory:

- A GHL Contact is not an EvalOS ClientAccount. A contact existing is **not** proof of portal
  registration. All three states (no contact and no account; contact only; both) are legal.
- One GHL Contact, many opportunities. A repeat deal is a new **opportunity** in GHL, never a
  second contact.
- **No public sign-up; the portal account is born with the case** (D3d, D4, Unit 64 — built
  2026-09-29, `CasePortalAccountListener` → `ClientAccountService.openForCase`). On `CASE_CREATED` after commit: portal brand + contact with email and GHL id
  → create (`created_via = 'CASE'`) or link the account, mail set-password; an account linked to a
  **different** contact is never relinked (flag the case); no email/GHL id → flag, no account.
  Failures never fail the case. D3a now reads: no unauthenticated route creates an account or CRM row.
- **`PORTAL_CLEANUP` only ever deletes `created_via = 'SIGNUP'` rows** (D3f, V59). The earlier
  predicate also matched every V45-seeded client and would have deleted the seeded backlog after
  30 days. Column defaults to `SEED` — a writer that forgets gets a row that survives.
- **`identify` repairs a missing `ghl_contact_id` before asking whether mail can reach them.**
  Without it, an account with no contact is unmailable, so it can never get a password, so it can
  never reach any other repair point. The repair is best-effort: a failed write is logged, never a
  500 on an unauthenticated route.
- **A GHL outage never refuses a sign-up, a set-password or a sign-in.** The account stands with no
  contact, `identify` answers `MAIL_UNAVAILABLE`, and `ensureCrmIdentity` repairs it at the next
  sign-in (D3c).
- **Mail leaves over SMTP and the provider is configuration** (D3e, rewritten 2026-09-18):
  `SmtpMailTransport` is the only `MailTransport`, and Brevo / Resend / Mailgun / Postmark / SES are
  `spring.mail.host/port/username/password` + `EVALOS_MAIL_FROM` — **four variables and a restart,
  no build**. The per-provider table is above `spring.mail` in `application.yml`; the username is not
  the account email on most of them and a wrong one is a 535 that reads like a wrong password.
  Both vendor transports are deleted (`ghl` 2026-09-16, `brevo` 2026-09-18) — each was a class, a
  config block, a credential and a live test proving one vendor. **Given up knowingly:** no provider
  message id, so `send` promises only that the message left EvalOS. **Kept:** the interface at one
  implementation, because `ClientMailerTest` fakes that seam; the `evalos.mail.transport` switch is
  gone (2026-09-30) — a choice among one. `isConfigured()` needs **both** relay and sender. Proved live by
  `SmtpMailTransportLiveTest` (opt-in `MAIL_LIVE_TEST=true`) — every failure is a swallowed `false`,
  so nothing short of a real send separates working from silent, and blocked egress or an
  authorised-IP list looks exactly like a bad password from inside the app.
- **A portal contact carries `source: "Client Portal"`** (D3b). GHL accepts no `utmSource` or
  `attributionSource` on a write — it fills those from its own form tracking, which an API caller
  never passes through — so the documented `source` string is the whole of what provenance can be.
  Every `upsertContact` caller names itself.
- **There is NO client request** (D8, Unit 64 — built 2026-09-29). Deals start in GHL
  (form, call, Sales, Marketing); a case is born only of `opportunity.won`. The portal opens no
  opportunity — D10, D10a, D10b, D10c, D12, D13 are retired.
- **Abandoned portal rows are swept** (`PORTAL_CLEANUP`, daily). Expired `client_credential_token`
  rows go one TTL past expiry; old `SIGNUP` `client_account` rows with no password, no GHL contact,
  no token and no session go after `abandoned-sign-up-after` (30d). `CASE` accounts are never swept. **Audit is never swept** —
  append-only by invariant, so a flood still grows `audit_event` and that is the one place growth
  is the feature.
- **GHL's pipelines and stages are MIRRORED rows now** (Unit 44a, `V50`), keyed on GHL's own ids.
  GHL owns every column except `pipeline.purpose`, which EvalOS owns and a sweep never writes. Rows
  are never deleted — `missing_since` instead. Nothing guesses a purpose from a pipeline's name.
- **A SALES/MARKETING member holds a SET of pipelines** (`team_member_pipeline`, Unit 44b), assigned
  by MIRROR id and never by a pasted GHL string. The one-owner rule is gone — Case Delivery is a
  pipeline nobody owns. `PipelineScope.mine()` returns a list; a CREATE uses `mineForWrite()`, which
  refuses rather than guessing when a desk holds several. **A revoke stamps `revoked_at` rather than
  deleting the row** (`V64`, 2026-09-18) — otherwise `backfillFromLegacyColumn` re-created the grant
  on the next `PIPELINE_MIRROR` pass, and `V39` forbids emptying the legacy column it reads.
- **`INTAKE` is retired** (Unit 64): `V78` moves any `INTAKE` pipeline to `UNASSIGNED` and drops it
  from the CHECK. EvalOS writes no requested-service or submitted field on any opportunity.
- **A case is created only by the `opportunity.won` webhook.** Build-enforced by
  `DomainInvariantsTest`. Never add a second creator.
- Invoicing is GHL's, full stop. EvalOS reads invoices and raises none.
- EvalOS sends **exactly two** emails: set-password and reset-password. Any other mail is a new
  decision. **Send-only, from a `no-reply@` sender with no mailbox** (D29a): inbound mail is not a
  channel, so mail *to* the sender bounces and the provider lists it as a blocked contact — which
  blocks delivery to it, never from it. Never aim a test recipient at the sender address:
  `EVALOS_MAIL_TEST_TO` must be a mailbox a human can open, and doing otherwise once got an
  account's transactional sending suspended.
- Brand-scoped by default. Append-only truth for `audit_event`, enforced by a database trigger.
  `opportunity_note` left that rule on 2026-09-24 (Unit 54a, D24 amended): its author may overwrite or
  hard-delete it; an audit row records who and when, never the text.
- **The one scoping exception is the GHL location, and it costs screens rather than being
  widened.** `evalos.ghl.location-id` is one global sub-account belonging to no brand, so every
  screen over it is GM-only. On 2026-09-16 the two GHL funnel screens were **removed** for exactly
  that reason — their audience was Marketing, who could not open them — instead of admitting a
  brand-locked role to an unattributable figure. `/api/opportunities/board` is the one narrowed
  case: `evalos.ghl.sales-brand` names the owning brand and assignment refuses any other, so its
  brand *is* provable and SALES/MARKETING reach it.
- Experts have **accounts** (D23, Unit 59): hired → added to the expert database → sign up → set
  password → sign in. **No staff-minted link exists any more.**

**The business answered five open questions on 2026-09-17 (D33–D38, D19c):**

- **ONE ID REPRESENTS A CONTACT EVERYWHERE, AND IT IS THE GHL `contact_id`** (D41). Wherever a
  contact is *named* — an S3 prefix, a route parameter, a payload field — it is GHL's id, so the
  same client resolves in both systems with no mapping table. **Primary keys are untouched**: D18
  stands, EvalOS mints its own UUID, and `client_account.contact_id` / `evalos_case.contact_id`
  stay real FKs to `contact_snapshot` — an internal join is not a name. **This reverses the
  2026-09-14 narrowing** of `DocumentStore.clientKey` onto `contact_snapshot.id`; what makes the
  GHL id safe to name again is D3d — every portal account is created from a case's contact, which
  Handoff A holds with its GHL id. A
  contact with no GHL id is **refused** with a message naming the repair, never filed under a
  guessed prefix.
- **Client documents arrive only on a case**, uploaded against the checklist the PC or CM sends
  (D33, D60), keyed by the **GHL contact id** (D41). No request documents, no Sales request tabs
  (D34 retired), no carry-forward; `V78` drops `client_application` and `application_document`.
  Spec `64-remove-client-requests.md`. Do not rebuild a request or questionnaire.
- **D35 retired with the request (Unit 64):** review, approval and rejection are GHL pipeline stages.
- **Sales reads their own pipelines and NO case at all** (D19c). `ScopePredicate`'s PIPELINE arm
  returning `cb.disjunction()` over `evalos_case` is **correct**, not a bug to fix. **Chat is the one
  exception** (Unit 57): Sales takes part in the Client and Internal conversations of their pipeline's
  cases, and still reads no case data.
- **The case is staffed PM-first** (D36): Handoff A → PM → PM assigns Coordinator, Case Manager and
  Expert → CM drafts and uploads → **client approves in the portal** → only then the expert
  downloads, signs and uploads back. This is what `CaseLifecycleService` already does; it is now a
  stated business rule.
- **Notifications are in-app AND push, and nothing else** (D37). No email, no SMS — invariant 14 is
  untouched, because a push is not a message to a mailbox. In-app is built; push is owed.
- **Deployment is DevOps's, outside this repo** (D38). The portals' absence from compose and CI is
  not debt to schedule here.

Changed a decision? Edit `.claude/current-decisions.md`, then this memory. Never leave a
contradicting note beside the old one.

**D32 (2026-09-16).** `client_account` and `contact_snapshot` stay **two tables, JOINED** — not
merged. `V55` adds `client_account.contact_id`, a real FK, backfilled on `ghl_contact_id` within
the brand and set at sign-up; null stays legal. **D6 is now schema-enforced** by a partial unique
index, where `ghl_contact_id` had been nullable AND not unique, so nothing stopped two accounts
naming one contact. The `contact_snapshot` → `contact` **rename is deferred for a mechanical
reason**: two seeds write that table (`V905`, `V951`), both 900+, both running after every
`db/migration` script, and `MigrationTreeTest` forbids a migration in that range while editing an
applied seed is a checksum mismatch that refuses the boot. Do it when the seed tree is rebaselined.


**D39/D40 (2026-09-17, Unit 45d).** A mirror webhook is a **trigger, not a payload** — GHL posts the
contact record flat, and `customData` is hand-typed, so an `opportunity.*` event carries only the
contact id and the mirror re-reads GHL for the values. A **contact** event is the exception: there
the payload is the entity. And a **"delta sweep" means a stale pipeline, never a changed row**,
because GHL's opportunity search filters on `createdAt` and has no updated-since filter at all.

**D42/D43 (2026-09-17, Unit 45e).** Ownership of a mirrored field is **per field, in code** — never
the blanket "EvalOS wins", which reverts the GHL automations GHL was kept for. GHL owns the
**assignee** and the **pipeline**; stage/status/amount/name are shared with EvalOS winning *and
reporting*; notes sync both ways but are stored apart (Unit 54, built 2026-09-24):
an EvalOS note is pushed to the deal's GHL contact and its author's edits/deletes follow it there
(54a); GHL notes show beside it and are changed in GHL only. A GHL note is filed by its `relations` (the search repeats contact notes
on every deal); a deal-less one is the contact's and shows on each of their deals. "EvalOS wins" means only "there is an unconfirmed local edit",
and the flag clears on a GHL win or on `linkGhl`. A null `ghl_updated_at` is a conflict **only when
there is an edit to defend**. And **a drift row is never resolved by a button** — the surface
answers *will this fix itself* with `owner`/`resolution`/`needsAHuman`, and only a row GHL no longer
returns needs a person.

**D44/D45/D46 (2026-09-17, Unit 46).** A desk **edit** writes the mirror and queues the push — it
does not call GHL, and it answers from the row. A **board** reads EvalOS rows and makes no GHL
request at all. A **create** still calls GHL inline, because the outbox stores an id and never a
payload and a create carries custom fields the mirror does not hold (tier 2, Unit 47). The four
editable fields are exactly 45e's shared set — the assignee is missing on purpose, it is GHL's.
A stage move must name a **live stage of the deal's own pipeline** (Q12, 2026-09-24) — a foreign
stage would be a pipeline move, which is GHL's workflow. **Known cost**: a won deal reaches GHL on the next drain (≤2m), so the case arrives later than it
used to; a win surviving an outage is worth more than the two minutes.
**The board's freshness contract**: `MIRROR_DELTA` every 5m; `lastSyncedAt` null = never synced
(never faked as "now") and counts as stale; `board-stale-after` **5m** (one missed pass) draws a "Sync delayed" banner;
`POST /api/opportunities/board/refresh` reconciles the mirror and then draws from it — it is never
a live board read.

**D47/D48/D49 (2026-09-17, Unit 47).** Mirror **what a screen reads**, not what the tier list
contains (`00d` §6.6 over `00c` §2c). Three lists got tables: custom field **definitions**,
calendars, location users. **Mirror the structure, never the availability** — free slots are never
mirrored, and Unit 48 inherits "the business runs without sync and cannot take a new booking".
**D49 (47b) mirrors tier 2/3 in full minus free slots**: custom field **values** on
`opportunity.custom_fields` keyed by field id, GHL notes, tags, task/appointment read-back. Since
2026-09-30 also the deal's **contact's** custom field values, country and tags on `contact_snapshot`
(`V81`), with `model=contact` definitions in `ghl_custom_field` — the deal screen shows everything
GHL holds on the deal and its contact.

**D19d/e/f (2026-09-17).** `evalos.ghl.sales-brand` takes a **brand slug** — a UUID is right in one
database only (IE = 1111… local, 3333… testprod) — resolved once by `SellingBrand`, which replaced
nine copies and fails the boot on a value matching no brand. The **GM's board is every live mirrored
pipeline**, not the union of assignments: the old query hid unowned pipelines (Case Delivery, Master)
AND read `team_member.ghl_pipeline_id`, the column 44b replaced. **`WY6bW2xUCI8Tz8gw7aLJ` is the
final location**; the abandoned id is purged from every live file.

**D19g/h (2026-09-17).** `StartupSync` fills the mirror once at boot (PIPELINE_MIRROR →
REFERENCE_MIRROR → MIRROR_DELTA, own thread, honours `evalos.jobs.enabled`) — `fixedDelay` counts
from the END of a run, so a fresh start was an hour from its first pipelines. The board's **Refresh
syncs pipelines before deals**, because an opportunities-only refresh cannot fix an empty mirror.
And a desk's pipeline claim is **re-read when the token carries none**: D19b's sign-in bound is
right for a reassignment and a trap for a first assignment.

**D44/D46 amended 2026-09-18 (review pass).** A queued `UPSERT` sends **only the fields the desk
edited** (`opportunity.locally_edited_fields`, `V63`); `updateOpportunity` omits a null, so an
unedited field is left alone in GHL rather than overwritten from a mirror that may be a
`MIRROR_DELTA` behind. A row with nothing outstanding sends nothing — GHL answers 422 to an empty
body. The confirmation is `OpportunityRepository.confirmPushed`, a conditional statement, not
`save(row)`: the entity was read before the round trip, so merging it lost any edit that landed
during it, and a zero row-count re-queues instead. **Both creates now write the mirror from GHL's
reply** (`absorbCreated`) so the next edit is not refused as "not in the mirror yet". Refresh reads
pipeline *structure* only when the mirror holds none.

**D23 edited 2026-09-18 — experts get accounts after all.** It said "experts do not have accounts;
an expert reaches the portal only through a staff-minted link". The stakeholder decision reversed
that: experts sign in like clients at `experts.internationalevaluations.com`, and staff-minted
expert links are retired. D1's refusal rested on there being no mail channel for a password reset,
and **Unit 52 built one** — the premise expired before the answer did.

**The direction is decided; the PROCESS is not** (Q6, still open, still gates code). An expert is
not a client: they are on the roster before they could sign in, their access is party-scoped rather
than account-scoped, and one person may sit on two brands' panels. Recommendation on file:
invitation-only sign-up bound to `expert.id`, party-scoped tokens kept underneath, brand on the
token and not the account.

**Staff-minted expert links are removed (2026-09-28)** — `mintForExpert` / `mintPartyForExpert`,
the staff "Send link" button, the portal-links ledger and the token-in-the-URL `/case` all went in
Unit 59, and `V73` revoked the live links. Signing in is the only mint.

**D50 (2026-09-25/26, Unit 57): case chat is EvalOS-owned.** Three conversations per case (Client,
Internal, Expert), membership computed from assignments, GM/BM as viewers, text only, read-only at
CLOSED. PostgreSQL is the record; Ably relays live updates (one private channel per person, no
browser publish); web push when the app is closed. Spec `57-case-chat.md`.

**D51 (2026-09-27, Unit 58): drafts are uploaded versions.** Word + PDF on the existing
`case_document` DRAFT versions (no new table); an immutable comment thread per version, open only on
the version in client review; the client approves / requests changes on a named version (409
`DRAFT_NOT_CURRENT` otherwise) and the answer is stamped on it; the signed letter reaches the client
only once Delivered. D33 note: drafts are stored files alongside the request documents. The client
portal is case-first (phase 3): Home lists every case; no client route resolves a case from the token.

**D52 (2026-09-28): no GHL conversation sidebar.** GHL conversations (SMS/email/WhatsApp/social)
stay in GHL; EvalOS messaging is the case chat (Unit 57) only. Closes Q10; drops Unit 47 tier 3.

**2026-09-28 — four business answers.** **Q6 → D23:** expert sign-up checks the email against the
expert database; found → set-password mail, account bound to that `expert.id`; not found → a mail
whose text the business will give (Q6b; sends nothing until then), same screen answer either way.
**Q13 → D51:** the draft PDF is viewed first (inline, staff-uploaded DRAFT PDFs only) with both
downloads beside it. **Q11 → D54** (a DRAFT-requests screen) — **retired by Unit 64**, there are no requests.
**D53:** no outbound webhooks, no Handoff C (invariant 11 struck).

**D23 built (Unit 59):** one expert account per roster row, brand from `evalos.portal.expert-brand`; two panels = two accounts. No staff-minted links.

**Q6b answered (2026-09-28):** an unknown email at expert sign-up gets NO mail — experts are told to sign up only after being hired. Same screen answer either way.

**2026-09-29 — five answers.** **D55 (Q9):** appointments use GHL's calendar APIs, standard practice
(cancel, guests, blocked time, notes; `team_member.ghl_user_id`) — built as Unit 60; guests have no GHL field (Q14). **D56 (Q1):** a marketing lead does
not overwrite an opportunity with a change pending in the sync queue; otherwise upsert as today.
**D57 (§12 b):** Sales reach the client in the Client conversation (already members). **D58 (§12 c):**
four client emails — checklist+link (sent on every checklist send, D60), draft ready, sign in to review, delivered. **D60:** the PC or CM adds documents to a case checklist; items are unsent (hidden from the portal) until one of them presses Send, which publishes all unsent items; later additions wait for the next Send; both see who sent and when; an expert's evidence-request item is unsent too (Q15); the CM also sets item status (Q16). **D59:** expert
payments are managed manually by the ENM; the expert portal shows what was recorded (spec 62), and **since Unit 63 a payout is Pending → Processing → Paid: the ENM's recorded transfer is Processing until the expert confirms receipt in the portal** (staff confirm removed; no approval or failed state); the ENM pays weekly and reports weekly / monthly / yearly by due date with totals and row CSV exports. **Edited 2026-09-30 (Unit 65, spec 65, built 2026-09-30):** every offer carries an amount (blank = standard fee, retake keeps the declined fee, none refused; GM / PM / PC / ENM set it, CM standard fee only), editable only while the offer is open, shown to the expert before accepting, frozen at acceptance; delivery opens the payout at it; pending-payout correction removed (a missing amount can be set once); no adjustments (bonus / deduction / advance); all logged in `audit_event`; Payouts is its own staff module (Overview, Cases register, Experts, Pay run). **D61 (Unit 63):** the ENM runs the expert lifecycle in EvalOS; the hiring pipeline is a GHL pipeline a GM tags `EXPERT_HIRING`, which every ENM of its brand works through the Sales desk machinery (derived claim, no assignment row); a won hiring opportunity never opens a case; Onboarded → pre-filled expert create form; credential verification and a derived per-expert case history. **D62:** a declined / timed-out case may be offered again to the same expert while it awaits a rematch and they are available (GM / PM / ENM / CM). **D63 (Unit 66b, 2026-09-30):** everyone on a case sees and downloads its documents — staff who read case content (GM, BM, PM, PC, CM) every document and draft version; the CM uploads each draft version (PC/PM/GM too, D51); the case's expert the client's current uploads and the client-approved draft (PDF + Word), from the offer on, while they are the case's expert. The ENM reads no case documents.
