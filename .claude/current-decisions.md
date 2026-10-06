# EvalOS — Current Decisions

Confirmed and in force as of **2026-09-30**. Short and explicit. No history, no abandoned
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
- **D3a.** **No unauthenticated route creates an account or a CRM row.** Public sign-up was the
  last one and is gone (Unit 64, 2026-09-29): a `permitAll` write there was a stranger's write into
  the sub-account Sales works in, spending GHL's shared 100-req/10s location budget. Spec `64` §3.
- **D3d.** **A portal account is created by EvalOS when the client's case is created** (Unit 64),
  from the case's contact — its email, names, `contact_snapshot` id and GHL contact id — and the
  set-password link is mailed. An existing account linked to the same contact is reminded (no
  password) or left alone (password set); one linked to a **different** contact is never relinked
  and the case is flagged. A contact with no email or no GHL id gets no account and a flag.
  `created_via = 'CASE'`. Failures never fail the case. Spec `64` §2.
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
  cheaper side. **What is kept:** the `MailTransport` interface, at one
  implementation, because it is the seam `ClientMailerTest` fakes. **The `evalos.mail.transport`
  switch is gone (2026-09-30)** — a choice among one; `ClientMailer` takes the one bean, and a
  second provider brings its own way to pick it. `ClientMailer` still owns the wording and the one
  `PORTAL_LINK_ISSUED` audit row, so the trail is not a property of whoever carries the mail.
  `SmtpMailTransportLiveTest` (opt-in, `MAIL_LIVE_TEST=true`) is the only check that proves a real
  credential, a verified sender and the deploy's egress — and unlike the one it replaced, it
  survives changing provider.
- **D4.** **There is no public sign-up** (Unit 64). Control of the mailbox is proved by the
  set-password link; sign-in with a known email and no password re-sends it, and an unknown email is
  told the account opens when their first case starts.
- **D5.** Sign-in creates no contact and no account.
- **D6.** One GHL Contact, many opportunities. A repeat deal is a **new opportunity on the existing
  contact** in GHL, never a second contact; its case lands on the same portal account.
- **D41.** **One id represents a contact everywhere in the system, and it is the GHL
  `contact_id`.** Decided 2026-09-17, for client-id consistency across EvalOS, GHL and anything
  built on either. Where a contact is _named_ — an S3 prefix, a route parameter, a payload field —
  it is named by GHL's id, so the same client resolves in both systems with no mapping table.
  **Database primary keys are untouched:** D18 stands, EvalOS still mints its own UUID and keeps
  `ghl_contact_id` beside it, and `client_account.contact_id` / `evalos_case.contact_id` stay real
  foreign keys to `contact_snapshot`. An internal join is not a name.
  **This reverses the 2026-09-14 narrowing** that moved `DocumentStore.clientKey` onto
  `contact_snapshot.id` after IE's sub-account swap left post-swap clients with no GHL id to build
  a key from. What makes the id safe to name again is D3d: every portal account is created from a
  case's contact, which Handoff A holds together with its GHL id (Unit 64).
  **Two consequences stated rather than hidden:** a contact with no GHL id yet is _refused_ with a
  message naming the repair instead of being filed under a guessed prefix, and a second sub-account
  swap would orphan the namespace again — reads resolve through the stored `object_key`, so nothing
  breaks retroactively, but new writes would land beside the old ones.
- **D7.** `client_account.email` is unique per brand (case-insensitive). Portal brand is fixed by
  `evalos.portal.client-brand` — the portal is single-brand today.

## The deal → case lifecycle

- **D8.** **There is no client request** (Unit 64, 2026-09-29). An **Opportunity** (GHL) is the
  commercial process and starts in GHL — a form, a call, Sales or Marketing. A **Case**
  (`evalos_case`) is production work. The portal never opens a deal. Spec `64`.
- **D9.** A case is created **only** by the `opportunity.won` GHL webhook (Handoff A). No other
  code path may create one; `DomainInvariantsTest` fails the build if a second class injects
  `CaseIntakeService`. (Invariant 8.) The service type comes from the webhook's
  `customData.serviceType`.
  **Amended 2026-10-07 (security review): the win is confirmed with GHL first.** The endpoint token is the
  webhook's whole credential (GHL's Custom Webhook cannot sign), so a delivery is a trigger, not proof:
  `GhlOpportunityHandler` re-reads the contact's deals from GHL and creates the case only if one is won
  (and, if the payload names an opportunity id, that exact deal). A GHL outage is a 5xx GHL redelivers; no
  won deal is a 409 `NOT_WON_IN_GHL`. **Knowingly not checked:** a brand that does not own the GHL
  location (D19d) and a deployment with no GHL token — refusing those would lose paid cases, so they are
  accepted with a WARN log. Still open: the token travels in the URL path, is stored unhashed, and has no
  rate limit or rotation runbook.
- **D10, D10a, D10b, D10c, D12, D13 — retired by Unit 64.** EvalOS opens no opportunity, writes no
  requested-service or submitted field, and has no `INTAKE` pipeline purpose (`V78` moves any to
  `UNASSIGNED`); there is no request, questionnaire or submit. The funnel is GHL's end to end until
  `opportunity.won`.
- **D11.** Stage placement and assignee are GHL automation's job. There is deliberately no "hot
  stage" setting.
- **D11a.** GHL's pipelines and stages are **mirrored** as EvalOS rows using GHL's own ids
  (Unit 44a, `V50`). GHL owns every column except `pipeline.purpose`, which EvalOS owns and a
  sweep never writes. Rows are upserted and **never deleted** — one GHL stops returning is
  stamped `missing_since`, because `purpose` is EvalOS's judgement and a pipeline archived for
  an afternoon must not come back meaning nothing. Nothing infers a purpose from a pipeline's
  name: a GM sets it, or it stays `UNASSIGNED`.

- **D33.** **Client documents arrive only on a case**, uploaded in the portal against the checklist
  the PC or the CM sends (D60). There is no pre-case upload (Unit 64 removed Unit 53's request
  documents). Case documents live at `DocumentStore.clientKey` — `{brand}/client/{ghl_contact_id}/{doc}`
  (D41). **Drafts are stored files too** (Unit 58, D51): Word + PDF on their `case_document` DRAFT
  versions.
- **D34, D35 — retired by Unit 64.** There is no request for Sales to read beside the deal, and no
  request status. Review, approval and rejection are GHL pipeline stages.
- **D36.** **The case is staffed PM-first; the expert is offered after the draft** (edited
  2026-10-02, Unit 73). Handoff A creates the case, a PM takes it and assigns the **Project
  Coordinator** and, at PM Review, the **Case Manager** (`assign-cm`, CM only → Drafting). The CM
  drafts and uploads; **once a draft exists** the **PM or CM** (also ENM / GM) offers the **Expert**
  an amount (`PATCH …/expert`, stage kept, `DRAFT_IN_PROGRESS` … `CLIENT_APPROVAL`; a CM offers at
  the expert's standard fee only, Unit 65). The client sees and approves the draft in the portal;
  **only then** does it reach the expert (`send-to-expert`, refused with no expert), who accepts,
  downloads, signs and uploads it back. Staffing is stage-preserving: the PM sets or changes the CM
  at any stage from More, and the expert can be changed in the same window (supersedes the open
  offer, revokes the old link, opens a new offer and its mail). Spec `73-expert-offer-after-draft.md`.

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
  lose them — and, since 2026-09-30, the deal's **contact's** custom field values, country and tags
  on `contact_snapshot` (`V81`), with `model=contact` definitions in `ghl_custom_field`, because the
  deal screen now shows everything GHL holds on the deal and its contact; D47's rule, a screen
  reads them), **GHL notes** (`ghl_note`), **tags** (`ghl_tag`), and **read-back for tasks and
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
- **D50.** **Case chat is an EvalOS-owned service** (Unit 57, `57-case-chat.md`, 2026-09-25/26,
  business decision; it replaced a Stream Chat setup, Unit 56, that was never committed). Every
  case has three conversations — Client (client, pipeline Sales, PM/Coordinator/Case Manager),
  Internal (pipeline Sales, PM/Coordinator/Case Manager, brand ENMs) and Expert
  (PM/Coordinator/Case Manager, brand ENMs, the expert from offer). EvalOS computes membership from
  assignments and never lets a browser create a conversation or change a member. GM and Brand
  Manager read as viewers. Text only; read-only at `CLOSED`. Spring Boot and PostgreSQL hold every
  message and rule; **Ably relays live updates only** (one private channel per person, publish never
  granted to a browser); web push for anyone without the app open. No chat platform owns the data.
- **D51.** **Drafts are uploaded versions** (Unit 58, `58-client-portal.md`, 2026-09-27, business
  decision). **Only the Case Manager** uploads each version (edited 2026-10-02: the PC, PM and GM no longer may; `POST …/drafts` is `CASE_MANAGER` only, no `GM_OR`) as **Word + PDF** onto the
  existing `case_document` DRAFT versions — no second version table. Each version carries an
  immutable comment thread (1–2,000 characters, optional page) open only while it is the one in
  client review. The client approves or requests changes **on a named version** (409
  `DRAFT_NOT_CURRENT` otherwise), and that answer is stamped on it (`CLIENT_APPROVED` /
  `CHANGES_REQUESTED`). The client sees the signed letter only once the case is **Delivered**, refused
  by the server before then. Legacy pasted draft links stay view-only.
  **The client portal is case-first** (Unit 58 phase 3, 2026-09-28): Home lists every case, each
  opens its own page, and no client route resolves a case from the token — which closes Q8.
  **The draft PDF is viewed first** (2026-09-28, closes Q13): it opens in the browser's own viewer,
  with Download PDF and Download Word beside it. **Edited 2026-10-02 (Unit 74,
  `74-view-and-download.md`): every document anyone may read has View and Download**, View first.
  A view is served `inline` with its type **forced** to `application/pdf`, `image/png` or
  `image/jpeg` from the filename's extension, so the browser opens it only in its PDF or image
  viewer and never as a page; Word files have no viewer and stay Download only. Everything else is
  still `attachment`. Who may read which document is unchanged.
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
  A SALES member reads the opportunities on the pipelines they hold (D19b) and **no
  case at all**. `ScopePredicate`'s PIPELINE arm matching no `evalos_case` row is therefore
  **correct**, not the gap `implementation-status.md` called it until 2026-09-17.
  _(Closes `00d` §12a, which asked how much of a case SALES gets: none.)_
  **Chat is the one exception (Unit 57, 2026-09-26):** Sales takes part in the Client and Internal
  conversations of cases from their pipeline, and still reads no case data.
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
  **The sign-up process was decided on 2026-09-28 (closes Q6):** an expert signs up with their
  email, and EvalOS checks it against the expert database. **Found → a set-password email** through
  the Unit 52 mail channel, the account bound to that `expert.id`;   **not found → no email at all**
  (Q6b, 2026-09-28): an expert signs up only after being hired and told to. The screen answers the
  two cases identically so the form cannot be used to probe the roster.
  **Built as Unit 59 (2026-09-28):** one account per roster row, brand from
  `evalos.portal.expert-brand`; an expert on two panels has two accounts, one per brand's portal.
  **Staff-minted expert links are removed (2026-09-28), not phased out.** The business set the
  flow: hired → added to the expert database → sign up → set-password link → sign in. There is no
  second door, so `mintForExpert` / `mintPartyForExpert`, the staff "portal link" button, the
  token-in-the-URL `/case` and the portal-links ledger all go in Unit 59, and live expert links are
  revoked by migration.
- **D72.** **The GM administers staff in EvalOS** (2026-10-02, Unit 68, spec `68-gm-admin.md`):
  create, edit (name, email, role, brand, segment, GHL user), deactivate / reactivate — never delete
  — and set a password, GM-only and audited without the password. **The GM sets the password and
  hands it over; no staff mail** (invariant 14; `open-decisions.md` Q18). **A deactivation bites on
  the member's next request** (`JwtFilter` re-reads `team_member.active`), not when their 8 h token
  expires; a role or brand change still waits for the next sign-in. The GM cannot deactivate
  themselves or change their own role; a desk holding pipeline grants keeps its role and brand
  until they are revoked. Pipelines (purpose, D61's screen), sync health (read-only, D43) and the
  brands (read-only — a brand carries the webhook secret, so it changes by migration) have GM
  screens too.

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
  untouched: a push is not a message to a mailbox. Decided 2026-09-17. **Built 2026-10-02**
  (`BellPushNotifier`, on chat's `push_subscriptions`): a recipient with the app open is not pushed,
  since the live bell already tells them. The GM (no brand to file a browser under) and Marketing
  (no chat connection to opt in through) stay in-app only.

## Other

- **D30.** No AI in the system at all; no AI makes a production decision.
- **D31.** Controllers stay thin. Long-lived work is a `@Scheduled` sweep with a DB lock and a
  `scheduled_job` ledger row.
- **D38.** **Deploying and wiring the Client and Expert Portals is DevOps's, outside this
  repository.** No Dockerfile, compose service or CI job is owed here for either, and their
  absence stops being a gap in the status table. Decided 2026-09-17. _(Closes Q7.)_
- **D53.** **No outbound webhooks and no Handoff C.** EvalOS does not dispatch events to GHL or
  to clients; the design (a signed, retried, dead-lettered dispatcher, invariant 11) was dropped
  earlier by the business and is recorded here on 2026-09-28. `event/CaseEvents.java` stays, as
  in-process events only.
- **D54 — retired by Unit 64.** There are no requests, so there is no unfinished-requests screen.
- **D55.** **Appointments are built on GHL's calendar APIs, to standard practice** (2026-09-29,
  closes Q9): cancel, guests, blocked-off time and notes all go through GHL's own calendar /
  appointment endpoints (GHL remains the calendar), searched in the GHL docs before any endpoint is
  assumed; employee availability joins a GHL user to a `team_member` (`team_member.ghl_user_id`).
  **Built as Unit 60 (2026-09-29).** Guests have no GHL endpoint to go through — see
  `open-decisions.md` Q14.
- **D56.** **A marketing lead does not overwrite a queued one** (2026-09-29, closes Q1): if the
  contact's open opportunity on that pipeline has a change still waiting in the sync queue, a new
  lead does not overwrite it; otherwise `upsertOpportunity` may update it as today.
- **D57.** **Sales reach the client through the Client conversation** (2026-09-29, closes 00d §12 b):
  pipeline Sales are already members of it (Unit 57), so no separate ownership split is needed.
- **D58.** **EvalOS sends four client emails beyond the auth mails** (2026-09-29, closes 00d §12 c,
  the recommendation taken): checklist + link, draft ready, expert signing link (now: sign-in),
  delivered — as one unit, with a written invariant-14 amendment. **Plus the Send chase button
  (2026-10-01, the business):** a PC/CM chase emails the client a reminder with the same count
  (`CHECKLIST_CHASED` → `CaseUpdate.CHASE`); the 24h/48h `DOC_CHASE` sweep still emails nobody and
  prompts the Coordinator in the bell. **The checklist
  email goes out on every checklist send (D60)**, first list or later additions: "you have N
  documents to upload — sign in"; the upload itself stays in the portal. **Built 2026-09-30 as
  Unit 64c** (spec `64c-case-progress-emails.md`): `CaseMailListener` on `CHECKLIST_REQUESTED`,
  `DRAFT_READY_FOR_CLIENT`, `EXPERT_SENT_FOR_SIGNING` (to the case's expert) and `CASE_DELIVERED`;
  each a deep link into the portal, never a credential; portal brand only; a failed send is logged.
- **D60.** **The document checklist is sent to the client by the PC or the CM** (2026-09-29, the
  business): either role may add any document to a case's checklist; items are **unsent** — not
  visible in the client portal — until one of them presses **Send**, which publishes every unsent
  item at once. Items added after a send stay unsent until the next Send, so a list is never
  "sent twice": with nothing unsent there is nothing to send, and both roles see who sent what and
  when. The client uploads against each published item, as today. Each send triggers D58's
  checklist email. **An expert's evidence-request item is unsent like any other** (closes Q15) —
  the coordinators are alerted and one Send publishes it. **The CM also sets item status**
  (approve / incorrect / missing), the same as the PC (closes Q16).
- **D59.** **Expert payments are managed manually by the Expert Network Manager** (2026-09-29): the
  staff payout screens stay the ENM's tool. **Edited 2026-09-29:** the expert portal shows the
  expert a Payouts page of what the ENM has recorded (case, amount, status, date sent; never
  `payment_detail`, never the transfer's method or reference) — spec `62`. **Edited again
  2026-09-29 (Unit 63):** a payout is **Pending → Processing → Paid**, and **the expert confirms
  receipt**, because every entry is manual: the ENM's recorded transfer (`PAID`) shows as
  *Processing* until the expert presses *Confirm received* in the portal (`CONFIRMED`, shown as
  *Paid*). **Staff no longer confirm** — that route is removed. The portal's one write is that
  confirmation; nothing there pays, requests or disputes a payout. The ENM pays weekly (the batch) and
  reports weekly, monthly or yearly by due date, with two exports: totals and every row (CSV). No approval step and no failed state (the business chose three).
  **Edited 2026-09-30 (Unit 65, spec `65-case-fee-and-payouts-module.md` — built 2026-09-30):**
  **every offer carries the amount the case pays** (a blank fee means the expert's standard fee, a
  retake keeps the declined offer's fee, no fee at all is refused; set by GM / PM / PC / ENM; a CM
  offers at the standard fee only), **editable only while the offer is open**, **shown to the expert before they accept**, and frozen at acceptance. Delivery opens the
  payout at that amount; the pending-payout amount correction is **removed** (only a *missing* amount can still be set, once). **No adjustments** —
  no bonus, deduction, advance or change after acceptance: per case it is offered amount, status,
  done or not. Every set, edit and outcome is in `audit_event`. Payouts becomes its own staff module
  (Overview, Cases register, Experts, Pay run) on the shell's period and brand filters.
- **D61.** **The ENM runs the expert lifecycle in EvalOS, and the hiring pipeline is a GHL
  pipeline** (2026-09-29, the business; Unit 63, spec `63-enm-workspace.md`). This reverses the two
  written refusals `00d` §7 names and replaces `00d` Phase 5's `expert_application` design. A GM
  tags a mirrored pipeline `EXPERT_HIRING` (stages *New Lead, Meeting Scheduled, Meeting Done, In
  Process, Onboarded, Dropped* live in GHL; EvalOS hard-codes none). **Every ENM of that pipeline's
  brand works it — the tag is the grant**, derived in `TeamMemberPipelineRepository.ghlIdsFor`.
  The ENM uses the Sales desk's board, stage move (mirror + outbox, D44), candidate upsert and deal
  notes; `PipelineScope` bounds every id. **A won hiring opportunity never opens a case**
  (`CaseIntakeService`, a second lock on invariant 8). *Onboarded* leads to the expert database by
  a pre-filled create form, never automatically. The directory adds credential verification
  (`expert.credentials_verified_at`) and a per-expert case history (offered / accepted / submitted
  / delivered / rejected / reassigned, derived). The ENM is told about GHL-side pipeline moves,
  payouts due and confirmed, declines and overdue signatures (in-app, D37).
- **D62.** **A declined case may be retaken** (2026-09-29, the business): the expert who declined or
  timed out may be offered the same case again while it still waits for a rematch
  (`EXPERT_DECLINED_REMATCHING`) and they are `AVAILABLE`. Whoever may reassign permits it (GM, PM,
  ENM, CM — `POST /api/cases/{id}/expert/retake`); it is the rematch transition with the
  same-expert guard lifted, back to `CLIENT_APPROVAL`, audited with a *retake* note.
- **D63.** **Everyone on a case sees and downloads its documents; the ENM does not** (2026-09-30,
  the business; Unit 66b, spec `66b-expert-case-documents.md`). Staff who can load the case and
  whose role reads case content (GM, BM, PM, PC, CM) see every document and every draft version.
  Only the CM uploads the draft and each new version (D51). **The case's
  expert** sees and downloads the client's current uploads and the client-approved draft (PDF +
  Word), from the offer on, for as long as they are the case's expert
  (`GET /api/portal/expert/documents/{id}/url`, audited); earlier draft versions stay internal. The
  **ENM** reads no case documents (`Tier.SUPPLY`): they staff experts, and a file name alone can
  name the client.
- **D64.** **The BDE's "Add lead" is GHL's full opportunity form** (2026-09-30, the business;
  Unit 39b, spec `39b-complete-lead-form.md`): contact, name, value, expected close, stage of the
  BDE's own pipeline, **owner** and the sales form's intake custom fields, still an upsert on
  (contact, pipeline). **The sales "Add opportunity" gains the same owner picker** (a GHL user from
  the mirrored location users; blank = GHL's round-robin). Pipeline, status and production-state
  fields stay off both forms.
- **D66.** **Sales edits every field of its deal and may delete it** (2026-09-30, the business;
  Unit 69, spec `69-deal-edit-delete.md`). Editable: name, value, stage (own pipeline), expected
  close, owner, intake custom fields; never pipeline or status (status stays Won / Lost /
  Abandoned). Name / value / stage stay queued (D44); close, owner and custom fields go to GHL
  inline, D46's reason — the owner is still GHL's under D42, EvalOS just writes it there. **Delete**
  is inline `DELETE /opportunities/{id}`, refused on a **won** deal (its case exists) and while a
  push is still queued; the mirror row is stamped missing, never deleted. Sales only.
- **D65.** **A case opening emails the client, once** (2026-09-30, the business; Unit 64b, spec
  `64b-case-opened-emails.md`). No password yet → *Your case has started — set your password*,
  naming the service and case code, with a set-password link valid **7 days** (sign-in and reset
  links keep `credential-ttl`). Password already set → *Your new case has started*, a sign-in link
  and no credential. The generic set / reset password mails stay for sign-in and forgot-password.
- **D67.** **An expert is emailed when a case is offered to them** (2026-09-30, the business; closes
  Q17; Unit 64c). On every `EXPERT_ASSIGNED` — first assignment, rematch and retake, each of which
  opens an offer — the case's expert gets *A new case is offered to you*: service, case code, the
  open offer's fee in the brand's currency (left out when unpriced), a deep link to the case in the
  expert portal, no credential. No answer deadline is stated: none is enforced on the offer.
- **D69.** **Every expert offer carries a note to the expert** (2026-10-01, the business). Assign CM +
  expert and Reassign expert refuse a blank note; a retake re-sends the expert's last note. The
  expert reads it with the offer, above Accept. Built as Unit 71 (`71-offer-and-win-notes.md`).
- **D70.** **A deal won in EvalOS carries a Sales note for production** (2026-10-01, the business;
  GHL's won has no place for one). Won refuses a blank note — **and so does moving the deal to its
  pipeline's Won stage** (board drag or stage picker), which wins the deal (stage + status won); the note is a deal note flagged
  `handoff`, synced to GHL like any other, and shown on the case page as the Sales handoff note.
  Built as Unit 71.
- **D71.** **Clients and experts accept the portal's policies on first sign-in** (2026-10-01, the
  business): the Privacy Policy, Disclaimer and Document Retention Policy, once per account and
  policy version, recorded on the account and as a `TERMS_ACCEPTED` audit row; fail closed. Both
  portals' sign-in screens carry the portal artwork on the right half. Built as Unit 72
  (`72-portal-terms-acceptance.md`).
- **D73.** **A portal sign-in survives a reload** (2026-10-02, the business). The client's and the
  expert's token is kept in `sessionStorage` (per tab, gone when the tab closes), replacing the
  memory-only token. Both portals have a **Sign out** that revokes the token on the server
  (`POST /api/portal/sign-out`); a 401 while signed in returns to sign-in with "Your session has
  ended". Built as Unit 75 (`75-portal-session-survives-reload.md`).
- **D74.** **The client sees a status card, not a progress bar** (2026-10-06, the business; Unit 76,
  spec `76-client-status-card.md`). Six client statuses — Awaiting Documents, In Preparation, Awaiting
  Client Review, Under Expert Review, Delivered, On Hold — are a **projection** of stage + exception
  state (`PortalStageProjection.ClientStatus`), never stored. Each status shows the **latest
  client-facing remark and its date**; the client also reads a **dated history of status changes**.
  Remarks are `case_client_remark` (`V85`), written by GM / PM / PC / CM, **append-only and apart from
  every internal note**, which never reaches the portal. Changes requested → In Preparation (the
  existing revision transition). **On Hold requires a reason, which the client sees, and the case
  keeps its stage so Resume returns to it.** A service that skips a review step simply never shows
  that status. Replaces Unit 58's milestones.
- **D68.** **EvalOS screens update themselves** (2026-10-01, the business). A committed write to a
  case (the case, its documents, checklist, offers, payouts, draft comments) sends a **signal, never
  data**, over Ably: `case.changed {caseId}` to a per-brand staff channel and to the case's client
  and experts, and `notifications.changed` to a bell's owner. Open screens re-read over REST in
  the background, and also on tab focus and on reconnect. GHL-mirrored screens and chat are out of
  scope. **Built as Unit 70** (`70-live-screens.md`, 2026-10-01). **Amended 2026-10-01 (the
  business): the staff app reads through TanStack Query (Unit 70a, both phases built — payouts and experts are case-shaped; dashboards and meetings re-read on focus)** — any
  successful write refreshes every case-shaped screen, from one interceptor; screens re-read on tab
  focus; the bell count every 60 s. That covers the acting person's own screens now and other
  people's on focus; Unit 70's push then becomes one invalidation.
- **D52.** **No GHL conversation sidebar.** EvalOS does not mirror or send GHL conversations
  (SMS / email / WhatsApp / social); that stays in GHL. The only messaging in EvalOS is the case
  chat (Unit 57). Decided 2026-09-28. _(Closes Q10; drops tier 3 of the Unit 47 mirror.)_

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
