# Unit 07 — App shell + role/brand-scoped dashboard routing (UI shell)

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 02, 06
**Unlocks:** 08 (board), 09 (case detail), 17 (dashboards) all mount inside this
shell
**Gating open questions:** none (real dashboard KPIs come in Unit 17)

## Goal

The internal React application shell every staff screen lives in: login, the
left nav (role/brand scoped), the top bar (brand switcher for the GM, global date
filter, search, notification bell wired to Unit 06), role-scoped routing, and
placeholder dashboard states per role. No real dashboard data yet.

**Verifiable result:** logging in as each role shows the correct nav items and
lands on the correct role dashboard placeholder; the GM sees a brand switcher and
can pick "All brands" or one brand; non-GM roles are locked to their brand; the
notification bell shows the unread count and lists the user's notifications;
unauthorized routes are blocked.

## In scope

- React auth flow: login page → store JWT → authenticated shell; `/api/me`
  hydrates the session (role, brandId).
- App shell layout from `ui-context.md`: fixed left nav, top bar (brand switcher,
  date filter, search, notification bell), main content region.
- Role-scoped route table + an unauthorized/403 view.
- Brand context in the client (GM switch → filter applied to scoped calls).
- Placeholder dashboard pages per role (skeleton tiles/empty states).
- One small backend addition: `GET /api/brands` (GM only) for the switcher.

## Out of scope

- Real dashboard KPIs and charts — Unit 17.
- The Kanban board (Unit 08) and case detail (Unit 09) — they mount here later.
- Client/expert portals — separate surfaces (Units 14/15).

## Backend addition
| Method | Path | Auth | Returns |
| --- | --- | --- | --- |
| GET | /api/brands | **GM only** | `[{ id, name, slug }]` for the brand switcher |

Non-GM users never call this; their brand is fixed from `/api/me`.

## Frontend deliverables
1. **Auth flow** (`features/auth`): login form → `POST /api/auth/login` → store
   token (in-memory + refresh strategy) → load `/api/me`. Unauthenticated users
   are routed to login; the token is attached by the `lib/api` client.
2. **App shell** (`features/shell`): fixed left nav with role-filtered items; top
   bar containing —
   - **Brand switcher**: GM sees "All brands" + each brand from `/api/brands`;
     everyone else sees a static brand label (no switcher). The selection is held
     in app state and, for the GM, passed as a `brandId` filter to scoped API
     calls (server still enforces scope).
   - **Global date filter**: today / week / month / year (state only; consumed
     by dashboards later).
   - **Search** box (placeholder wiring).
   - **Notification bell**: unread count from `/api/notifications/unread-count`,
     a dropdown listing `/api/notifications`, and mark-read /
     `/api/notifications/read-all` actions.
3. **Role-scoped routing** (`features/shell/routes`): a route table keyed by
   role. Each role's default landing is its dashboard placeholder. Navigating to
   a route the role isn't allowed → the 403 view. Roles: `GM`, `BRAND_MANAGER`,
   `PROJECT_MANAGER`, `PROJECT_COORDINATOR`, `CASE_MANAGER`,
   `EXPERT_NETWORK_MANAGER`.
4. **Placeholder dashboards** (`features/dashboards`): one page per role with
   skeleton RAG tiles and empty states, labeled with that role's PRIMARY KPI slot
   (real data in Unit 17). The GM page notes it aggregates across brands.
5. **Tokens/components**: use the `ui-context.md` tokens (from Unit 01) and the
   protected `components/ui` primitives; no hardcoded hex, tabular figures for
   any numeric placeholders.

## Nav items by role (initial)
- **GM**: Dashboard, Cases (all brands), Experts, Payouts, Brands.
- **Brand Manager**: Dashboard, Cases, Experts, Payouts (own brand).
- **Project Manager**: Dashboard, Board, Cases (team), Experts (read).
- **Project Coordinator**: Dashboard, Doc Checklists, Delivery, Cases (own).
- **Case Manager**: Dashboard, My Cases.
- **Expert Network Manager**: Dashboard, Expert Database, Payouts.
(Items resolve to real screens as later units land; here they route to
placeholders.)

## Acceptance criteria
- [ ] Logging in as each of the six roles shows the correct nav set and lands on
      that role's dashboard placeholder.
- [ ] The GM sees a brand switcher populated from `/api/brands` with an "All
      brands" option; switching updates the active brand filter. Non-GM roles see
      a static brand label and no switcher.
- [ ] The notification bell shows the correct unread count and lists the user's
      notifications; mark-read and read-all update the count live.
- [ ] Navigating (or deep-linking) to a route outside the role's allow-list shows
      the 403 view, not the screen.
- [ ] Logout clears the session and returns to login; an expired token routes to
      login on the next call.
- [ ] `npm run build` passes with no TypeScript or console errors; `./mvnw
      verify` passes (for `/api/brands`).

## Invariants honored
The GM is the only cross-brand surface (brand switcher); every other role is
locked to its brand (1). UI scoping is convenience only — the server enforces
brand/role/ownership on every call (architecture principle 7). No mail; the bell
is the staff channel (14).

## Files touched (created)
Backend: `.../web/BrandController.java` (+ DTO), `.../service/BrandQueryService.java`.
Frontend: `frontend/src/features/auth/*`, `frontend/src/features/shell/*`
(`AppShell`, `LeftNav`, `TopBar`, `BrandSwitcher`, `DateFilter`,
`NotificationBell`, `routes`), `frontend/src/features/dashboards/*`
(per-role placeholders), `frontend/src/lib/{api,auth,session}.ts`,
`frontend/src/components/Forbidden.tsx`.
