# EvalOS — Current Decisions

Confirmed and in force as of **2026-09-17**. Short and explicit. No history, no abandoned
approaches, no proposals. Unresolved items are in `open-decisions.md`.

## Identity

- **D1.** A GHL Contact and an EvalOS ClientAccount are different things. A GHL Contact is CRM
  identity; a ClientAccount is portal identity and access. A contact existing is **not** proof of
  portal registration.
- **D2.** Three identity states are all legal and all supported: (a) no contact + no account =
  new client; (b) contact + no account = GHL lead who never registered; (c) contact + account =
  portal client.
- **D3.** GHL decides whether a contact is new. EvalOS asks via `POST /contacts/upsert` (matches
  on email, then phone) rather than deciding itself.
- **D3a.** **A stranger must not be able to drive unbounded writes into the live CRM.**
  `/auth/sign-up` is `permitAll` behind one per-IP counter, so a contact write there is an
  unauthenticated write — an IP-rotating script fills the sub-account Sales works in and spends
  GHL's 100-req/10s location budget, which 502s every GHL-backed staff screen. **This is the
  property; D3d changed how it is held.** From 2026-09-16 morning to 2026-09-16 evening it was held
  by ordering (contact created at `setPassword`); it is now held by the route's own gate plus
  `PORTAL_CLEANUP`. Spec `52` §8.
- **D3d.** **Sign-up creates no GHL contact — D3a's ordering, restored.** The contact lived on
  `/auth/sign-up` for exactly as long as GHL carried the set-password mail, which required a
  `contactId` and would not take a bare address. Every transport since takes the address, so the
  reason is gone and the exposure is not worth keeping: that route is `permitAll` behind one
  per-IP counter, so a CRM
  write there is a stranger's write — an IP-rotating script fills the sub-account Sales works in
  and spends GHL's shared 100-req/10s location budget, which 502s every GHL-backed staff screen.
  **Two call sites had to go, not one:** `signUp` itself, and `issueCredential` — because `signUp`
  falls through to `identify`, so removing only the first would have looked fixed and changed
  nothing. `signingUpReachesGhlZeroTimes` pins both. The contact is created at `setPassword`, at
  the next sign-in, or at the first request that needs one (D3c). Spec `52` §11.
- **D3e.** **Mail leaves over SMTP, and the provider is configuration — never a class**
  (rewritten 2026-09-18). `SmtpMailTransport` is the only `MailTransport` there is; which provider
  carries the mail is `spring.mail.host/port/username/password` plus `EVALOS_MAIL_FROM`, so moving
  between Brevo, Resend, Mailgun, Postmark or SES is **four environment variables and a restart,
  with no build**. The per-provider settings are tabulated over `spring.mail` in `application.yml`,
  because the username is not the account email on most of them and a wrong one fails as a 535 that
  reads like a wrong password. **This replaced two vendor-specific transports in a year**: `ghl`
  (deleted 2026-09-16 — it addressed a `contactId`, which is what forced D3d's contact-at-sign-up)
  and `brevo` (`POST /v3/smtp/email`, deleted 2026-09-18). Each was a class, a config block, a
  credential and a live test that proved exactly one vendor; the seam was supposed to make swapping
  cheap and instead made a second thing to swap. **What is knowingly given up:** an API transport
  reads the provider's own message id, and SMTP gives back a protocol accept — "it left EvalOS" is
  the whole of what `send` can promise, and delivery, bounces and suppression are read in the
  provider's dashboard. For two messages whose failure a support conversation recovers, that is the
  cheaper side. **What is kept:** the `MailTransport` interface and `evalos.mail.transport`, at one
  implementation, because it is the seam `ClientMailerTest` fakes and a name matching nothing
  **fails at startup** naming what it found. `ClientMailer` still owns the wording and the one
  `PORTAL_LINK_ISSUED` audit row, so the trail is not a property of whoever carries the mail.
  `SmtpMailTransportLiveTest` (opt-in, `MAIL_LIVE_TEST=true`) is the only check that proves a real
  credential, a verified sender and the deploy's egress — and unlike the one it replaced, it
  survives changing provider.
- **D4.** Signup never signs anyone in. It returns a state; control of the mailbox is proved by
  the set-password link. Signing up with a known email creates nothing and cannot overwrite.
- **D5.** Sign-in creates no contact and no account.
- **D6.** One GHL Contact, many opportunities. A repeat request creates a **new opportunity on the
  existing contact**, never a second contact.
- **D41.** **One id represents a contact everywhere in the system, and it is the GHL
  `contact_id`.** Decided 2026-09-17, for client-id consistency across EvalOS, GHL and anything
  built on either. Where a contact is _named_ — an S3 prefix, a route parameter, a payload field —
  it is named by GHL's id, so the same client resolves in both systems with no mapping table.
  **Database primary keys are untouched:** D18 stands, EvalOS still mints its own UUID and keeps
  `ghl_contact_id` beside it, and `client_account.contact_id` / `evalos_case.contact_id` stay real
  foreign keys to `contact_snapshot`. An internal join is not a name.
  **This reverses the 2026-09-14 narrowing** that moved `DocumentStore.clientKey` onto
  `contact_snapshot.id` after IE's sub-account swap left post-swap clients with no GHL id to build
  a key from. What makes the id safe to name again is D3d and D3c: the contact is created at
  set-password, at the next sign-in, or at the first request that needs one, and a document is
  uploaded at questionnaire submit, which already ensures the id before it opens the deal.
  **Two consequences stated rather than hidden:** a contact with no GHL id yet is _refused_ with a
  message naming the repair instead of being filed under a guessed prefix, and a second sub-account
  swap would orphan the namespace again — reads resolve through the stored `object_key`, so nothing
  breaks retroactively, but new writes would land beside the old ones.
- **D7.** `client_account.email` is unique per brand (case-insensitive). Portal brand is fixed by
  `evalos.portal.client-brand` — the portal is single-brand today.

## The request → case lifecycle

- **D8.** Request ≠ Case. A **Request** (`client_application`) is what the client asked for. A
  **Case** (`evalos_case`) is production work. An **Opportunity** (GHL) is the commercial process
  between them.
- **D9.** A case is created **only** by the `opportunity.won` GHL webhook (Handoff A). No other
  code path may create one; `DomainInvariantsTest` fails the build if a second class injects
  `CaseIntakeService`. (Invariant 8.)
- **D10.** The opportunity is created **when the client submits the request**, not when they pick
  a service. **Changed 2026-09-16, third time of asking.** It opened at service-pick until then, so that a
  client who abandoned the questionnaire still reached a salesperson; the requirement said submit from the start,
  EvalOS refused it twice, and the third asking carries it. The deal on the board is now a **finished
  request** and nothing else — which is what makes Sales' review step mean something, and is the
  trade taken knowingly: **an abandoned questionnaire now reaches nobody.** Recovering those is a
  separate job (nothing sweeps `DRAFT` applications today) and is in `open-decisions.md`.
- **D10c.** The funnel is **submit → opportunity → Sales review → won → payment**. EvalOS creates
  the deal at submit and stops; review, win and invoicing are GHL's and Sales', exactly as D11
  already said about placement. A case is still born only of `opportunity.won` (D9, invariant 8).
- **D10a.** EvalOS sends GHL the **requested service id** as an opportunity custom field
  (`evalos.ghl.opportunity-service-field`) and nothing else about placement. A GHL workflow routes
  the deal to a pipeline from it. The `SUBMITTED` marker rides on that same create now — under D10
  every opportunity is a submitted one, so a second call to announce it would say nothing.
  Mapping services onto pipelines is a business rule and lives in
  the workflow — the same ruling that deleted `hot-stage-name`. No stage, no assignee, no price.
- **D11.** EvalOS puts the opportunity on the intake pipeline and **stops**. Stage placement and
  assignee are GHL automation's job. There is deliberately no "hot stage" setting.
- **D10b.** The client's request lands on the pipeline a GM marked `INTAKE`, not on one matched by
  name. `evalos.ghl.intake-pipeline-name` is retired (Unit 44b) — a rename in GHL silently stopped
  every request reaching Sales. Zero or two `INTAKE` pipelines are both refusals that name the fix.
- **D11a.** GHL's pipelines and stages are **mirrored** as EvalOS rows using GHL's own ids
  (Unit 44a, `V50`). GHL owns every column except `pipeline.purpose`, which EvalOS owns and a
  sweep never writes. Rows are upserted and **never deleted** — one GHL stops returning is
  stamped `missing_since`, because `purpose` is EvalOS's judgement and a pipeline archived for
  an afternoon must not come back meaning nothing. Nothing infers a purpose from a pipeline's
  name: a GM sets it, or it stays `UNASSIGNED`.
- **D12.** Submit changes `client_application.status` **and is what opens the opportunity** (D10).
  The `SUBMITTED` custom field is written **on that create**, not by a follow-up call — every
  opportunity is now a submitted one, so announcing it separately would say nothing, and
  `setOpportunityFields` is no longer on this path. It still moves no stage, sends no pipeline and
  writes no note. **A GHL failure refuses the submit (502) and leaves the draft intact**, which is
  stricter than the swallow this decision used to describe: under D10 a failed create means Sales
  has no deal at all, and telling a client "sent" for that would be a lie.
- **D13.** Sales reads the questionnaire through `GET /api/opportunities/{id}/application`, which
  answers `null` + 200 for deals that did not come from the portal. **The documents ride on that
  same read** (D34) — one opportunity, one screen, both halves of what the client sent.

- **D33.** **Documents arrive with the request, at questionnaire submit, and are stored against the
  contact** — the person — not against a case, which does not exist yet. This is the DOCUMENT
  SUBMISSION step the target lifecycle always named and Unit 43 deferred, because every upload
  route EvalOS had took a checklist item on a **case**. Decided 2026-09-17.
  **"Contact" means the GHL contact id** (D41). The request upload reuses `DocumentStore.clientKey`
  unchanged in shape — `{brand}/client/{ghl_contact_id}/{doc}` — and the upload set carries forward
  into `case_document` at Handoff A as a row insert over the **same S3 object**: nothing copies,
  nothing re-keys, and Production starts holding exactly what Sales read.
  Spec `53-request-documents.md`. _(Closes `open-decisions.md` Q4.)_
- **D34.** **Sales clicks one opportunity and sees both** the questionnaire answers and the
  documents. The documents are **a second screen on that same deal, never a second permission** —
  their own route and their own tab beside the application, reached by whoever could already open
  the opportunity. Nothing about them asks a new authorisation question.
- **D35.** **There is no EvalOS sales-review state.** `client_application.status` stays `DRAFT` /
  `SUBMITTED`. Review, approval and rejection are GHL **pipeline stages** — D10c said that of the
  funnel and it now settles the request row too. The business named the shorter flow on 2026-09-17:
  submitted → Sales reads → won → case. _(Closes Q2 and Q3, which both recommended adding states.)_
- **D36.** **The case is staffed PM-first, and the PM staffs the rest.** Handoff A creates the case,
  a PM takes it, and the PM assigns the **Project Coordinator**, the **Case Manager** and the
  **Expert**. The CM drafts and uploads; the client sees and approves it in the portal; only then
  does it reach the expert, who downloads, signs and uploads it back. `CaseLifecycleService` and
  `CaseTransitions` already do exactly this — it is recorded here because it is a stated business
  rule now, not an implementation detail that happened to be convenient.

## GHL relationship

- **D14.** GHL is the CRM, pipeline engine, automation engine and invoicing/QuickBooks
  integration. EvalOS is the interface Sales and Marketing work in.
- **D15.** **Invoicing is GHL's, full stop.** EvalOS reads invoices (Client Portal) and raises none.
- **D16.** EvalOS writes to GHL. `GhlHttp` exposes exactly `get`/`post`/`put`/`delete` — the verb
  list is closed and a fifth fails the build. Every caller of a write verb must reach
  `AuditService`; that too is a build-failing structural test.
- **D17.** `upsertOpportunity` means one open opportunity per contact per pipeline — correct for a
  **marketing lead**, wrong for a genuine second deal. Genuine second deals use
  `createOpportunity`, with duplicate risk managed one layer up.
- **D39.** **A mirror webhook is a trigger, not a payload** (45d, 2026-09-17). GHL's Custom Webhook
  action posts the _contact record_ flat — no stage, no status, no value — and everything else in
  the body is `customData`, hand-typed by whoever edited the workflow last. So an
  `opportunity.*` event supplies one fact, the contact id, and the mirror reads the field values
  back from GHL. A contact event is the one exception, because there the payload **is** the entity.
  Every spelling of an opportunity change routes to one handler; **`opportunity.won` never joins
  them** — that is Handoff A and it creates a case (invariant 8).
- **D40.** **"Delta sweep" means a stale pipeline, never a changed row**, and the API decides that:
  GHL's opportunity search filters on `createdAt` and has **no updated-since filter at all**. There
  is no read that returns only what changed. `MIRROR_DELTA` asks `refreshIfStale` of every live
  mirrored pipeline, so it costs only the pipelines nobody is reading — which are the ones the
  nightly audit would otherwise report as drift when the truth is that nobody looked.
- **D42.** **Ownership of a mirrored field is per field, in code** (45e, 2026-09-17), never the
  blanket "EvalOS wins" `00c` §4b proposed — that rule reverts GHL automations, which is the one
  thing GHL was kept for, and a round-robin reassigning a deal is not a conflict to undo. GHL owns
  the **assignee** and the **pipeline** (and anything unclassified, deliberately);
  `stage`/`status`/`amount`/`name` are **shared**, where EvalOS wins **and the conflict is
  reported**; `opportunity_note` is EvalOS's — **pushed to the deal's GHL contact, and its author's
  edits and deletes follow it there** (Units 54/54a, 2026-09-24; see D49). **"EvalOS wins" means one narrow
  thing** — the mirror keeps a shared field only while `local_updated_at` says EvalOS holds an edit
  GHL has not confirmed, and that flag is cleared the moment GHL's answer supersedes it or GHL
  acknowledges the create. **A null `ghl_updated_at` is a conflict, never "GHL is newer"** — but
  only when there is an edit to defend, or the rule degrades into the blanket one it replaced.
  **Nothing is pushed to GHL by this**: correcting GHL is Unit 46's, through the outbox.
- **D43.** **A drift row is never resolved by a human pressing a button.** Clearing it clears the
  symptom while the two systems still disagree. `GET /api/sync/drift` instead answers *will this fix
  itself*: every row carries `owner` and `resolution` (`GHL_WINS` / `EVALOS_WINS` /
  `NEEDS_A_HUMAN`), derived at read time and never stored, and the envelope carries `needsAHuman`.
  **Only a row GHL no longer returns needs a person** — re-creating a lost deal, or deleting the
  mirror's copy, is a business decision a sweep must not take.
- **D44.** **A desk edit writes the mirror and queues the push; it does not call GHL** (Unit 46,
  2026-09-17). Rename, re-price, stage move and close on both desks are now: edit the local row,
  `enqueue`, answer from the row. **A stage move must name a live mirrored stage of the deal's
  own pipeline** (Q12, 2026-09-24): a desk holding several pipelines sees their stages on one
  board strip, and a foreign stage id would be a pipeline move in GHL — GHL's workflow, not a desk
  edit — so `moveToStage` refuses it before anything is written or queued. The salesperson sees the change immediately and no edit is lost
  to a GHL outage. **The four editable fields are exactly 45e's shared set**, because what a desk
  may edit locally is what EvalOS is allowed to win a conflict over — the assignee is absent for
  that reason. **The cost is named rather than hidden: a won deal reaches GHL on the next drain
  (≤2m), so Handoff A's case arrives that much later.** Taken deliberately — a win lost to an
  outage is the more expensive failure.
  **The push sends the fields the desk actually edited and no others** (`V63`,
  `opportunity.locally_edited_fields`, amended 2026-09-18). It sent all four, and the mirror's stage
  is up to one `MIRROR_DELTA` behind — so a rename re-sent a stale stage, undoing a GHL workflow's
  card move and then having the revert read back as truth on the next sweep. `updateOpportunity`
  omits a null from the body, so an unedited field is left alone in GHL; a row with nothing
  outstanding sends nothing at all, because GHL answers 422 to an empty body and "they already
  agree" is success.
  **The confirmation is a conditional statement, not a save** (`OpportunityRepository.confirmPushed`).
  The drain reads the row, spends a round trip, and comes back to clear the stamp; merging the
  entity it read lost any edit that landed in between — in the mirror, in the queue, and in GHL at
  once. A zero row-count means the row moved, and the drain re-queues rather than clearing.
- **D45.** **A board reads EvalOS rows and makes no GHL request at all** (Unit 46) — on load, on
  refetch, and on moving a card. A browser reload reads the mirror like everything else, so a lead
  created in GHL appears only once a sync has brought it in.
  **`MIRROR_DELTA` therefore runs every 5 minutes** (`delta-ttl` 4m): it was a floor under webhooks
  at 15m, and with the refill gone it is one of only two things that put a new lead in front of a
  salesperson, so it became a cadence somebody waits on.
  **The screen says how old it is and stops pretending when it does not know.** `lastSyncedAt` is
  **null when the sync has never confirmed those pipelines** — never substituted with "now", which
  is the one lie a freshness indicator must not tell — and a null counts as stale.
  `evalos.ghl.board-stale-after` (**5m — one missed pass**, set by the business over a proposed 15)
  draws a **"Sync delayed"** banner naming the consequence: new GHL deals are not arriving on this
  screen. The threshold equals the sweep interval, so a long pass shows the banner briefly before
  clearing; the fix for that noise is 6m, never a slower sweep.
  **`POST /api/opportunities/board/refresh` is a reconciliation, not a live read** — it syncs the
  caller's own pipelines then draws from the mirror, behind a 30-second floor so a double-click
  costs one read. **The pipeline *structure* read is gated on the mirror holding no live pipeline**
  (amended 2026-09-18): the floor covered the deal reads and not that one, so every press also
  spent a paged structure read on a location whose pipelines change a few times a year, and the GM
  — whose board is every live pipeline — fanned it out over all of them. The hourly
  `PIPELINE_MIRROR` sweep keeps structure current; the button only has to be able to fix a mirror
  that has none, which is the state it was added for.
- **D46.** **A create still calls GHL inline** — `SalesDeskService.createDeal` and
  `MarketingLeadService.openLead`. The outbox stores an id and never a payload (`45` §2C.2), and a
  desk create carries an expected close date and custom field values the mirror does not hold;
  queueing one would mean either a payload column or silently dropping what the salesperson typed.
  Custom fields are tier 2, which is Unit 47. The contact upsert and GHL tasks stay inline for the
  neighbouring reason: `sync_outbox` is opportunity-scoped, and nothing mirrors a task.
  **A create writes the mirror from GHL's own reply before answering** (amended 2026-09-18,
  `OpportunityMirrorService.absorbCreated`). It did not, and every desk *edit* refuses a deal the
  mirror has not absorbed — correctly, since a row that does not exist cannot be pushed — so
  correcting the name or value of a deal opened seconds ago answered 400 for up to a full
  `MIRROR_DELTA`. From the create's reply rather than a second GHL read: it has just said what it
  stored. GHL's own timestamps are left null for the next sweep to fill rather than filled with
  EvalOS's clock.
- **D47.** **Unit 47 mirrors what a screen reads, not what the tier list contains** (2026-09-17),
  which is `00d` §6.6's ruling over `00c` §2c: *a sync surface with no consumer is pure drift risk*.
  After Unit 46 exactly three live GHL reads were left on a desk render, and those three are what
  got mirrored — **custom field definitions, calendars, and the location's users** (`ghl_custom_field`,
  `ghl_calendar`, `ghl_user`, `V60`), refreshed by one hourly `REFERENCE_MIRROR` sweep. *(Its cut of
  tags and GHL notes was reversed by D49; GHL notes gained their screen in Unit 54.)*
- **D48.** **Mirror the structure, never the availability.** A GHL calendar is mirrored; its **free
  slots never are**, and must not be — GHL computes them from open hours, buffers, caps and the
  assignee's other appointments, so a mirrored slot is wrong within a minute of being written.
  **Unit 48 inherits the consequence**: with sync disabled the business keeps its boards, desks and
  production and **cannot take a new booking**. That belongs on 48's list of what degrades.
- **D49.** **Tier 2 and tier 3 are mirrored in full, minus free slots** (Unit 47b, 2026-09-17).
  Custom field **values** (`opportunity.custom_fields`, keyed by GHL **field id** so a rename cannot
  lose them), **GHL notes** (`ghl_note`), **tags** (`ghl_tag`), and **read-back for tasks and
  appointments** — a task completed or an appointment cancelled in GHL now reaches EvalOS.
  *(This reverses 47 §4's cuts, which the business overruled. One of them was also wrong on the
  facts: §4 claimed tasks could only be listed per contact, but `getNotes`/`getTasks`/
  `getCalendarEvents` are parameters on the opportunity search the mirror already runs, so all of it
  costs zero extra requests.)*
  **Notes sync both ways, and are stored apart** (Unit 54, 2026-09-24 — the business reversed
  "never synced"; built the same day, `context/specs/54-two-way-note-sync.md`). An
  `opportunity_note` is queued on write and pushed once to the deal's **contact** — GHL notes have
  no opportunity — with an author/deal trailer, its GHL id recorded in the insert-only
  `opportunity_note_ghl_link`; `ghl_note` rows are shown on the deal beside it, the echo of a pushed
  note dropped. **A GHL note is filed by its own `relations`, not by the deal it was listed under**
  (the search repeats a contact's notes on every deal); one with no deal is the contact's and shows
  on each of that contact's deals, labelled so. **The two tables never merge.** **Its author may overwrite or hard-delete an
  EvalOS note** (Unit 54a, `54a-note-edit-delete.md`) and the change is queued to GHL (`PUT` / `DELETE`
  on the contact note); GHL notes stay read-only in EvalOS, changed in GHL and mirrored back. **A task EvalOS never created is not invented** on somebody's desk.
  **Tags are read, never written**: GHL workflows key off them.
  **D46's blocker is gone** — the mirror now holds the values a queued desk create would need.
- **D18.** The target is an **id-faithful mirror** of GHL (same pipeline/stage/contact/opportunity
  ids both sides), synced both ways, that keeps working when sync is off. Units 44–48
  (`context/specs/00c-ghl-independence-programme.md`). EvalOS mints its own primary key and keeps
  `ghl_id` beside it, nullable.

## Access

- **D19.** Brand-scoped by default. Every scoped query filters by `brand_id`. One stated exception:
  GHL reads go to the single `evalos.ghl.location-id` sub-account, which belongs to no brand —
  those routes are **GM-only** (`GET /api/metrics/gm`; `/api/opportunities/board` is the narrowed
  case, see D19a).
  **2026-09-16 — the exception cost two screens rather than being widened.** `/marketing/email`
  and `/sales/pipeline` rendered a GHL funnel stage by stage and were GM-only under this rule, so
  the audience for a marketing funnel could not open one. The business removed both screens, their
  controller, service, cache table and `countIn`. A funnel screen comes back only after Unit 25
  puts the location on `brand`, at which point it is brand-scoped and Marketing can be let in.
- **D19d.** **`evalos.ghl.sales-brand` takes a brand SLUG, and one class resolves it** (2026-09-17).
  It names the brand that owns `evalos.ghl.location-id` — a sub-account belongs to no brand on its
  own, which is invariant 1's exception. **A UUID is right in exactly one database** (IE is
  `1111…` local, `3333…` testprod), so a shared config carrying one is silently wrong everywhere
  else; the slug is the same in all of them. `SellingBrand` resolves it once, replacing **nine**
  services that each parsed the property themselves, and a value matching no brand **fails the
  boot** naming the fix. **Blank is legal and means no brand sells yet** — and switches every
  mirror off, which the board now says out loud (`syncConfigured`).
- **D19e.** **The GM's board is every LIVE MIRRORED pipeline, not the union of assignments**
  (2026-09-17). Deriving it from the roster hid exactly the pipelines that belong to the business
  rather than to a person — `00d` §6.7's unowned Case Delivery, and the location's Master Pipeline
  — and it read `team_member.ghl_pipeline_id`, the column Unit 44b replaced, so it answered empty
  once assignment moved to `team_member_pipeline`. The GM is `Tier.ALL`; the mirror is the list.
  **A GM holds no assignment rows and must not** — `team_member_pipeline_matches_role` permits
  SALES/MARKETING only.
- **D19f.** **`WY6bW2xUCI8Tz8gw7aLJ` is the final GHL location** (confirmed 2026-09-17). The
  abandoned sub-account's id is removed from every live config and spec so it cannot be copied back
  in. It survives only where it cannot be edited or cannot mislead: an applied seed (a checksum
  mismatch refuses the boot), recorded review diffs, a build log, `context/archive/`, and the dated
  `context/audit/2026-09-13/` evidence.
- **D19g.** **The mirror fills itself at startup, and the Refresh button can fix an empty one**
  (2026-09-17). `StartupSync` runs `PIPELINE_MIRROR` → `REFERENCE_MIRROR` → `MIRROR_DELTA` once when
  the app is ready, in that order and on its own thread. `fixedDelay` counts from the end of the
  previous run, so a fresh start was otherwise an hour from its first pipelines — an hour of empty
  boards whose only remedy was a GM running a job by hand. **Nobody should have to run a job to see
  their own pipeline.** The board's Refresh now syncs **pipelines before deals** for the same
  reason: in the one state somebody presses it, an empty mirror, there were no pipelines to refresh
  deals for, so the button could not fix what it was offered for.
- **D19h.** **A desk's pipeline claim is re-read when the token carries none.** D19b's set is read
  at sign-in — a deliberate staleness bound for a *reassignment*, and a trap for a *first*
  assignment: a desk that signed in before the mirror ran saw an empty board for the whole session.
  An empty claim now means "ask again", not "you have none". It widens nothing: same member, own
  row, and a member with no assignment still gets an empty list.
- **D19a.** `/api/opportunities/board` is the one narrowed case: `evalos.ghl.sales-brand` names the
  brand that owns the location and assignment refuses any other brand's member with a 400, so that
  screen's brand _is_ provable and SALES/MARKETING reach it.
- **D19b.** A SALES/MARKETING member holds a **set** of GHL pipelines (`team_member_pipeline`,
  Unit 44b), not one. The one-owner rule is retired: Case Delivery is a pipeline nobody owns.
  Assignment is by **mirror id**, never a pasted GHL string — that is `00d` C4 closed structurally.
  The set is read at sign-in and carried in the token, so a reassignment takes effect on next
  sign-in; unchanged in kind from the single-claim model it replaced.
- **D19c.** **Sales reads their own pipelines and nothing else, and their world ends at won.**
  A SALES member reads the opportunities and requests on the pipelines they hold (D19b) and **no
  case at all**. `ScopePredicate`'s PIPELINE arm matching no `evalos_case` row is therefore
  **correct**, not the gap `implementation-status.md` called it until 2026-09-17.
  _(Closes `00d` §12a, which asked how much of a case SALES gets: none.)_
- **D20.** Eight staff roles with ABAC tiers: `GM`(ALL), `BRAND_MANAGER`(BRAND),
  `PROJECT_MANAGER`(TEAM), `PROJECT_COORDINATOR`/`CASE_MANAGER`(SELF),
  `EXPERT_NETWORK_MANAGER`(SUPPLY), `SALES`/`MARKETING`(PIPELINE).
- **D21.** Sales and Marketing are roles; attorney/employer/individual are `team_member.segment`,
  not roles — identical permissions.
- **D22.** Two Spring Security chains: `/api/portal/**` on an opaque portal token (order 1),
  everything else on staff JWT (order 2).
- **D23.** Clients authenticate with email + password. **Experts will too — the stakeholder
  decision came back on 2026-09-18: experts sign in like clients, and staff-minted expert links
  are retired.** This reverses what D23 said (*"experts do not have accounts — an expert reaches
  the portal only through a staff-minted link"*), and the reversal is cheap to justify: D1 refused
  an account because a password store needs a reset flow and a reset flow needs a mail channel
  invariant 14 denied, and **Unit 52 built that channel**. The premise expired before the answer
  did.
  **What is decided is the direction, not the process** — see Q6, which is still open and still
  gates any code. An expert is not a client: they are party-scoped, they exist on the roster
  before they could sign in, and the same person may sit on two brands' panels, so the Unit 42
  flow is a starting point rather than a template.
  **Nothing is removed yet, and that ordering is deliberate.** `PortalAccessService.mintForExpert`
  and `mintForParty` are still wired into four staff screens and every link already in an
  expert's inbox points at `/case` on the expert portal, so minting and the route it feeds are
  retired together, in one change, once the replacement exists. Until then the expert portal's `/`
  is a **holding page** that offers no door, because offering a sign-in that does not exist is
  worse than saying so.

## Data

- **D24.** Append-only truth: `audit_event` carries a database trigger that raises on UPDATE and
  DELETE. **`opportunity_note` left this rule on 2026-09-24** (Unit 54a, `V67`): the business chose
  to let a note's author overwrite or hard-delete it, with no revision kept. What survives is an
  `audit_event` (`NOTE_EDITED` / `NOTE_DELETED`) naming who and when — never the text.
- **D25.** Every state transition writes an audit row (invariant 13). Failed client sign-ins too.
- **D26.** Schema changes ship as new Flyway migrations. An applied migration is never edited.
- **D27.** EvalOS hosts no files — S3 holds them, presigned reads expire in 5 minutes and are
  never stored.
- **D28.** Expert `payment_detail` is encrypted at rest and appears in no DTO.

## Mail

- **D29.** EvalOS sends **exactly two** messages, both authentication: set-password and
  reset-password. Any other mail is a new decision. (Invariant 14, amended 2026-09-11.)
- **D29a.** **Outbound mail is send-only, from a `no-reply@` sender with no mailbox behind it**
  (decided 2026-09-17). Inbound email is not a channel EvalOS has, so an address that could
  receive one would be an inbox nobody is assigned to read — which is worse than a bounce,
  because a client who replies to it believes they have been heard. The two messages carry a
  link, not a conversation; a client who needs a person uses the support address the portal's
  `MAIL_UNAVAILABLE` screens name. **Consequence to expect, not to fix:** mail sent *to* the
  sender address hard-bounces, and the provider will list it as a blocked contact. That blocks
  delivery **to** it and never **from** it, so it is noise in a dashboard rather than a fault.
  Never point a test recipient at it — `EVALOS_MAIL_TEST_TO` must name a mailbox a human can open,
  and `SmtpMailTransportLiveTest` refuses to run without one. Doing the opposite once already cost
  a suspended account.

## Notifications

- **D37.** **Notifications are in-app and push — both, and only those two.** The `notification`
  table and the bell stay the record of what happened; web push is added beside them so a user
  who is not on the screen still hears about it. **No email and no SMS**, so invariant 14 is
  untouched: a push is not a message to a mailbox. Decided 2026-09-17.

## Other

- **D30.** No AI in the system at all; no AI makes a production decision.
- **D31.** Controllers stay thin. Long-lived work is a `@Scheduled` sweep with a DB lock and a
  `scheduled_job` ledger row.
- **D38.** **Deploying and wiring the Client and Expert Portals is DevOps's, outside this
  repository.** No Dockerfile, compose service or CI job is owed here for either, and their
  absence stops being a gap in the status table. Decided 2026-09-17. _(Closes Q7.)_

## Resolved 2026-09-16

- **D32.** `client_account` and `contact_snapshot` stay **two tables, joined** — not merged.
  `V55` adds `client_account.contact_id`, a real foreign key, backfilled on `ghl_contact_id`
  within the brand and set at sign-up; null stays legal. **D6 is now enforced by the schema**, by
  a partial unique index, where it previously was not — `ghl_contact_id` was nullable and not
  unique, so nothing stopped two accounts naming one contact. _(This closes `open-decisions.md`
  Q5, which recommended the merge.)_
  **The `contact_snapshot` → `contact` rename is deferred and the reason is mechanical:** two
  seeds write that table (`V905` local, `V951` testprod), both numbered 900+, both running after
  every `db/migration` script — and `MigrationTreeTest` forbids a migration in that range while
  editing an applied seed is a checksum mismatch that refuses the boot. A rename has nowhere to
  sit. Do it in the change that rebaselines the seed tree. `contact_snapshot` **is** the mirror's
  contact table until then, and nothing about that is wrong except its name.
