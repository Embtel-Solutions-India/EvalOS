# frontend/ — Core

Vite SPA under `frontend/src`. Structure is `lib/`, `components/`, `features/`, `pages/`, `styles/`;
`features/` is where the real screens live — `auth`, `shell`, `board`, `case`, `checklist`,
`dashboards`, `experts`, `queues`, `client-portal`, `marketing`.

`components/ui/` **exists as of Unit 22 slice 1 and is protected — do not hand-edit.** It holds
vendored shadcn-*style* wrappers over the unified `radix-ui` package (`dialog.tsx` = Dialog +
Sheet, `menu.tsx` = DropdownMenu + Popover + Tooltip, `tabs.tsx`, `card.tsx` = the dashboard card
system). **The foundation is frozen**: later Unit 22 slices extend the decision tables and add
screens, and may not redesign a shared component — without that rule a role-by-role cut produces
one shell per role. A shared component genuinely needing to change is a stop-and-ask.

**The card state union is the contract** — `loading · ok · warning · error · empty · unavailable`.

**Numbers go through `lib/money.ts`**: `formatMoney` (`$86,950`) or `formatCount` (`11,400`).
Both group thousands, only the first carries a `$`, and the `$` is **opt-in per figure** —
`KpiCard` takes a `money` boolean. Set by an owner-approved edit to protected `card.tsx`, which
had been printing `$` in front of *every* KPI value: all 28 tiles carried one, so "Deals in
pipeline" read `$93` on a count. Only the four Revenue tiles and "Pipeline value" are money.
`CaseCard`'s private USD formatter was folded into the same file. Asserted in `lib/money.test.ts`.
`unavailable` names the blocking unit rather than rendering a zero for data that does not exist
yet; `empty` carries operational copy ("All incoming cases are assigned"), and **`empty` and zero
are different states** — a figure summing to zero renders `0`. A card is clickable **only** if
given a `to`, which is what stops every tile looking interactive.

`features/queues/` holds the three queue screens — `/inbox`, `/drafts` (PM) and `/delivery`
(Coordinator). **All three read `/api/cases/board`** rather than adding endpoints: a second read
would mean a second scope predicate that could drift from the board's. Selection lives in the pure
`queueRules.ts` and is the only part with tests.

**`/inbox` is the front door for incoming work and is the PM's alone (Unit 23).** A paid case
arrives in the pool and surfaces under *Unassigned*; the PM takes it, then staffs the coordinator
and the case manager. `AssignPopover` is the staffing cell and renders one of **three** states —
`IN_POOL` → *Take this case* (`assign-pm` with `useMe().id`), taken-but-no-CM → a link to the case
(`assign-cm` needs a CM *and* an expert in one call), staffed → the reassignment popover with
each candidate's load. The GM left this screen and the board's pool lane (`SEES_POOL` is
`['BRAND_MANAGER']`) — **nav only, no backend gate narrowed**.

`features/dashboards/` is one component per role, chosen by the `DASHBOARDS` table in
`RoleDashboard` — the `NAV_ITEMS` / `STAGE_ACCESS` shape, so a role's landing screen is a data row.
**Every role reads live figures as of Unit 22; the placeholder tiles are gone.** GM and Brand
Manager share `RevenueDashboard`, differing by payload rather than layout — but they are *not* the
same role with a filter: `SEES_STRATEGY_NOTES` excludes the Brand Manager, so nothing on a shared
screen may assume oversight means full visibility.

`useMetrics` is the shared loader. **Its reset-on-refetch is the load-bearing part**: leaving the
previous payload in place while a new request is in flight shows last month's figures under this
month's header, and the tile looks live when it is not. `emptyWhen` exists so no dashboard can
claim "nothing to do" while still loading — an empty queue is a claim about the operation, so it
may only be made about data that arrived.

## Wiring — two surfaces, split inside `App.tsx`

- `src/main.tsx` — root, and nothing else: `StrictMode` > `BrowserRouter` > `App`. The router provider
  lives here so `App` renders without a second one. **`AuthProvider` deliberately does not** — see
  below.
- `src/App.tsx` — the split. `/portal/*` returns `features/client-portal/PortalRoot` **before any
  staff-session code runs**; everything else renders `<AuthProvider><StaffApp/></AuthProvider>`, where
  `StaffApp` is the old `App` body (the three auth states + the in-shell route table).
  - **Why the provider moved down out of `main.tsx`:** mounting it on a client's page would read the
    staff token out of `sessionStorage` and call `/api/me` for somebody who has no account. Wrapping
    only `StaffApp` is what lets the portal route live in this file — which is where the unit spec put
    it — while still mounting no auth provider, no `AppShell`, no nav and no brand switcher for a
    client. A client is not a staff user with fewer links.
  - No router inside the portal either: a token admits one case, so there is one screen and nowhere to
    navigate.
- `src/App.tsx` — the route table, and nothing else. Three states: loading (session restore),
  anonymous (only `/login`), authenticated (everything inside one pathless
  `<Route element={<AppShell/>}>`). Every in-shell route is wrapped in `RoleRoute`, which checks the
  **same `features/shell/navigation.ts` table the nav filters** — a deep link outside the role's
  allow-list renders the 403 view rather than redirecting, so the user sees which URL was refused.
- **`SCREENS` in `App.tsx` is the live-screen map.** A `NAV_ITEMS` path with no entry there renders
  `PlaceholderPage`. Live today: `/board` and `/my-cases` (both `BoardView` — a Case Manager's "my
  cases" is the board narrowed by the server), `/checklists`, `/experts`. Plus `/dashboard`
  (`RoleDashboard`) and the parameterized `/cases/:id`.
- `features/shell/` — `AppShell` (top bar + left nav + `<Outlet/>`), `BrandSwitcher` (GM only),
  `DateFilter`, `NotificationBell`, and `filtersContext` (`useFilters()` → `activeBrandId`, the date
  window). `useMe()` from `lib/authContext` is the principal; it throws below the shell rather than
  returning null.
  - **`DateFilter` is backwards-looking only** (Unit 28): four calendar-to-date buttons, a native
    `<select>` for Last month / Last year / Custom, and two native `<input type="date">` for a
    date-to-date range — no picker dependency. The custom draft is held locally and committed only
    when **both** edges are set and ordered, because a `from`-only window is a 400 and repainting
    the app mid-edit is worse than waiting.

## Sticky layering — everything that sticks does so BELOW the top bar

`TopBar` is `sticky top-0 z-20` and owns the viewport top. **Anything else that sticks takes
`top: var(--header-height)`**, never `top-0`: two sticky elements at the same offset is not a
layering problem to solve with z-index — whichever loses is simply hidden. `StageActions` (the case
detail header) shipped as `sticky top-0 z-10` and was painted over by the bar; fixed 2026-08-27.

**`--header-height` (72px) is a contract, not a current value.** Sticky offsets are measured from
it, so **nothing may make the top bar taller** — that is why `DateFilter` does not `flex-wrap`.

**And sideways is not a free escape either.** The bar's search is `min-w-0 flex-1`, but it bottoms
out at **54px and stops shrinking**; past that the row spills and `Sign out` goes off the edge —
25px at a 1100px bar, which is the 1366px laptop `styles` says the staff run. So a control that
needs real width goes in a **popover** (`components/ui/menu.tsx`), not inline in the bar: that is
what the custom date range does, behind a chip that doubles as its readout.

**And check the padding you think you are cancelling.** `AppShell`'s main is
`padding: 0 var(--shell-gutter) var(--shell-gutter)` — **no top padding**. A `-mt-*` on a
full-bleed band cancels nothing and just pulls it into the bar. The horizontal bleed is
`--shell-gutter` (1.25rem), not `-mx-6` (1.5rem).

## Verifying the frontend — `npx tsc --noEmit` CHECKS NOTHING

The root `tsconfig.json` is `{"files": [], "references": [...]}`, so plain `tsc` has no inputs and
exits 0 over an empty set. Only **`tsc -b`** — which `npm run build` runs — checks the referenced
projects. Unit 28 found **three real errors** behind that no-op, one of them a production call site
(`AssignPopover.tsx` passing a bare string where the union was required).

**Verify with `npm run build`**, or `tsc --noEmit -p tsconfig.app.json` for a type-only pass. A
green typecheck that inspected no files is worse than none, because it gets reported as evidence.
And note what even a real typecheck misses: rendering an object in JSX (`{dateRange}`) type-checked
fine and throws at runtime.

## navigation.ts is one table, three consumers

Nav rendering, the router's allow-list (`mayReach`), and the placeholder's escape link
(`boardPathFor`) all read `NAV_ITEMS`. Keeping them separate is how a screen ends up deep-linkable
but unguarded, or listed and then 403 — four separate defects have been that one bug.
`navigation.test.ts` asserts the equivalence in both directions and **pins each screen's role list to
its backend gate** (`/experts` → `ExpertController.ROSTER_READ`). Grep it before adding any
cross-screen link.

**Two entries are deliberately narrower than their backend gate, and the test says so** (Unit 23):
`/inbox` is `['PROJECT_MANAGER']` and `/checklists` is `['BRAND_MANAGER', 'PROJECT_COORDINATOR']`.
Both gates still carry `GM_OR` server-side, so a GM reaches either action from the board or the
case — what they no longer have is somebody else's worklist in their sidebar. **This is the one
place the "role list equals backend gate" rule is knowingly broken; do not "fix" it back.**

**`/drafts` is `['PROJECT_MANAGER']` for a different and stronger reason** (Unit 23a): it *does*
match its gate, because `GM_OR` was removed from `draft/pm-approve` and `draft/pm-return` on the
server. See `mem:backend/lifecycle` for why. The client half is `boardRules`' **`gm` field**, which
replaced the old `gmOnly` boolean and has three states — absent = GM-also (the default),
`'only'` = the two refund rulings, `'never'` = the two draft rulings. `actionsFor` and
`boardRules.test.ts` both go through the exported **`admits(action, role)`** rather than
re-deriving it, because a second copy is how "the GM sees everything" survives a decision against
it.

**One path per screen.** `/cases` beside `/board`, `/delivery` with no screen behind it, and
`/experts` beside `/expert-database` were all deleted for the same reason, and the test asserts their
absence so they are not re-added.

**`/marketing/google-ads`, `/marketing/email` and `/sales/pipeline` are GM-only, and that is a
scoping fact rather than a taste call** (Units 24, 26, 27). `NavGroup` gained **`Marketing`** and
then **`Sales`**, both sitting above `Pipeline` because these are the funnels *before* EvalOS takes
custody and Pipeline is everything after. The role list is `['GM']` because all three endpoints read
the one configured GHL location, which has no mapping to a brand: no `brand_id` can narrow the
figure, so only the cross-brand role may see it, and no endpoint accepts a `brandId` at all.
**The Brand Manager is the tempting addition and is the leak** — single-brand on every other screen,
and these are the figures that cannot honour it. `navigation.test.ts` loops the assertion over **all
three** paths with the reasoning inline, so a fourth screen added without the same door fails a test
rather than shipping.

**`Sales` is its own group, not a third `Marketing` entry, and the reason is not cosmetic**: the
first two are campaign funnels (leads a channel produced), while `/sales/pipeline` is a
salesperson's working pipeline carrying stages they do not have. One heading over all three would
present them as comparable channel results. It sits **between** Marketing and Pipeline because
that is the order of the business — campaign, then close, then custody at Handoff A. **Grouping is
by consecutive runs of `group`, so an entry's position in `NAV_ITEMS` *is* the heading order**; a
`Sales` item filed anywhere else in the list would render a second `Sales` heading.
**The nav split does not follow through to the API** — the route stays
`/api/marketing/sales-pipeline` and the types stay in `marketingApi.ts`. A `salesApi.ts` would
duplicate every type to rename one string.

## lib/money.ts — the one place a figure becomes text

`formatMoney` (`$86,950`) and `formatCount` (`11,400`). **Two functions, and `formatMoney` is
opt-in at every call site**: a currency symbol is a claim about what a number *is*, so defaulting it
on would put `$93` on a deal count. That is a failure this *prevents*, not one the repo shipped —
the card previously rendered a bare value with no currency symbol at all. (`card.tsx` and
`money.ts` both used to credit the design to an unconditional `$` "this tile used to print",
which is not in the history.)

Cents are **rounded** away, not truncated (`maximumFractionDigits: 0` rounds half-up) — truncating
would understate a summed column systematically, always in the same direction.

**All three formatters are folded in as of 2026-08-26.** `CaseCard`'s copy went first; `ExpertProfile`
was still rendering Standard fee as `1,250.00` (no symbol, two decimals, default locale) against the
board's `$1,250`. It now delegates, keeping only the `null` → `—` distinction the shared formatter
cannot know. USD is assumed — if a brand ever bills in anything else that needs a column, not a
second guess here.

## features/marketing — the screens that read GHL (Units 24, 26, 27)

`marketingApi.ts` + `MarketingPipelinePage.tsx`. **One component serves all three funnels**, taking
`funnel: 'ads' | 'email' | 'sales'` (which endpoint) and `title` (a placeholder heading only — GHL's
own pipeline name arrives in the payload and replaces it, which is what lets three identical layouts
stay tellable apart). `funnel` sits in the `useMetrics` deps beside `dateRange`, or a route change
would leave another funnel's numbers under this one's heading. The file was `AdsPipelinePage.tsx`
until Unit 26.

**Unit 27 added no file here** — a union member and one route-table line in `App.tsx`. If a fourth
funnel needs a component of its own, something has actually changed; check that first.

**The two-directions collision is RESOLVED as of Unit 28, and this is the note to read first.**
`dateRange` used to be shared by two filters pointing opposite ways: the dashboards read it
backwards ("what happened since") while `BoardView` read it **forwards** through `dueBeforeFor`.
Setting it to `year` for this screen once moved the board's deadline window from one month out to
twelve and left the production board effectively unfiltered for every role on first load.

It is split now, **in the type**: `features/board/deadlineWindow.ts` owns `DeadlineWindow`
(`week|month|year`, forward, rendered as the board's own `Due within` select) and the shell owns
`DateRange` (backward). Passing one where the other belongs does not compile. The split was forced
rather than tidied — the filter gained `last-month`, `last-year` and a custom interval, and
**`last-month` as a "due before" cutoff returns every open case** while an interval is two edges
where a cutoff needs one. **Do not re-point `BoardView` at the shell filter.**

`DateRange` is a **discriminated union** — `{kind: NamedRange} | {kind: 'custom', from, to}` — so a
custom period cannot exist without its dates. Go through the three helpers rather than branching on
`kind`: `rangeParams` (wire params — sends `from`/`to` **only** for custom, because the server 400s
on dates with a named range rather than ignoring them), `rangeLabel` (screen label, and it renders a
custom period's actual dates rather than the word "custom"), `sameRange` (which control is lit).
**`rangeLabel` is not optional for display**: `dateRange` is an object, and rendering it directly
throws "Objects are not valid as a React child" — a bug `tsc` did not catch and `PmDashboard` had.

The four "this" ranges are **calendar-to-date**, not rolling windows. That moved every dashboard
figure: `month` was the last 30 days and is now since the 1st.

**The default is still `month`** — no longer for the board's sake, which now has its own filter, but
because a dashboard opening on a twelve-month window is one the reader has to narrow before it
answers anything. **Do not add a per-screen default either** — that is a second source of truth for a
control the user can already see, and this screen's empty state names the window it searched and
says to widen it. One click beats unfiltering the board for everyone.

**Guard `data?.sources?.` as well as `data?.stages`.** JSX children and the `state` prop are both
evaluated before `Card` decides whether to render them, so `data?.sources.length` on a payload
missing the field throws during render and takes the whole page white — the same failure `chartState`
was written for.

Four panels, all through the existing `Card`/`KpiCard` shells so they inherit
`loading`/`error`/`empty` rather than inventing states. **The header prints "one GHL location" and
the `readAt` clock time** — two things a reader would otherwise assume wrongly, since every other
screen here is brand-scoped and live. It said **"all brands"** until the first real credential
arrived; that was wrong — each brand has its own sub-account, so this is *some* brand's funnel and
EvalOS cannot say whose.

**Two of the four cards can go quiet while the other two stay exact, and that is by design.**
Deal counts come from GHL's own match count and are exact for any period; money and sources are a
sum and a group-by over every row, which on this pipeline's year is ~11.4k of them. The payload's
`detail` says which of three you have:

- `READY` — everything present.
- `TOTALLING` — the server is reading the rows on a background thread. The Pipeline value tile and
  the Sources table render `empty` with "Totalling N deals…", and **the page polls `reload()` every
  5s until `detail` changes**. It stops on any non-TOTALLING value, `UNAVAILABLE` included — polling
  a failure forever is how a spinner becomes permanent.
- `UNAVAILABLE` — not coming; the copy tells the reader to narrow the period.

**Never a partial total** in any state, which reads exactly like a real one. `value` and
`totalValue` are `null` unless `READY`, so treat them as nullable everywhere: null is "not counted",
not "worth nothing".

**`useMetrics` clears `data` only when its `deps` change, never on `reload()`.** A refetch of the
*same* window is not a stale-data risk, and the clear-on-every-fetch it replaced made this screen's
poll blank all four cards every 5 seconds. An error while data is already on screen is likewise not
rendered as an error card — the figures are still the last true answer and `readAt` dates them.

**The stage strip is Recharts horizontal bars** (`layout="vertical"` — Recharts' name for it, and
the axis components swap roles: `XAxis` is the numeric one, `YAxis` takes the `dataKey`). It was
`clip-path` chevrons, then briefly a line; both implied a *progression* the data has not, since
Won/Cold/Lost are parallel outcomes. Bars carry only length, which is all these figures support.
Three rules it set, now written into `ui-context.md`:
an ordered ramp is **`color-mix` between `--accent-primary` and `--sidebar-bg` (40→90%)**, not a
sixth `--chart-*` token; the ramp stops at 90% because white on `--accent-primary` is 4.54:1 and
the 11px label would pay for it; and **a funnel is never shaded red→green**, because RAG is
load-bearing here.

**"N won · N lost" under the deal count comes from the STAGE NAME, not GHL's `status` field.**
The server sends an `outcome` per stage (`WON`/`LOST`/`ABANDONED`/`OPEN`), matched on the name
ignoring case; the page just sums it, so the rule for which names mean what stays in one place.
Do not reintroduce a status axis beside it — 144 deals sit in the *Won* stage against 3 with
`status: "won"`, and two figures for one fact is two places to disagree.

**It shows share of pipeline, not step-to-step conversion**, for the same reason the chevrons
went: Won / Cold / Lost are parallel outcomes, not a progression, so a percentage between two of
them is arithmetic over unrelated buckets. Share is
`null` (em dash), never `0`, on an empty pipeline — the standing rate rule. **Money prints as
grouped whole units with no `$`**: GHL's `monetaryValue` carries no currency, and `RevenueDashboard`
already prints money the same way.

## Notes & timeline is one panel (Unit 23)

`features/case/Timeline.tsx` renders the append-only trail **and** owns the note composer at its
foot. Not two tabs: a note is usually *about* the transition beside it, and a note is stored as an
audit row (`NOTE_ADDED` via `postNote` → `POST /cases/{id}/notes`), which is what lets them
interleave at all.

- The composer is rendered for **every** role with **no client-side permission check** — the
  server's gate is the case scope, so anyone who could load the page may write. A role list here
  would be a second copy of that scope.
- `IS_NOTE` (`NOTE_ADDED`, `FLAGGED`) picks the note rendering: accent left rule, text as the body,
  stage line suppressed. Everything else keeps the stage line and quotes its reason.
- The textarea clears **only after** the server accepts, and the error renders beside the box
  rather than in the page's sticky header — the person retyping is looking at the box.

## HTTP layer

- `src/lib/api.ts` exports a single shared `api` axios instance — always import it; never call
  `axios` directly or create per-feature instances. `unwrap()` turns the envelope into data or a
  thrown `Error`, and the response interceptor lifts the server's `error.message` onto the Error so a
  refused action can state the real reason.
- **One deliberate exception: `features/client-portal/portalApi.ts` has its own instance.** The shared
  one attaches the staff bearer from `lib/session`, and importing it would pull the module that reads
  and writes the staff token into a page whose whole point is holding no staff session. It sends one
  credential in an `X-Portal-Token` header, keeps the token in a module variable read out of the URL
  **fragment**, and persists nothing — deliberately unlike the staff token in `sessionStorage`, because
  a link forwarded to a shared machine is a different risk. Only a `type` is imported from `lib/api`,
  which the build erases. Do not create a third instance without a reason of that kind.
- `baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api'`. Leave the env var **unset in dev** so
  requests stay relative and flow through the Vite proxy; `baseURL` already contains `/api`, so call
  paths omit it (`api.get('/health')`).
- A 401 clears the token here (the router then shows login); a 403 is left alone — it means "signed
  in, not allowed", which is a screen.
- One multipart call exists (`features/experts/expertApi.ts`): the mapping travels as a JSON *part*,
  and `Content-Type` is passed as `undefined` so the browser can set the multipart boundary instead of
  the instance's `application/json` default.

## Per-feature shape

Each feature folder is `<Screen>.tsx` + `<feature>Api.ts` + an optional `<feature>Rules.ts` holding
the pure logic and the types (`boardRules`, `checklistRules`, `expertRules`, `shortlistRules`,
`redactionRules`). The rules module is what
carries a vitest file; components are not render-tested (no jsdom, no Testing Library — see
`mem:tech_stack`). Async UI state is a discriminated union, and every fetch effect uses an
`AbortController` because `StrictMode` double-invokes effects in dev.

Where a rules module mirrors a backend vocabulary (`QUICK_ACTIONS` ↔ the transitions, `FIELD_TAGS` /
`LETTER_TYPES` ↔ `domain/FieldTag` + `V18`'s CHECKs) the duplication is deliberate — the UI has to
offer the closed list rather than let it be typed — and **the two move together or the API starts
rejecting what the screen offered**.

`boardRules` has **no `mark-paid` action** since Case Creation v2.0 (spec `05b`): the case arrives paid
from the won GHL opportunity, and the endpoint behind that button is deleted. Do not re-add a "Record
payment" quick action — GHL is the only source of that fact, so a screen offering to set it would be
offering to disagree with GHL.

## Production Process v2.0 — three things about surfaces

- **`/delivery` is coming back**, with a real screen this time: cases in `FINAL_DELIVERY`, oldest
  first, one-click Deliver. `navigation.test.ts` currently **asserts that entry is absent** (it was
  deleted in Unit 10 as a nav item pointing at nothing), so that assertion **flips** with the work — a
  failure there is expected, not a regression. The business asked for the queue twice.
- **The business's 8 Kanban columns are a derived view, not new stages.** `CaseCard.draftChip()`
  already computes Draft in progress / PM review / Client review from `pm_approval_status` and
  `client_approval_status`; QC vs Expert Signing needs Unit 15's returned signature. The `Stage` union
  stays five values on the frontend. **Do not add a column by adding a stage.**
- **The expert portal's sign step is download-then-upload** (Unit 15), not a provider hand-off: the
  expert downloads the letter, signs it in their own tool, and uploads the signed PDF. The copy must say
  a scanned wet signature is expected and accepted, or people hunt for an e-signature button that does
  not exist. The **attestation tick sits next to the file input** and gates the upload button.
- **The client portal gains a document-upload view** (Unit 21): the client's own checklist as rows,
  one upload control per required item, showing required / uploaded / flagged-with-reason. Portal
  chrome rules still apply — minimal, no nav. State the accepted types and size cap **on the control**;
  a rejection must say which rule it broke rather than just failing.

Capacity indicators (the PM's Case Manager workload widget) use the RAG bands already fixed in
`context/ui-context.md` — green <70%, amber 70–90%, red >90%. Do not invent thresholds. And no tile is
colour-only: the RAG treatment always carries a label, as `SlaRail` does.

`redactionRules`/`RedactedProfilePanel` (Unit 13, in `features/case/`, mounted under `ExpertCard` on
the case detail) refuses it for the same reason and a sharper stake: **no redaction happens in the
browser.** The profile HTML arrives fully rendered from `service/RedactedProfileService`, whose
whitelist is the only thing that decides what may appear — a second opinion about "is this anonymous"
is a second answer, and the one that loses is the one that leaked. Three things to keep if you touch
it: the preview is an **iframe with an empty `sandbox`** (empty is the *strongest* value — it
withholds every capability — and a test asserts it precisely because it looks like an oversight
somebody would "fix" by adding `allow-scripts`), `srcDoc` rather than a blob URL so nothing is written
anywhere; `MAY_PUBLISH_TO_DRIVE` must equal `ExpertProfileController.toDrive`'s `@PreAuthorize`, and
the Case Manager's absence from it is deliberate — they read both profiles and publish neither; and
the Drive link is **not pre-checked** client-side, because restating a server rule is the copy that
goes stale (the Unit 10 lesson) — the 409 names the unusable link.

`client-portal/` (Unit 14) is the whole client surface: `PortalRoot` (token out of the fragment, three
states, honest failure copy), `ClientDraftView` (one screen — draft link, redacted profile, approve /
ask-for-changes, both confirming inline first), `portalApi`, `portalRules` (+ its test). Two things
its test pins because they are copy rather than logic and still load-bearing: the failure message
**never mentions signing in** on any status (a client has no account, so offering one sends them
hunting for a password that does not exist), and the post-approval message says what happens *next*
rather than just "approved". `mayAct` reads the server's own `awaitingAnswer` instead of re-deriving it
from the status, and additionally refuses when there is no draft link — approving a document you were
never shown is not a decision. Staff-side, `case/PortalLinkPanel` mints the link and shows it **once**;
`MAY_MINT_PORTAL_LINK` in `redactionRules` must equal `PortalLinkController.MAY_MINT` and, unlike
`MAY_PUBLISH_TO_DRIVE`, it **includes the Case Manager** — they wrote the draft and field "my link
doesn't work".

`shortlistRules`/`ShortlistPanel` (Unit 12) is the one place that duplication is **refused**: there is
deliberately no client-side scoring. The ranking and its factor breakdown come from the server, so
"why did this expert come first" has one answer — do not add a local scorer. The panel is embedded in
`board/QuickActionDialog`, not a route of its own. Two guards worth keeping if you touch it:
`factorShare` clamps to `[0,1]` and returns 0 on a zero weight, because `NaN%` as a CSS width is
*silently dropped* rather than visibly wrong; and `breakdownAddsUp` makes the panel say so if the
rows ever contradict the total above them.

## Styling — design tokens only

- `src/styles/tokens.css` (imported by `src/index.css`) is the single source of truth, mirroring
  `context/ui-context.md`: surface/text/border/accent colors as `:root` custom properties, plus a
  Tailwind v4 `@theme` block for `--font-sans/num/mono` and the `--radius-md/lg/xl` scale.
- **Shell geometry is tokens too, not numbers in components**: `--sidebar-width` (15rem, 13rem
  ≤1200px), `--shell-gutter` (1.25rem), `--header-height` (4.5rem), `--board-column-max`. A height
  guessed twice is a height that drifts.
- **No hardcoded hex and no Tailwind palette colors** (`slate-*`, `violet-*`) in components —
  reference `var(--token)`. `text-white` on an accent-filled button is the one accepted exception.
  Light workspace only; the old `dark:` class pairing was removed.
- `--status-red/amber/green` are **reserved for RAG status** (deadlines, SLA, capacity, overdue) and
  must never be used decoratively — `--accent-primary` is the brand/interactive color. Expert
  availability counts as capacity, which is why the roster's badges use them; "on leave" is
  deliberately muted rather than red, since it is not a problem.
- Radius by context: `rounded-md` badges/inline **and controls**, `rounded-lg` cards/panels/Kanban,
  `rounded-xl` overlays only. Numeric/currency/date/ID columns use `font-num` + `tabular-nums`.
- **The visual language changed on 2026-08-25 and the Protend one is gone.** `UI_MIGRATION_GUIDE.md`
  carries a SUPERSEDED banner; its method and scope rule still hold, its hexes and geometry do not.
  What reversed:
  - **The nav rail is dark navy, flush to the viewport edge, full height** — not a white floating
    rounded card inset by the gutter. `--sidebar-*` is its own token group and the rail is the only
    dark surface in the app. Content offsets by `--sidebar-width` alone; `--shell-gutter` now means
    the content area's padding and nothing else.
    Contrast is **measured, not assumed**: text 13.5:1, muted 5.4:1, active white 11.1:1. Changing
    `--sidebar-bg` re-opens all three.
  - **`--radius-xl` went 30px → 12px.** At 30px every 36px control was a pill; a row of pills across
    a dense header is noise. A control reaching for `xl` is now a bug in the control.
  - Accent violet `#3c21f7` → blue `#2563eb`; canvas `#f9fafe` → `#f4f5f7`.
  - Elevation is **border-first**: a hairline plus a short shadow, not the old 50px ambient bloom,
    which pooled between adjacent panels once screens got dense.
  - **KPI figures are large and semantically coloured** via `KpiCard`'s `tone` prop, with a delta
    chip carrying an arrow, a sign *and* an `sr-only` direction. Colouring the number is status use,
    not decoration, so it is a legitimate call on the RAG tokens — a count with no health reading
    passes no tone and stays in `--text-primary`.
  - **Deliberately not copied from the reference: sparklines on KPI tiles.** There is no trend
    *series* behind these figures, only a single delta. Add them when a metric endpoint actually
    returns a series; drawing one from invented data is the failure this repo keeps catching.
- **Icons come from `lucide-react`** as of Unit 22 slice 1. Existing inline SVGs (`LeftNav.NAV_ICONS`,
  the bell, the search) are fine where they stand and are not a migration backlog; new work imports.
  `NAV_ICONS` stays keyed by the same `path` the router uses, so a missing entry degrades to a
  fallback instead of adding a second list.
- **Density: the reference screen is 1366 × 768, not 1920.** 36px for every control (pill, select,
  search, icon button, nav item), 72px header, 240px sidebar, 288px board column, `text-2xl` screen
  `h1`. The adopted template ships 44–48px controls, a 400px sidebar and a 136px header; that scale
  was rejected on purpose — it spends 28% of a 1366 viewport's width and 18% of its height on chrome.
  Table in `context/ui-context.md` → "Density"; the reasoning is the deviation table in
  `UI_MIGRATION_GUIDE.md`. Do not restore the roomier sizes screen by screen.
- **The board scrolls on both axes, and each axis has exactly one owner.** The column strip owns
  horizontal (`overflow-x-auto` on the flex row in `BoardView`); each column's card list owns vertical
  (`overflow-y-auto` capped at `--board-column-max` in `StageColumn`). Nothing else scrolls. That
  split is what pins the SLA rail and the column headers while cases move under them — the rail is the
  board's one instrument and it used to leave the screen with the page. `PoolLane` is capped at two
  rows of pills and "Off the pipeline" starts **closed**; both used to push the columns below the
  fold. `--board-column-max` subtracts a measured 22rem of chrome from `100svh` and is marked
  `ponytail:` — the non-magic version is a viewport-height app frame (`AppShell` owning the scroll for
  every screen, strip as `flex-1 min-h-0`), which is not worth it for one board.

Style rules and the `@/*` alias caveat: `mem:conventions`. Commands and the fixed dev port:
`mem:suggested_commands`.
