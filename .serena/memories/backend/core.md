# backend/ — Core

Spring Boot 3.5.16 / Java 21, base package `com.ie.evalos`, Maven Wrapper committed. **Units 01–10 +
05a (all of Phase 1) and Units 11–12 are built**: config + response envelope, the tenancy/auth/RBAC
spine, the domain schema, the case state machine + SLA, the inbound webhook gateway with Handoff A,
the staff notification centre, the board/case-detail/checklist reads behind four frontend surfaces,
the expert database + sheet upload, and the assist-mode match scorer. **Unit 14 (the client draft-review
portal — a second filter chain, the first non-staff caller) is built too.** **Unit 13 (redacted expert
profile + the first outbound Google Drive client) is code-complete, with its manual live upload
still owed** — see `mem:core`. Unit 15 is next. See `mem:core` for counts and the phase map.

## Package boundaries (all under `com.ie.evalos`)

`web` (thin controllers + DTOs) · `service` (all business logic + `@Transactional`) · `domain` (JPA
entities + enums) · `repository` (Spring Data + brand/team/assignee scoping) · `integration` (GHL,
Google Drive clients) · `webhook` (inbound gateway: verify → resolve brand → dedupe → archive →
route) · `event` (domain events + outbound HMAC dispatcher) · `job` (`@Scheduled` sweeps) ·
`notification` (in-app staff center) · `security` · `common` (envelope, encryption converter, error
types) · `config`.

`web`/`service`/`domain`/`repository`/`security`/`common`/`webhook`/`event`/`notification` and — since
Unit 13 — `integration` are populated; **`job` is still an empty `.gitkeep` placeholder**. Put code in
the package that matches the concern — controllers never hold logic, entities never leave the service
layer (map to DTOs). `notification/NotificationListeners` is the only subscriber to `event` so far;
the outbound dispatcher (Unit 18) is the next.

`integration` holds `GoogleDriveClient` + `DriveUnavailableException` (Unit 13), the first outbound
client, and `GhlPipelineClient` + `GhlUnavailableException` (Unit 24), the first **read** client.
The pattern both follow: **one narrow
capability, not an SDK wrapper**; a bounded request with an explicit timeout, because these are called
from controller-triggered paths and invariant 6 forbids long-lived work there; and a failure that is a
**502 changing nothing in EvalOS** rather than a partially-applied state. If a call stops fitting in
one bounded request it moves to `job` (Unit 19), which is where that rule points.

**The one read-side exception**: `MarketingPipelineService` totals a GHL window over ~1,000
opportunities on its own single daemon thread (`ghl-totaller`), not in `job`. It writes no EvalOS
row, so there is no side effect to lose — the rule's actual concern. Forced by arithmetic: a year is
~11.4k opportunities in 115 *cursor* pages (unparallelisable), and GHL allows 100 requests per 10s
per location, so ~13s minimum, past the browser's 15s timeout. `GhlPipelineClient` paces every
request 110ms apart to stay under that limit — shared across all threads, since the limit is per
location. The payload carries `Detail` = `READY | TOTALLING | UNAVAILABLE`; the screen polls the same
URL and the existing `(funnel, window)` cache is the handover, so there is no job id and no second
endpoint. A failed background read lands as `UNAVAILABLE` so a poller stops.

**`GhlPipelineClient` (Unit 24) — the GHL read client. Plain `RestClient`, no new dependency**
(`spring-boot-starter-web` already ships it; timeouts via `SimpleClientHttpRequestFactory`). It has
**no write method, by design** — the token is `opportunities.readonly`, so read-only by grant *and*
by code, and a mistake in either place is still not a write to a GHL pipeline. Four decisions in it
worth knowing:

- **Only three fields are bound off each opportunity** (`pipelineStageId`, `monetaryValue`,
  `source`). GHL's search response also carries every contact's name, email, phone and tags; a stage
  count needs none of it, and the narrow record is what keeps marketing PII out of an EvalOS
  response — not a projection somebody has to remember.
- **`status` is deliberately unbound, and the live data settled the argument.** 144 opportunities
  in this account sit in the stage named *Won* while only **3** carry `status: "won"` — the rest
  were dragged into the column without anyone pressing GHL's separate win button. So the stage is
  what actually happened and the status field is not usable. `MarketingPipelineService.Outcome`
  derives the outcome from the **stage name, matched ignoring case and surrounding space**
  (`Won`/`won`/`WON`/`" Won "` are one thing), and every `StageFunnel` carries it. `Cold` proves
  it is a match and not a vibe: not one of GHL's four status words, so it stays `OPEN`. The rule
  is on the name, so **all three funnels** get it with nothing special-cased — including Unit 27's
  `Meeting booked`, `Invoice sent` and `Refund`, which are `OPEN` for exactly `Cold`'s reason.
- **Cursor pagination** (`startAfter`/`startAfterId`, what GHL's own `nextPageUrl` uses), not page
  numbers: a salesperson dragging a card mid-read is the normal case, and a cursor cannot skip or
  double-count the row that moved. Loop capped at **1,500** pages — a runaway guard only, because
  **row paging is no longer how the funnel is counted**. (It said 50 here and in the code, which was
  *silently truncating*: 50 pages is 5,000 rows against the email funnel's 11.4k year. A cap below
  what a caller may legitimately ask for is not a guard, it is a wrong answer with a log line.)
- **`countIn(pipelineId, stageId, from, to)` is the counting path, and it is the reason the Year
  view exists.** GHL returns the match count in `meta.total` on any search, so a `limit=1` request
  with `pipelineStageId` applied gives an exact stage count in **one** request. Counting by
  pagination cost one request per hundred rows — 115 of them on the email pipeline's year (11,432
  opportunities), which blew the frontend's 15s axios timeout and rendered nothing at all.
  The filter is **`pipeline_stage_id`, snake_case** — like `location_id`/`pipeline_id` beside it,
  unlike the camelCase `date`/`endDate` on the same route. It shipped camelCase once and GHL
  answered `422 "property pipelineStageId should not exist"`: the spelling had been checked
  through a tool that **normalises parameter names before sending**, so the evidence was for a
  request the app never makes. **Only a call built the way the app builds it is evidence for this
  API.** Pinned in `GhlPipelineClientHttpTest`, and now **exercised against the real API by
  `GhlPipelineClientLiveTest`** (opt-in: `GHL_LIVE_TEST=true`; token read from the gitignored
  `backend/config/application-local.yml`, never from a command line). That live run is what
  confirms the client's *mixed* casing is deliberate: `locationId` camelCase on
  `/opportunities/pipelines`, `location_id` and `pipeline_stage_id` snake_case on
  `/opportunities/search`. Verified live across all six stages of the email
  funnel: 2026-08-26 they summed to **11,417** over a 365-day window (11,432 was the earlier
  hand count over a wider one — the figure moves with the window and the live data, so treat any
  recorded total as an observation and not an invariant). `meta.total` can be a row or two stale
  against a paginated read; that is the accepted trade, and it is why the *loop bound* in
  `opportunitiesIn` still does not use it.
- **The pipeline is resolved by name, not id.** The id is opaque to whoever provisions an
  environment; the name is what they can read in GHL. A rename there is a stated 502, not an empty
  funnel that looks like a bad month.

`MarketingPipelineService` aggregates it and **owns the cache, which is the rate limiter rather than
a speed-up**: one payload, one TTL, shared by every caller — without it N open dashboards are N
multi-page GHL reads per refresh. A **failed refresh is never served from the previous value**; it
propagates so the screen shows the error, because a kept figure shown without its failure is the
"looks live and is not" bug. The payload carries `readAt` so the screen states its own age. Two
racing callers both call GHL — left unguarded on purpose, since the alternative holds a lock across
a network call. **The WRITE is guarded though**: it is compare-and-set (`@Version`), because the
loser of that race must not overwrite a completed background total with `TOTALLING`, and a failed
background read must not blank a `READY` row to `UNAVAILABLE`.

**The cache is a TABLE, `ghl_funnel_cache`, since 2026-08-26 — it was a `ConcurrentHashMap`.** The
old note here said "nothing is persisted", and that is no longer true of the aggregate. It moved
because a heap map is private to one process, which cost three things: a completed background total
was lost on restart; a screen polling a `TOTALLING` window could hit an instance that had never
heard of it and wait forever, or flip between `READY` and `TOTALLING`; and the rate-limit protection
was per instance, so N instances meant N times GHL's budget. Row per `(funnel, range_name)`, payload
as one `jsonb` document (nothing queries inside it), `detail` and `read_at` lifted out as columns,
`totalling_since` as the background claim — **a timestamp so the claim can EXPIRE**, since a row
outlives the instance that wrote it and a killed totaller would otherwise wedge the window forever.

**What is still NOT persisted: opportunity rows. No `ghl_opportunity` table, and there must not be
one** — a stage dragged five seconds ago would already be wrong in it. Only the aggregate the screen
draws is stored. Not brand-scoped, deliberately: the figures come from one global GHL location
EvalOS cannot attribute to a brand, so a `brand_id` would be a column nobody could fill in
correctly. Unit 25 adds it with the location move. Safe to truncate; losing it costs one slow page.

A payload this version cannot deserialise is treated as a **cache miss, never an error**, so a
record that gained a field does not 500 the first request after a rollout.

**Source rows are grouped case-insensitively**, keyed on `toLowerCase(Locale.ROOT)` — `ROOT`
because a Turkish-locale JVM lower-cases `I` to `ı` and would split the rows this joins. The row
keeps the **first spelling seen**: these are hand-typed campaign strings, so one source arrives
cased several ways, and a canonical casing invented here would show a label that exists nowhere in
GHL.

**Counts and money come from different reads, and the split is load-bearing (Unit 26).** Every
`deals` figure — the total and each stage's — is `countIn`, so it is **exact for a period of any
size** and costs one request per stage. `totalValue`, each stage's `value` and the whole `sources`
breakdown are a *sum* and a *group-by*, which GHL aggregates neither of, so they need every row:
those are read only when `totalDeals <= DETAIL_ROW_BUDGET` (1,000 = 10 pages). Above it,
`detailAvailable` is false, the money fields are **null (never zero)** and `sources` is empty, and
the screen says which figures it could not compute. **Never a partial total** — a sum over
whichever rows fitted looks exactly like a real number, which is the failure this replaced.

**Three funnels, one service (Units 26, 27).** A `Funnel` enum (`ADS`, `EMAIL`, `SALES`) keys into
three configured names, `evalos.ghl.{ads,email,sales}-pipeline-name`. **There is deliberately
no pipeline-name parameter** — the location holds seven pipelines and four are other teams', so a
name on the query string would let any GM read all of them and make the screen's contents a
caller's argument rather than a deployment decision. The cache key is `(Funnel, DateRange)` and
**both halves are load-bearing**: the payloads are identical in shape, so an unkeyed slot
serves one funnel under another's heading for a whole TTL with nothing to contradict it.

**Unit 27 was the test of this shape and it held**: the third pipeline cost a property, an enum
constant, a controller method, a nav entry and a union member — **no new class on either side**.
Keep doing that. `SALES` is the sales team's own working pipeline (nine stages, including
`Meeting booked`, `Invoice sent`, `Refund`) rather than a campaign funnel, so **its screen sits
under a `Sales` nav heading while its route stays `/api/marketing/sales-pipeline`** on
`MarketingController`. That asymmetry is deliberate: a `SalesController` holding one method that
called `forCaller` would split one integration across two doors to fix a word. None of its three
new stage names is special-cased — `Outcome` reads names and knows only GHL's status words, so all
three are `OPEN`, as `Cold` already was. **`Refund` was declined as an outcome constant**: it is a
money event belonging to the payment record, not a shape in a funnel.

**`GhlPipelineClient.pipelineNamed` collapses whitespace runs and trims before comparing, and that
is load-bearing rather than defensive.** The live sales pipeline is named `Aditya's··pipeline`
with **two spaces** — so the single-space spelling any human types into config did not match, and
the screen answered 502 whose cause is invisible in both places anyone would look (the two strings
render identically). Pasting the double space into the yml profiles was rejected: correct only
until an editor, linter, shell or reviewer normalises whitespace. **Case and whitespace are the
only things forgiven** — no punctuation stripping, no fuzzy matching, because a name differing by a
real character *is* a different pipeline and must still fail loudly, which is the entire reason
matching is by name. Pinned in `GhlPipelineClientHttpTest` (fixture holds the real double space)
and, decisively, in `GhlPipelineClientLiveTest` — only a real call proves the fixture matches what
GHL returns.

**Periods are `DateWindow`, not `DateRange`, as of Unit 28 — and `DateRange` no longer does any
arithmetic.** It carried an `int days` and every window was "now minus N days"; that cannot express
a calendar-to-date period (no fixed width — "this month" is 1 day wide on the 1st) or `last-month`
(does not end today at all). So `DateRange` is now **only the vocabulary** — `today`, `week`,
`month`, `year`, `last-month`, `last-year`, `custom`, hyphenated on the wire — and
`common/DateWindow` resolves a name into a pair of **inclusive days**. `startFrom` and
`startDateFrom` are gone; there is one resolver.

Three things about it that are load-bearing:
- **Days are the primitive, instants are derived** (`startInstant()`/`endInstant()`). GHL's filter
  is date-only and inclusive; the metrics callers convert. The old code went the other way and
  shipped a bug — an instant window converted to dates came out a day too wide, so "today" covered
  yesterday and roughly doubled its figure.
- **`endInstant()` is EXCLUSIVE** while the days are inclusive. An inclusive end would be the last
  representable instant, and anything stored with finer precision falls outside it.
- **It takes a `Clock` (`BusinessCalendar.clock()`), which carries both the zone and "today".** One
  source, so a window cannot be resolved in one zone and converted in another. Tests pass
  `Clock.fixed`, which is why every boundary case is testable without waiting for a calendar.

**Validation is at the boundary and refuses rather than ignores**: `custom` needs both ISO edges,
`from` may not follow `to` (equal is fine), and **explicit dates on a NAMED range are a 400** — a
caller writing `?range=month&from=…` means that window, and answering for this month is a wrong
number wearing a right label. No maximum span, deliberately: the GHL screens already degrade to
`UNAVAILABLE` past their row ceiling, and a second definition of "too big" would disagree with it.

**`ghl_funnel_cache` is keyed on the RESOLVED WINDOW (`window_key`, V26), not the range name.**
Every custom period is *named* `custom`, so a name-keyed row would be shared by two different
windows and serve one's figures for the other — undetectable, because the payloads are identical in
shape. Same failure as dropping `funnel` from the key, one axis over. It also fixed a smaller fault
free: a `month` row used to keep answering after midnight. V26 **deletes** old rows rather than
translating them — which window a row covered depended on the day it was written, which the row
never recorded.

**The old note below is kept because the rule it states still holds, one layer down.**

**The GHL window is whole days, both edges inclusive** — now guaranteed by `DateWindow` rather than
by picking the right accessor. `startFrom` is a *half-open instant* window (the last 24 hours), which is right for
`MetricsController` reading EvalOS rows. GHL's filter is **date-only and inclusive on both ends**,
so converting `startFrom` to a `LocalDate` made every window a day too wide and made `today` span
*yesterday and today* — a screen headed "today" showing roughly double GHL's own figure. Fixed
2026-08-26; `DateRangeTest` pins both shapes so the two methods are not collapsed into one.

**The cache writes are compare-and-set, not `put`.** Two callers racing past a stale entry is
still deliberately unguarded (a lock across a network call is worse). What is guarded: a slow
inline reader must not overwrite the background totaller's completed `READY` figures with
`TOTALLING` (which also restarted a background read for work just finished), and a failed
background read must not blank a `READY` entry to `UNAVAILABLE` for the rest of the TTL.

**Every field off the GHL wire is null-guarded, `stages()` included.** `pipelines()`,
`opportunities()`, `meta()` and `stages()` — an unguarded NPE escapes `GhlUnavailableException` and
becomes a 500 telling the GM to report an EvalOS bug, instead of the 502 telling them the upstream
pipeline is misconfigured, which is the only one of the two they can act on.

`MarketingController` (`GET /api/marketing/ads-pipeline`, `/email-pipeline` and `/sales-pipeline`,
all three `hasRole('GM')`, each taking `range` plus `from`/`to` for `range=custom`) is **its own controller rather than a sixth route on `MetricsController`** —
everything there reads EvalOS tables scoped to the caller, while this leaves the building, is
unattributable to a brand, and 502s instead of 500s. **No route takes a `brandId`**: the
location is one global setting with no mapping to a brand, so a parameter would narrow nothing
while implying it had. One route per funnel rather than `/pipeline/{name}`, for the same reason
the enum exists. **The GM-only rule is per-*location*, not per-screen** — `/sales-pipeline` sits
under a different nav heading and inherits it unchanged, which is the assumption most likely to be
dropped when a fourth screen is added.

## Payouts (Units 16 + 16b) — `PayoutService`, and why it is two tables

**EvalOS records that money moved; it never moves money.** No rail, no bank API, no stored
credential. `method` and `reference` are whatever the person who sent the transfer wrote down.

**The expert charges per draft and is paid weekly**, so money owed and money sent are two different
counts and therefore two tables. A `payout_ledger` row is one delivered draft, opened inside
`CaseLifecycleService.deliverToClient`'s own transaction (delivered and owed are one fact) and
prefilled from `Expert.standard_fee` — **null when there is none, never zero**, because a prefilled 0
is a number somebody could settle without noticing. A `payout_payment` row is one transfer covering
however many drafts it covered; the ledger rows point at it.

`PayoutService` carries both axes — the transitions (`openForDelivery`, `settle`, `correctAmount`,
`editPayment`, `confirm`) and the screen projections (`list`, `batch`, `history`, `payment`). The tell
that it is near its limit: `CaseRepository` and `TeamMemberRepository` are injected purely so reads
can resolve display names, which the write path never needs. If a fourth projection arrives, split
`PayoutQueryService` off and leave the transitions here.

**Two rules do the load-bearing work, and both exist to stop the ledger disagreeing with the bank:**

1. A payment's amount must equal the sum of the drafts it settles, compared with **`compareTo`, never
   `equals`** — `BigDecimal.equals` is scale-sensitive, so `700.0` and `700.00` would be "different"
   and refuse a settlement nobody could fix from the screen. Without the check at all, a payment
   could claim drafts it never covered and nothing downstream could detect it.
2. The attach is one conditional `UPDATE` whose affected-row count is asserted — see
   `mem:backend/persistence`. Short count means someone else took a row, and the whole settlement
   including the payment insert rolls back.

`PENDING → PAID → CONFIRMED`, forward only. **`PAID` is only ever reached through `settle`** (money
leaves in transfers, not in drafts) and **`CONFIRMED` is set on the payment and cascades** (one
transfer, one acknowledgement — there is no route that confirms a single draft). `VOIDED` is set only
by `RefundService`, only on `PENDING` rows: a row already attached to a payment is money that left,
and a database write cannot un-send it. "Overdue" is **derived** (`PENDING` past `due_date`), never a
fifth status — a status flipped by a clock is wrong between ticks.

**Writes are GM, Brand Manager and ENM.** `PayoutService.MAY_RECORD` is the single authority and is
`public` so `PayoutControllerTest` can assert by reflection that the controllers' `@PreAuthorize`
names exactly those three — the nav gate and the server gate are provably one list. The guard is
re-checked in the service as well as at the route, the `RefundService` precedent: a money path must
not be reachable as anyone else by a later job, webhook handler or service.

`expert.total_payments_pending` stays dead and derived, beside `current_active_count`.

## `job`, when it stops being empty (Unit 19)

Decisions already taken, so the package does not get invented from scratch:

- **Spring `@Scheduled` + `@EnableScheduling`.** No Quartz, no ShedLock — both are a dependency and a
  table for what is already on the classpath. Intervals bind from `evalos.jobs.*`;
  `evalos.jobs.enabled=false` in the test profile so the suite cannot race a sweep.
- **Every sweep claims `pg_try_advisory_lock(hashtext(:jobType))` first** and returns if it loses,
  releasing it in a `finally`. Not for scale-out — because **every rolling deploy runs two instances
  for a few seconds**, and two sweeps ticking together double-chase a client and double-alert staff
  with nothing in the logs to say why.
  **Session-scoped, not `pg_try_advisory_xact_lock`.** The xact variant releases on commit, and a sweep
  runs *one transaction per item* — so it would drop the lock after the first item and leave the rest of
  the run unprotected, which is the exact failure it was added to prevent. Claim it outside the per-item
  transactions.
- **`scheduled_job` records runs, not intentions.** No row-per-future-timer: a sweeper asking "what is
  overdue right now" is correct on the first run after any outage. Idempotency comes from the data the
  action already writes (`CHASED` audit rows for chases, notification rows for thresholds), never from
  an "already ran" marker — which is why `POST /api/jobs/{type}/run` is safe to press twice.
- **One transaction per item**, so one poisoned case cannot stop the sweep; the run is recorded
  `FAILED` with the error and the next tick retries.
- **A sweep prompts and publishes; it never transitions.** No sweep may fire `EXPERT_TIMED_OUT`.
- Unscoped reads are deliberate (no authenticated caller, so `ScopePredicate` does not apply); every
  side effect goes through `AuditService.recordSystemEvent` with the brand from the row.
- **Five sweeps, not six** — retention left the unit; GHL owns it.
- The **queue is the `webhook_delivery` outbox**, `FOR UPDATE SKIP LOCKED`. See `mem:backend/webhooks`.

## Response envelope — non-negotiable

`common/ApiResponse<T>` (`success`, `data`, `error{code,message}`, `@JsonInclude(NON_NULL)`) is
returned by **every** endpoint; `ApiResponse.ok(...)` / `.error(...)`. `common/ApiExceptionHandler`
(`@RestControllerAdvice`) maps exceptions to it — but failures raised **inside the security filter
chain never reach the advice**, so `common/ApiErrors` writes the envelope for those 401/403s.
Handled: validation (400), `HttpMessageNotReadableException` (400 — an unknown enum value in a body
used to fall through to the catch-all and answer **500**; the message names the offending value but
never echoes Jackson's, which quotes the payload and lists every legal value),
`InvalidRequestException` (400, message returned — same "may not be an existence oracle" rule as
`IllegalTransitionException`), `MaxUploadSizeExceededException` (400), auth (401), forbidden (403),
`IllegalTransitionException` (409), webhook rejection (its own status),
`DriveUnavailableException` (**502** — an upstream fault, so the caller retries rather than reports a
bug; Unit 13), `GhlUnavailableException` (**502**, same reasoning; Unit 24 — kept as its own handler
with its own `GHL_UNAVAILABLE` code rather than folded in with Drive, so the code names *which*
upstream failed), `NoResourceFoundException` (**404** — this advice is a plain `@RestControllerAdvice`
and does not inherit `ResponseEntityExceptionHandler`, so Spring's own `ErrorResponseException`s fall
to the catch-all: **every unmapped URL used to answer 500 and log at error level**. Same class of bug
as the enum one above; found in Unit 05b while asserting `/mark-paid` was gone. Body carries no
detail — whether a path exists is not information a caller is owed), catch-all (500). The
frontend's typed mirror lives in `frontend/src/lib/api.ts`.

## Config & schema

- `application.yml` + `application-local.yml` / `application-prod.yml`, every value env-backed:
  `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `JWT_SECRET`, `JWT_TTL` (PT8H),
  `EVALOS_FIELD_KEY`. **No secret is ever committed.** `prod` has no defaults at all; `local`
  supplies localhost fallbacks (`postgres`/`1234`@5432, db `evalos`) plus dev-only fallbacks for the
  two keys. `spring.profiles.default: local`.
- **`evalos.drive.*` (Unit 13).** `key-json` (`GOOGLE_DRIVE_KEY_JSON`, inline JSON) and
  `credentials-path` (`GOOGLE_APPLICATION_CREDENTIALS`) both default to **empty**, and
  `required` — true in `application.yml`, restated in `prod`, **false only in `local`** — is what
  makes a missing key fatal. Deliberately not an unresolvable placeholder like `EVALOS_FIELD_KEY`:
  that could only ever demand one specific variable, so setting the other would fail the boot.
  `GoogleDriveConfig`'s **constructor** throws, so the context does not come up, and the key is read
  at startup so an unreadable path also fails there rather than at the first upload. Also
  `scope` (defaults to `drive.file`; the `.../auth/drive` fallback is a property, not a code change)
  and `timeout` (20s). **`local` is the only profile that runs without a key** — every route works
  and only the Drive write answers 502.
  A `@WebMvcTest` slice never loads `GoogleDriveConfig`, so **only the gated DB run proves these keys
  bind**; a typo here is invisible to `verify` alone. **Unit 24 closed that hole for its own config
  rather than repeating it** — `GhlPipelineClientTest` binds the bean against the real
  `application.yml` with `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`,
  which is the pattern to copy for the next `@Value`-with-no-default bean. Note it needs
  `ApplicationConversionService.getSharedInstance()` set on the bean factory: `10s` → `Duration` is
  a lenient conversion that a real boot installs via `SpringApplication` and a bare context runner
  does not, so without it the harness fails on something production does correctly.
- **`evalos.ghl.*` (Unit 24).** `token` (`GHL_API_TOKEN`) and `location-id` (`GHL_LOCATION_ID`)
  default to **empty**, and — unlike `evalos.drive.required` — **there is no `required` flag and a
  missing token does not fail the boot.** Deliberate difference: `JWT_SECRET` and `EVALOS_FIELD_KEY`
  must be fatal because signing with a guess or storing plaintext is unrecoverable, while this gates
  **one read-only GM screen**, so the app serves everything else and answers 502 there.
  `GhlPipelineClient` logs a warning at boot so it is not discovered as a surprise. Also
  `base-url`, `api-version` (GHL versions by *header*, `2021-07-28` — a name, not a secret, so it
  has a default and a bump is an environment change), `ads-pipeline-name` (`Google ADS Pipeline`),
  `email-pipeline-name` (`Shivangi's Email Marketing`, Unit 26), `sales-pipeline-name`
  (`Aditya's pipeline`, Unit 27 — **written with ONE space where GHL stores TWO; the client
  normalises whitespace, so do not "fix" it**) — three names, **not** a list: a list
  needs a slug per entry to route and label it, and that slug is what the `Funnel` enum already is,
  `timeout` (10s, **per page**) and `cache-ttl` (`PT5M`). The token must be a GHL **Private
  Integration Token scoped `opportunities.readonly` and nothing wider**.
  **`location-id` is ONE location for the whole deployment — and note what that does NOT mean.**
  Each brand has its own GHL sub-account, so whatever is set here is **one brand's funnel, not a
  cross-brand total**. EvalOS has no mapping from a location to a brand, so it cannot say which
  brand, label it, or filter by it — which is why the view is GM-only: an *unattributable* figure
  must not be shown to a role locked to one brand. (An earlier note here, and the comment in
  `application.yml`, said the brands *share* one location and the figure was "cross-brand by
  construction". That was wrong and is withdrawn.) A second brand is **not** a second property —
  it is Unit 25 putting the location on `brand`.
- `ddl-auto: validate`, `open-in-view: false` — do not relax either. Hibernate never touches schema.
- Flyway `classpath:db/migration`: `V1` pgcrypto · `V2` brand · `V3` team_member · `V4`
  contact_snapshot · `V5` evalos_case · `V6` document_checklist_item · `V7` expert (+ the deferred
  `evalos_case.expert_id` FK) · `V8` payout_ledger · `V9` notification · `V10` audit_event · `V11`
  brand GHL secret · `V12` webhook_event · `V13` brand-scoped webhook idempotency key · `V14`
  `evalos_case.paid`/`paid_at` · `V15` partial unique index for one open case per contact+service ·
  `V16` contact identity · `V17` `evalos_case.assigned_coordinator` · `V18` expert contact columns +
  the closed-vocabulary CHECKs + the per-brand email index · `V19` `expert_case_offer` · `V20`
  `evalos_case.draft_link` · `V21` `portal_access` · `V22` `audit_event.actor_type` · `V23` the
  one-unrevoked-token index that turned "one live portal token" from a service check into a
  constraint (all in `mem:backend/persistence`).
  **Never edit an applied migration** — `V12`'s constraint was once renamed in place, which would
  have made `V13`'s `DROP CONSTRAINT` fail on a fresh database while breaking checksums on existing
  ones.
- The `local` profile additionally lists `classpath:db/seed-local` (`V900` seed: 2 brands, 5
  logins, password `DevPassw0rd!`; `V901` per-brand webhook secrets; `V902` the remaining roles;
  `V903` seed experts; `V904` brand currency; **`V905` the demo dataset — 13 experts, 29 cases across every stage, nine months of closed history, and a full clear of every transactional table first, which makes it idempotent**) and sets
  `flyway.out-of-order: true` — the seed deliberately outranks every real migration, so without that
  flag the next unit's `V-N` is refused on an already-seeded dev database. `prod` keeps the strict
  default and never sees the seed.
- **`application-testprod.yml` + `db/seed-testprod/V950` (test-production deploy, 2026-08-27).**
  Activated as `SPRING_PROFILES_ACTIVE=prod,testprod` — **both, never `testprod` alone**, which
  would boot on the base profile's local-ish defaults with none of prod's rules. `prod` supplies the
  entire configuration; this profile adds only the seed location, `out-of-order: true` (V950
  outranks every migration, same reason as `local`), and three `flyway.placeholders`.
  **`V950` seeds a REAL environment, so no credential is written in it.** `ie-webhook-token`,
  `xp-webhook-token` and `seed-password-hash` arrive as Flyway placeholders resolved from
  `IE_WEBHOOK_TOKEN` / `XP_WEBHOOK_TOKEN` / `SEED_PASSWORD_HASH`, **with no defaults** — a missing
  one fails the migrate rather than seeding a brand whose token is the literal `${...}`. It inserts
  the 2 brands (ids `3333…`/`4444…`, `currency` inline — no V904-style follow-up needed) and 11
  logins (ids `eeee…`, `@testprod.evalos.local`, one per role on **both** brands plus the single
  brand-less GM), and **nothing else**.
  **`V951__seed_testprod_demo_data.sql` (2026-08-28) adds the demo slice** beside it — a separate
  file because V950 is applied and Flyway checksums it, so editing it (comments included) would
  fail the next migrate; the profile lists the directory, so no config change was needed. It
  seeds 5 experts, 7 contacts and 8 cases — one per lifecycle position, pool through closed,
  across both brands — plus 6 expert offers (one still `OFFERED`, one `DECLINED` and rematched),
  2 payouts (one `PENDING`, one `PAID` by a single `payout_payment` left unconfirmed), 6
  notifications, and a generated checklist and audit timeline per case (`INSERT … SELECT` over
  `evalos_case`, scoped `WHERE id::text LIKE '66666666-%'` so it cannot touch a real case). Ids
  are range-partitioned (`ffffffff…` experts, `55555555…` contacts, `66666666…` cases,
  `77777777…` payouts, `88888888…` payments, `99999999…` offers) and times are relative
  `now() - INTERVAL …`. It carries no credential: no `portal_access` row (a usable token would
  have to be committed), `payment_detail` NULL (only PaymentDetailConverter writes that
  ciphertext), `ghl_funnel_cache` empty (seeding a cache of a live API is fiction). It does not
  replace the real paths — Handoff A still creates cases, and Unit 11's upload still upserts the
  roster over these experts on `lower(email)`.
  It deliberately reuses **none** of `seed-local`'s ids, logins or hash — those are committed to
  this repository. `brand.ghl_webhook_secret` is left NULL: no Java reads it, and NULL fails closed.
  Rotate a webhook token with an `UPDATE`, never by editing `V950` (invariant 9).
  `ConfigSecretsTest` scans this profile like any other; `MigrationTreeTest` checks both seed trees.
- **The seed tree is a sibling of `db/migration`, never a child, and that is load-bearing.** It sat
  at `db/migration/local` until 2026-08-06 in the belief that only the profile naming that path
  would apply it. Flyway scans a location *and every sub-directory*, so prod's plain
  `classpath:db/migration` reached it: a production boot would have inserted the two seed brands and
  six logins sharing one committed BCrypt hash, GM included, plus the throwaway webhook secrets.
  Flyway has no exclude filter, so directory separation is the entire mechanism, and
  `config/MigrationTreeTest` now fails the build if anything reappears below `db/migration`.
  `db/seed-testprod` is a sibling for the same reason and is covered by the same test.
- Actuator exposes `health` only.

## Running & tests

- **A reachable Postgres is required to start.** This machine has PostgreSQL 18 with the `evalos`
  database: its `public` schema is at **`V22`** (+ the `V90x` local seeds) and its `evalos_test`
  schema at **`V23`**, because the gated suite runs migrations and a `spring-boot:run` has not
  happened since `V23` landed — **the next one applies it** — see `mem:suggested_commands`. Its `public`
  schema also holds ~46 junk experts and ~150 junk cases from integration-test runs that predate the
  `evalos_test` schema; they are dev noise, not data, and they show up on the roster screen.
- Map every controller under `/api` (the Vite dev proxy). Endpoints are **secured by default**: a new
  one answers 401 until `SecurityConfig` permits it or the caller bears a token — and a route under
  `/api/portal/**` lands on the *other* chain (`PortalSecurityConfig`), which accepts no JWT at all.
- Tests are slice (`@WebMvcTest`) or plain unit tests needing no DB. Everything that needs a real
  schema lives in one `@SpringBootTest`, `LocalPostgresIntegrationTest`. No Testcontainers, no Docker.
- **That suite now RUNS whenever a Postgres is reachable (changed 2026-08-26).** It used to be gated
  on `-Devalos.db.test=true` alone, which meant 27 tests silently skipped on every machine that had a
  database — so a green `mvnw test` said nothing about the schema, the migrations or the encryption.
  The gate is a connection probe (`postgresIsUsable`); the flag still wins when set, in **both**
  directions (`true` forces it on so CI fails loudly on a broken provisioned DB, `false` forces it
  off for a fast offline run). Every connection failure means *skip*, not *fail*, so a fresh checkout
  without Postgres still builds.

Deeper: `mem:backend/security` for the auth chain, JWT, tenant context and the scoping/ownership
mechanism; `mem:backend/persistence` for entity, repository, audit and field-encryption patterns;
`mem:backend/lifecycle` before touching any case transition, the paid guard, SLA or refund logic;
`mem:backend/webhooks` before touching the inbound gateway, idempotency or Handoff A.
Java style and the deliberate absence of Lombok: `mem:conventions`, `mem:tech_stack`.
