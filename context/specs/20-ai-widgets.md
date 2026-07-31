# Unit 20 — AI widgets (later)

**Phase:** 3 — Close the loop — final unit
**Depends on:** 12 (the rule-based shortlist this layers over), 17 (the metrics the
anomaly check reads)
**Unlocks:** nothing. This is the last unit in the build plan.
**Gating open questions:** an **Anthropic API key and a decision to send case data
to an external API at all** (see the data-boundary section — this is a product and
compliance decision, not a technical one). Also inherits Unit 17's StatCommand
question if the anomaly figures are ever meant to leave EvalOS.

> **Written ahead of its code.** Specs 11–20 were written in one pass, so this one
> describes a layer over two units that do not exist yet. Re-read it against Units
> 12 and 17 at the start of the unit and revise it — in particular, the anomaly
> metrics below must match what `DashboardService` actually computes.

## Goal

Two widgets the design has always described as "later", both **assist-only**:

1. **KPI anomaly detection** — flag a metric that moved more than 15% against its
   4-week rolling average.
2. **AI-enhanced expert suggestion** — a second opinion layered on top of Unit
   12's rule-based top-3.

**Verifiable result:** a GM or Brand Manager sees anomaly flags on the dashboard
with the figures behind each one, and a PM at assignment sees the rule-based
shortlist with an optional AI note explaining what the rules cannot see — with the
AI layer disabled by default, degrading silently when it is off, and never
reordering or auto-selecting anything.

## Half of this unit needs no AI at all

**"KPI anomaly detection (>15% vs 4-week rolling average)" is arithmetic.** It is
one query, a mean, and a comparison — there is no model in it, and adding one would
make a deterministic threshold non-reproducible and unexplainable. It is in this
unit because the build plan groups it with the AI work, not because it needs a
model.

So this unit is really two units' worth of unrelated work, and
`ai-workflow-rules.md` says to split when a step combines unrelated boundaries.
**Build the anomaly detector first and ship it.** It depends only on Unit 17,
costs nothing, needs no external service, and has no open question against it —
whereas the suggestion layer is gated on a decision nobody has taken. If the
gate never clears, the anomaly half still ships.

### The anomaly detector

`service/AnomalyService` — for each metric Unit 17 computes, compare the current
week against the mean of the previous four, flag a swing over ±15%.

Metrics worth watching, all already computed in Unit 17:

| Metric | Why a swing matters |
| --- | --- |
| Cases created | Demand moved, or Handoff A broke |
| Open liability | Money collected is outrunning delivery |
| Median cycle time per stage | A stage is silting up |
| Expert acceptance rate | The roster is going cold |
| Review requests sent | The #2 health metric slipped |

Rules:

- **Reuses `DashboardService`'s functions; it does not recompute.** Same
  reasoning as everywhere else in this build — two definitions of one figure is how
  a dashboard and an alert come to disagree.
- **Brand-scoped, and the GM's view is per-brand as well as cross-brand.** A
  cross-brand total can look flat while one brand collapses and another spikes,
  which is the exact anomaly worth seeing.
- **Needs five weeks of history to say anything.** Below that the flag is
  suppressed and the tile says why — an anomaly computed against two weeks of a
  new brand's data is noise, and "no baseline yet" is an honest answer.
- **A flag is a notification, not just a tile** — `kpi.anomaly` → in-app to the GM
  and that brand's Brand Manager (Unit 06). Fired by Unit 19's daily sweep, which
  is where timed work lives (invariant 6).
- Direction is stated, not just magnitude. "Cycle time up 22%" and "down 22%" are
  not the same news, and a tile that only says "anomaly" makes the reader go
  looking.

## The data-boundary decision — read before writing any of the AI half

The suggestion layer sends data about a live case and real experts to an external
API. Nothing in EvalOS has ever done that: Drive holds documents EvalOS links to,
Dropbox Sign holds letters it does not read, and GHL is the system EvalOS is a
back office for. **This is the first outbound flow of internal case content to a
third party**, and it is a decision for the business, not an implementation detail.

If it proceeds, these hold without exception:

- **`payment_detail` never leaves.** Invariant 4. It is not in the prompt, not in
  the context, not in a log line. There is no read path for it anyway (Unit 11).
- **No client identity.** No client name, no attorney or company, no email, no
  case code, no `invoice_ref`, no `deal_value`, no `campaign_attribution`.
- **No expert identity.** Send the same fields the Unit 13 redaction whitelist
  permits — tier, field tags, letter types, load, acceptance rate — under the
  per-case reference labels Unit 13 already generates. The model ranks
  "Expert A / B / C", and EvalOS maps them back locally.
- **No free-text field, ever.** Not `notes`, not `pm_strategy_notes`, not
  `recruitment_source`, not a decline reason. Unit 13's rule applies for the same
  reason: free text is where a name ends up.
- **A whitelist builder, tested by the same method** — seed distinctive tokens
  into every excluded field and assert none of them appear in the serialized
  request body. Third whitelist in the codebase after Units 13/14, same test shape.

**What is left after that is anonymous, structured, tag-level data** — which is
also the honest argument that the AI layer's value here is limited: with identity,
notes and case content all excluded, the model is scoring the same numbers Unit 12
already scores. **Say so at build time.** If the layer cannot beat the rule-based
shortlist on the PM's own judgement over a few weeks, delete it; a widget nobody
trusts is worse than no widget.

## The AI layer, if it proceeds

- **Dependency:** `com.anthropic:anthropic-java`, added here and nowhere earlier.
- **Model:** `claude-opus-5` — the current Opus. $5 / $25 per million input /
  output tokens. Adaptive thinking (`ThinkingConfigAdaptive`) — which is also the
  default, so thinking runs whether or not it is set, and a fixed `budgetTokens`
  is rejected outright. (`ThinkingConfigDisabled` is legal at effort `high` or
  below, and is not wanted here.) Because thinking counts against `maxTokens`,
  size that for the reasoning plus the note, not the note alone.
  `output_config.effort` at `medium` — this is a short structured judgement, not
  long-horizon work.
- **Structured output**, not prose parsing: the Java SDK derives a JSON schema
  from a record via `.outputConfig(Suggestion.class)`, so the response is typed
  rather than scraped. A shortlist assembled by regex over free text is a defect
  waiting for a model update.
- **Key config:** `ANTHROPIC_API_KEY`, env-backed, **no non-local default** — the
  rule `EVALOS_FIELD_KEY` and the Drive and Dropbox Sign credentials all set.
- **Off by default.** `evalos.ai.suggestions.enabled=false`. A feature that costs
  money per call and reaches an external service does not default on.
- **Failure is invisible to the PM.** Timeout, rate limit, missing key, disabled
  flag — all produce the Unit 12 shortlist with no AI note and no error. The
  shortlist is the product; the AI note is a garnish. **Asserted by a test that
  runs the endpoint with the client hard-failing.**
- **Not in the request path if it is slow.** One bounded call with a timeout,
  same standard as Unit 13's Drive upload; if it turns out slow in practice it
  becomes a precomputed note, not a spinner on the assignment dialog.
- **Every call is audited** — actor, case, model id, and whether a suggestion was
  returned. Not the prompt (it would duplicate data that is already on the case)
  and not the tokens spent, which belongs in the provider's own billing.

### Assist-only, stated as testable properties

"Assist mode" is the word Units 12 and 20 both hang on, so it is pinned rather than
described:

- The AI layer **cannot reorder** the shortlist. Unit 12's score decides the order;
  the AI note attaches to a card.
- The AI layer **cannot add** an expert Unit 12 filtered out — an `AT_CAPACITY` or
  another brand's expert cannot appear because a model mentioned it.
- The AI layer **cannot assign**. `assign-cm` neither knows nor cares that it ran.
- The note is **labelled as AI-generated** on screen. A suggestion the PM cannot
  tell apart from a computed figure is one they will over-trust.

## Backend

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/dashboard/anomalies | GM · Brand Manager | flagged metrics with current value, 4-week mean, % change and direction. Brand-scoped; `brandId` narrows only |
| GET | /api/cases/{id}/expert-shortlist?fieldTag=&ai=true | GM · Brand Manager · PM | **the Unit 12 route**, with an optional AI note per card. `ai=true` is ignored when the flag is off |

**No new route for the AI suggestion.** It rides the Unit 12 shortlist response,
because it is an annotation on that answer and a second endpoint would let the two
disagree about which experts are even in scope.

Anomalies are gated to the two oversight roles: a swing in cross-brand cycle time
is a management signal, and every operational role already has the boards and the
notifications that show them their own work.

## Frontend deliverables

1. **Anomaly strip** on the dashboard (Unit 17): flagged metrics only, each with
   the current value, the 4-week mean, and the direction. Nothing flagged shows
   "no anomalies this week", and too little history shows "baseline builds from
   week 5" — the `slaMix` rule that zero and no-data are different facts.
2. **AI note on the shortlist card** (Unit 12's `ShortlistPanel`): collapsed by
   default, **visibly labelled AI-generated**, and absent — not empty, not
   erroring — when the layer is off or failed.
3. No new screen and no new nav entry. Both widgets attach to surfaces that
   already exist, which is what "layered on top" means.

## Acceptance criteria

- [ ] A metric that moved more than 15% against its 4-week mean is flagged, one
      just under is not, and both directions are reported distinctly. Asserted
      against a fixture with known weekly values.
- [ ] A brand with fewer than five weeks of history is **not** flagged and says
      why.
- [ ] The GM sees per-brand anomalies as well as cross-brand, and a Brand Manager
      sees only their own brand's.
- [ ] Anomaly figures **equal** the Unit 17 dashboard's figures for the same
      metric, brand and period — same functions, not a second implementation.
- [ ] `kpi.anomaly` notifies the GM and that brand's Brand Manager, once per
      flag per week, not once per sweep.
- [ ] With the AI flag **off**: the shortlist is byte-identical to Unit 12's, and
      no outbound request is made.
- [ ] With the AI flag on and the client **hard-failing**: the shortlist still
      returns 200 with the rule-based ranking and no note.
- [ ] The request body contains **none** of `payment_detail`, the expert's real
      name, institution, email, phone, `notes`, `recruitment_source`, the client's
      name, `deal_value`, `invoice_ref`, `campaign_attribution`, or
      `pm_strategy_notes`. Asserted by seeding distinctive tokens into every one
      and grepping the serialized body.
- [ ] The AI layer cannot reorder the shortlist, cannot introduce an expert Unit
      12 excluded, and `assign-cm` behaves identically whether it ran or not.
      **These three are the definition of assist-only** and each is asserted.
- [ ] The app **fails to start** with the AI flag on and no API key configured —
      rather than starting and failing per request.
- [ ] `npm run build` green; `./mvnw verify` green, with the AI client stubbed.

## Invariants honored

Brand isolation on the anomaly reads, GM the only cross-brand reader (1); EvalOS
still runs no marketing or sales analytics (2); role gates on both routes, checked
in the service (3); **`payment_detail` in no prompt, no context and no log** (4);
open liability read through Unit 17's definition, never restated (5); the anomaly
sweep runs in `job`, and the AI call is one bounded request that moves to `job` if
it stops being one (6); no transition, no write to a case — both widgets are reads
(13, trivially); no email, no file (14).

**One invariant this unit stretches, stated rather than glossed:** invariant 7
makes EvalOS the system of record, and nothing here changes that — but sending case
data to a third party is a new boundary crossing that `architecture.md` does not
currently describe. **Update `architecture.md`** with the AI integration, what is
sent, and what is excluded, before the code lands.

## Files touched

**Created.** Backend: `service/AnomalyService.java`,
`web/AnomalyController.java`, `job/AnomalySweep.java`. *(AI half, if it
proceeds:)* `integration/ClaudeClient.java`,
`service/ExpertSuggestionService.java` (+ the whitelist prompt builder and its
`Suggestion` record), `config/AiConfig.java`. Frontend:
`frontend/src/features/dashboards/AnomalyStrip.tsx` + its rules test.

**Modified.** `service/DashboardService.java` — the weekly-series accessor the
anomaly check reads (a read, not a second computation).
`event/CaseEvents.java` — `kpi.anomaly`.
`notification/NotificationListeners.java` — its recipients.
`frontend/src/features/experts/ShortlistPanel.tsx` — the AI note.
`web/ExpertShortlistController.java` — the optional `ai` parameter.
`pom.xml`, `application.yml`. **`context/architecture.md`** — the AI integration
and its data boundary. `context/progress-tracker.md`.

**Not touched.** `service/ExpertMatchService.java` — the rule-based score is not
modified, replaced or weighted by this unit; that is what "layered on top" means.
`service/CaseTransitions.java`, `service/ScopePredicate.java`,
`common/PaymentDetailConverter.java`, every applied migration. **This unit adds no
migration** — nothing is persisted.
