# Tech Stack

Versions are pinned in `frontend/package.json` / `backend/pom.xml`; both halves sit on recent
majors, so training-data-era API habits are frequently wrong. Verify against current docs before
using older idioms.

**Locked by `context/ai-workflow-rules.md`:** Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) +
Flyway + Spring Security/JWT on the backend, React/Vite + Tailwind on the frontend. Do not introduce
a Node backend, another database, an object store, a mail server, or a different auth model. Install
a dependency only in the unit where it first unlocks real behavior.

**Also decided, so these are not open choices (Production Process v2.0):**

| Need | Answer | Not |
|---|---|---|
| Scheduling the Unit 19 sweeps | Spring `@Scheduled` + `@EnableScheduling`, already on the classpath | **Quartz** — no dynamic schedules or per-row timers to justify its tables |
| Stopping two instances double-firing a sweep | `pg_try_advisory_lock(hashtext(:jobType))` — **session-scoped**, released in a `finally`; the `_xact_` variant is wrong because sweeps use one transaction per item | **ShedLock** — another dependency and another table for a Postgres builtin |
| Queue / outbound delivery | the `webhook_delivery` outbox, `FOR UPDATE SKIP LOCKED` | **Kafka / Rabbit / SQS** — the only cross-process work is retrying one webhook |
| Accepting a client's document, or a signed letter | stream through to Drive (`InputStreamContent`) | **S3 or any blob column** |
| Expert e-signature | **no provider** — the expert signs in their own tool and uploads the PDF back through their portal | **Dropbox Sign** (dropped), DocuSign, or an in-browser signature pad. This removed an account, API key, template, callback secret, an SDK and a second inbound webhook source |
| Charts (Unit 17 cycle-time p90) | **undecided** — small library vs hand-rolled SVG, chosen at the start of that unit | installing one before a screen renders it |

The mail-server ban is still in force but is **under review** — the open GHL-vs-EvalOS-mail decision is
in `context/process-automation.md`. Until it is taken, do not add an SMTP dependency.

## frontend/

- React 19 + react-dom 19, react-router-dom 7 (`Routes`/`Route` element API, not v5 `Switch`).
- TypeScript ~6.0, Vite 8, `@vitejs/plugin-react`.
- Tailwind v4 via the `@tailwindcss/vite` plugin — CSS-first config (`@import 'tailwindcss'` +
  `@theme`). There is deliberately **no `tailwind.config.js`**; extend the `@theme` block in
  `src/styles/tokens.css` instead.
- oxlint (not ESLint) — config `frontend/.oxlintrc.json`, plugins react/typescript/oxc.
- axios for HTTP. No state-management or data-fetching library. **Vitest is installed**
  (`npm run test` → `vitest run`) and is used for pure rules modules — `boardRules`,
  `checklistRules`, `navigation`, `expertRules`, `shortlistRules`, `redactionRules`, `portalRules` —
  not for
  component rendering: there is no
  jsdom/Testing Library, so a component's behaviour is still verified by typecheck + lint + running
  it.
- Planned but not installed: shadcn/ui-style Radix primitives, Lucide icons.

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
