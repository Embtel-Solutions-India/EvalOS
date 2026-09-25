# Current decisions

**The authoritative file is `.claude/current-decisions.md` (49 numbered decisions). Read it.**

The ones most often violated from memory:

- A GHL Contact is not an EvalOS ClientAccount. A contact existing is **not** proof of portal
  registration. All three states (no contact and no account; contact only; both) are legal.
- One GHL Contact, many opportunities. A repeat request makes a new **opportunity**, never a
  second contact.
- **Sign-up creates NO GHL contact** (D3d) — D3a's ordering, restored once Brevo replaced GHL mail
  and removed the `contactId` requirement that forced it there. `/auth/sign-up` is `permitAll`, so a
  CRM write behind it is a stranger's write. **Two call sites, not one:** `signUp` and
  `issueCredential` (sign-up falls through to `identify`, which lands there). The contact is created
  at `setPassword`, the next sign-in, or the first request that needs one (D3c).
- **`PORTAL_CLEANUP` only ever deletes `created_via = 'SIGNUP'` rows** (D3f, V59). The earlier
  predicate also matched every V45-seeded client and would have deleted the seeded backlog after
  30 days. Column defaults to `SEED` — a writer that forgets gets a row that survives.
- **`identify` repairs a missing `ghl_contact_id` before asking whether mail can reach them.**
  Without it, an account with no contact is unmailable, so it can never get a password, so it can
  never reach any other repair point. The repair is best-effort: a failed write is logged, never a
  500 on an unauthenticated route.
- **A GHL outage never refuses a sign-up, a set-password or a sign-in.** The account stands with no
  contact, `identify` answers `MAIL_UNAVAILABLE`, and `ensureCrmIdentity` repairs it at the next
  sign-in or the first request (D3c).
- **Mail leaves over SMTP and the provider is configuration** (D3e, rewritten 2026-09-18):
  `SmtpMailTransport` is the only `MailTransport`, and Brevo / Resend / Mailgun / Postmark / SES are
  `spring.mail.host/port/username/password` + `EVALOS_MAIL_FROM` — **four variables and a restart,
  no build**. The per-provider table is above `spring.mail` in `application.yml`; the username is not
  the account email on most of them and a wrong one is a 535 that reads like a wrong password.
  Both vendor transports are deleted (`ghl` 2026-09-16, `brevo` 2026-09-18) — each was a class, a
  config block, a credential and a live test proving one vendor. **Given up knowingly:** no provider
  message id, so `send` promises only that the message left EvalOS. **Kept:** the interface and
  `evalos.mail.transport` at one implementation, because `ClientMailerTest` fakes that seam and an
  unmatched name fails the boot. `isConfigured()` needs **both** relay and sender. Proved live by
  `SmtpMailTransportLiveTest` (opt-in `MAIL_LIVE_TEST=true`) — every failure is a swallowed `false`,
  so nothing short of a real send separates working from silent, and blocked egress or an
  authorised-IP list looks exactly like a bad password from inside the app.
- **A portal contact carries `source: "Client Portal"`** (D3b). GHL accepts no `utmSource` or
  `attributionSource` on a write — it fills those from its own form tracking, which an API caller
  never passes through — so the documented `source` string is the whole of what provenance can be.
  Every `upsertContact` caller names itself.
- **Request is not Case.** `client_application` is the request, `evalos_case` is production work,
  and the GHL opportunity is the commercial process between them.
- **The opportunity opens when the client SUBMITS** (D10) — changed 2026-09-16 on the second
  asking, having been reaffirmed against the first. It opened at service-pick so a half-finished
  request still reached Sales; it no longer does, and that cost is accepted rather than overlooked.
  The `SUBMITTED` custom field rides the create now, not a follow-up call. A failed create refuses
  the submit and keeps the draft. Then: Sales review → won → payment (D10c).
- **Abandoned portal rows are swept** (`PORTAL_CLEANUP`, daily). Expired `client_credential_token`
  rows go one TTL past expiry; `client_account` rows with no password, no GHL contact, no token, no
  application and no session go after `abandoned-sign-up-after` (30d). **Audit is never swept** —
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
- **The client's request lands on the pipeline marked `INTAKE`**, not one matched by name.
  `evalos.ghl.intake-pipeline-name` is retired: a rename in GHL used to stop every request silently.
- **EvalOS sends GHL the requested SERVICE ID as an opportunity custom field and nothing else about
  placement** (D10a). A GHL workflow routes the deal to a pipeline from it. Mapping services onto
  pipelines is a business rule and lives in the workflow — the same ruling that deleted
  `hot-stage-name`. No stage, no assignee, no price: EvalOS holds no price list.
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
- Experts have **no accounts** — a staff-minted link is the only way in, and whether that changes
  is **waiting on a stakeholder discussion**, not on code (D23, Q6).

**The business answered five open questions on 2026-09-17 (D33–D38, D19c):**

- **ONE ID REPRESENTS A CONTACT EVERYWHERE, AND IT IS THE GHL `contact_id`** (D41). Wherever a
  contact is *named* — an S3 prefix, a route parameter, a payload field — it is GHL's id, so the
  same client resolves in both systems with no mapping table. **Primary keys are untouched**: D18
  stands, EvalOS mints its own UUID, and `client_account.contact_id` / `evalos_case.contact_id`
  stay real FKs to `contact_snapshot` — an internal join is not a name. **This reverses the
  2026-09-14 narrowing** of `DocumentStore.clientKey` onto `contact_snapshot.id`; what makes the
  GHL id safe to name again is D3d + D3c — the contact is created at set-password, sign-in or the
  first request, so it exists by the time anything needs to name it. A
  contact with no GHL id is **refused** with a message naming the repair, never filed under a
  guessed prefix.
- **There is NO client questionnaire** (D13, Unit 55, 2026-09-25, business decision). The portal
  request is service + purpose + documents; Sales asks the rest on the call. No questions step, no
  `PUT /api/portal/applications/{id}`, no `answers` on the entity or the API. `client_application.answers`
  is unmapped and awaits `V69` (drop held for an explicit go-ahead). Do not rebuild a questionnaire.
- **Documents arrive WITH the request**, before submit, keyed by the **GHL contact id**
  (D41). Reuses `DocumentStore.clientKey`; Handoff A carries them into `case_document` as row
  inserts over the **same S3 object**. Unit 53, spec `53-request-documents.md` (D33).
- **Sales clicks one opportunity and sees the request AND documents** — the documents are **their own
  route and tab on that deal, never a second permission** (D34).
- **There is NO EvalOS sales-review state** (D35). `client_application.status` stays
  `DRAFT`/`SUBMITTED`; review, approval and rejection are GHL **pipeline stages**. Do not add
  `IN_REVIEW`/`ACCEPTED`/`REJECTED` — an earlier recommendation said to, and the business said no.
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
**Values are not mirrored, only definitions**: values are what a queued desk create would need
(D46), so they arrive with that unit and its reader.

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

**Nothing is deleted yet, and the ordering is deliberate.** `mintForExpert`/`mintForParty` are
still wired into four staff screens and every link already in an expert's inbox points at `/case`,
so minting and that route retire in ONE change once the replacement exists. Until then the expert
portal's `/` is a holding page that offers no door.

**D50 (2026-09-25/26, Unit 57): case chat is EvalOS-owned.** Three conversations per case (Client,
Internal, Expert), membership computed from assignments, GM/BM as viewers, text only, read-only at
CLOSED. PostgreSQL is the record; Ably relays live updates (one private channel per person, no
browser publish); web push when the app is closed. Spec `57-case-chat.md`.
