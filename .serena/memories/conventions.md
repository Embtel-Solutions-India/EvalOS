# Conventions

Full rules live in `context/code-standards.md` — this is the distilled, enforced-by-nothing subset.

## frontend/

- TS/TSX: **no semicolons**, single quotes, 2-space indent. Match this — no Prettier/formatter is
  installed anywhere in the repo.
- One default-exported component per file, named after the file
  (`export default function Dashboard`). oxlint's `react/only-export-components` is on (warn).
- Path alias `@/*` → `./src/*` is configured in **both** `vite.config.ts` (`resolve.alias`) and
  `tsconfig.app.json` (`paths`); changing one without the other silently breaks the build. Existing
  source uses relative imports; either style is acceptable.
- Async UI state is a **discriminated union**, not booleans — follow `pages/Dashboard.tsx`.
- fetch/axios effects use an `AbortController` and bail on `signal.aborted` in `catch`; required
  because `StrictMode` double-invokes effects in dev.
- Colors come from CSS custom properties (`style={{ color: 'var(--text-muted)' }}` or arbitrary
  Tailwind values), never hex literals or Tailwind palette classes — see `mem:frontend/core`.
  Conditional class strings are built with array `.join(' ')`; no `clsx`/`cva`.
- `tsconfig.app.json` sets `noUnusedLocals`/`noUnusedParameters`/`erasableSyntaxOnly` but **not
  `strict`** — annotate deliberately rather than relying on inference; prefer `unknown` in catch.

## backend/

- Java files use **tabs** for indentation (Initializr default), package `com.ie.evalos`.
- Prefer immutability: `record` for DTOs/value objects, `final` fields, **constructor injection
  only** — no field `@Autowired`. Lombok is deliberately absent.
- No raw nulls across boundaries: repositories/finders return `Optional<T>`.
- Model state with enums and types, never loose strings (`Stage`, `PayoutStatus`, `Role`, …).
- Entities and DTOs stay separate; a controller never accepts or returns a JPA entity.
- Controllers are thin: `@Valid` the request DTO → authorize (role + brand + ownership) → call a
  service → return a DTO in `ApiResponse`. Business rules live in `service`, never in controllers or
  entities. Errors are mapped centrally by a `@RestControllerAdvice` from typed domain exceptions.
- Bean Validation on every inbound request DTO and webhook payload — parse-then-trust. Where two
  paths write the same object, define the constraints **once** and run them both ways: Unit 11's
  `ExpertService.ExpertForm` is bound with `@Valid` by the controller and validated programmatically
  through a `Validator` by the sheet import, so "a quality score is 1–10" cannot come to mean two
  things.
- Test classes are package-private (`class HealthControllerTest`), slice-scoped (`@WebMvcTest`) or
  plain unit tests. A test that needs a real database is gated, not silently skipped —
  `mem:suggested_commands`.
- Persistence conventions are load-bearing, not stylistic: scoped entities extend `ScopedEntity`,
  foreign keys are raw `UUID`s rather than associations, scoped reads go through
  `ScopedRepository.findScoped(...)`, and accessors are written when a consumer appears rather than
  upfront. Details in `mem:backend/persistence`.

## Protected — do not touch without explicit instruction

`frontend/src/components/ui/*`, the audit entity + its write path (append-only), the
`payment_detail` encryption `AttributeConverter`, webhook secret-verification and brand resolution,
the repository brand-scoping filter, and any applied Flyway migration.
