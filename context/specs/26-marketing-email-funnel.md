# Unit 26 - Marketing: the email funnel (GM)

> **Status: built.** Backend, frontend, config and tests landed together.
>
> **Spec written alongside the code, not after it** - the correction Unit 24
> recorded as process debt. It is short because the unit is small: Unit 24 built
> the machinery and this points a second screen at a second GHL pipeline.

## Why this exists

Unit 24 gave the GM the top of the funnel - but only the **paid-search** half of
it. The same GHL location runs a second acquisition channel, **Shivangi's Email
Marketing**, and it was invisible from inside EvalOS for no reason other than
that Unit 24 hard-coded one pipeline name.

`progress-tracker.md` recorded, correctly, that "a second marketing screen is a
new question, not this one answered again". **This unit is that question, asked
and answered**: yes for a second *reading* of a pipeline in the location EvalOS
already reads, on the same terms; still no for anything that would make EvalOS
run marketing. The line Unit 24 drew has not moved - see below.

## What it builds

- `GET /api/marketing/email-pipeline` - GM-only, `range` in
  `today|week|month|year`, identical payload to `/ads-pipeline`.
- **A counts-based read of both funnels** (`GhlPipelineClient.countIn`), replacing
  Unit 24's count-by-pagination, which this pipeline's volume broke outright. See
  *What reading the live pipeline revealed* below - it is the substantive part of
  this unit.
- `/marketing/email` in the nav ("Email marketing"), under the existing
  **Marketing** group, GM-only.
- `evalos.ghl.email-pipeline-name`, defaulting to `Shivangi's Email Marketing` -
  the name the live location actually uses, verified against GHL.

## The shape of the change: one funnel became two, nothing became general

The two pipelines have the **same stage shape** (New Lead - Warm - Hot - Won -
Cold - Lost), sit in the **same location**, and answer the **same question**. So:

- `MarketingPipelineService` gained a `Funnel` enum (`ADS`, `EMAIL`) that keys
  into configured pipeline names. It is **not** a pipeline name on the query
  string: the location holds seven pipelines and five belong to other teams, so a
  name parameter would let any GM read all of them and would make the screen's
  contents a caller's argument rather than a deployment decision.
- The cache key became `(Funnel, DateRange)`. **This is the load-bearing part.**
  Both payloads have the same shape, so an unkeyed slot would have served the ads
  funnel under the email heading for a whole TTL with nothing on screen to
  contradict it - the same failure keying by period already prevented.
- One React component serves both screens, with `funnel` in the `useMetrics`
  deps so a route change refetches. The heading is the **pipeline's own name from
  GHL**; the prop is only a placeholder until the payload lands.
- One route per funnel rather than `/pipeline/{name}`: the reachable set stays a
  deployment decision, and the ads URL is unchanged.

## The line this does not cross - unchanged from Unit 24

Reading is not running. Nothing creates a lead, moves a stage, prices a deal or
sends a campaign; the credential is `opportunities.readonly` and the client has
no write method. **Nothing is persisted** - there is still no `ghl_opportunity`
table and there must not be. This is a second *window*, not a marketing module.

**Still not brand-scoped, still GM-only, for the same reason:** it reads the same
single `evalos.ghl.location-id`, which has no mapping to a brand. Unit 25 maps
locations to brands; Unit 25a then re-scopes **both** marketing screens together.

## What reading the live pipeline revealed

Two facts, both found by querying the live location before shipping, and both
changing what the screen had to do:

1. **The email pipeline holds ~11,432 opportunities over a year, and Unit 24's
   read could not survive that.** Counting deals by paging every row is 115
   sequential GHL requests; the Year view **timed out at the frontend's 15s axios
   limit and rendered nothing at all**. The first attempt at this was an honest
   version of the wrong approach: cap the read at 5,000 rows and flag the payload
   `truncated`. That still meant the GM could not see the Year figures.

   **The fix is to stop counting by reading.** GHL reports the match count in
   `meta.total` on any search, so a `limit=1` request with `pipelineStageId`
   applied returns an exact stage count in **one** request. `GhlPipelineClient`
   gained `countIn(pipelineId, stageId, from, to)`, and the funnel now costs one
   request per stage whatever the period holds - **exact, nothing capped,
   truncated or estimated**. The pipeline total is the sum of its stages, so the
   parts always add up to the whole on screen. `MAX_PAGES` stays as a runaway
   guard; reaching it is now a bug rather than a busy pipeline.

   Note the parameter, and the process failure behind it: it is
   `pipeline_stage_id`, **snake_case**, matching `location_id` and `pipeline_id`
   beside it. It shipped as camelCase first and GHL answered
   `422 "property pipelineStageId should not exist"` on the first real call. The
   spelling had been "verified" through a tool that **normalises parameter names
   before sending** - so the evidence was for a request the app never makes. The
   lesson is the one this client's comments already record about GHL's naming: only
   a call built the way the app builds it is evidence. Pinned in
   `GhlPipelineClientHttpTest` with `.doesNotContain("pipelineStageId=")`.

   Verified live, all six stages: New Lead 11,364 · Warm 0 · Hot 20 · Won 48 ·
   Cold 0 · Lost 0 - **summing to 11,432**, exactly the unfiltered pipeline total,
   which is the check that the filter applies at all and that the parts add up.

   The trade, stated: `meta.total` is GHL's own count and can differ by a row or
   two from a paginated read if a card moves mid-read. A funnel is read for shape
   and magnitude; a count one deal stale is a fine count, an unreadable screen is
   not. (The pagination loop still does **not** use `meta.total` as its bound - a
   stale *bound* silently drops the tail of a page.)

2. **A sum and a group-by still need the rows**, and GHL aggregates neither.
   Pipeline value, per-stage value and the sources table are read inline only
   when the period holds `<= INLINE_ROW_BUDGET` (1,000 deals = 10 pages).

   **Above it they are computed on a background thread, not refused** (revised
   2026-08-26; the original refusal is below for the reasoning it still carries).
   The payload comes back `detail: TOTALLING` with counts exact and money null, a
   single daemon thread reads the rows, and the screen polls the same URL every 5s
   until the `(funnel, range)` cache entry turns `READY`. No job id, no second
   endpoint - the cache is the handover, so it survives a browser refresh.

   Why it cannot stay in the request: 11,443 opportunities is 115 **cursor** pages,
   and a cursor comes out of the page before it, so they cannot be parallelised.
   GHL allows **100 requests per 10 seconds per location**, which puts a ~13s floor
   under the read - past the frontend's 15s axios timeout. `GhlPipelineClient` now
   paces every request 110ms apart to stay under that limit. Past
   `DETAIL_ROW_CEILING` (100,000) the answer is still a refusal, as `UNAVAILABLE`,
   and a failed background read lands there too so a poller stops.

   **Never a partial total**, in any of the three states: a sum over whichever rows
   arrived looks exactly like a real number. The Google Ads funnel (~93 deals a
   year) stays inline in every period.

3. **Its newest opportunity was created 2026-05-06**, so Today / Week / Month all
   render the existing empty state naming the days that found nothing. That is a
   correct answer, not a fault - but it means **Year is the only window with data
   today**: exact stage counts (New Lead ~11,364, Hot 20, the rest single digits)
   with the value and sources cards standing down.

## Two matching rules, both case-insensitive, both funnels

Added after the first live read, because the raw data made both necessary.

**A stage named for an outcome IS that outcome — GHL's `status` field is not
used.** 144 opportunities sit in the stage named "Won" while only 3 carry
`status: "won"`: the rest were dragged into the column without anyone pressing
GHL's separate win button. Reporting 3 wins over a column of 144 would be
arithmetically defensible and useless. So `Outcome.ofStageNamed` matches a stage
name against GHL's own status words **ignoring case and surrounding space** -
`Won`, `won`, `WON` and `" Won "` are one thing, because they are one thing to
whoever typed them - and each `StageFunnel` carries the result. The screen sums
it into "N won · N lost" under the deal count.

`Cold` is the case that proves this is a match and not a vibe: it reads like an
ending, it is not one of GHL's four statuses, and it stays `OPEN`. Nothing is
special-cased per pipeline - the rule is on the name, so both funnels get it.

**Source rows are grouped case-insensitively.** These strings are typed by hand
into campaigns and forms over months, so one source arrives cased several ways;
two rows for one source halves a figure for a reason nothing on screen explains.
Keyed on the lower-cased name (`Locale.ROOT` - a Turkish-locale JVM lower-cases
`I` to `ı` and would split exactly the rows this joins), and the row keeps the
**first spelling seen** as its label, because a canonical casing invented here
would show a string that exists nowhere in GHL.

## Tests

- `MarketingPipelineServiceTest` (15) - adds
  `cachesEachFunnelSeparatelySoOnePipelineNeverAnswersForAnother` (each funnel
  resolves its own configured name and neither evicts the other),
  `countsAHugePeriodWithoutReadingASingleRow` (11,432 deals counted, `stages`
  fully populated, and `opportunitiesIn` **never called**),
  `reportsNoMoneyOrSourcesRatherThanAPartialTotalOnAHugePeriod`, and
  `stillTotalsTheMoneyAndSourcesWhenThePeriodIsSmallEnoughToRead`,
  `groupsSourcesThatDifferOnlyInCase` and
  `readsAStagesOutcomeFromItsNameIgnoringCase` (including `Cold` staying `OPEN`).
- `GhlPipelineClientHttpTest` (10) - adds
  `countsAStageFromGhlsMatchTotalWithoutPagingTheRows` (one request, `limit=1`,
  `pipelineStageId` camelCase, the window still applied) and
  `treatsAMissingTotalAsZeroRatherThanFailing`.
- `MarketingControllerTest` (9) - adds
  `theEmailRouteReadsTheEmailPipelineAndNotTheAdsOne`, asserted on the `Funnel`
  the service receives rather than the body (the bodies are identical in shape,
  which is precisely the bug a body assertion would miss), and
  `theEmailRouteIsGmOnlyForTheSameReasonTheAdsOneIs`.
- `navigation.test.ts` - the GM-only assertion now loops over **both** marketing
  paths, because a second screen onto the same location added without the same
  door is how this leaks next.
- Green: `Marketing*Test` + `Ghl*Test` -> 39 passing; the full backend suite ->
  451 passing, 0 failures (27 skipped are the `-Devalos.db.test` ones). Frontend
  `npm test` -> 113 passing, `tsc --noEmit` and `npm run build` clean.

### The one item still open — **CLOSED 2026-08-26**

The same one Unit 24 carried, and closed with it: `GhlPipelineClientLiveTest` now makes
the call from EvalOS's own code and passes. This pipeline resolved to id
`LHoIRjpypwhswqO8Ayn0` with six stages, and the year window
(`2025-08-27..2026-08-26`) counted **New Lead 11,349 · Hot 20 · Won 48 · total 11,417**
— every figure from GHL's own `meta.total`, one request per stage, nothing paged.

That is the load-bearing claim of this whole unit verified against production data: the
counts do not come from rows, so the five-figure funnel renders without the 115
sequential requests that blew the frontend's 15s timeout.

**Still owed, and a different item:** the screen itself has not been opened in a browser
against live GHL, so the poll-until-`READY` handover has not been watched end to end with
real figures.

Depends on: 24 (the client, service, card system and nav entry this extends).
