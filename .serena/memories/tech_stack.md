# Tech Stack

Versions are pinned in `frontend/package.json` / `backend/pom.xml`; both halves sit on recent
majors, so training-data-era API habits are frequently wrong. Verify against current docs before
using older idioms.

**Locked by `context/ai-workflow-rules.md`:** Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) +
Flyway + Spring Security/JWT on the backend, React/Vite + Tailwind on **both** frontends. Do not
introduce a Node backend, another database, a mail server, or a different auth model. **The object
store came off that list in Unit 30** — S3 is the document store; what stays banned is storing bytes
in EvalOS. Install a dependency only in the unit where it first unlocks real behavior.

**Three apps, and the two frontends are pinned differently on purpose** — they are separate
deployments with separate lockfiles, so `frontend/` being on Tailwind 4 while `client/` is on
Tailwind 3 is a fact to work with, not drift to reconcile.

**Also decided, so these are not open choices (Production Process v2.0):**

| Need | Answer | Not |
|---|---|---|
| Scheduling the Unit 19 sweeps | Spring `@Scheduled` + `@EnableScheduling`, already on the classpath | **Quartz** — no dynamic schedules or per-row timers to justify its tables |
| Stopping two instances double-firing a sweep | `pg_try_advisory_lock(hashtext(:jobType))` — **session-scoped**, released in a `finally`; the `_xact_` variant is wrong because sweeps use one transaction per item | **ShedLock** — another dependency and another table for a Postgres builtin |
| Queue / outbound delivery | the `webhook_delivery` outbox, `FOR UPDATE SKIP LOCKED` | **Kafka / Rabbit / SQS** — the only cross-process work is retrying one webhook |
| Accepting a client's document, or a signed letter | **stream through to S3** via the AWS SDK v2 `DocumentStore` (put + presign; no delete, no list). **Unit 30 replaced Drive** — the API client, service account, dependency and `drive_link` are gone | **a blob column, a byte array or a temp file.** The store changed; "EvalOS holds keys, never bytes" did not |
| Expert e-signature | **no provider** — the expert signs in their own tool and uploads the PDF back through their portal | **Dropbox Sign** (dropped), DocuSign, or an in-browser signature pad. This removed an account, API key, template, callback secret, an SDK and a second inbound webhook source |
| Charts (Unit 17 cycle-time p90) | **undecided** — small library vs hand-rolled SVG, chosen at the start of that unit | installing one before a screen renders it |
| Reading GHL's public API (Unit 24) | **`RestClient`** from `spring-boot-starter-web`, already on the classpath; timeouts via `SimpleClientHttpRequestFactory`, wire shapes as `record`s bound by Boot's own `ObjectMapper` | **a GHL SDK, WebClient/`spring-boot-starter-webflux`, or a generated client** — this is two GET requests, and a `Map<String,Object>` in place of the records would turn a GHL response change into a `ClassCastException` three layers up instead of a compile error |
| Drawing a stage funnel (Units 24, 26) | **Recharts horizontal bars** | **`clip-path` chevrons** (what this started as) or a funnel-chart package — the chevrons implied a progression the data has not: Won/Cold/Lost are parallel outcomes, so bar *length* is the only claim the figures support |
| Caching the GHL read | **a `ConcurrentHashMap` keyed by `(Funnel, DateRange)` + a TTL compare inside the service** | **`spring-boot-starter-cache` / Caffeine / Redis** — a handful of payloads with one TTL, bounded by construction (both key halves are small enums), and a distributed cache is infrastructure for a few seconds of skew on a funnel count. It was a single `AtomicReference` when there was one funnel and no period selector; each added dimension **must** join the key, or one combination answers for another for a whole TTL |

The mail-server ban is **settled, not under review**: Unit 18's outbound dispatcher was removed
(2026-09-02), so EvalOS has no outbound channel at all and there is nothing for SMTP to plug into.
What is still open is who reaches the client — `context/process-automation.md`. Do not add an SMTP
dependency.

## client/ (the portal frontend) — see `mem:client/core`

- React 19 + react-router-dom 7, TypeScript ~6.0, **Vite 8**, `@vitejs/plugin-react`. Port **5174**,
  **no `/api` proxy** — genuinely cross-origin.
- **Tailwind v3 with a real `tailwind.config.js`** and PostCSS/autoprefixer — the opposite of
  `frontend/`'s CSS-first Tailwind 4. Tokens are HSL triples in `src/styles/globals.css` behind
  shadcn names, with a `.dark` palette.
- **Many more runtime deps than the staff app**, and they were not chosen unit by unit: per-primitive
  `@radix-ui/react-*` (not the unified `radix-ui`), `@tanstack/react-query`, `react-hook-form` +
  `@hookform/resolvers` + `zod`, `framer-motion`, `sonner`, `recharts`, `date-fns`,
  `class-variance-authority` / `clsx` / `tailwind-merge`, `lucide-react`, `axios`. Wiring it is the
  moment to ask which of these earn their place; **`recharts` on an Analytics page for a single
  client is the first candidate.**
- oxlint (config `client/.oxlintrc.json`) and **Vitest 4** (added Unit 34a), with no config file of
  its own — it reads `vite.config.ts`, same as `frontend/`.

## frontend/

- React 19 + react-dom 19, react-router-dom 7 (`Routes`/`Route` element API, not v5 `Switch`).
- TypeScript ~6.0, Vite 8, `@vitejs/plugin-react`.
- Tailwind v4 via the `@tailwindcss/vite` plugin — CSS-first config (`@import 'tailwindcss'` +
  `@theme`). There is deliberately **no `tailwind.config.js`**; extend the `@theme` block in
  `src/styles/tokens.css` instead.
- oxlint (not ESLint) — config `frontend/.oxlintrc.json`, plugins react/typescript/oxc.
- axios for HTTP. No state-management or data-fetching library. **Vitest is installed**
  (`npm run test` → `vitest run`) and is used for pure rules modules — `boardRules`,
  `checklistRules`, `queueRules`, `navigation`, `expertRules`, `shortlistRules`, `redactionRules`,
  `portalRules` — not for
  component rendering: there is no
  jsdom/Testing Library, so a component's behaviour is still verified by typecheck + lint + running
  it.
- **Seven runtime deps as of Unit 22 slice 1**, not four. `radix-ui` (the unified package, not
  per-primitive), `lucide-react` and `recharts` joined axios/react/react-dom/react-router-dom. Each
  was installed against a screen that renders it, per the "install a dependency only in the unit
  where it first unlocks real behavior" rule:
  - **`radix-ui`** — `components/ui/` is now a **protected path**. Vendored shadcn-*style* wrappers,
    no CLI and no `components.json`. Split by behaviour, not by count: `dialog.tsx` holds Dialog
    *and* Sheet (a sheet is a dialog against an edge), `menu.tsx` holds DropdownMenu/Popover/Tooltip
    (one raised-surface treatment), `tabs.tsx` and `card.tsx` stand alone.
  - **`recharts`** — settles the charting question `ui-context.md` left open. Series colours come
    from the `--chart-1..5` ramp, **never** the RAG tokens.
  - **`lucide-react`** — the glyph-count condition the old note set was met. Existing inline SVGs
    are fine where they stand; new work imports from Lucide.
- **Still deliberately absent, with triggers written in `context/specs/22-role-operations-ui.md`:**
  dnd-kit (nine of twenty-one quick actions need a field a drop cannot supply, including the only
  way out of Expert Assignment), TanStack Table (dashboard tables are single-digit rows), and
  Motion — **CSS keyframes keyed on Radix's `data-state` cover the whole animation list**, under
  the `prefers-reduced-motion` block already in `index.css`.
- `ReflectionTestUtils` is used in exactly one place (`PmMetricsServiceTest`) to pin
  `ScopedEntity.createdAt`, which is `@PrePersist`-stamped with no setter. Preferred over adding a
  production setter that exists only for tests.

## backend/

- Spring Boot **3.5.16** parent, Java 21 (`java.version` property; the toolchain JDK may be newer —
  compilation targets 21). Boot 3 artifact naming: `spring-boot-starter-web` and a single
  `spring-boot-starter-test`.
- Starters: `web`, `data-jpa`, `validation`, `actuator`, `security`. `flyway-core` +
  `flyway-database-postgresql`. `postgresql` driver at `runtime` scope. `spring-security-test` at test
  scope.
- JWT: **jjwt 0.13.0** (`jjwt-api` compile, `jjwt-impl` + `jjwt-jackson` runtime) — the 0.11 builder
  API is wrong here; use `Jwts.builder().subject(...).signWith(key)` and
  `Jwts.parser().verifyWith(key).build().parseSignedClaims(...)`.
- **The sheet import's two parsers (Unit 11): `commons-csv` 1.12.0 and `poi-ooxml` 5.4.1.** Only
  `service/ExpertImportService` uses either. POI is ~10 MB with transitives against commons-csv's
  ~50 KB and was bought on instruction so an ENM can upload `.xlsx` straight from Excel; both feed
  one validator, so nothing downstream knows which format arrived. Note commons-csv 1.12 uses
  `CSVFormat.DEFAULT.builder()…build()` — `.get()` is 1.13+.
- **Google Drive, Unit 13: `google-api-services-drive` `v3-rev20260428-2.0.0` +
  `google-auth-library-oauth2-http` 1.48.0.** Only `integration/GoogleDriveClient` and
  `config/GoogleDriveConfig` touch either. **No PDF library accompanies them and none should be
  added**: the profile HTML is uploaded with a target mime type of
  `application/vnd.google-apps.document` so Drive converts it to a Doc, and Drive's own export
  produces a PDF from that — `openhtmltopdf`/PDFBox would duplicate a feature of an integration
  already present. Credentials are provisioned, not coded: `GOOGLE_DRIVE_KEY_JSON` or
  `GOOGLE_APPLICATION_CREDENTIALS`, with `evalos.drive.required` making a missing key a **boot
  failure** outside `local`.
- **No Lombok and no Testcontainers** — both dropped from the Initializr default in Unit 01 (records +
  constructor injection instead of Lombok). There is no Docker on this machine, so DB-dependent tests
  are gated rather than containerised. Boot 4 was deliberately downgraded to 3.x per the unit spec.
- Maven Wrapper is committed — use it rather than a system `mvn`.
