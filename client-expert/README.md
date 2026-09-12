# EvalOS — Portal Frontends (client + expert)

The external portal frontends for the EvalOS production CRM, built with React, TypeScript, Vite, Tailwind CSS and shadcn/ui-style components. **Two applications, one dependency set**: `client/` and `expert/` build and deploy separately — one subdomain each — and share `shared/` plus the single `package.json` and `node_modules` at this level.

> **Its backend exists, and one screen now calls it.**
>
> This is one of three applications in the EvalOS monorepo (`backend/`, `frontend/`, `client-expert/`). EvalOS is a Java 21 / Spring Boot / PostgreSQL system, and the client portal's endpoints are **built and verified**: `GET /api/portal/client/case`, `POST /api/portal/client/approve`, `POST /api/portal/client/request-revisions`, and `POST /api/portal/client/documents`. The expert half is built too (Unit 15, 2026-09-03): `GET /api/portal/expert/case`, `POST /accept`, `POST /request-evidence`, `POST /decline`, `GET /letter`, `POST /signed-letter`.
>
> **The expert's `/case` is wired (Unit 34 slice 34e, 2026-09-03).** Open it as
> `/case#<token>` in the expert app: the assigned case, the letter, the evidence it rests on, the
> three answers, and the sign step — download, sign in your own tool, upload the PDF back with the
> attestation ticked. Everything else in the expert app (the assignments list, login, payments,
> profile) is still mock: the list needs decision D1 and payments needs D6.
>
> **`/documents` is wired (Unit 34 slices 34a + 34c).** It reads the checklist, uploads through EvalOS to S3 with a real progress bar, and opens a document through a 5-minute presigned URL. It takes a scoped portal token out of the URL fragment, so open it as `/documents#<token>`; it sits outside the account shell on purpose.
>
> **Read `context/specs/34-portal-frontend-wiring.md` before wiring anything else here.** This app was built against a different auth model (accounts) than EvalOS runs (scoped `X-Portal-Token` links), a different case model (a list of cases vs. one case per token), and its own lifecycle vocabulary. Three EvalOS invariants sit in the path of the intake, payments/invoices and messaging screens. Five decisions gate the wiring, and none is taken.

Every other screen is still a **frontend-only** build. Authentication, profile/onboarding data, and the remaining portal records (applications, payments, invoices, reports, messages, tickets) are served by a mock service layer backed by `localStorage`, so the full journey can be exercised end to end.

## Getting Started

```bash
npm install          # once, here — the two apps share this node_modules
npm run dev:client   # client portal on http://localhost:5174
npm run dev:expert   # expert portal on http://localhost:5175
```

Port **5174** is the origin the EvalOS backend already allows for `/api/portal/**`; **5175** has to be added to `evalos.portal.allowed-origins` before the expert app can call it. There is **no `/api` proxy** — portal calls are genuinely cross-origin, so a missing origin shows up as a failed preflight that looks exactly like a bad token.

Copy each app's `.env.example` to `.env` **inside that app's folder** (Vite reads env from the app it is serving) and set `VITE_API_URL` to the backend origin.

## Scripts

All run from this folder; each one changes into the app it builds.

- `npm run dev:client` / `npm run dev:expert` — the two dev servers (5174 / 5175)
- `npm run build:client` / `npm run build:expert` — type-check (`tsc -b`) and build into `client/dist` / `expert/dist`
- `npm run build` — both, in order
- `npm run preview:client` / `npm run preview:expert` — preview a production build
- `npm run lint` — oxlint over everything
- `npm run test` — Vitest once, over all three folders (pure rules modules only; no jsdom, no Testing Library)

## Project Structure

```
client/               the client portal — its own vite.config.ts, index.html, tsconfig, dist
├── src/
│   ├── components/   intake/, layout/ (portal shell), common/ (client-coupled bits)
│   ├── layouts/      Auth, Intake, Portal
│   ├── pages/        dashboard, requests, documents, reports, intake/, ...
│   ├── routes/       guards.tsx
│   ├── context/      AuthContext
│   ├── services/     the client half of the real/mock boundary
│   └── schemas, constants, mock, types, utils, lib, hooks, assets

expert/               the expert portal — same shape, port 5175
├── src/
│   ├── components/   expert/ (case card, signing badge), layout/ (expert shell)
│   ├── layouts/      ExpertAuth, ExpertPortal
│   ├── pages/expert/ dashboard, case detail, payments, profile
│   ├── routes/       expertGuards.tsx
│   ├── context/      ExpertAuthContext
│   └── services/     expertAuthService, expertCaseService (mock until Unit 15)

shared/               imported by both, as @shared/*
└── src/
    ├── components/ui/      shadcn/ui-style primitives
    ├── components/common/  EmptyState, FileDropzone, FormField, PageHeader, ...
    ├── services/           apiClient.ts — the one real HTTP seam
    ├── lib/portal.ts       portal wire types and pure display rules (+ its tests)
    ├── styles/             Tailwind entry + design tokens
    └── constants, schemas, mock, utils, hooks, pages/NotFound, assets
```

**`@/` is the app you are in; `@shared/` is `shared/src`.** An app never imports from the other app — that is what makes the two deployable apart. Anything both need moves into `shared/`.

## Connecting the EvalOS Backend

Every function in `src/services/*` is written to mirror a REST call, and **that isolation is the one property that makes this wiring tractable** — pages depend on service function signatures, nothing else. A page that reaches past `src/services` ends that property.

The full plan, its five gating decisions and its five slices are in `context/specs/34-portal-frontend-wiring.md`. Three things about the seam are worth knowing before reading it:

1. **The credential is `X-Portal-Token`, a header**, minted by EvalOS and delivered as a link. `apiClient.ts` reads it from the URL fragment and holds it in memory — never `localStorage`.
2. **`withCredentials` is gone and must stay gone.** The portal chain sets `allowCredentials(false)` on purpose — the credential is a header, never a cookie, and allowing credentials would turn a mistaken origin into a session-riding hole. The chain accepts only `GET`, `POST`, `OPTIONS` and only the headers `Content-Type` and `X-Portal-Token`.
3. **Ten of the twelve service modules are still `localStorage` mocks.** `apiClient.ts` and `documentService.ts` are real; the rest are not.

~~Replace the `localStorage`-backed session in `authService.ts` with real HTTP calls~~ — **not as written.** EvalOS has no account system and no login endpoint; whether it grows one, or the portal token widens to name a party instead of a case, is decision **D1** in the spec above. It is not a wiring detail.
