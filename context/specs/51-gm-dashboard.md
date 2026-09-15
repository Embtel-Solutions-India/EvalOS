# Unit 51 — The GM dashboard, and the mapping behind every widget

**Status: BUILT (2026-09-15)**, except the rows marked ✗ and ⚠ in §3, which are not built
because they cannot be computed from anything EvalOS or GHL holds today. Those are the answer
to *"tell me if anything is not possible to calculate"*, and each one names what it would take.

**Source of the design:** `IE_GM_Dashboard.pdf` — a monthly overview laid out in five blocks
(THE NUMBER, SALES, MARKETING, EXPERT MANAGEMENT, EVALUATION DEPARTMENT) with a colour key of
*Sales · Marketing · Experts · Evaluation*, an *Alert — GM acts on this* mark and an
*Up vs last month* mark. The figures on it are the business's own illustrative placeholders.

Placed at 51 rather than inside Phase 6's continuous list because it lands a route and a screen,
and `00d` §11 Phase 6 names *"GM/BM operational row · KPI drill-through everywhere"* without a
unit number. Unit 50 is ENM-as-a-function; this does not depend on it, but §3's expert
recruitment rows do.

---

## 1. What this screen is, and what it is not

**It is the GM's, alone.** `RoleDashboard` used to send the GM and the Brand Manager to the same
`RevenueDashboard` on the argument that the difference between them was a filter. That held while
every figure was brand-scoped. Half of this screen reads the **GHL location**, which
`architecture.md` licenses as invariant 1's one stated exception **only while the reader is the
cross-brand role** — a Brand Manager shown a pipeline figure cannot tell, and neither can the
server, whether it is theirs. So:

| Role | Lands on | Why |
|---|---|---|
| `GM` | `GmDashboard` | The one role that may read an unattributable location |
| `BRAND_MANAGER` | `RevenueDashboard`, unchanged | Brand-scoped and true |

**It computes almost nothing new.** Five reads back this screen and **four already existed**:

| Read | New? | What it supplies |
|---|---|---|
| `GET /api/metrics/gm` | **yes** | The windowed GHL figures + the by-service and evaluation figures |
| `GET /api/metrics/revenue` | no | Open liability, refunded |
| `GET /api/metrics/pm` | no | At risk now, on-time delivery rate |
| `GET /api/metrics/expert-network` | no | Roster, onboarding, acceptance, coverage gaps |
| `GET /api/opportunities/board` | no | Open deals, pipeline value, *no movement 7d+* |

A second copy of "at risk" or "onboarded this month" computed here would be a second number to
keep in step with the first, and those always drift. Each read carries its **own** card state, so
GHL being down cannot blank the evaluation department.

**`/api/metrics/gm` answers 200 when GHL is down.** The production half is EvalOS's own rows and
is still true; `pipelineUnavailable` carries the reason, and the screen paints it as an `error`
state on exactly the tiles that reason invalidates. Refusing the whole payload would take four
working tiles down with the four that broke.

---

## 2. The one thing about GHL that shapes this whole unit

`GET /opportunities/search`'s `date` / `endDate` filter on the opportunity's **`createdAt`**.
Verified against the live API on 2026-09-15 (narrow the window to one month, get back only rows
created in it; rows *updated* two months later are still returned). GHL offers **no filter on
`lastStatusChangeAt`**.

So *"won this month"* is not a query. A deal opened in July and won in September is a September
win and a July creation, and every window that asks for September's creations misses it. The
shape that is correct:

> read the **wins** (`status=won`) over a created-window wide enough to contain the sales cycle,
> and bucket them in EvalOS by `lastStatusChangeAt`.

That is `evalos.sales.won-lookback-days` (default **180**). It is a correctness bound, not a
tuning knob: set it shorter than the real sales cycle and the won figure is **silently low**.
`status=won` is what keeps the read small — six months of *wins* is a fraction of six months of
*leads*.

`GhlPipelineClient.Opportunity` grew from three fields to ten for this: `id`, `pipelineId`,
`pipelineStageId`, `status`, `monetaryValue`, `source`, `assignedTo`, `createdAt`,
`lastStatusChangeAt`, `lastStageChangeAt`. The narrowing is still the point — the search response
also carries the contact's name, email, phone, tags, attributions and custom fields on every row,
and binding only these keeps marketing PII out of an EvalOS response by construction.

**Per-salesperson figures key on `team_member.ghl_pipeline_id`, never on GHL's `assignedTo`.**
EvalOS has no mapping from a GHL user to a `team_member` (`SalesOpportunityController` says so,
and it is still true). It *does* own the pipeline link Unit 36 built the whole pipeline-scoped
role on. `assignedTo` is bound so a future mapping has something to map, and is read by nothing.

**Scope = every pipeline owned by an active `SALES` or `MARKETING` member of
`evalos.ghl.sales-brand`** — the same set as the GM's opportunity board, so the overview and the
board cannot disagree about what "the pipeline" means.

---

## 3. The mapping — every widget on the PDF

Legend: **✓** built · **⚠** built, but a definition or a setting is the business's to give ·
**✗** not possible today, with what it would take.

### 3.1 THE NUMBER — this month

| PDF widget | Status | Source | How it is computed |
|---|---|---|---|
| **Money vs goal** — `$38.4k / $55k`, `70% to goal` | ⚠ | GHL opportunities + config | Σ `monetaryValue` of opportunities with `status=won` whose `lastStatusChangeAt` falls in the window, across every desk pipeline. Goal is `evalos.sales.monthly-goal` (`SALES_MONTHLY_GOAL`). **⚠ Nobody has set the goal** — it defaults to `0`, and the tile then shows the amount with *"No monthly goal set"* instead of a percentage of nothing. **The percentage is suppressed on any window that is not a calendar month**: a monthly target against a week is arithmetic dressed as a business figure. |
| **Business by source** — Referral / Website / Cold email / Unknown | ✓ | GHL `opportunity.source` | The same won set, grouped by `source`, blank → *Unattributed*. Sorted by value. Sums exactly to the tile above. |
| **Business by service** — Letters / Evaluations / RFE-NOID / PERM | ✓ | `case.service_type` + `case.deal_value` | Σ open value + delivered-in-window value per `ServiceType`. **Deliberately a different denominator from "by source" and the tile says so**: a source lives on a GHL opportunity, a service lives on an EvalOS case, and a case exists only after a deal is won. They will never sum to the same total. |

### 3.2 SALES

| PDF widget | Status | Source | How it is computed |
|---|---|---|---|
| **New leads** — `142`, `$61k` | ✓ | GHL | Count and Σ `monetaryValue` of opportunities **created** in the window on a `SALES` desk pipeline. `date`/`endDate` filter on exactly this, so it is one query per desk. |
| **Won this month** — `$22k`, `up 12%` | ✓ | GHL | As §3.1, restricted to `SALES` desks. The delta is against the **previous window of the same length**, so it works on any range the shell's filter can produce, custom included. Null rather than `100%` when the previous window had no wins — a first win is not a percentage improvement. |
| **Open, no movement** — `6`, `worth $19k` | ✓ | GHL board cache | Open deals whose GHL `updatedAt` is older than **7 days**, from `/api/opportunities/board` — the same `staleDeals` predicate the Sales/Marketing dashboard uses, moved to `dashboards/dealAge.ts` rather than copied. |
| **Hot leads** — `24`, `$28k` | ✗ | — | *"Hot" is a stage name, and EvalOS deliberately holds no opinion about pipeline placement.* A `hot-stage-name` property existed for one afternoon in Unit 43 and was removed **on the business's instruction** — it went stale the first time somebody reworked the workflow in GHL. **What it takes:** the business names the stage(s), in writing, and accepts that renaming them in GHL breaks the tile. The stage-by-stage counts and values are on `/opportunities/board` today and include the Hot column under GHL's own name for it. |
| **Invoice sent** — `12`, `$18k` | ✗ | — | Same problem, same answer. There is a second possible source — GHL's invoice API, which EvalOS already reads for the client portal — but *"an invoice exists"* and *"the deal is at the Invoice-sent stage"* are different facts and the PDF's block is a pipeline block. **What it takes:** name the stage, or decide the tile means *invoices raised in the period* and it becomes a GHL invoice read. |
| **Salesperson table** — New / Hot / Invoiced / Won | ⚠ | `team_member.ghl_pipeline_id` + GHL | Built as **Desk / New / Won / Value**. The two missing columns are the two rows above — same blocker. |

### 3.3 MARKETING

| PDF widget | Status | Source | How it is computed |
|---|---|---|---|
| **New leads** — `142`, `worth $61k` | ✓ | GHL | Opportunities created in the window on a `MARKETING` desk pipeline. |
| **Leads with no source** — `61%`, *needs fixing* | ✓ | GHL `opportunity.source` | Share of the window's opportunities with a blank `source`, across every desk. Null, never `0`, when the window held nothing to attribute. Painted **amber, not red**: unattributed leads are a measurement failure, and red would put it beside *"3 cases are late"* as if they cost the same. |
| **Email marketing** — `2,840 sent`, `71 replies`, `9 booked` | ✗ | — | GHL **does** expose this: `GET /emails/locations/{locationId}/campaigns/stats/{source}/{sourceId}`, plus `GET .../campaigns/emails` to list campaigns. **What it takes, and it is not code first:** the deployment's Private Integration Token carries `opportunities.readonly` **and nothing wider** — deliberately, so that a mistake in the code is still not a write. This needs `emails/stats.readonly` + `emails/campaigns.readonly` added to the PIT, which is an operational change and a widening of what one leaked token can read. Then a client, a service and roughly a day. **`replies` is also not certain to be in the stats payload** — sends, deliveries, opens and clicks are; a reply is a conversation-side event and may need `conversations.readonly` and a second read. |
| **Social media** — `18 leads`, `4.2k reach` | ✗ | — | **`reach` does not exist in GHL at all.** The Social Planner API publishes posts and reads posts back; there is no analytics, impressions or reach endpoint (checked 2026-09-15). Reach lives in Meta/LinkedIn/X's own APIs, which is a new integration, new credentials and a new invariant conversation — not a widget. **The `18 leads` half is computable today** as the source breakdown already built, filtered to the social sources; it is not shown as its own tile because half a widget labelled with the other half's title is worse than neither. |
| **BDE table** — Leads / Replies / Meetings per BDE | ✗ | — | Three separate gaps. **(a) There is no BDE role** — `Role` has eight values and none of them is business development. **(b) Replies** is the same conversation-scope gap as above. **(c) Meetings** is the closest to possible: `meeting` rows carry `booked_by`, so meetings-per-staff-member is a query — but it counts meetings **EvalOS booked**, and Sales books into GHL through the desk, so it undercounts anything booked in GHL directly. **What it takes:** a decision on whether BDE is a role or a `Segment`, then the conversations scope. |

### 3.4 EXPERT MANAGEMENT

| PDF widget | Status | Source | How it is computed |
|---|---|---|---|
| **Total experts** — `412 / 63 active / inactive` | ✓ | `expert.availability` | `AVAILABLE + AT_CAPACITY` against `INACTIVE + ON_LEAVE`, from `/metrics/expert-network`. |
| **Onboarded** — `4`, `up 2` | ⚠ | `expert.date_onboarded` | Count in the calendar month, against `evalos.roster.monthly-onboarding-target`. **The `up 2` delta is not built** — the existing endpoint returns this month against a target, not against last month, and adding a second comparison to a shipped payload for one arrow is not worth the drift. |
| **New interested** — `14 this month` | ✗ | — | There is no expert *application*. `Expert` has `agreement_status` (`SENT`/`SIGNED`/`EXPIRED`) and `date_onboarded`, which describe somebody already on the roster. A recruitment funnel — application → screened → meeting → agreement → onboarded — is **Unit 50** (`00d` §7, ENM as a function), which exists precisely because these three widgets have nowhere to come from. |
| **Meeting done** — `6 of 14 interested` | ✗ | — | Same: Unit 50. `meeting` is opportunity-scoped and client-facing; there is no expert meeting. |
| *(added)* **Offer acceptance**, **Coverage gaps** | ✓ | existing endpoint | Already computed for the ENM and free to show here — they are the two supply figures a GM acts on. |

### 3.5 EVALUATION DEPARTMENT

| PDF widget | Status | Source | How it is computed |
|---|---|---|---|
| **Open cases by service** + their value bars | ✓ | `case` | Not delivered and not `CLOSED`, grouped by `ServiceType`, with Σ `deal_value`. |
| **Delivered this month** — `34`, `worth $28k` | ✓ | `case.delivery_date`, `case.deal_value` | Delivery date inside the window. |
| **Late cases** — `3`, `past promised date` | ✓ | `case.deadline` | Open **and** `deadline` already passed. **Deliberately narrower than `PmMetrics.atRiskNow`**, which also counts cases merely inside the red band and sits on the same screen as its own tile. One word answering two definitions is how a dashboard stops being believed. |
| *(kept)* **Open liability**, **Refunded** | ✓ | `/metrics/revenue` | The two money figures the PDF has no tile for and the GM still owns. Collected and recognised are **not** repeated — *Business won* and *Delivered, worth* answer the same two questions on this screen's own window. |

### 3.6 The three marks in the PDF's legend

| Mark | Status | How |
|---|---|---|
| Department colour key (Sales / Marketing / Experts / Evaluation) | ⚠ | Rendered as **section headings**, not colour. `ui-context.md` reserves the RAG palette for status and forbids decorative colour; four departmental hues beside red/amber/green would be exactly the collision the rule exists to prevent. |
| *Alert — GM acts on this* | ✓ | The `KpiCard` `tone` — red on late cases and untouched deals, amber on unattributed leads and open liability. Status is never carried by colour alone: the figure and its note say it too. |
| *Up vs last month* | ✓ | The `Delta` chip, on *Won*. The caller declares which direction is good, so no tile is read against an assumption. |

---

## 4. Configuration this unit adds

```yaml
evalos:
  sales:
    monthly-goal: ${SALES_MONTHLY_GOAL:0}          # 0 = no goal, tile shows the amount alone
    won-lookback-days: ${SALES_WON_LOOKBACK_DAYS:180}
```

Both follow the shape `evalos.workload.cases-per-cm` and
`evalos.roster.monthly-onboarding-target` already set: the figure was always computable, only the
target had nowhere to live. **One number for the selling brand**, not a column — per-brand goals
need an admin screen to maintain and a second selling brand to justify, and neither exists.

**`SALES_MONTHLY_GOAL` is the one setting the business owes this unit.** Until it is set, the
headline tile is honest and incomplete.

---

## 5. Cost, and the one shortcut taken

One page load is **two GHL searches per desk** — roughly eight paced requests for a team of four,
about a second, for a screen one person opens. There is **no cache**, marked `ponytail:` in
`GmOverviewService`. The upgrade path is the table that already exists: `ghl_funnel_cache` is
keyed `(funnel, window_key)` and would take a `"GM"` row with no migration. Do that when this
screen starts polling, when the team grows, or when a desk's month stops fitting in a page or
two — not before, because the optimistic-lock race handling that comes with it is real code
protecting one reader.

That table has been **orphaned since 2026-09-16**, when the two GHL funnel screens were removed
and took its only reader with them. It could not be dropped in the same change: `V905` (a local
seed) clears it, so a `DROP` must order after it, and `MigrationTreeTest` fails the build on any
`db/migration` script numbered 900 or above — while editing an applied `V905` is a checksum
mismatch that refuses the boot. Adopting it as this service's cache settles that as a side
effect, which is a point in the upgrade's favour.

Unit 44's mirror removes the problem rather than caching it: once opportunities are EvalOS rows,
every figure in §3.1–3.3 is one SQL statement and `won-lookback-days` disappears.

---

## 6. Acceptance

- [x] `GET /api/metrics/gm` is `GM`-only; a `BRAND_MANAGER` token is refused.
- [x] A deal created before the window and won inside it counts as won **in** the window.
- [x] `pctToGoal` and `goal` are null on any window that is not a calendar month, and when the
      goal is unset.
- [x] A `GhlUnavailableException` yields a 200 whose production half is complete and whose
      `pipelineUnavailable` names the failure; the screen renders those tiles as errors.
- [x] `late` counts open cases past their deadline, and not a case delivered late.
- [x] Every countable tile that has a list behind it links to it, and only where the GM's nav can
      actually go.
- [x] `frontend` typechecks, lints and its 127 tests pass.
