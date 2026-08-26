# Unit 24 — Marketing: the Google Ads funnel (GM)

> **Status: built.** Backend, frontend, config and tests landed together.
>
> **This spec was written after the code, which is the wrong order and is recorded
> as such.** The rule is that a change of direction gets a versioned spec first
> (see the workflow rules' *Handling Missing Requirements*, and this repo's
> standing "pivots are specced before they are coded"). This unit resolves a
> **known-open question** — whether EvalOS builds sales/marketing dashboards,
> whose default was *no* — so it is exactly the kind of change that spec should
> have gated. Nothing below is retro-fitted justification: read it as the record
> of what was decided, and treat the ordering as the process debt it is.

## Why this exists

The GM reads the business end to end. Everything EvalOS shows them starts at
Handoff A — a won opportunity, already paid, becoming a case. The question *"how
much is coming?"* was answerable only by leaving the app and opening GHL, so the
one role whose job is the whole funnel had the top of it missing.

This unit puts GHL's **Google Ads pipeline** on a screen inside EvalOS: how many
deals stand in each stage, what they are worth, and which sources produced them.

## The line this does not cross

`project-overview.md` puts marketing out of scope and invariant 2 says EvalOS
never runs marketing, sales or invoicing. **Both still hold**, because reading is
not running:

- Nothing here creates a lead, moves a stage, prices a deal, or sends anything.
  The GHL credential is `opportunities.readonly` and the client has no write
  method — read-only twice over, so a mistake in either place is still not a write.
- Nothing is persisted. There is no `ghl_opportunity` table and there must not be:
  a stage a salesperson dragged five seconds ago is already wrong in a copy, and
  the contact snapshots EvalOS *does* hold are the ones a case needs, arriving by
  webhook at Handoff A.
- GHL stays the system of record for the funnel. This is a **window**, not a sync.

The distinction to hold onto: EvalOS gained a *marketing reading*, not a
*marketing function*. The day something here writes back to GHL, two systems own
one pipeline and invariant 2 is gone.

## Scoping — and why this one screen is not brand-scoped

Every other query in EvalOS filters by `brand_id`. This one **cannot**, and the
reason is not an oversight to fix later:

- It reads no EvalOS rows at all. It reads one GHL sub-account (location), and
  the brands share it.
- So no `brand_id` predicate exists that could narrow the figure. The endpoint
  therefore accepts **no `brandId` parameter** — accepting one would narrow
  nothing while telling the caller it had.
- Which is precisely why the endpoint and the nav entry are **GM only**. The GM is
  the one role allowed cross-brand reads (`code-standards.md`), and this figure is
  cross-brand by construction rather than by omission.

**The Brand Manager is deliberately excluded.** They are locked to a single brand
on every other screen; this is the one figure that could not honour that, so
showing it to them would be the cross-brand leak the scoping rule exists to
prevent, dressed up as a dashboard. `navigation.test.ts` pins the GM-only list so
adding them fails a test rather than shipping.

**If the brands are ever split across two GHL locations**, `location-id` becomes a
column on `brand`, this screen becomes brand-scoped like everything else, and the
whole argument above is rewritten with it. Do not add a second global location
property instead.

## Backend

| Piece | File | What it does |
| --- | --- | --- |
| Client | `integration/GhlPipelineClient` | Two read calls against GHL's public API: pipelines, and a cursor-paginated opportunity search. |
| Failure | `integration/GhlUnavailableException` | → **502** via `ApiExceptionHandler`, beside the Drive one. |
| Aggregation + cache | `service/MarketingPipelineService` | Stage counts, values, shares, source rollup; one cached payload. |
| Route | `web/MarketingController` | `GET /api/marketing/ads-pipeline`, `hasRole('GM')`. |

### Decisions worth the words

**Pipeline resolved by name, not by id.** `evalos.ghl.ads-pipeline-name` defaults
to `Google ADS Pipeline`. The id is a 20-character opaque string that means
nothing to whoever provisions an environment; the name is what they can read in
GHL. The trade is that a rename over there breaks this screen — which surfaces as
a stated 502 rather than an empty funnel that looks like a bad month. Failing
loudly is the right direction.

**Only three fields are bound off each opportunity** (`pipelineStageId`,
`monetaryValue`, `source`). GHL's search response also carries every contact's
name, email, phone and tags. A stage count needs none of that, and binding it
would put marketing PII inside an EvalOS response — kept out by the shape of the
record rather than by a projection somebody has to remember.

**`status` is deliberately not read.** GHL carries `open`/`won`/`lost` per
opportunity *and* has stages called Won, Cold and Lost. The live data disagrees
with itself — opportunities sitting in the **Won** stage still report
`status: "open"` — so a status axis beside the stage axis would state the same
fact twice and give it two places to differ. One axis: the stage.

**Cursor pagination, not page numbers.** `startAfter`/`startAfterId`, which is
what GHL's own `nextPageUrl` uses. A salesperson dragging a card mid-read is the
normal case here, and a cursor cannot skip or double-count the row that moved.
The loop stops at 50 pages (`ponytail:` comment on the constant) — a runaway
guard, not a business limit.

**The cache is the rate limiter, not an optimisation.** One payload, one TTL
(`GHL_CACHE_TTL`, default 5 minutes), shared by every caller. Without it, N open
dashboards are N multi-page GHL reads per refresh and GHL's own rate limit
becomes an EvalOS outage. Two consequences, both tested:

- A **failed** refresh is never served from the previous value. It propagates and
  the screen shows the error. A kept figure presented without its failure is the
  "looks live and is not" bug the cache would otherwise introduce.
- The payload carries `readAt`, and the header prints it, so the screen states its
  own age instead of implying it is this second's.

Two callers racing past a stale entry both call GHL and the second write wins.
Left unguarded on purpose: the alternative holds a lock across a network call,
which turns one slow upstream into every dashboard blocking on it.

**Inline from a request path, which `code-standards.md` normally forbids.** That
rule is about a *lifecycle side effect* — those go through a domain event so they
cannot be lost. A dashboard read has nothing to lose: on failure the screen says
so and nothing in EvalOS is half-done. The timeout and the cache are what keep it
inside invariant 6's "one bounded request".

## Frontend

`features/marketing/` — `marketingApi.ts` and `AdsPipelinePage.tsx` (**renamed
`MarketingPipelinePage.tsx` in Unit 26**, which points the same component at a second
funnel). Nav entry
`/marketing/google-ads` under a new **Marketing** group, above Pipeline: this is
the funnel *before* EvalOS takes custody, and the Pipeline group is everything
after.

Four panels, all through the existing `Card`/`KpiCard` shells so they inherit
`loading` / `error` / `empty` rather than inventing states: **Deals in pipeline**,
**Pipeline value**, **Funnel by stage** (the chevron strip), **Sources**.

### The chevron strip

Stages as interlocking arrows — value above, stage name and deal count inside,
share below. CSS `clip-path` polygons with a negative margin so they bite into
each other; the strip scrolls itself, because a chevron that wraps mid-funnel
stops reading as a funnel and the page must never scroll sideways.

**Share of the pipeline, not step-to-step conversion — and this is the one place
this screen departs from the reference design it was drawn from.** A conversion
figure between two chevrons only means something when the second stage is
downstream of the first. This pipeline's stages are not all a progression: Won,
Cold and Lost sit beside each other as outcomes. "70%" printed between Won and
Cold is arithmetic over unrelated buckets — a number that looks like a rate and
is not one, which is the failure this codebase keeps finding and removing. Share
of the pipeline is true of every stage whatever order GHL puts them in.

**An empty stage is a row.** A funnel that drops the stages nobody is in looks
shorter than it is, and "nothing is sitting in Warm" is half of what this screen
is for.

**No stage name is special-cased anywhere.** The stage list, its order and its
labels all come from GHL. This pipeline happens to end Won / Cold / Lost today;
hard-coding that would make a rename in GHL a silent hole.

**Share is `null`, never `0`, on an empty pipeline**, rendered as an em dash. The
codebase's standing rule for every rate: no deals at all is not the claim that 0%
of them are here, and a row of noughts reads as a collapse that did not happen.

**Colour: `color-mix` from `--accent-primary` into `--sidebar-bg`, 40% → 90%.** No
hex, no new token for a ramp one screen draws, and — the point — nowhere near
`--status-red/amber/green`. RAG is load-bearing here, and a funnel shaded
red-to-green would say five stages are in trouble. The ramp stops at 90% rather
than 100% because white on `--accent-primary` is 4.54:1: it passes AA by a hair,
and the 11px stage label is the text that would pay for it.

**Money prints as grouped whole units with no currency symbol.** GHL's
`monetaryValue` carries no currency, so stamping `$` on it is a claim this app
cannot support. `RevenueDashboard` already prints money this way, so the two
screens agree.

## Configuration

All under `evalos.ghl.*` in `application.yml`. `token` and `location-id` default
to **empty**, and that is a deliberate difference from `JWT_SECRET` /
`EVALOS_FIELD_KEY`: those must fail the boot, because signing with a guess or
storing plaintext is unrecoverable. This gates one read-only GM screen, so an
environment that forgets it serves the whole app and answers 502 on that one view.
`GhlPipelineClient` logs a warning at boot so it is not a surprise.

| Property | Env | Default |
| --- | --- | --- |
| `base-url` | `GHL_API_BASE_URL` | `https://services.leadconnectorhq.com` |
| `api-version` | `GHL_API_VERSION` | `2021-07-28` |
| `token` | `GHL_API_TOKEN` | *(empty → 502)* |
| `location-id` | `GHL_LOCATION_ID` | *(empty → 502)* |
| `ads-pipeline-name` | `GHL_ADS_PIPELINE_NAME` | `Google ADS Pipeline` |
| `timeout` | `GHL_TIMEOUT` | `10s` (per page) |
| `cache-ttl` | `GHL_CACHE_TTL` | `PT5M` |

The token must be a GHL **Private Integration Token scoped
`opportunities.readonly` and nothing wider.**

## Verification

**24 tests across four classes.** The first pass shipped only the service test; the
other three were added in a second pass that went looking for what the first one
was taking on trust.

- `MarketingPipelineServiceTest` (7) — the aggregation: position ordering with
  empty stages kept, `null` share on an empty pipeline, source rollup with
  blank/absent folded into `Unattributed`, an unpriced opportunity counted as a
  deal worth zero, the TTL asking GHL once and then again, **a failed refresh
  never served from cache**, and no opportunity search spent on a pipeline that
  was not found.
- `MarketingControllerTest` (5) — the route: the GM gets the funnel, **every other
  role including the Brand Manager gets 403**, unauthenticated gets 401, a
  `brandId` on the query string narrows nothing (asserted through the service
  call, so adding such a parameter later fails the test), and
  `GhlUnavailableException` maps to **502 / `GHL_UNAVAILABLE`** rather than 500.
- `GhlPipelineClientHttpTest` (8) — **the client against a real JDK `HttpServer`
  serving GHL's actual response shapes.** As close to the live criterion as is
  reachable without a credential: the pipeline-by-name lookup picking the right
  one out of several, stage `position` surviving, the `Authorization` and
  `Version` headers, the **camelCase** query-parameter names taken from GHL's own
  `nextPageUrl`, only the three needed fields bound out of GHL's full row (contact
  block and all), `monetaryValue`/`source` nulls tolerated, the cursor followed to
  a second page and stopped by a short one, a wrong pipeline name failing loudly
  without naming other teams' pipelines, and a 401 from GHL becoming a 502 with
  the token absent from the message.
  *Fixtures keep GHL's field-for-field shape with invented contact values — real
  ones were on hand and deliberately not committed. A fixture is source control,
  and marketing PII does not belong there.*
- `GhlPipelineClientTest` (4) — the boot properties. **This closes a gap that was
  otherwise invisible**: `GhlPipelineClient`'s `base-url`, `api-version` and
  `timeout` have no defaults, so a typo in any of those keys is a *boot failure* —
  and no `@WebMvcTest` slice instantiates the bean while the only full-context test
  is gated behind `-Devalos.db.test=true`. The same hole `mem:backend/core` already
  records for `GoogleDriveConfig`. An `ApplicationContextRunner` +
  `ConfigDataApplicationContextInitializer` binds the bean against the real
  `application.yml`, and the rest asserts that a missing token **does not** fail
  the boot (the deliberate difference from Drive), that the 502 names both
  variables, and that the token's value never reaches a message.
- `navigation.test.ts` — the nav entry is GM-only, with the reasoning inline.
- Green: `./mvnw -Devalos.db.test=true test` → **430 tests, 0 failures, 0
  skipped**. Zero skipped matters here: it means the gated `@SpringBootTest`
  context ran, so **the whole application boots with this bean in it** — the
  single largest unknown after the first pass.
- `npm test` (113) and `npm run build` clean. The chevron geometry was checked in
  a browser against Tailwind's `border-box`.

### The one item still open — **CLOSED 2026-08-26**

**The live GHL exercise is done.** `GhlPipelineClientLiveTest` makes real calls and
passes: `Google ADS Pipeline` resolves to id `g6lo50r9Wn0qZvmp2bMP` with six stages,
`countIn` reads GHL's own `meta.total`, and an unknown pipeline name raises
`GhlUnavailableException` live. It confirmed what no stub could — GHL's two endpoints
genuinely disagree on parameter casing (`locationId` camelCase on `/pipelines`,
`location_id` and `pipeline_stage_id` snake_case on `/search`), so the mixed casing in
the client is the live answer rather than a typo.

The test is skipped unless `GHL_LIVE_TEST=true` and reads the token from
`backend/config/application-local.yml`, so no credential reaches a command line and CI
never touches the network.

**Also closed 2026-08-26: the screen itself.** Opened in a browser as the GM against live GHL —
see `26-marketing-email-funnel.md` for the figures, since the email funnel is the one with data in
it today. This unit's own screen renders the same way from the same component; the ads pipeline
holds no opportunities inside any window the filter offers, so it correctly shows the empty state
naming the days it searched.

For reference, to open either screen yourself:

```
GHL_API_TOKEN=<private integration token, opportunities.readonly>
GHL_LOCATION_ID=kBumF0uUOmMBB5bneYjx
```

Until then the route answers **502 `GHL_UNAVAILABLE`** with a message naming those
two variables. The aggregation was designed against real responses from this
account's `Google ADS Pipeline` (93 opportunities; New Lead 7, Warm 26, Won 14),
so the numbers to expect on the first live load are known — if the funnel totals
93 and those three stages match, the path is proven end to end.

Depends on: 07 (shell + nav table), 17/22 (the card system).
