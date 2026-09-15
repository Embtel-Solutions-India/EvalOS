# EvalOS ↔ GoHighLevel integration audit

Independent audit, 2026-09-13. Read against the code on `development` @ `dee45c6`.
Target end-state: an id-faithful two-way mirror that keeps working with the sync off.

Serena was down; everything below is from Glob/Grep/Read/git and from GHL's own API
schema via the `leadconnector` MCP (`search_operations` / `describe_operation` only —
no `execute_operation` was called, no live read or write touched the account).

---

## Integration surface

### Outbound — every call EvalOS makes to GHL

One HTTP door: `GhlHttp` (`backend/src/main/java/com/ie/evalos/integration/GhlHttp.java`).
Four verbs, closed list (`GhlHttp.java:133,144,149,160`), one shared 110 ms pacer
(`GhlHttp.java:77,226`), one 10 s connect+read timeout (`GhlHttp.java:263`), one error
type (`GhlUnavailableException` → HTTP 502, `ApiExceptionHandler.java:171`).

| # | Verb + path | Scope | Caller `path:line` | On a user request path? | Audited? |
|---|---|---|---|---|---|
| 1 | `GET /opportunities/pipelines?locationId=` | `opportunities.readonly` | `GhlPipelineClient.java:142` | **Yes** — every board draw (`OpportunityBoardService.java:184`), every `pipelineNamed()` (`:158`), the GM pipeline picker (`GhlPipelineController.java:55`) | n/a (read) |
| 2 | `GET /opportunities/search` (cursor-paged, `limit=100`) | `opportunities.readonly` | `GhlPipelineClient.java:206` (funnel rows) | Yes, behind 5 m DB cache | n/a |
| 3 | `GET /opportunities/search` (`limit=1`, `meta.total` only) | `opportunities.readonly` | `GhlPipelineClient.java:270` (`countIn`) | Yes, behind 5 m DB cache | n/a |
| 4 | `GET /opportunities/search` (cursor-paged, board shape) | `opportunities.readonly` | `GhlOpportunityClient.java:102` ← `OpportunityCache.replace` ← `OpportunityBoardService.java:178` | **Yes** — synchronous inside the board request | n/a |
| 5 | `POST /contacts/upsert` | `contacts.write` | `GhlWriteClient.java:90` ← `MarketingLeadService.java:59` | **Yes** — new-lead form | Yes, `GhlWriteClient.java:97` |
| 6 | `POST /opportunities/upsert` | `opportunities.write` | `GhlWriteClient.java:121` ← `MarketingLeadService.java:60` | **Yes** | Yes, `:125` |
| 7 | `PUT /opportunities/{id}` | `opportunities.write` | `GhlWriteClient.java:152` ← `SalesDeskService.java:62,78`, `MarketingLeadService.java:76` | **Yes** — rename, re-price, stage drag | Yes, `:156` |
| 8 | `PUT /opportunities/{id}/status` | `opportunities.write` | `GhlWriteClient.java:187` ← `SalesDeskService.java:95` | **Yes** — won/lost/abandoned | Yes, `:191` |
| 9 | `POST /contacts/{contactId}/tasks` | `contacts.write` | `GhlWriteClient.java:221` ← `SalesDeskService.java:119` | **Yes** — follow-up | Yes, `:225` |
| 10 | `GET /calendars/?locationId=` | `calendars.readonly` | `GhlCalendarClient.java:114` ← `SalesMeetingService.calendars()` | **Yes** — on every deal-card open (`DealActions.tsx:67`) | n/a |
| 11 | `POST /calendars/events/appointments` | `calendars/events.write` | `GhlCalendarClient.java:169` | **Yes** | Yes, `:179` |
| 12 | `PUT /calendars/events/appointments/{id}` | `calendars/events.write` | `GhlCalendarClient.java:204` | **Yes** | Yes |
| 13 | `GET /contacts/{contactId}/appointments` | `contacts.readonly` | `GhlCalendarClient.java:247` ← `PortalMeetingService` | **Yes** — client portal | n/a |
| 14 | `GET /invoices/?altId&altType&contactId&limit&offset` | `invoices.readonly` | `GhlInvoiceClient.java:63` ← `PortalInvoiceService.java:61` | **Yes** — client portal billing tab | n/a |

`DELETE` exists on the door (`GhlHttp.java:160`) with **zero callers** anywhere in
`main`. See F14.

**No GHL call is ever made off a request thread.** Nothing in `job/` touches GHL:
`DocChaseSweep`, `DocEscalationSweep`, `ExpertSignSweep`, `StageSlaSweep` are all
pure-EvalOS. So today the entire GHL budget is spent by human clicks, and there is
no background repair path of any kind.

### Inbound — every event EvalOS accepts

| Route | Auth | Handler | Behaviour |
|---|---|---|---|
| `POST /api/webhooks/ghl/{endpointToken}` | `brand.webhook_endpoint_token` in the **path**, `AndActiveTrue` (`WebhookGateway.java:94`). `permitAll` in Spring (`SecurityConfig.java:48`) | `WebhookRouter.java:49` | `opportunity.won` → `GhlOpportunityHandler` → `CaseIntakeService` |
| same route, `event_type ∈ {refund.requested, contact.created, contact.updated}` | same | none | `DEFERRED` — logged, archived, acked 200 (`WebhookRouter.java:41,53-55`) |
| same route, any other `event_type` | same | none | logged `warn`, archived, acked 200 (`:56-58`) |

That is the whole inbound surface. **There is exactly one route and one handler.**

---

## Identity & duplicate-risk map

Four origins, and they do not converge on one identity resolver. There is no single
"find or create this person" function in the codebase — there are three, in three
packages, with three different keys.

### Origin 1 — GHL-native (a lead born in GHL, won there)

`opportunity.won` → `GhlOpportunityHandler.java:156` → `CaseIntakeService.syncContact`
(`CaseIntakeService.java:237`).

Resolution order: `ghl_contact_id` first, then `lower(email)` as a fallback, and an
email match that **contradicts** a supplied id is refused (`CaseIntakeService.java:272`,
`:283`). Backed by two partial unique indexes: `uq_contact_per_brand_ghl_id` (V16) and
`uq_contact_per_brand_email … WHERE ghl_contact_id IS NULL` (V27).

This path is the **only** one that is actually hardened. It is also the only one that
was ever the subject of a wrong-merge incident (commit `4c595da`).

Residual duplicate risk: a delivery with **no** `ghl_contact_id` and **no** email
inserts a fresh snapshot every time — neither partial index is in scope. The payload's
`contact_id` is `@NotBlank` (`GhlOpportunityHandler.java:84`) so this is closed *for
this handler*, and open for any future one.

### Origin 2 — Marketing-entered (`POST /api/marketing/leads`)

`MarketingLeadService.openLead` (`:47`) → `POST /contacts/upsert` →
`POST /opportunities/upsert`. **Nothing is written to EvalOS at all** — no
`contact_snapshot` row, no `client_account` row. The lead exists only in GHL plus,
two minutes later, in `ghl_opportunity_cache`.

Three duplicate paths here:

1. **Contact dedupe is delegated to a GHL location setting EvalOS cannot see.**
   GHL's own schema for `upsert-contact`: *"The Upsert API will adhere to the
   configuration defined under the 'Allow Duplicate Contact' setting at the Location
   level."* EvalOS does not send `createNewIfDuplicateAllowed` and does not read the
   setting. The guard at `MarketingLeadService.java:50-57` (require email or phone)
   is correct and necessary but not sufficient: if the fresh
   `WY6bW2xUCI8Tz8gw7aLJ` location has duplicates *allowed* with no matching
   sequence configured, every save mints a contact. Nobody has checked.
2. **`/opportunities/upsert` keys on `(contactId, pipelineId)`, not on identity.**
   GHL's schema marks `id` optional, `pipelineId` + `contactId` required.
   `GhlWriteClient.upsertOpportunity` (`:110-122`) never sends `id`. So a repeat
   client's *second* deal in the same pipeline does not create a second
   opportunity — **it silently overwrites the first**, name and value included. This
   is the exact inverse of a duplicate and it is worse: nothing is left to reconcile.
   It also directly contradicts the premise `V41__opportunity_note.sql:20-24` is
   built on ("a repeat client is ONE contact and TWO opportunities").
3. No idempotency on the HTTP layer: a double-submit of the new-lead form is two
   upserts. Because both are upserts they converge — *provided* (1) and (2) hold.

### Origin 3 — Sales-entered (`/api/sales/opportunities/**`)

Sales never *creates* an opportunity — every route is `PUT` or a sub-resource on an
existing `{opportunityId}` (`SalesDeskController.java:75,82,97,108,125,133`). So no
duplicate contact and no duplicate opportunity. Two other duplicates instead:

- **Meetings.** `SalesMeetingService.java:27` states it in its own javadoc: *"no
  idempotency for appointments, so a double-submit books two meetings."* True —
  `POST /calendars/events/appointments` is a bare create with no client key.
- **Follow-ups.** `createFollowUp` (`GhlWriteClient.java:214`) is likewise a bare
  `POST /contacts/{id}/tasks`. A retry or a double-click is two tasks in the
  salesperson's GHL inbox.

### Origin 4 — Client-Portal signup (Units 42/43) — highest risk, and **not yet live**

Verified: there is **no signup endpoint**. `ClientAuthController` exposes exactly
`identify`, `sign-in`, `forgot-password`, `set-password`
(`ClientAuthController.java:70,75,84,90`). `identify` returns `UNKNOWN` and the
frontend offers "Get Started", which goes nowhere. Unit 43 is the unit that builds
the door, and it is unbuilt.

The risk is therefore **entirely prospective, and it is already loaded**:

- `client_account` is seeded only from `contact_snapshot` (V45), and
  `contact_snapshot` only ever holds contacts that reached EvalOS through
  `opportunity.won`. **Every prospect, and every lead Marketing opened this month,
  is `UNKNOWN` to the portal** — because `contact.created` / `contact.updated` are
  DEFERRED (`WebhookRouter.java:41`) and Marketing's own leads are never written to
  EvalOS at all (Origin 2).
- So the modal case at `client.domain.com` is: a person GHL already knows, whom
  EvalOS has never heard of, signing up. Unit 43 will resolve them against
  `client_account` (empty) and push them to a GHL intake pipeline. Unless Unit 43
  resolves against **GHL** — `GET /contacts/search/duplicate?email=` or
  `POST /contacts/upsert` — it will mint a second GHL contact for a person the sales
  team is already working. That is the duplicate that matters, and the current
  architecture has no component that can prevent it.
- `client_account` has its own uniqueness (`client_account_brand_email_key`, V43)
  which is *case-insensitive per brand*, but `ClientAccountService.normalize`
  (`:310`) only `trim()`s — the `lower()` comes from the index and
  `findByBrandIdAndEmailIgnoreCase`. That half is fine.
- `client_account.brand_id` is filled from a **global** config
  `evalos.portal.client-brand` (`ClientAccountService.java:100`,
  `application.yml:105`). One portal, one brand. XpertsPortal clients cannot sign up
  at all, and if that value is ever changed the existing accounts become invisible
  rather than erroring.

### The convergence gap, stated once

| | writes `contact_snapshot` | writes `client_account` | writes GHL contact | resolves against GHL |
|---|---|---|---|---|
| GHL-native (won) | ✅ | ❌ | ❌ | n/a |
| Marketing-entered | ❌ | ❌ | ✅ | ✅ (via upsert) |
| Sales-entered | ❌ | ❌ | ❌ | n/a |
| Portal signup (planned) | ❌ | ✅ | ? | ? |

Four origins, four different persistence outcomes, **no shared resolver**. Today this
is survivable because only one origin writes EvalOS rows. The mirror makes every
origin write the same table, and that is the moment this table stops being a curiosity
and becomes the duplicate generator.

---

## Findings

### F1 — There is no inbound authenticity check at all, and the column that says so lies
- **What.** Webhook authenticity is a bearer token in a URL path, nothing else. HMAC
  verification was deleted 2026-08-27 (commit `4c595da`) because GHL's Custom Webhook
  workflow action cannot compute one. `WebhookEvent.signatureVerified` is `NOT NULL`,
  never set, and permanently `false` (`WebhookEvent.java:56-62`). `brand.ghl_webhook_secret`
  (V11) is a dead column still populated by the local seed (`V901__seed_local_webhook_secrets.sql:7`).
- **Evidence.** `WebhookGateway.java:89-144` (no signature parameter),
  `SecurityConfig.java:48` (`permitAll`), `architecture.md:799-801` (invariant 10 records it).
- **Failure mode.** Anyone who learns a `webhook_endpoint_token` — a URL in a GHL
  workflow screenshot, a proxy log, a browser history on a shared machine, a
  disgruntled ex-contractor with GHL access — can POST `opportunity.won` and mint a
  **paid** case with an arbitrary contact, name, email and deal value. Invariant 8
  says a case can only be born of a won opportunity; this is how you forge one. The
  token never rotates on its own and nothing detects use.
- **Decision.** MODIFY. The HMAC genuinely cannot come back through a Custom Webhook.
  The cheap real guards: (a) rotate the token on demand via an admin route and audit
  the rotation; (b) IP-allowlist GHL's egress at the edge; (c) **rate-limit the
  endpoint per token** — today a forger can loop; (d) alert on any
  `WebhookRejected(UNKNOWN_ENDPOINT)`, which is the only signal that somebody is
  probing. Drop the two dead columns in one migration when the mirror's migration
  lands anyway.
- **Priority. P0.** This is the door to paid custody.

### F2 — A failed webhook is never retried, by anyone, and nothing can see that it failed
- **What.** On handler exception the gateway stamps `error`, leaves `processed=false`,
  and **rethrows** (`WebhookGateway.java:134-141`), so the caller gets a 5xx. GHL's
  Custom Webhook action does not redeliver. EvalOS's own redelivery left with Unit 18
  — confirmed: nothing in `job/` reads `webhook_event`, and `WebhookEventRepository`
  has exactly one consumer, the gateway itself (`grep` over `main`: only
  `WebhookGateway.java:17,77,81`).
- **Evidence.** `WebhookGateway.java:130-143`; `architecture.md:719-736` (invariant 6:
  "neither is an outbound outbox, which left with Unit 18").
- **Failure mode.** One transient DB blip, one constraint violation, one 500 during a
  deploy, and a **paid case is silently never created**. The money is taken in GHL,
  the client is told nothing, and the only evidence is one row in a table no screen
  reads and one `log.error` in a file nobody tails. There is no dead-letter, no
  counter, no alert. This has the same blast radius as F1 and a much higher
  probability.
- **Decision.** ADD. A `WebhookReplaySweep` in `job/` under the existing `JobLock`
  (`JobLock.java:61`): select `webhook_event WHERE processed=false AND received_at >
  now()-7d`, re-route through `WebhookRouter`, exponential backoff on an `attempts`
  column, dead-letter past N. This is ~60 lines and it reuses `Sweep`, `JobLedger` and
  `JobSchedule` wholesale. Add an unprocessed-count to the existing jobs admin panel.
- **Priority. P0.**

### F3 — Deferred `contact.created` / `contact.updated` cost the portal its entire prospect base
- **What.** Both are recognised and dropped (`WebhookRouter.java:41,53-55`). So
  `contact_snapshot` contains **only** contacts that won an opportunity.
- **Evidence.** `WebhookRouter.java:41`; `V45__seed_client_accounts.sql` seeds
  `client_account` from `contact_snapshot` and from nothing else.
- **Failure mode.** Three, compounding. (i) Every prospect who signs up at the portal
  is `UNKNOWN` even when GHL knows them — the Origin-4 duplicate above. (ii) A contact
  who changes their email in GHL diverges from `contact_snapshot` permanently, and
  `contact_snapshot.email` is what the client uses to sign in. (iii) The mirror (Unit
  44) needs exactly these two events; they are the cheapest half of Unit 45 and they
  are already wired to the door.
- **Decision.** ADD. Implement both handlers now, ahead of Unit 44, writing
  `contact_snapshot` through the *same* `CaseIntakeService.syncContact` resolver so
  there is one identity function rather than two. This is the single highest
  value-per-line change available today.
- **Priority. P1** (P0 the day Unit 43 ships).

### F4 — `/opportunities/upsert` with no `id` merges a repeat client's second deal into their first
- **What.** GHL's upsert keys on `(contactId, pipelineId)` when `id` is absent.
  `upsertOpportunity` never sends `id`.
- **Evidence.** `GhlWriteClient.java:110-122`; GHL `Upsert-opportunity` schema —
  `pipelineId` required, `contactId` required, `id` optional.
  `MarketingLeadService.java:60` is the only caller. The response's `new` flag
  (`GhlWriteClient.java:303`) is EvalOS's only clue and it is returned to the UI as
  `created` (`MarketingLeadService.java:64`) rather than acted on.
- **Failure mode.** Marketing opens a second lead for a returning client in the same
  pipeline. The first deal's name and `monetaryValue` are overwritten; its notes in
  `opportunity_note` now describe a deal that no longer says what it said. No error,
  no duplicate, nothing to reconcile — the first deal is simply gone. `isNew=false`
  comes back and the form says "lead opened".
- **Decision.** MODIFY. Either switch the create path to `POST /opportunities/` (a
  true create — GHL has it) and keep `upsert` only for the *retry* of a known id, or
  surface `isNew=false` as a confirmation step ("this contact already has a deal in
  this pipeline — open it, or create a second?"). The first is smaller.
- **Priority. P1.**

### F5 — `PipelineScope` authorises writes against a droppable cache
- **What.** Every Sales and Marketing write is authorised by asking whether the
  opportunity is in `ghl_opportunity_cache` (`PipelineScope.java:63` →
  `OpportunityCache.isInPipeline` → `existsByGhlOpportunityIdAndGhlPipelineId`).
- **Evidence.** `PipelineScope.java:61-67`; `V40__ghl_opportunity_cache.sql:22`
  ("droppable without loss… no write path reads it to decide anything" — which is now
  false, and the migration comment's own clause 2 is violated by `PipelineScope`).
- **Failure mode.** Two directions, both live. (a) **Fails closed wrongly:** truncate
  the cache, or let a 2 m TTL lapse while GHL is down, and every salesperson gets
  "That opportunity is not in your pipeline" on work that is theirs. A newly opened
  lead is *immediately* in this state — `openLead` does not insert into the cache, so
  `MarketingLeadService.value()` on the lead you just created 403s until the next
  refill. (b) **Fails open wrongly:** the cache is only replaced wholesale per
  pipeline (`OpportunityCache.replace`, `:76-85`) and there is **no TTL sweep** (the
  migration anticipates one; none exists), so a deal moved to another rep's pipeline
  in GHL stays authorised here until someone loads that board.
- **Decision.** REFACTOR. An access predicate must not live in a droppable cache. Once
  the mirror lands the `opportunity` row carries `pipeline_id` authoritatively and
  this resolves itself; until then, on a cache miss fall through to
  `GET /opportunities/{id}` (one request, already a bounded call) rather than 403.
- **Priority. P1.**

### F6 — No write updates the cache, so the board snaps every card back
- **What.** `OpportunityCache.evict` (`:94`) has **zero callers** in `main`. No write
  path refreshes the row it just changed.
- **Evidence.** `grep "\.evict("` over `main` → only the definition.
  `SalesDeskService.moveToStage` (`:73`) returns GHL's answer to the caller and leaves
  the cache holding the old `stage_id` for up to `board-cache-ttl` (2 m,
  `application.yml`).
- **Failure mode.** Drag a card, get a success response, redraw the board, watch it
  return to the old column. Every salesperson will learn to distrust the board inside
  a day, and the natural workaround is to open GHL — which defeats the entire premise
  of `00b`.
- **Decision.** MODIFY. Every write already receives GHL's authoritative row back
  (`UpsertedOpportunity`, `GhlWriteClient.java:56`). Write it straight into the cache
  — that is not optimistic local state, it is GHL's own response, which is exactly
  what `00b` §1.3 permits.
- **Priority. P1.**

### F7 — Outbound writes have no durability, no retry, no dead-letter, and lose the user's work
- **What.** A write is one synchronous HTTP call on the request thread. 4xx, 5xx and
  timeout all collapse to `GhlUnavailableException` (`GhlHttp.java:193-215`) → HTTP
  502 → a line of red text in the form (`DealActions.tsx:80,283`,
  `NewLeadForm.tsx:51,75`). Nothing is persisted; a refresh loses it.
- **Evidence.** `GhlHttp.java:58-59` records the policy in the class comment ("writes
  do not retry"); `architecture.md:700-703` states it as invariant.
- **Failure mode.** A 4xx (bad stage id, dead pipeline, revoked scope) is indistinguishable
  from a 5xx and from a timeout in the UI — all three say "GHL refused the request
  with HTTP nnn", which is honest and useless to a salesperson. A timeout is the worst
  of the three: the write may well have landed, and the only safe response — retry —
  is the one the code refuses.
- **Decision.** ADD (the outbox, `00c` §4d). See the architecture section; the key
  point the spec misses is that the 4xx/5xx/timeout split must be made *at the door*,
  because only 5xx and timeout are retriable and today `GhlUnavailableException`
  throws the status away into a string.
- **Priority. P1.**

### F8 — Invariant 13 holds for opportunity writes and has two real holes
- **What.** Every `GhlWriteClient` and `GhlCalendarClient` write audits
  (`GhlWriteClient.java:97,125,156,191,225`; `GhlCalendarClient.java:179`), and
  `GhlHttpTest` structurally pins it. Two holes.
- **Evidence.** (a) The audit is written **after** the HTTP call returns, in the same
  transaction, so a write that lands in GHL and then fails on the EvalOS side leaves
  **no trace at all** — precisely the failure invariant 13's extension names
  (`architecture.md:813`). (b) The audit key is
  `UUID.nameUUIDFromBytes("GHL_OPPORTUNITY:" + ghlId)` (`GhlWriteClient.java:262`) —
  a derived, non-referential id. After the sub-account swap those UUIDs point at GHL
  ids that no longer exist, so the pre-cutover GHL audit trail is unjoinable to
  anything.
- **Decision.** MODIFY (a) — write an intent row before the call and a result row
  after, which the outbox gives you for free. KEEP (b) — it is the correct shape for a
  foreign id; it just needs the mirror's stable `opportunity.id` once that exists.
- **Priority. P2.**

### F9 — Pipelines are identified by name from config, and one of the two rules is backwards
- **What.** Three properties (`ads-pipeline-name`, `email-pipeline-name`,
  `sales-pipeline-name`) resolve a pipeline by case-insensitive, whitespace-collapsed
  **name** (`GhlPipelineClient.pipelineNamed`, `:158-172`). Access, by contrast, uses
  the **id** (`team_member.ghl_pipeline_id`, `V39__pipeline_scoped_access.sql`).
- **Evidence.** `application.yml:191-213`; `V39:24-30` argues the split explicitly and
  correctly ("an access key that breaks on a rename locks an employee out").
- **Failure mode.** A rename in GHL 502s the GM's funnel screens — the stated,
  intended direction. But the *other* half is the problem: a pipeline **deleted and
  recreated** in GHL keeps its name and changes its id, so the dashboards keep working
  while every salesperson's access key silently points at nothing, and the board is
  empty with no error. That is exactly what the sub-account swap did (see C4).
- **Decision.** REFACTOR at Unit 44. With `pipeline` mirrored, both name and id
  resolve against an EvalOS row and the config properties become seed data rather than
  runtime lookups. Until then: log the resolved `(name → id)` pair at boot so a
  changed id is visible in one grep instead of a support ticket.
- **Priority. P2** now, **P0 at cutover** (C4).

### F10 — Named pipelines: the mirror's shape is right, the naming scheme is not
- **What.** The target set is Marketing BDE 1/2/3; Sales Evaluation & Translation,
  PERM + IBP + Prefill, Corporate, Law Firm; plus a Case Delivery pipeline. Nine
  pipelines. The current config carries **three** name properties and
  `OpportunityBoardService` assumes one pipeline per person
  (`pipelinesFor`, `:143-149` — `List.of(caller.ghlPipelineId())`).
- **Failure mode.** Nine pipelines needs nine properties, or a list — and
  `application.yml:199-203` explicitly forbids the list ("THREE NAMES, NOT A LIST").
  That instruction was right at three and is wrong at nine. Worse: a Case Delivery
  pipeline connecting Sales to Production is a pipeline **no single person owns**,
  which `uq_team_member_pipeline` (one live owner, `00b` §1.2) cannot express, and
  which `PipelineScope.mine()` (`:37-43`, returns exactly one id) cannot read.
- **Decision.** REDESIGN, at Unit 44 and not before. The mirrored `pipeline` table is
  the list; a `purpose` enum column on it (`MARKETING`, `SALES`, `DELIVERY`) replaces
  every `*-pipeline-name` property. Access becomes a join table
  `team_member_pipeline (member_id, pipeline_id)` — many-to-many is the real shape
  once Case Delivery exists, and pretending otherwise costs a second migration.
- **Priority. P1** — this changes Unit 44's schema, so it must be decided before 44
  starts, not after.

### F11 — `00c` §2b (mirror GHL's stage ids verbatim) is correct, and its argument is stronger than it says
- **What.** `00c` §2b keeps GHL's `stage_id` as the value stored on an opportunity,
  resolving it against a mirrored `pipeline_stage` row for name and position.
- **Assessment. Endorse.** The reasoning in the spec is "the id is not opaque once
  there is a stage table", which is true but weak. The stronger argument is the one
  `00c` §4c needs: **a drift check is an equality comparison or it is a heuristic.**
  With a mapping table, "is EvalOS's stage the same as GHL's stage" requires resolving
  both through a translation that can itself be stale or wrong, and a nightly audit
  built on it reports mapping bugs as data drift. With verbatim ids it is `a == b`.
  That is what makes "a single digit of mismatch" a checkable claim.
- **The one thing to add.** GHL stage ids are **not stable across a pipeline delete
  and recreate**, and IE has now demonstrated it will recreate things. `pipeline_stage`
  must therefore carry `(pipeline_id, position, name)` as a *secondary* natural key
  and the nightly audit must report "GHL has a stage at position 3 named X whose id I
  have never seen" as a distinct, louder category than ordinary drift. Without that,
  a recreated pipeline reads as every opportunity in it having drifted.
- **Decision.** KEEP the decision, ADD the recreate-detection.
- **Priority. P1.**

### F12 — The 100/10s budget: today it is fine, the mirror is where it breaks
- **What.** One process-wide pacer, 110 ms spacing, serialised through a `synchronized`
  slot claim (`GhlHttp.java:226-246`). Ceiling: ~90 req/10 s, under GHL's 100.
- **Today's cost of a board render.** Cache warm: **1 request**
  (`pipelines()` at `OpportunityBoardService.java:184` — uncached, every draw) plus 1
  for the calendar list per deal card opened (`DealActions.tsx:67`). Cache cold:
  1 + ⌈deals/100⌉ **synchronously inside the HTTP request**. At IE's stated ~11.4k
  opportunities/year that is up to 115 pages ≈ 13 s for a whole-year pipeline — the
  arithmetic `00b` §1.3 and V40 already record — but a *live* pipeline holds far
  fewer, so a cold board is realistically 2–5 requests and ~0.5 s. Acceptable.
- **What can exhaust it.** (i) The pacer is a **blocking `Thread.sleep` on the request
  thread** (`GhlHttp.java:238`). Thirty concurrent cold boards do not fail — they
  queue, and the last one waits 3+ seconds *before* its own 10 s timeout starts. With
  Tomcat's 200 threads and a slow GHL, every request thread in the pool can be parked
  inside `pace()`; **the whole app stops serving, including screens that never touch
  GHL.** That is the real exhaustion risk and it is not a GHL limit, it is a thread
  limit. (ii) `pipelines()` is uncached and is called by every board draw *and* every
  `pipelineNamed` — free requests with no value.
- **Decision.** MODIFY. Cache `pipelines()` for the board TTL (one line, kills most of
  the traffic). Bound the pacer wait: if the claimed slot is more than ~2 s out, throw
  `GhlUnavailableException("busy")` rather than parking a request thread. ADD a
  semaphore capping concurrent GHL calls well below the thread pool.
- **Priority. P1.**

### F13 — Failure visibility is zero: there is no sync-status surface anywhere
- **What.** If GHL is down for an hour: the salesperson mid-edit gets red text and
  loses the edit (F7); a webhook gets a 5xx, is archived unprocessed and is never seen
  again (F2); **no case is created for anything paid in that hour, ever**; the portal's
  invoices screen 502s (`PortalInvoiceService.java:61` → `GhlInvoiceClient` → 502) and
  so does the meetings tab; the boards keep painting stale cache and say `stale=true`
  in the payload (`OpportunityBoardService.java:219`) — the one honest signal in the
  system.
- **Evidence.** `grep "GHL_UNAVAILABLE"` over `frontend/src` → **no hits**. The
  frontend never special-cases it; the 502 arrives as a generic thrown `Error` and is
  rendered as its message. No health indicator, no banner, no admin page, no metric.
  `webhook_event` is written and never read.
- **Decision.** ADD. `GET /api/admin/ghl/status` returning: configured yes/no,
  location id, last successful call, last failure + status, unprocessed `webhook_event`
  count with oldest timestamp, and per-pipeline cache age. Put it on the existing jobs
  admin panel, which already has the shape (`JobAdminService`). A red dot in the shell
  when `unprocessed > 0` or `lastFailure < 5 min ago`.
- **Priority. P0** — not because it is hard, but because F1, F2 and F4 are all
  currently *invisible*, and an invisible P0 cannot be triaged.

### F14 — `GhlHttp.delete` is a write capability with no caller
- **What.** `delete` (`GhlHttp.java:160`) is unreachable from `main`.
- **Failure mode.** None today. But invariant 2's replacement guarantee is precisely
  "a verb no endpoint accepts is exactly the present-and-unused capability this
  invariant used to be about" (`GhlHttp.java:164-166`, about `patch`). `delete` is that
  same thing and was waved through. `00c` §7g decides deletions are soft — so nothing
  should ever call it.
- **Decision.** REMOVE, and let `GhlHttpTest`'s closed-verb-list assertion pin three.
- **Priority. P2.**

### F15 — One GHL location, hardcoded globally, in a multi-brand product
- **What.** `evalos.ghl.location-id` is a single global (`GhlHttp.java:94`),
  `evalos.ghl.sales-brand` names the one brand allowed to hold a pipeline
  (`OpportunityBoardService.java:91`), `evalos.portal.client-brand` names the one brand
  allowed to sign in (`ClientAccountService.java:100`). `ghl_opportunity_cache` has
  **no `brand_id` at all** (V40, deliberately).
- **Failure mode.** XpertsPortal cannot sell, cannot be mirrored, and its clients
  cannot sign into the portal. That is known and accepted (`00b` §1.5, Unit 25). What
  is *not* stated: the mirror tables in `00c` §2 all carry `brand_id`, which means
  Unit 44 either builds the per-brand credential store (Unit 25) or ships a `brand_id`
  column that holds one value — the exact anti-pattern V40 refused to ship.
- **Decision.** MODIFY the sequence: Unit 25 belongs **before** Unit 44, not parked.
  `00c` §5 claims invariant 1 is "strengthened… this finishes what Unit 25 started" —
  but Unit 25 has not started.
- **Priority. P1** (sequencing, not code).

---

## Sync architecture recommendation

`00c`'s plan is sound in its bones — three layers, an outbox, EvalOS-wins-and-report,
verbatim ids — and I would not restart it. Six things need changing or adding.

### Tables

Take `00c` §2's four tables as written, with these amendments:

```sql
-- as specced
pipeline        (id uuid pk, brand_id, ghl_id unique, name, position, purpose, synced_at,
                 deleted_in_ghl_at)
pipeline_stage  (id uuid pk, brand_id, pipeline_id, ghl_id unique, name, position,
                 synced_at, deleted_in_ghl_at,
                 unique (pipeline_id, position))          -- F11: recreate detection
contact         (id uuid pk, brand_id, ghl_id unique null, email, phone, name, …,
                 ghl_updated_at, local_updated_at, deleted_in_ghl_at)
opportunity     (id uuid pk, brand_id, ghl_id unique null, contact_id, pipeline_id,
                 stage_id, name, amount, status,
                 ghl_updated_at, local_updated_at, deleted_in_ghl_at)

-- added
sync_outbox     (id uuid pk, brand_id, entity_type, entity_id, intent, payload jsonb,
                 attempts int, next_attempt_at, last_status, last_error,
                 created_at, sent_at, dead_at,
                 unique (entity_type, entity_id, intent) where sent_at is null
                                                          and dead_at is null)
sync_watermark  (brand_id, entity_type, last_updated_at, last_run_at,
                 primary key (brand_id, entity_type))
sync_drift      (id uuid pk, run_at, entity_type, entity_id, field,
                 evalos_value, ghl_value, evalos_at, ghl_at, outcome)
```

`sync_drift` is missing from `00c` entirely — §4b and §4c both say "the drift report"
as if it were a document. It has to be a table, because §4c's guarantee 1 ("every
divergence is detected") is only checkable if yesterday's divergences are still
queryable.

`CachedOpportunity` **is** unsalvageable, and `00c` §7c's reason is right but
understated. It gives three: `ghl_opportunity_id` is the PK, and `ghl_contact_id` /
`stage_id` are `NOT NULL` (`V40__ghl_opportunity_cache.sql:31,40,44,46`), so a
portal-born opportunity GHL has not seen cannot exist in it. Two more: it has **no
`brand_id`** (V40, by design), and its only write path is
`delete-all-then-insert-all per pipeline` (`OpportunityCache.replace`, `:76-85`) — a
mirror needs per-row upsert with a version, and every row's identity is destroyed on
every refill, so nothing can hold a foreign key into it. Drop the table; do not migrate
it. Its content is reconstructible in one sweep.

### Ownership per field

`00b` §1.3's table is too coarse for a two-way sync — "GHL owns the opportunity" does
not say who wins on `name`. Make it per-field and put it in code, not prose:

| Entity.field | Owner | Conflict |
|---|---|---|
| `pipeline.*`, `pipeline_stage.*` | **GHL**, read-only | GHL always wins; EvalOS never pushes |
| `contact.email`, `.phone`, `.name` | **shared** | EvalOS wins, report |
| `contact.tags`, `.custom_fields` | **GHL** (workflows key off them) | GHL wins, report |
| `opportunity.name`, `.amount`, `.stage_id`, `.status` | **shared** | EvalOS wins, report |
| `opportunity.assigned_to` | **GHL** (round-robin automations) | GHL wins, report |
| `opportunity_note.*` | **EvalOS**, sole | never synced |
| invoices | **GHL**, read-only | n/a until Unit 49 |

The rows where GHL wins are the ones `00c` §4b's blanket "EvalOS wins" gets wrong: a
GHL round-robin reassigning a deal, or a workflow adding a tag, is not a conflict to be
reverted — it is the automation engine doing the job `00b` kept it for. A blanket
EvalOS-wins turns every automation into drift. **Per-field ownership is the amendment
`00c` §4b most needs.**

### Event flow

```
GHL workflow  ──► POST /api/webhooks/ghl/{token}  ──► WebhookGateway (dedupe, archive)
                                                       └─► WebhookRouter
                                                            └─► MirrorService.applyFromGhl()

WebhookReplaySweep   (new, F2)  ─► re-routes unprocessed webhook_event, backoff, dead-letter
DeltaSweep     (5 min)          ─► GET /opportunities/search?…  since sync_watermark
                                   ─► MirrorService.applyFromGhl()
NightlyAudit   (03:00)          ─► full compare both ways ─► sync_drift ─► repair or conflict

EvalOS desk write ─► MirrorService.applyLocal()  (writes the row, local_updated_at = now)
                     └─► sync_outbox row (entity, id, intent)
OutboxSweep    (30 s)           ─► GHL write ─► apply GHL's response back onto the row
```

The load-bearing change from `00c`: **the desk writes the mirror row first and the
outbox second, and returns immediately.** Today a desk write is a synchronous GHL call
(F7) and that is the thing that must stop, because it is what makes the sync-off switch
a real switch rather than a config value. With sync off, `applyLocal` still works and
the outbox simply accumulates.

### Idempotency

`(entity_type, entity_id, intent)` is **the right key**, and it is right for the reason
§7h gives — a retry must collapse onto the pending row even if a field changed. Three
things the spec does not say that will bite:

1. **It must be a partial unique index, not a plain one** — `where sent_at is null and
   dead_at is null`. Otherwise the second edit of the same opportunity, an hour later,
   collides with the sent row from the first. `00c` §7h says "dedupe key" without
   saying "pending only", and a plain unique constraint is the obvious wrong reading.
2. **Collapsing means re-reading the row, not merging payloads.** Store `entity_id`,
   not a payload snapshot, and have the sweep read the current mirror row at send time.
   Otherwise "collapse onto the pending one even if a field changed" silently sends the
   *older* field values.
3. **`intent` must be coarse.** `UPSERT` / `CLOSE` / `DELETE` per entity, not one intent
   per field, or the collapse never happens.

**The gap `00c` has no answer for:** the outbox gives at-least-once delivery, and
`POST /opportunities/upsert` without an `id` is **not idempotent** (F4). A retry after a
timeout on a *create* is exactly how one opportunity becomes two — the failure invariant
2 forbade retries to avoid. The outbox does not fix this; upserts do not fix this. The
fix is a client-side correlation key: write the EvalOS `opportunity.id` into a GHL
custom field on create, and on retry-after-timeout search that field before creating.
That requires tier-2 (custom fields) at Unit 44, not Unit 47. **This is the single
biggest sequencing error in `00c`.**

### Retry and backoff

Classify at the door, which `GhlHttp` currently cannot do (F7): it flattens status into
a message string (`GhlHttp.java:200-214`). Add a `status` field to
`GhlUnavailableException`.

- `408`, `429`, `5xx`, timeout, connect failure → retriable. Exponential
  `30s · 2^attempts`, jitter, cap 1 h, dead-letter at 10 attempts (~ 1 day).
- `429` specifically → honour `Retry-After` and **pause the whole outbox**, not just
  this row; the budget is per location.
- `400`, `404`, `422` → permanent. Dead-letter immediately and raise a drift row. A
  `404` on an update is the interesting one: it means GHL deleted the entity, which is
  §7g's soft-delete signal arriving through the outbox rather than the audit.
- `401`, `403` → **halt the outbox entirely** and alert. Retrying a scope failure
  10 times × every pending row is how you spend a whole rate budget on a credential
  problem.

### Conflict policy

"EvalOS wins + report" is implementable, but **not with what is stored today**, and
`00c` does not notice. Last-writer-wins needs two timestamps per field or at minimum
per row, and `CachedOpportunity` has `updated_in_ghl_at` (nullable — "GHL does not
always send it", V40:49) and `fetched_at`, which is when *we read*, not when *we wrote*.
`00c` §2's `opportunity` adds `local_updated_at`, which is the missing half — good. Two
remaining problems:

1. **`ghl_updated_at` is nullable and GHL-supplied.** When it is null there is no
   comparison, and the policy degrades to "EvalOS always wins", silently. Make the rule
   explicit: null `ghl_updated_at` ⇒ treat as a conflict and report it, never as
   "EvalOS is newer".
2. **Row-level timestamps make every concurrent edit a conflict.** Marketing changes
   the amount in EvalOS while a GHL workflow moves the stage: nothing actually
   conflicts, but one `local_updated_at > ghl_updated_at` comparison reverts the stage
   move. With per-field ownership (above) this resolves — compare only on fields both
   sides may write, and take GHL's value unconditionally on GHL-owned fields.

### Drift reporting and the nightly audit's budget

**The nightly full audit is feasible, and `00c` never checks.** Neither `00c` nor
`00b` costs it against 100 req/10 s — confirmed, the string "100 requests" does not
appear in `00c`. The arithmetic:

- Opportunities: `GET /opportunities/search` at `limit=100`, one pipeline at a time.
  ~11.4k opportunities/year ⇒ ~115 pages ⇒ at 110 ms spacing ≈ **13 s**. Fine.
- Contacts: `GET /contacts/` pages the same way; IE's contact count is the same order.
  Another ~13 s.
- Pipelines and stages: 1 request.
- **Total ≈ 30 s of wall clock at ~9 req/s.** It fits inside the budget by an order of
  magnitude, with room for the 5-minute delta sweep to run alongside it.

What does *not* fit, and is the trap: a naive audit that fetches each opportunity by id
to compare it (11.4k requests ≈ 21 minutes of continuous budget, starving every desk).
**Write it as a paged full-list diff, never a per-row `GET`,** and say so in the spec —
the per-row version is the one somebody writes first because it is easier to reason
about.

Second budget note: the 5-minute delta sweep at ~1–3 pages per run is ~900 requests/day,
trivial. The audit and the sweep must share the existing `GhlHttp` pacer (they will,
it is a singleton) and must hold `JobLock` so two instances never double it.

### The sync-status surface

`00c` has no sync-status surface at all — §4c promises "the window is measured and
reported" and nothing in the plan holds the measurement. This is F13 and it is the
cheapest high-value thing in the programme:

`GET /api/admin/sync/status` →
```
ghl: { configured, locationId, lastOkAt, lastFailureAt, lastFailureStatus }
inbound:  { unprocessedWebhooks, oldestUnprocessedAt }
outbox:   { pending, oldestPendingAt, deadLettered, halted, haltReason }
delta:    { perEntity: { lastRunAt, watermark, rowsSeen } }
audit:    { lastRunAt, rowsChecked, diverged, repaired, conflicted }
```
Shell shows a red dot when `deadLettered > 0 || conflicted > 0 || unprocessed > 0 ||
halted`. Build it at Unit 45, not after; a sync engine whose state is invisible is a
sync engine nobody will trust enough to switch off.

### What else is missing from `00c` that will bite

1. **No custom-field correlation key** (above) — at-least-once + non-idempotent create
   = duplicates, which is the one outcome the programme's own framing forbids.
2. **No webhook replay** — §4a says the delta sweep "repairs dropped deliveries", which
   is true for *drift* and false for *events*. A dropped `opportunity.won` is not drift
   — no case exists to diverge — and the delta sweep on `opportunity` will never create
   it, because Handoff A lives in `CaseIntakeService`, not the mirror. **`opportunity.won`
   needs its own replay path** (F2), separate from the mirror sync.
3. **No ordering guarantee.** Webhook, delta sweep and outbox can apply changes to one
   row concurrently. Needs optimistic locking (`@Version`) on the mirror rows and a
   rule that a sweep never overwrites a row whose `local_updated_at` is newer than the
   sweep's own start time.
4. **Unit 25 is not sequenced** (F15) — every mirror table carries `brand_id` and there
   is one credential.
5. **Invariant 8 vs Unit 43 is unreconciled.** `architecture.md:783-796` says only
   `opportunity.won` may create a case and `DomainInvariantsTest` build-fails any other
   path; `00c` §5 lists invariant 8 as "untouched by 42–48" while Unit 43 ships a client
   intake funnel. The reconciliation is presumably "an application is a GHL opportunity,
   not a case" — but it is nowhere in writing, and the first person to implement 43 will
   have to guess.
6. **Nothing says what happens to the desks during the audit's repair.** A nightly
   repair that pushes 300 corrections through the outbox at 03:00 is fine; one that does
   it at the same moment as a `429` is a halted outbox at the start of the working day.

---

## Cutover repair list

The sub-account swap (`kBumF0uUOmMBB5bneYjx` → `WY6bW2xUCI8Tz8gw7aLJ`, 2026-09-11, no
migration). Every EvalOS row holding a GHL id now names something that does not exist.

| # | Broken surface | Evidence | Repair |
|---|---|---|---|
| **C1** | **Handoff A is dead.** The `opportunity.won` workflow lived in the old sub-account. No workflow ⇒ no webhook ⇒ **no case is created by anything**. | `WebhookRouter.java:50`; `00c` §1b | **P0.** Recreate the Custom Webhook workflow in `WY6bW2xUCI8Tz8gw7aLJ` pointing at `/api/webhooks/ghl/{brand.webhook_endpoint_token}` — the EvalOS token is unchanged. Verify with one live won opportunity before anything else in this list. |
| **C2** | **Local config still points at the dead location.** | `application-local.yml:112` — `${GHL_LOCATION_ID:kBumF0uUOmMBB5bneYjx}` | **P0.** Change the default to `WY6bW2xUCI8Tz8gw7aLJ`. One line. Every developer currently reads a dead account and sees empty screens that look like bugs. |
| **C3** | **Token.** The old PIT authorises nothing on the new location; a 401 here reads as a scope problem. | `GhlHttp.java:105-117` (this is exactly what that logging was written for) | **P0.** New PIT with `opportunities.readonly+write`, `contacts.readonly+write`, `invoices.readonly`, `calendars.readonly`, `calendars/events.write`. Full `pit-` prefix, 40 chars. Check `token length=` in the boot log. |
| **C4** | **`team_member.ghl_pipeline_id` holds dead ids** — this is an *access key*, so every SALES/MARKETING member is scoped to a pipeline that does not exist. Board is empty with **no error** (`pipelinesFor` returns the dead id, cache is empty, board draws zero columns). `openLead` posts to a dead `pipelineId` → 4xx. | `V39__pipeline_scoped_access.sql:30`; `OpportunityBoardService.java:148`; `PipelineScope.java:38` | **P0.** Re-assign every active SALES/MARKETING member to a pipeline in the new location, through the GM assignment route. Nothing detects this on its own — add the boot-time `(name → id)` log from F9 so the next recreate is visible. |
| **C5** | **The three `*-pipeline-name` properties** name pipelines the new account may not have. | `application.yml:195,204,213` | **P1.** Reset to the new account's names, or accept the 502 (which is the designed behaviour and is honest). Add `GHL_INTAKE_PIPELINE_NAME` and `GHL_HOT_STAGE_NAME` when Unit 43 starts. |
| **C6** | **`ghl_opportunity_cache` holds an entire pipeline's worth of dead ids**, and `PipelineScope` authorises writes from it (F5), so a stale row would authorise a write to a dead opportunity. | `V40`; `PipelineScope.java:63` | **P1.** `TRUNCATE ghl_opportunity_cache`. It is droppable by design and this is the one situation that was designed for. |
| **C7** | **Client Portal invoices return empty for every pre-cutover client.** `PortalInvoiceService` passes the stored `ghl_contact_id`; GHL has no such contact. | `PortalInvoiceService.java:61`; `00c` §1c | **P1.** No repair is possible without re-linking contacts. Interim: the screen must say *"we can't reach your billing records right now"* rather than showing an empty list that reads as "you have no invoices". Currently it returns `List.of()` on a null id (`:59`) and a 502 otherwise — neither is that message. |
| **C8** | **Client Portal meetings, same.** | `GhlCalendarClient.java:247` | **P1.** Same message treatment. |
| **C9** | **`evalos_case.ghl_opportunity_id` (V24) names dead opportunities** on every pre-cutover case. `uq_case_open_per_opportunity` is partial on non-CLOSED, so a *new* won opportunity in the new account could in principle collide with an old id — GHL ids are 20-char random, so practically it will not. | `V24__case_ghl_opportunity.sql` | **P2.** Accept. The id's only remaining job is support ("which deal is this"), and it still answers that historically. Do not null it. |
| **C10** | **`client_account.ghl_contact_id` and `portal_access.ghl_contact_id` name dead contacts** — but the EvalOS join (`PortalCaseService.authorized()` → cases) still works. | `V45` header; `V44`; `00c` §1c | **KEEP as is.** V45's correction is right and the reasoning in it is the best-argued thing in the repo: two jobs, one column, only the GHL-facing one broke. Nulling it would blank every client's case list. |
| **C11** | **Pre-cutover `audit_event` rows with `object_type = 'GHL_OPPORTUNITY'`** have derived UUID keys over dead GHL ids (F8b). | `GhlWriteClient.java:262` | **P2.** Accept — append-only, invariant 13. They are historically true. |
| **C12** | **`brand.ghl_webhook_secret` and `webhook_event.signature_verified` are dead columns** carried since 2026-08-27, plus a local seed that still writes the first. | `V11`; `WebhookEvent.java:56-62`; `V901__seed_local_webhook_secrets.sql:7` | **P2.** Drop both in Unit 44's migration (which is touching this area anyway) and delete the local seed. A column that looks like a security control and is not is worse than no column. |

**The order that matters: C1 → C3 → C2 → C4.** Until C1 and C3 are done nothing else is
testable, and until C4 is done every desk is silently empty.

---

## Open questions

Each with my recommendation, per the house rule.

**Q1. Does the outbox replace synchronous desk writes, or sit behind them?**
`00c` §4d says "every push to GHL becomes a durable row" without saying whether the
salesperson still waits for GHL. **Recommend: the mirror row is written and returned
synchronously, the GHL push is asynchronous.** That is the only version in which the
sync-off switch (Unit 48) is a real switch, and it is what removes F7. The cost is that
a GHL rejection (bad stage) surfaces seconds later on the sync-status surface rather
than in the form — which is exactly what the drift report is for.

**Q2. What resolves identity for a portal signup (Unit 43)?**
**Recommend: `POST /contacts/upsert` against GHL with the signup email, before any
EvalOS row is written**, and adopt the returned `ghl_contact_id`. It is one request, it
reuses `GhlWriteClient.upsertContact` unchanged, and it is the only thing that makes a
portal signup and a GHL lead the same person. The alternative — resolve against
`client_account` only — guarantees the duplicate described in Origin 4, because
`client_account` structurally cannot know about prospects (F3).

**Q3. Should `contact.created`/`contact.updated` be implemented now, before Unit 44?**
**Recommend: yes.** They are already routed, already archived, already acked; the
handler is the only missing piece and it can write `contact_snapshot` through the
existing resolver. It closes F3, it is half of Unit 45's inbound layer, and it is the
prerequisite for Q2 being useful.

**Q4. Per-field ownership, or `00c` §4b's blanket "EvalOS wins"?**
**Recommend: per-field**, with the table above. Blanket EvalOS-wins reverts GHL
automations, which is the one thing `00b` explicitly kept GHL for.

**Q5. Does the mirror need tier-2 custom fields at Unit 44 rather than 47?**
**Recommend: yes, one field only** — the EvalOS opportunity/contact id written into a
GHL custom field as a correlation key. Not the general tier-2 sync. Without it,
at-least-once delivery over a non-idempotent create produces duplicates, and "a single
digit of mismatch will not be tolerated" is not survivable.

**Q6. Is Unit 25 (per-brand location + token) a prerequisite for Unit 44?**
**Recommend: yes, sequence it before 44.** Every mirror table carries `brand_id` and
there is exactly one credential; shipping 44 without 25 means a `brand_id` column with
one value, which is the anti-pattern V40 explicitly refused. If the business will not
fund it, then 44's tables should carry `ghl_location_id` instead of `brand_id` and
derive the brand — but that decision must be taken, not drifted into.

**Q7. How is the `opportunity.won` replay path separated from mirror sync?**
**Recommend: keep them separate.** The mirror's delta sweep repairs *state*; Handoff A
is an *event* with a side effect (a case, a checklist, a notification) that no state
comparison can reconstruct. Build the webhook replay sweep (F2) as its own thing, and
do not let Unit 45 absorb it.

**Q8. What does the portal show when GHL is unreachable?**
**Recommend: an explicit "billing is temporarily unavailable" state**, distinct from
"no invoices". Today `PortalInvoiceService.java:59` returns an empty list for a null
contact id and a 502 otherwise, and the frontend distinguishes neither. Post-cutover
(C7) every legacy client hits this, so it is not hypothetical.

**Q9. Does `GhlHttp`'s blocking pacer need bounding before the mirror ships?**
**Recommend: yes, and it is a two-line change.** A background sweep and a desk request
now compete for the same pacer; today only humans do. Bound the wait and throw rather
than parking a Tomcat thread indefinitely (F12).
