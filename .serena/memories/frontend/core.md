# frontend/ — Core

Vite SPA under `frontend/src`. Structure is `lib/`, `components/`, `features/`, `pages/`, `styles/`;
`features/` is where the real screens live — `auth`, `shell`, `board`, `case`, `checklist`,
`dashboards`, `experts`, `client-portal`. `components/ui/` (generated headless primitives) is
**protected — do not hand-edit** and does not exist yet.

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

## navigation.ts is one table, three consumers

Nav rendering, the router's allow-list (`mayReach`), and the placeholder's escape link
(`boardPathFor`) all read `NAV_ITEMS`. Keeping them separate is how a screen ends up deep-linkable
but unguarded, or listed and then 403 — four separate defects have been that one bug.
`navigation.test.ts` asserts the equivalence in both directions and **pins each screen's role list to
its backend gate** (`/checklists` → `ChecklistController.COORDINATION`, `/experts` →
`ExpertController.ROSTER_READ`). Grep it before adding any cross-screen link.

**One path per screen.** `/cases` beside `/board`, `/delivery` with no screen behind it, and
`/experts` beside `/expert-database` were all deleted for the same reason, and the test asserts their
absence so they are not re-added.

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
- Radius by context: `rounded-md` badges/inline, `rounded-lg` cards/panels/Kanban, `rounded-xl`
  modals/drawers. Numeric/currency/date/ID columns use `font-num` + `tabular-nums`.
- **Icons are inline SVG, and Lucide is not being installed.** ~15 glyphs (the nav's seven, the bell,
  the search) do not earn a dependency in an app with four runtime deps. Stroke-based, `h-5 w-5`,
  `stroke="currentColor"`. Revisit only past ~30 glyphs. `LeftNav.NAV_ICONS` is keyed by the same
  `path` the router uses, so a missing entry degrades to a fallback instead of adding a second list.
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
