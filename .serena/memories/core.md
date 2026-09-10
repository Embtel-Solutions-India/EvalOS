# EvalOS — Core

> ## ⚠ SCOPE CUT 2026-09-02 — Units 13, 18 and 20 are REMOVED
>
> - **13 Redacted CV** (was built): deleted. `RedactedProfileService`,
>   `ExpertProfileController`, `RedactedProfilePanel`, `redactionRules`, the
>   `REDACTED_PROFILE` document kind, and the portal's `expertProfile` /
>   `expertReference`. **The client is told nothing about the expert at all now.**
>   `AuditAction.EXPORTED` is **kept** — the audit trail is append-only, so the enum must
>   read every value any historical row carries. `mayMintPortalLink` moved to
>   `client-portal/portalRules.ts` (it was Unit 14's rule, only sharing a file).
> - **18 Outbound dispatcher + Handoff C** (never built): removed. **Two handoffs, not
>   three. EvalOS emits NOTHING outbound.** Inbound GHL webhooks and read-only funnel pulls
>   are the whole integration surface. The payout entry on delivery survives — that was
>   Unit 16's. Telling GHL a case was delivered is now **manual**.
> - **20 AI widgets** (never built): removed, and promoted to **invariant 15 — no AI makes
>   a production decision and there is no AI in the system.** Unit 12's match engine is not
>   an exception: declared factors, inspectable arithmetic, never auto-assigns, no model.
>
> **Consequences to carry:** invariant 14's "sends no email" is settled, not pending —
> there is no outbound channel at all. Unit 30's PDF question is **closed by removal**: the
> redacted profile was the only document EvalOS generated. And client-facing messages
> (chases, "draft ready") have **no automated route** — manual in GHL, or they become
> states in the client portal, which is still open.

> ## ✅ production lifecycle v2 — twelve stages, eight columns (Unit 31, BUILT 2026-09-02)
>
> **Read `context/specs/31-production-lifecycle-v2.md` before touching the state machine,
> the board, or any transition gate.** Everything below describes the **code as it stands**
> — five active stages plus sub-status chips. It no longer describes the **decision**.
>
> - **Twelve stages, one owner each; the board draws EIGHT columns** — two stages share
>   one only where they share an owner (05+06 Coordinator, 07+08 CM), so a column still
>   answers "whose turn is it". Delivered and Closed are a filter, not columns.
> - **A stage is entered by the act that starts its clock** — Client Review by the
>   Coordinator's *Send*, Expert Signing by the CM's *Send to Expert*. No `sent_at` columns. PM approval, client approval and the QC/delivered
>   split stop being sub-statuses and become stages. The three columns stay, demoted: they
>   record a review *outcome*, they no longer drive the stage.
> - **Two transitions are added.** `qc-fail` (a failed final QC has nowhere to go today)
>   and `send-to-expert` (the act that should start the 24h SLA — it currently runs from
>   `stage_entered_at`, so an expert sent the letter late is charged for the delay).
> - **Gates move.** The **Case Manager** takes expert signing, `expert/timed-out` and
>   reassignment; ENM is notified and supports. `docs-complete` narrows to the Coordinator.
>   `draft/pm-approve` and `pm-return` stay PM-only with no GM override (Unit 23a) —
>   unchanged, and checked rather than assumed.
> - **The draft becomes a versioned file** in a new `case_document` table; `draft_link` and
>   `draft_version_count` are replaced. No version is ever overwritten.
> - **No AI in any production decision** — verification, expert selection, drafting,
>   review, approval, reassignment, QC. Automation is limited to notifications, timestamps,
>   versioning and audit.
> - **Reverses spec 08's derived-grouping decision.** A chip says what state work is in,
>   not whose turn it is.

> ## ✅ S3 document store — Google Drive is GONE (Unit 30, BUILT 2026-09-02)
>
> **Read `context/specs/30-s3-document-store.md` before touching any document path.**
> Drive is not "still in the code": the API client, the service account, the dependency and
> `drive_link` (`V34`) are all deleted. Any Drive note elsewhere in this memory is history.
>
> - `integration/DocumentStore` — **two capabilities and no more**: `put` and `presignedUrl`.
>   No delete, no list, no copy. Unconfigured is a **502, not a failed boot**.
> - **Keys are brand-first**: `{brandId}/client/{ghlContactId}/{documentId}` and
>   `{brandId}/case/{caseId}/{folder}/{documentId}`. The object name is the document's own id —
>   which closes path traversal, collisions and PII-in-the-key at once.
> - `{ghlContactId}` is **GHL's contact id** — one client across GHL, the portal and EvalOS, no
>   mapping table. **Email stays a fallback key (V27), never the identity, and never in a key.**
> - Reads are **5-minute presigned URLs**, minted *after* the scope check, never stored.
> - **⚠ The first draft of spec 30 said a separate Client Portal application writes to S3 with
>   EvalOS read-only on `client/`. That was CORRECTED the same day and is wrong.** The portal is a
>   separate **frontend** with **no AWS credential at all**; it calls EvalOS, and **EvalOS is the
>   only writer**. What replaces the IAM guarantee: bucket versioning is non-optional, and EvalOS
>   never overwrites a `client/` key — every upload mints a new one.
> - **`CLIENT_UPLOAD` and, since Unit 15, `SIGNED_LETTER` rows have an `object_key`.** `draft_link`
>   is still a free-text link the CM pastes and a `DRAFT` `case_document` is created with no key —
>   which is why **the hash of the letter *as sent* cannot be recorded**: EvalOS holds no bytes of
>   it, and `DocumentStore` has no read capability. Half of Unit 15's hash pair is deliberately
>   missing rather than faked; it lands when a draft becomes an object.
> - **Invariant 14 is amended and "No object storage" is deleted** from `architecture.md`.
> - **CORS is built with it**, on `/api/portal/**` only — see the module-contract bullet below.

**The shell's date filter is backwards-looking and the production board's is forwards — two types,
not one (Unit 28).** They were one shared value for two units, which once left the production board
effectively unfiltered when the default was widened for a marketing screen. `DateRange` (seven
periods: today/week/month/year to-date, last-month, last-year, custom) is the shell's; the board
owns `DeadlineWindow` (week/month/year). **Enforced by the type — do not re-merge them.**
`DateWindow` resolves a period into inclusive days and is the only place that arithmetic lives.

**Verify the frontend with `npm run build`, never `npx tsc --noEmit`** — the root tsconfig has
`files: []` with project references, so plain `tsc` checks nothing and exits 0. Three real errors
hid behind it once.

Production CRM for a **multi-brand** credential-evaluation business (International Evaluations,
XpertsPortal). Takes custody at **`opportunity.won`** in GoHighLevel (GHL) and owns the case to
signed delivery + expert payout.

**It is no longer only back-of-house.** This memory used to end that paragraph with *"GHL stays
front-of-house (leads, sales, invoicing, review campaigns); EvalOS never does marketing, sales, or
invoicing."* **That clause is dead as of Unit 37 (2026-09-10)** — the sentence is rewritten here
rather than contradicted a line below it, because a memory that argues with itself is worse than
none. What is true now: **GHL stays the CRM, the pipeline engine, the automation engine and the
invoice/QuickBooks integration; EvalOS is the interface Sales and Marketing work in.** Invoicing
is still GHL's — EvalOS reads invoices and raises none.

**Three of six units are built (36, 37, 38).** The **GHL
operational programme (Units 36–41)** makes EvalOS the interface Sales and Marketing *work in*, so
they never open GHL; GHL stays the CRM, pipeline, automation and invoice/QuickBooks layer
underneath. Spec: **`context/specs/00b-ghl-operational-programme.md`** — read it before any GHL
work, it holds the truth model and the invariant ledger.

**What is decided vs. what is live**, because the gap is where mistakes go:

| | |
|---|---|
| **Decided** | Two roles `SALES`/`MARKETING` (`Tier.PIPELINE`) + a `segment` column, *not* six roles; one personal exclusive pipeline each; **GHL owns the opportunity, EvalOS owns the note stream keyed on `ghl_opportunity_id`**; a droppable non-authoritative opportunity cache; **single selling brand** (`evalos.ghl.sales-brand`) enforced with a 400 until Unit 25 |
| **Built** | **36** (`V39`): eight roles, `Tier.PIPELINE` fails closed, `team_member.ghl_pipeline_id` + `segment`, two GM routes, single-brand ceiling enforced with a 400. **37**: `GhlHttp` has `post`/`put`/`delete`; **invariant 2 dead and rewritten**; guard replaced by "verb list closed" + "every write caller reaches `AuditService`". **38** (`V40`): `ghl_opportunity_cache`, `GhlOpportunityClient`, `GET /api/opportunities/board` for SALES/MARKETING/GM, and the SPA's two new roles + their board. |
| **Next** | **Unit 39** — the marketing lead desk (`V41`). Amends invariant 7, introduces `opportunity_note` (keyed on `ghl_opportunity_id`), and is **the first caller of the write door**, so it owns the idempotency decision Unit 37 deferred. |

**⚠ THE PIVOT IS NO LONGER CHEAPLY REVERSIBLE, as of Unit 38.** `ghl_opportunity_cache` is the
first EvalOS row holding a pipeline fact — the exact thing invariant 2 spent years warning about,
and the absence of which made Unit 29's removal cost one migration and no reconciliation. Three
properties keep it honest and all three are checkable: **every column is a field GHL owns**, it is
**droppable without loss** (`TRUNCATE` costs a refill), and a pipeline is **replaced wholesale**
rather than upserted. If a column ever appears that GHL has no field for, the truth model in `00b`
§1.3 is void and gets re-argued before the column lands.

**Board facts worth not rediscovering:**
- **One board, not two.** Sales and Marketing ask the same question of the same data; the role
  gate is the only difference. `GET /api/opportunities/board` takes **no pipeline parameter** and
  must never take one — the caller's principal decides everything.
- **The cache is scoped structurally, not by `ScopePredicate`.** It has no `brand_id` (deliberately
  — one selling brand would make it a column pretending to be a scope), so every finder *requires*
  the pipeline id and it comes from the principal. `CachedOpportunityRepositoryScopeTest` fails the
  build on an unscoped finder, and asserts `-parameters` is on so it cannot pass by being blind.
- **Refill is inline, TTL 2m, no webhook eviction.** §4's ~13s floor is a *year of one marketing
  funnel*, not one person's pipeline. There is no `opportunity.updated` subscription to evict on.
- **P1 answered: the GM's union is the configured selling brand's pipelines**, not every brand's.
- **`/api/marketing/sales-pipeline` and the three `*-pipeline-name` properties were NOT deleted**,
  reversing what specs 36 and 38 said. Those are analytics funnels over a date window; the board is
  an operational card list with none. Different questions. The naming redundancy is real but fails
  loudly (502 on rename).

**⚠ `LocalPostgresIntegrationTest` can skip silently and report SUCCESS.** Its probe timeout was
2s and lost the race under a full `verify`, skipping **all 36** schema tests while the build went
green — the same tests that caught `V39`'s NULL-in-CHECK bug. Raised to 10s in Unit 38. **Use
`-Devalos.db.test=true` in CI**, which forces the suite on so a broken database fails rather than
vanishes. If a run reports a jump in "Skipped", check this first.

**Two things the programme does NOT touch:** invoicing stays GHL's (Unit 41 *reads* invoices,
raises none), and **invariant 8 is untouched** — a case is still born only of a won opportunity
through Handoff A, even though Sales now marks it won from an EvalOS screen.

**"Never does" means never *runs* — Units 24, 26 and 27 draw the line and are the only things on the
other side of it.** EvalOS *reads* three GHL pipelines onto GM screens — the **Google ADS Pipeline**
(Unit 24), **Shivangi's Email Marketing** (Unit 26) and **Aditya's pipeline**, the sales team's own
funnel (Unit 27) — all in the one configured location, and owns none of them: no lead created, no
stage moved, no campaign sent, no write back, and **nothing persisted** — there is no `ghl_opportunity` table and there must not be, because a stage a
salesperson dragged five seconds ago is already wrong in a copy. This resolves a question open
since Unit 17 (whether EvalOS builds the sales/marketing dashboards) as **read-only GM views over
pipelines in that location, everything else in GHL**.

**Unit 27 is the sharpest test of "reads, never runs"**: it shows stages named `Invoice sent` and
`Refund` and acts on neither, because invoicing is GHL's and a refund is a payment fact. Reading a
*sales* pipeline is no more selling than reading a campaign funnel is marketing — and the screen's
`Sales` nav heading does **not** buy it any different scoping: same `location-id`, same
unattributable brand, same GM-only door.

**⚠ Unit 29's sales desk was BUILT (2026-08-29) and REMOVED (2026-09-02).** It amended
invariant 2 — a `SALES_EXECUTIVE` operated *Aditya's pipeline* from EvalOS, writing straight to
GHL — and **the amendment is reverted with it**. Everything above about the three funnel screens
was true throughout and is untouched.

**⚠ THAT RULE IS OVER as of Unit 37 (BUILT 2026-09-10). `GhlHttp` writes.** It has `post`, `put`
and `delete`, and invariant 2 is rewritten in `architecture.md` rather than annotated.

**What guards it now, because "the test was deleted" is not an answer.** Two build-failing
assertions replaced the old "no write verb" one, both in `GhlHttpTest`:
1. **The verb list is closed** — exactly `get`/`post`/`put`/`delete`. A fifth fails the build, so
   the next capability is also a decision. No `patch`: GHL's API does not use it.
2. **Every caller of a write verb must reach `AuditService`** — a source scan. This is
   invariant 13 for writes landing in another system: *a mutation whose only trace is in GHL is
   invisible to EvalOS forever.* `GhlHttp` audits nothing itself (it is transport — invariant 12's
   reasoning), and **the caller set is empty today**, so the first unit that writes has to add
   itself and notice the requirement.

**Also true and easy to lose:** writes **do not retry** (no idempotency key scheme yet — a blind
retry is one opportunity becoming two), reads and writes share the **one** location pacer, and the
credential is unchanged and was never the guarantee.

Unit 29's own artefacts are still gone: `/api/sales/**`, `SalesController`, `SalesBoardService`,
`GhlSalesClient`, `Role.SALES_EXECUTIVE` and `team_member.ghl_user_id` are deleted, and
`V30__drop_sales_executive.sql` reverses V29 in the database. **Unit 36/37 did not restore any of
them** — the new model is pipeline-scoped, not assignee-scoped.

**The reason the removal was cheap is the decision that never changed:** nothing was ever stored
here — no `ghl_opportunity` table, no sales row anywhere. So it cost one migration and no data
reconciliation. **That property is being spent deliberately at Unit 38**, whose cache is the first
EvalOS row to hold a pipeline fact — so *this* pivot will not be reversible at Unit 29's price,
and that is its real cost. The question *"does this make EvalOS store a pipeline fact?"* is still
the right one to put to any GHL proposal; the answer is now "yes, and only fields GHL owns, in a
table that is droppable without loss".

**Do not cite the desk as precedent for writing to GHL.** It was tried, shipped and undone; a
future write adds the verb to `GhlHttp` and answers for it in invariant 2.

**That future write is now scheduled: Unit 37, the write door.** Two things carry over from the
Unit 29 episode rather than being overturned by the new direction:

1. **Unit 37 is a unit of its own precisely because of this history.** Deleting the
   `GhlHttpTest` guard as step four of a feature ticket is how a deliberate constraint disappears
   with nobody deciding. Unit 37 is where somebody decides, and it ships no feature.
2. **"Does this make EvalOS store a pipeline fact?" is still the right question — but the answer
   is now knowingly yes, at Unit 38.** The cache is what makes a board load at all (~115
   un-parallelisable cursor pages, a ~13s floor, past the browser's 15s timeout). It is kept
   honest by holding *only* fields GHL owns and by being droppable without loss. **So the pivot is
   NOT reversible at the price Unit 29's was**, and that — not the code — is its real cost.

Unit 24 said a second marketing screen would be a new question; Unit 26 asked it and the answer is
**yes for another *reading* of a pipeline in the same location, on identical terms**. Read that
narrowly too: a marketing *module* — anything that creates, sends, prices or attributes — is still
a different question, and its default is still no.

The custody trigger has moved twice. **Case Creation v2.0 (spec `05b`) is current: the webhook *does*
prove payment.** GHL invoices and collects before an opportunity is marked Won, so that one event is
both the reason the case exists and the record that it was paid — the case is created **paid**, and no
staff action sets `paid`. Unit 05a's rule (`contact.created`, an unpaid case, payment recorded by hand
afterwards) is history; so is the note that used to sit here saying "the webhook proves payment" is
wrong. In v2.0 it is right.

**Custody symmetry — GHL owns pipelines, EvalOS owns what is real.** A case at
`opportunity.won`; an expert when the ENM adds them to the roster; retention never. This is why there
is **no expert-recruitment pipeline** here — a prospect moving through Identified → Contacted →
Agreement Sent is the same object as a sales opportunity, and GHL already runs pipelines. So
`expert.agreement_status` is GHL's fact and has no writer on purpose; if it ever needs to be live the
shape is an inbound `expert.agreement_signed` mirroring `opportunity.won`.

**`context/process-automation.md` is the trigger→recipient map.** Every A-numbered automation from the
CRM build spec, what event it publishes, who hears it, the owning unit, and whether it is built. Read
it before adding a notification or a timer — and update it in the same step as the code, because
moving a row from *gap* to *built* is part of the unit that built it.

## Spec-driven build — read before coding

This repo is built unit-by-unit against written specs, not improvised. `CLAUDE.md` is the entry
point; `context/` holds the working build context (`project-overview`, `architecture`, `ui-context`,
`code-standards`, `ai-workflow-rules`, `progress-tracker`). The authoritative design is the **EvalOS
Technical Design Document v1.1**; where a context file conflicts with it, v1.1 wins.

- Ordered unit list: **`context/specs/00-build-plan.md`** (20 units, 3 phases). An earlier version of
  this memory claimed it sits at `context/` root and that `CLAUDE.md` was wrong about it — that was
  itself wrong; `CLAUDE.md` has always been right.
- Unit specs are `context/specs/NN-name.md`. **01–10 were generated just before each unit was built;
  11–20 were written in one pass at the start of Phase 2.** The just-in-time rule stands as the
  default, so 11–20 are **drafts to re-read and revise at the start of their own unit**, not settled
  contracts (18–20 say so in their own headers). Writing them ahead already produced four
  corrections to earlier assumptions, which is the cost the rule exists to avoid.
- Do not invent product behavior absent from the context files — add an open question to
  `context/progress-tracker.md` instead. That file is also where **decisions and their costs** are
  recorded (Phase 2 readiness section + Open Questions); read it rather than re-deriving why
  something was chosen.
- Update `context/progress-tracker.md` after every meaningful change; update the relevant context
  file (and the TDD) if a decision changes.

**Current state: Phase 1 (Units 01–10, with 05a since re-pointed by 05b — see the trigger
note above) is complete and verified, and Phase 2 has started.**
Scaffold + response envelope, the tenancy/auth/RBAC spine, the domain schema, the case state machine
+ SLA calendar, the inbound webhook gateway with Handoff A, the in-app notification centre, and five
live frontend surfaces (app shell + role routing, production Kanban board, case detail + notes/timeline,
document checklist board, expert database). CI runs the DB suite against a real Postgres on every
push.

**Phase 2 is Units 11–17 and is under way. Units 11 (expert database + sheet upload), 12 (match
scoring engine), 14 (client draft-review portal) and 05b (Case Creation v2.0) are complete and
verified. Unit 13 (redacted CV generation + the Drive write) is code-complete with ONE acceptance
criterion outstanding — the manual live upload, blocked on a Google service account that does not
exist. Unit 16 (payout ledger) is next — not 15, which waits on Unit 21 and on that same Google
account; the schedule is `00-build-plan.md`'s "Execution sequence for v2.0", and it differs from the
numbering on purpose.** Migrations run to **`V26`** (Unit 13 added none — nothing it produces is
persisted; Unit 14 added three, its code review added `V23`, Unit 05b added `V24`, the funnel cache
added `V25`, and Unit 28 re-keyed it in `V26`);
**494 backend tests** (29 DB-backed, and the DB suite no longer skips itself when Postgres is
reachable) and **130 frontend tests**. Counts move every unit — treat them as a rough marker, and
run the suites rather than quoting these.
**Unit 23 made the Project Manager the front door and gave the case a conversation.** The GM lost the
board's pool lane and the `/inbox` + `/checklists` nav entries (**nav only — no backend gate was
narrowed**); `assign-pm` now admits the PM, who claims a pooled case from their inbox. A PM can read
a pooled case because `ScopePredicate.Fields.unteamedVisible` is set on cases and nowhere else
(`mem:backend/security`). **Case notes are audit rows, not a table** — `NOTE_ADDED`, written with no
`@PreAuthorize` because the scoped load is the gate; see `mem:backend/lifecycle` and
`mem:backend/persistence`. `Timeline` is now *Notes & timeline* (`mem:frontend/core`). Spec:
`context/specs/23-case-notes-and-pm-routing.md`.

**Unit 25 is specced and NOT built. It is UNSCHEDULED, not blocked — the one decision it waited on
was signed off 2026-08-26.** It replaces Unit 24's hand-pasted Private Integration Token with a
**per-brand OAuth grant**, turning the GHL credential from global config into a brand-scoped row —
the move `architecture.md` already anticipated. A refresh token must be *recoverable* (we replay it
to GHL) so it cannot be hashed like a portal token, which makes it **EvalOS's second encrypted
column** against `code-standards.md`'s former "only encrypted field".

**The approved way to add it (option 1 of four): extract the AES-GCM from `PaymentDetailConverter`
into one `common/EncryptedStringConverter`, leaving `PaymentDetailConverter` a thin subclass.** That
is a **named, narrow exception** to the protected-file rule — that extraction only, expert-path
behaviour unchanged (same key, AES-256-GCM, fresh 12-byte IV per write, authenticated failure on a
tampered column). Every other change to that file still needs its own sign-off. **Do not write the
extraction until Unit 25 is actually built**: a shared abstraction with one implementation is what
this codebase deletes. The rule that did not move — a credential that never has to be replayed is
**hashed, not encrypted** (portal tokens); encryption is only for what must be recovered. Two things to know before touching it: **GHL rotates the refresh token on every
refresh**, so refresh must hold `SELECT … FOR UPDATE` on the row and re-read after acquiring the
lock or two rolling-deploy instances will retire each other's grant; and **the PIT is deleted rather
than kept as a fallback**, which was free while no deployment used the PIT — note the live
*client* test now runs against it (read-only), but no environment serves the screen from it yet, so
the window is still open. It closes the day a deployment sets `GHL_API_TOKEN` for real. Spec:
`context/specs/25-ghl-oauth-connection.md`. Its follow-on **25a** re-scopes the funnel (brandId
legal, Brand Manager admitted, invariant 1's exception removed) and is deliberately a separate unit.

**Unit 24 added the first *pull* across the GHL seam.** Until it, that seam was events in
(Handoff A) and events out (Handoff C); `GhlPipelineClient` is a third direction and the only one
that is not a handoff — two read calls on an `opportunities.readonly` token, **no write method on
the client at all**. Three things to know before touching it: the **cache is the rate limiter, not
a speed-up** (without it N open dashboards are N multi-page GHL reads per refresh, and a failed
refresh is deliberately **never** served from the previous value, so the screen shows the error
rather than a stale figure presented as live); **`status` is deliberately not read**, because
opportunities sitting in the *Won* stage still report `status: "open"` and two disagreeing axes is
two places for one fact to be wrong; and **no stage name is special-cased anywhere** — order,
labels and membership all come from GHL, so a rename there is not a silent hole. Config is
`evalos.ghl.*`, and `GHL_API_TOKEN` / `GHL_LOCATION_ID` default to empty on purpose: a missing
token gates one read-only screen (502) rather than failing the boot the way `JWT_SECRET` must.
Spec: `context/specs/24-marketing-google-ads-funnel.md` — whose header records that **it was
written after the code**, which is the wrong order for a change that resolves an open question.
**24 tests over four classes**, including the client driven against a real JDK `HttpServer` serving
GHL's captured response shapes (`GhlPipelineClientHttpTest`) — so header names, the camelCase query
params and the pagination cursor are proven, not assumed.

**The live run from inside the app is DONE (2026-08-26).** `GhlPipelineClientLiveTest` — opt-in on
`GHL_LIVE_TEST=true`, reading the token from the gitignored `backend/config/application-local.yml`
so no credential reaches a command line — calls the real API and passes. Observed: `Google ADS
Pipeline` id `g6lo50r9Wn0qZvmp2bMP`, `Shivangi's Email Marketing` id `LHoIRjpypwhswqO8Ayn0`, both
six stages; the email funnel counted **11,417** over `2025-08-27..2026-08-26` from `meta.total`
alone; the ads pipeline returned **0 rows in the last 30 days**.

**Re-run green for Unit 27 (2026-08-26).** `Aditya's  pipeline` id `tj2agZ90S1LQgCpDAoKi`,
**nine** stages `[Meeting booked, New Lead, Warm, Hot, Invoice sent, Won, Cold, Lost, Refund]`,
resolved from the **single-space** configured name — which is what proves the client's whitespace
normalisation against GHL's real answer rather than against a fixture. **GHL stores that name with
two spaces**; see `backend/core.md`. The location holds seven pipelines, four of them other teams'
(`Alex Pipeline`, `Ayush's Professors Pipeline`, `Master Pipeline`, `Prince's Pipeline`) — which is
why the readable set is a closed enum and not a query parameter. **The old "expected first load of
93 deals (New Lead 7 / Warm 26 / Won 14)" was a hand check, never a live observation, and is
stale — do not use it as an expected result.**

**The screen itself is verified too, 2026-08-26.** Opened in a browser as the GM against live GHL:
Year renders `Aug 26, 2025 – Aug 25, 2026` (365 days inclusive), **11,432 deals · 48 won · $34,301**,
all six stages as rows including empties, and the sources table (`Unattributed 11,300 / $23,801`,
`LCA 35 / $0` — an unpriced source counting as nothing). Month correctly renders the empty state
naming its window. The poll-until-`READY` handover was watched end to end on the same run:
`TOTALLING` with exact counts immediately, `READY` with the money ~75s later, same URL throughout.
**Nothing about the marketing units is unverified now except brand scoping (Unit 25a).**

**The Postgres cache was proven cross-process on the same run**: a *third* JVM with an empty heap,
started after the figures were computed by another, served them in **0.14s** with a byte-identical
`readAt` — so it read the other instance's row rather than calling GHL. That is both the
restart-survival and the multi-instance handover, neither of which the old heap map could do.
Dev login for this: `gm@evalos.local` / `DevPassw0rd!` (seeded by `V900`, and the seed is **not** in
the app's default Flyway locations — a dev database only has it if it was seeded deliberately).

Unit 05b re-pointed Handoff A to `opportunity.won` and **deleted the manual payment path** — details
in `mem:backend/webhooks` and `mem:backend/lifecycle`. Its live hand-fired run is still owed, blocked
on confirmation of what GHL actually sends on Won.
Unit 11 added the closed
`FieldTag`/`LetterType` vocabularies (enum **and** DB CHECK), `email`/`phone`/`letter_types`/
`standard_fee` on `expert`, the write-only `payment_detail` path, `ExpertLoadService` (load derived
from `evalos_case`, never from the dead `V7` counters), the CSV+XLSX roster import, and the
`/experts` screen — details in `mem:backend/persistence` and the tracker's Unit 11 entry.
**The first 28 `FieldTag` values shipped WITHOUT the ENM's sign-off**, on instruction. **Unit 33
widened the list for the first time** and the three-places rule held exactly as written — a new
migration (`V35`) widening `V18`'s CHECK, plus the enum, plus
`frontend/src/features/experts/expertRules.ts`, moved together. What the widening found is the part
worth keeping: the original list was drawn for *credential-evaluation degree fields*, and **10 of the
22 disciplines on a real EOL roster could not be spelled at all** — which `ExpertMatchService` scores
as a zero on a 40-point factor rather than raising anything. A vocabulary that is closed and wrong
fails silently.
Unit 12 ranks that roster for the PM at assignment. It added `V19__expert_case_offer` +
`ExpertCaseOffer`/`OfferOutcome` — **the only queryable record of an accept/decline**, so acceptance
rate is computed from it and never from `expert.performance_flags` (a flag, not a rate),
`evalos_case.expert_id` (overwritten by `reassignExpert`), or an audit `before_snapshot` blob —
and `ExpertMatchService`, whose four factors are **one weighted table** (field 40 / letter-type 25 /
acceptance 20 / load 15) so a reweighting is a data diff; the score is the sum of the rounded parts,
so the breakdown shown to the PM adds up by construction. Two consequences that bite:
`fieldTag` is a **required query parameter**, and it stayed one — but **Unit 33 ended the
matching half of that omission**: `assignCaseManager` / `reassignExpert` now write
`evalos_case.field_of_expertise` from the tag the PM supplies, so a delivered case can say what
discipline it was. Unit 12's argument was against an *intake source* (a webhook carrying no
discipline, then a stale guess), not against persistence, and it named its own closing condition —
"a second consumer, with a real source". The engine still takes the tag as an argument; the
assignment is what stores it, and a null means no match has been run. And an expert below 3 resolved
offers scores
**the roster mean, not zero**, because last place is what stops a newcomer ever getting a record.
Offer invariants are in `mem:backend/persistence`; which transitions stamp them, in
`mem:backend/lifecycle`.
Unit 33 (2026-09-03) closed the gap between what the business records and what EvalOS stored.
An audit of the two sample workbooks against `V1`–`V34` found **19 of 36 expert facts had no column,
no `ExpertForm` component and no import target** — degree, position, affiliation type, location,
LinkedIn, supported visa categories, publications, citations, h-index, patents, awards, memberships,
editorial roles, languages, rush capability, `IE-EXP-###`, turnaround — and **no case stored the
applicant's name**, which is a correctness gap the moment a client is a law firm rather than an
individual (`contact_snapshot` holds who we *deal with*; the letter is about someone else).
`applicant_name` is on `evalos_case` and not the snapshot, because invariant 7 makes the snapshot
read-only GHL truth and Handoff A's confirmed payload carries no beneficiary. Schema detail is in
`mem:backend/persistence`; the UI rule — **list stays lean, detail shows everything** — in
`mem:frontend/core`. Two sheet facts were refused as columns: `last_active_date` (derived from
`expert_case_offer`) and, still and always, any payment detail from a sheet.
Phase boundaries are 01–10 / 11–17 / 18–20 — earlier tracker entries mislabelled 06 onward as Phase 2
and were corrected.
Unit 13 generates the anonymous expert profile a client approves the expert from, and files it into
the case's Drive folder. Three things to know before touching it: redaction is a **whitelist** in
`RedactedProfileService.credentials` (a blacklist is how a field added later leaks by default, and
the test proves it by seeding tokens in every excluded field and searching the output); the
`Expert AK` reference label is a digest of the **case and expert ids together**, so it is stable per
case and different for the same expert on another case; and an unparseable `drive_link` is a
**refusal, never a fallback** to a default folder — a misfiled document is a cross-brand leak
outside the database. `mem:backend/core` for the config and the 502 path.

Unit 14 gave the client their own surface, and it is the first non-staff caller in the system. Four
things to know before touching it. **Two filter chains, neither accepting the other's credential** —
`PortalSecurityConfig` matches `/api/portal/**` and holds no JWT filter, and its `PortalTokenFilter` is
constructed rather than annotated so Boot cannot register it globally (that is the detail that would
otherwise let a portal token authenticate a staff route). **A portal caller is not a `TenantContext`**:
`PortalPrincipal` carries the one case the token names, so the token *is* the scope and
`ScopePredicate` is not involved — see `mem:backend/security`. **A portal link is a credential**: 256
random bits, returned once, stored only hashed, absolute expiry, and re-minting revokes the previous
one; unknown/expired/revoked are one indistinguishable 401. And **`audit_event` grew its first new
column ever** (`actor_type`, on explicit instruction, nullable and unbackfillable) so a client's
approval is attributed to the client rather than to a null that reads as the system —
`mem:backend/persistence`. Handoff B is now something a client can perform. The link still has to be
**copied out by staff**: whether GHL can deliver it on an event is open question (b), and Unit 18 owns
the dispatch if the answer is yes.

Its **code review found five real things and none of them were in the scoping, the whitelist, the two
chains or append-only** — three were comments describing code that had changed under them (two of
them saying the portal mounts from `main.tsx`, which it does not), one was `recordEvent` hardcoding
`ActorType.STAFF` where its own contract allows a null actor, and one was the mint being a
check-then-act, fixed with `V23`'s index. The lesson to carry: on this codebase the comments *are* the
contract, so a design decision reversed mid-unit has to be chased through every place that describes
it — the tracker, the memory, the context file and the javadoc.

Later units carry named external dependencies that do not exist yet (the GHL outbound contract for
18) — all listed in the tracker. **Unit 13's Google service account is
the one that has already bitten**, and it now blocks three units rather than one: 13's own live
upload, Unit 21's client document upload, and Unit 15's signed-letter upload. Unit 15 used to be
gated on a Dropbox Sign account; there is no signature provider any more, and **Unit 15's code is
now built (2026-09-03)** — what it still owes is the same live round-trip against a real bucket.
Here, the code is finished and the live upload is not, so the unit is
open. Until it runs, three things are proven only against a test double — that the credentials
work, that the `drive.file` scope suffices for a create into a shared folder, and that Drive's
HTML → Doc conversion is worth sending to a client.

## Layout

Monorepo of **three** apps, no root build: each is built and run from **inside its own
directory**, with its own `package.json` / `pom.xml` and its own lockfile.

- `backend/` — Spring Boot 3.5 / Java 21 Maven project, base package `com.ie.evalos`.
  `mem:backend/core` for package boundaries, config profiles, Flyway ownership, the response
  envelope.
- `frontend/` — the **internal staff** Vite + React 19 + TS SPA, port 5173, `/api` proxied
  same-origin. `mem:frontend/core` for routing, the HTTP layer, and the design-token styling
  system.
- `client-expert/` — the **external portal frontends** (added 2026-09-03), cross-origin
  against `/api/portal/**`. **Two apps, two builds, one dependency set**: `client/` (5174) and
  `expert/` (5175) with a `shared/` folder both import as `@shared/*`, split 2026-09-03 so each
  can take its own subdomain. **One screen is wired** — the client's `/documents`, against the
  real S3-backed portal API (Unit 34 slices 34a + 34c); everything else is still a
  `localStorage` mock. Read `mem:client-expert/core` before
  touching it or either portal: it carries a different auth model, a different case model and four
  duplicate lifecycle vocabularies, and three invariants are in its path. The rest of the wiring
  is Unit 34, gated on decisions D1 and D5.
- `context/` — the specs and design docs above. `README.md` — local run + verify steps.

**Four portal decisions were taken 2026-09-04 and one unit was struck** (`mem:client-expert/core`
for what they mean to the apps). **D1: a portal credential names a party, not a case** — one link
per client or expert, case-scoped links still legal, party tokens 7 days, **and no accounts, which
was refused rather than deferred**. **D5**: one projected vocabulary. **D6**: an expert reads their
own payout rows, never `payment_detail`. **D8**: analytics off, by deletion. **G14**: the AV posture
is implemented — sniff both upload surfaces, serve every presigned read as an `attachment`, and
scanning is the bucket's job. All of it is `context/specs/35-party-scoped-portal-access.md`, specced
and **not built**.

**Unit 20 is gone from the schedule as well as from scope**: no Anthropic key to request, no
anomaly *unit* (that arithmetic is a Unit 17 tile if wanted). With Units 13 and 18, three units are
removed — `V33`, and invariant 15.
- `.github/workflows/ci.yml` — the only CI. Note it runs **`npm install`, not `npm ci`**: the
  lockfile is written on Windows and records wasm-fallback bindings without their `@emnapi/*` deps,
  which `npm ci` rejects on Linux. Cost is that CI resolves within semver ranges instead of pinning;
  regenerating the lockfile once on Linux restores `npm ci`. Reason is written into `ci.yml`.
- No root `package.json`, workspace tool, or Docker compose.

## Project-wide invariants (override convenience everywhere)

- **Brand-scoped by default.** Every scoped query filters by `brand_id` (plus team/assignee where
  applicable). A query without brand scoping is a defect, enforced at the repository/service layer —
  never only in the UI. GM is the only cross-brand role. **Read the rule as: every query over
  EvalOS rows.** Unit 24's marketing funnel is the one screen that is not scoped and it queries no
  EvalOS rows at all — it reads a GHL location the brands share, so no `brand_id` predicate exists
  that could narrow it. That is why it is **GM-only and accepts no `brandId`**: a parameter there
  would narrow nothing while implying it had. The **Brand Manager is deliberately excluded** —
  single-brand everywhere else, and this is the one figure that could not honour it;
  `navigation.test.ts` pins the GM-only list so adding them fails a test. If the brands are ever
  split across two GHL locations, `location-id` becomes a column on `brand` and the exception
  closes. It licenses nothing about unscoped queries over EvalOS rows. **A second exception, added
  in Unit 11 and not a scope:** `POST /api/experts` and the two import endpoints take an optional `brandId` naming
  *where a new row goes*, because a GM has no brand of their own and this is the first unit where
  staff create a scoped row. `OwnershipGuard.assertCanAct` decides whether the caller may act there,
  so a brand-locked role naming another brand gets a 403. Reads never take brand from a request; a
  `brandId` on a read can only narrow.
- **Append-only truth.** Audit + assignment history are never updated or deleted; no update/delete
  path may exist on those repositories. This has a consequence worth knowing before you hit it:
  `audit_event` rows **can never be backfilled** — see `mem:backend/persistence`. Unit 14 is the first
  unit to have felt it, and also the precedent for touching that table at all: the entity and its write
  path are **protected files**, so its one new column was signed off before it was written, not argued
  for afterwards.
- **Flyway owns the schema.** `ddl-auto: validate`. Every change is a new migration; an applied
  migration is never edited.
- **S3 is the document store; EvalOS still hosts no bytes, and there is no mail server.**
  **Google Drive is gone as of Unit 30** — API client, service account, dependency and the
  `drive_link` column (`V34`). Documents are **S3 object keys**, read through **5-minute presigned
  GET URLs** minted per request *after* the same scope check that guards the case, never stored.
  Keys are **brand-first**: `{brandId}/client/{ghlContactId}/{documentId}` and
  `{brandId}/case/{caseId}/{folder}/{documentId}` — the object name is the document's own id,
  which closes path traversal, collisions and PII-in-the-key at once. Staff alerts are in-app,
  client messages go out through GHL, an expert is reached by a scoped portal link.
  **Do not add SMTP.**
  "Hosts no files" means **stores none, not accepts none** — every upload **streams**: the expert
  roster sheet (Unit 11, parsed in memory and thrown away, with
  `multipart.file-size-threshold` set equal to `max-file-size` so the container cannot spool it to
  a temp file), the client document (Unit 30's portal upload), and the expert's signed letter
  (Unit 15, **built 2026-09-03** — digested with a `DigestInputStream` as it streams, and the
  upload fails loudly if the store did not read the whole file, because a hash of a partial read
  filed as the letter's hash is worse than no hash). No byte array, no temp file, no blob column, and it is a test rather than
  a convention.
  Sending email is **settled, not under review**: Unit 18's outbound dispatcher was removed
  (2026-09-02), so EvalOS has **no outbound channel of any kind** and the question of whether it
  sends mail *itself* has no mechanism behind it. What remains open is who reaches the client at
  all — `context/process-automation.md`, where the portal frontend now offers a third option
  (in-portal state, nothing sent) whose limit is that a client who never opens it is never told.
- **A case is created only by a per-brand GHL webhook endpoint, from a won opportunity** — no other
  path and no other event, enforced structurally by `DomainInvariantsTest` (only
  `GhlOpportunityHandler` may depend on `CaseIntakeService`, so adding a `POST /api/cases` breaks the
  build). The case is created **paid**; no staff action sets `paid`.
- **Unpaid work stops at `DOC_COLLECTION`.** Revenue is recognized only when paid **and** delivered.
  Both live in `mem:backend/lifecycle`.
- **A client-offered link/action must be checked against the reader's allow-list.** Four separate
  defects have been one bug: a screen or escape hatch linked without `mayReach`. `navigation.ts` is
  one table for nav + router + allow-list, and `boardPathFor(role)` walks it. Grep before adding any
  cross-screen link.
- Module contract is HTTP under `/api`. **The staff app is same-origin** (Vite proxies `/api` to
  8080) and has **no CORS and must not gain any** — add staff endpoints under `/api` rather than
  introducing it. **`/api/portal/**` is the one exception and CORS there is built** (Unit 30),
  because the portals are a separate frontend on another origin: origins from
  `evalos.portal.allowed-origins` (no default in prod, so a missing value fails the boot), methods
  `GET/POST/OPTIONS`, headers `Content-Type` + `X-Portal-Token`, **`allowCredentials(false)`** — the
  credential is a header, never a cookie, and allowing credentials would turn a mistaken origin
  into a session-riding hole. Omitting `X-Portal-Token` from the allowed headers is the trap: the
  preflight passes, the header is stripped, and the 401 looks exactly like a bad token.

Cross-cutting refs: `mem:tech_stack`, `mem:suggested_commands`, `mem:conventions`,
`mem:task_completion`.
