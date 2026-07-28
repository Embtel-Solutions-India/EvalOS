# frontend/ — Core

Vite SPA, ~8 source files under `frontend/src`. Structure is `lib/`, `components/`, `pages/`,
`styles/`. Target structure per `context/code-standards.md`: `components/ui/` (generated headless
primitives — **protected, do not hand-edit**), `features/` (board, case detail, dashboards, client
portal, expert portal), `lib/` (API client, hooks). Add those folders when the first real feature
lands, not before.

## Wiring

- `src/main.tsx` — root: `StrictMode` > `BrowserRouter` > `App`. Router provider lives here, **not**
  in `App.tsx`, so `App` renders without a second router.
- `src/App.tsx` — route table only; all routes nest in one pathless `<Route element={<Layout/>}>`,
  `index` redirects to `/dashboard`, catch-all `*` is inside the layout too.
- `src/components/Layout.tsx` — header + `<Outlet/>`. Currently no nav links (the real app shell —
  brand switcher, date filter, notification bell — is a later unit).
- `src/pages/Dashboard.tsx` — health probe against `GET /api/health`; the reference pattern for
  fetch + discriminated-union state + RAG dot.

## HTTP layer

- `src/lib/api.ts` exports a single shared `api` axios instance — always import it; never call
  `axios` directly or create per-feature instances.
- `baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api'`. Leave the env var **unset in dev** so
  requests stay relative and flow through the Vite proxy (`frontend/.env.example`); set it only when
  the SPA is served from a different origin.
- `baseURL` already contains `/api`, so call paths omit it: `api.get('/health')`.
- `ApiResponse<T>` in the same file mirrors the backend envelope as a discriminated union — type
  every call `api.get<ApiResponse<T>>(...)` and narrow on `success` before touching `data`.
- A response interceptor console-logs failures under `import.meta.env.DEV` and re-rejects; error
  handling stays at the call site.

## Styling — design tokens only

- `src/styles/tokens.css` (imported by `src/index.css`) is the single source of truth, mirroring
  `context/ui-context.md`: surface/text/border/accent colors as `:root` custom properties, plus a
  Tailwind v4 `@theme` block for `--font-sans/num/mono` and the `--radius-md/lg/xl` scale.
- **No hardcoded hex and no Tailwind palette colors** (`slate-*`, `violet-*`) in components —
  reference `var(--token)`. Light workspace only; the old `dark:` class pairing was removed.
- `--status-red/amber/green` are **reserved for RAG status** (deadlines, SLA, capacity, overdue) and
  must never be used decoratively — `--accent-primary` is the brand/interactive color.
- Radius by context: `rounded-md` badges/inline, `rounded-lg` cards/panels/Kanban, `rounded-xl`
  modals/drawers. Numeric/currency/date/ID columns use `font-num` + `tabular-nums`.
- Icons: Lucide React, stroke-based, `h-4 w-4` inline / `h-5 w-5` in buttons+nav (not installed yet).

Style rules and the `@/*` alias caveat: `mem:conventions`. Commands and the fixed dev port:
`mem:suggested_commands`.
