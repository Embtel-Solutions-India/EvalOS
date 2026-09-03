# Unit 34 — The portal frontend, and wiring it to EvalOS

> **Status: slices 34a and 34c BUILT 2026-09-03. The rest is SPECCED, not built.**
>
> Built: the HTTP seam (`X-Portal-Token` out of the URL fragment, `withCredentials` deleted,
> Vitest installed), the client's document screen against real S3-backed endpoints, **two new
> portal reads the backend was missing**, and — **as of 2026-09-03 — slice 34e, the expert's case
> screen** against Unit 15's six routes. 536 backend tests green (was 532), `client/` builds and
> its 10 tests pass. See §5 for what each slice covers and §9 for what the build found.
>
> **⚠ D1, D5, D6 and D8 were DECIDED on 2026-09-04, all as recommended.** A portal credential
> names a **party**; the lifecycle vocabulary is EvalOS's, projected into the payload; an expert
> reads their own payout rows and never a payment detail; and third-party analytics is **off**.
> The implementation is **`35-party-scoped-portal-access.md`**, which also answers open question
> (a) — a party token lives **7 days** against the case token's 30. §4 below is kept as the record
> of the reasoning; the recommendations in it are no longer proposals.
>
> **D2, D3 and D4 stand as recommended and unreversed**: the intake funnel is cut, payments and
> invoices link out to GHL, and messaging is not built.
>
> The two built screens took a *case*-scoped token — the credential that existed at the time — and
> sit **outside** the account shell, which is what kept them from answering D1 by accident. They
> keep working unchanged: a case-scoped link stays legal for the thing it is better at.
>
> **⚠ AMENDED 2026-09-03 — the folder is `client-expert/` and it now holds TWO apps.**
> `client/` (port 5174) and `expert/` (port 5175) are separate builds with separate
> `dist` output, so each can be deployed to its own subdomain; what both use lives in
> `shared/src` and is imported as `@shared/*`. **Dependencies stayed single** — one
> `package.json` and one `node_modules` at `client-expert/`, no workspaces. Everything
> below about the gaps, the decisions and the slices is unchanged by this: it was a
> packaging change, not a contract change. Two consequences worth stating — **slice 34e
> now has a whole app to fill rather than a route group** inside the client's, and a
> deployment must name **both** origins in `EVALOS_PORTAL_ORIGINS` (`local` defaults to
> both) or the expert app dies at the preflight.
>
> This is a **pivot spec**. An external
> frontend (`client/`) has landed in the repo carrying **both portals**, and it was built
> against a **different auth model, a different case model and a different lifecycle
> vocabulary** than the one this system runs. Three invariants are in its path. Nothing is
> wired until the decisions in §4 are taken, because wiring it as delivered would either
> fail at the first preflight or create a case outside Handoff A.
>
> **It is not an unwelcome surprise — it is the deployment Unit 30 predicted.** Unit 30 §1
> wrote *"one external frontend deployment, two portals, one backend"*, and
> `application-local.yml` has named `http://localhost:5174` as the allowed portal origin
> since that unit shipped. `client/vite.config.ts` serves on **5174**. The shape is right;
> the contract is not.
>
> **What it amends when built:** `architecture.md` (repo layout, the *Portal auth* row,
> the *Client portal* auth bullet), `project-overview.md` (Client Portal / Expert Portal
> features), `ui-context.md` (a fourth surface with its own token set),
> `process-automation.md` (a third channel for T1–T8).

**Phase:** 2 — Connect the seams
**Depends on:** 14 (portal token model, built), 30 (S3 + CORS, built), 31 (production
lifecycle v2, specced), 15 (expert portal backend, **not built**)
**Supersedes:** nothing yet — it *proposes* superseding Unit 14's passwordless-link access
model. That reversal is decision **D1** and is not taken here.

---

## 1. What landed

`client/` — a Vite 8 / React 19 / TypeScript 6 / Tailwind 3 SPA, ~9.5k lines across 130
source files, **two portals in one deployment**, served on port **5174**.

| | Client portal | Expert portal |
|---|---|---|
| Entry | `/login`, `/start` (intake funnel) | `/expert/login` |
| Session | `AuthContext` + `localStorage` | `ExpertAuthContext` + `localStorage`, fully separate |
| Screens | dashboard, requests + detail, documents, payments, invoices, reports + detail, messages, tickets, profile, settings, analytics | dashboard, case detail, payments, profile |
| Data | 12 service modules, **all mock, all `localStorage`** | same |

`src/services/apiClient.ts` was an Axios instance pointed at `VITE_API_URL ?? '/api'` and
**imported by nothing**. Every service module was a mock; the real/mock boundary is
isolated to `src/services/*`, which is the one thing that makes this wiring tractable.
**As of 34a/34c that is no longer wholly true**: `apiClient` and `documentService` are real, and
`documentService` is the first module in the app that calls EvalOS. Everything else in §1 still
describes what is there.

**The app has its own design system** — DM Sans / DM Serif Display, International
Evaluations navy `#003152` and crimson `#c8102e`, a dark mode, `--radius: 0.625rem`. It
shares no token with `frontend/src/styles/tokens.css` and should not: an external
client-facing surface is not the internal operations tool. See §4 D7 for the multi-brand
half of this, which is a real gap rather than a divergence.

**It shipped no tests and no test runner.** `frontend/` has 141; this had zero. **Vitest was
installed in 34a** (`npm run test`, 10 tests over `lib/portal`), and `task_completion` now names
`client/` as its own gate.

---

## 2. What EvalOS already provides

Built and verified. The first four had no caller until 34c wired the last of them:

| Surface | Endpoint | Built in |
|---|---|---|
| Client — read the draft (stamps the read receipt) | `GET /api/portal/client/case` | 14 |
| Client — approve (**this is Handoff B**) | `POST /api/portal/client/approve` | 14 |
| Client — request revisions (reason required) | `POST /api/portal/client/request-revisions` | 14 |
| Client — upload one document against one checklist item | `POST /api/portal/client/documents` | 30 |
| Auth | `X-Portal-Token` header → `PortalTokenFilter` → `PortalPrincipal` | 14 |
| CORS | `/api/portal/**` only, origins from `evalos.portal.allowed-origins` | 30 |

**Two of those rows are new, and the reason is a hole this unit found.** `POST .../documents`
shipped in Unit 30 taking a `checklistItemId`, and **no portal route revealed one** — the endpoint
was not insecure, it was *uncallable*. `ClientDraftView` excludes the checklist by design, and that
design is right, so the answer was a second read rather than a widening of the first:

| Added in 34c | What it answers |
|---|---|
| `GET /api/portal/client/documents` | the checklist (Unit 10's own status vocabulary) + the client's own uploads |
| `GET /api/portal/client/documents/{documentId}/url` | a 5-minute presigned read, minted per click |

Both carry **two** filters and the second is half the authorization: the document must be on the
token's case **and** be `CLIENT_UPLOAD`. Drop the kind filter and a client can name their own draft
— or, once Unit 15 lands, the expert's signed letter — and read it outside the flow that decides
when they may. Neither response contains an object key.

**The expert half does not exist.** There is no `ExpertPortalController`, no expert route,
no signed-letter upload endpoint. `PortalAudience.EXPERT` is declared and
`PortalPrincipal.current(EXPERT)` works; nothing calls it. **Unit 15 is the prerequisite
for wiring the expert portal at all**, and this spec does not replace it.

---

## 3. The gap register

Each row is a place the delivered app and the built system disagree. Severity is what
happens if it is wired as-is.

| # | Gap | Severity | Decision |
|---|---|---|---|
| **P1** | **Auth model.** The app has email+password accounts in `localStorage` for *both* audiences. EvalOS has one scoped token per case per audience, in a header, never persisted. Nothing in the app reads a token. | **Blocking — the fork every other row hangs off** | D1 |
| **P2** | **One case vs many.** A `portal_access` row names **exactly one case**. The app is multi-case: `/requests` lists, `/expert` lists. The token model cannot express "my cases" — today a client with two cases needs two links. | **Blocking** | D1 |
| **P3** | **No draft review anywhere in the app.** A search for `approve` or `revision` across `client/src` returns nothing. The three endpoints EvalOS actually implements have no screen. `/reports` is download-only. | **Blocking — the portal's whole reason for existing is missing** | D5, slice 34b |
| **P4** | **The intake funnel mints a case.** `intakeService.submitRequest()` generates a reference of the form `IE-{year}-{6 digits}` and writes a `ClientRequest`. **Invariant 8**: only `opportunity.won` creates a case, enforced structurally by `DomainInvariantsTest`. | **Blocking — invariant** | D2 |
| **P5** | **Payments + Invoices pages.** **Invariant 2**: invoicing is GHL's, full stop. No EvalOS endpoint exists and none may be built. | **Blocking — invariant** | D3 |
| **P6** | **Messages + Tickets.** EvalOS has no client messaging and, since Unit 18's removal, **no outbound channel of any kind** (invariant 14). | **Blocking — invariant** | D4 |
| **P7** | **Four duplicate lifecycle vocabularies.** `RequestStatus` (6 values), `SigningStatus` (3), `DocumentStatus` (7), `IntakeDocumentStatus` (5) — against Unit 31's 12 stages, `PortalAccess`'s read receipts and the checklist item state. | **High — this is where "no duplicate workflow" fails first** | D5 |
| **P8** | **`withCredentials: true` against `allowCredentials(false)`.** Concrete runtime failure. The chain also allows only `GET, POST, OPTIONS` and only the headers `Content-Type` + `X-Portal-Token` — no `Authorization`, no `PUT`/`PATCH`/`DELETE`. | **High — fails on the first real call** | slice 34a |
| **P9** | **Expert payout figures.** `ExpertCase.amount` / `paymentStatus` / `paymentDate`. EvalOS has `payout_ledger` + `payout_payment` (Units 16/16b) but **no expert-facing read path**, and `payment_detail` has no read path at all (invariant 4). | **Medium — a new whitelist, needs sign-off** | D6 |
| **P10** | **Single-brand.** IE navy + crimson, an `IE-` reference prefix, `VITE_PORTAL_URL=portal.internationalevaluations.com`. EvalOS is multi-brand and XpertsPortal has no home here. | **Medium** | D7 |
| **P11** | **No expert accept / request-evidence / decline.** Handoff B's three exception paths have no screen; only sign-and-upload. Unit 15 owns them and is unbuilt. | **Medium** | Unit 15 |
| **P12** | **`VITE_GTM_ID` / `VITE_GA4_ID`.** A third-party tag on pages that show identity documents. | **Medium** | D8 |
| **P13** | **No tests, no runner.** | **Medium** | slice 34a |

---

## 4. The decisions this forces

Each carries a recommendation. Recommendations are defaults to implement when told to,
not conclusions already reached.

### D1 · Auth: what a portal credential names — **DECIDED 2026-09-04: widen the token's scope, no accounts.** Built in Unit 35

The delivered app assumes accounts. The built system has tokens. The lazy answer that
holds is neither "throw the app away" nor "build a credential system":

> **`portal_access` stops naming a case and starts naming a *party*.** A `CLIENT` row
> names a `ghl_contact_id`; an `EXPERT` row names an `expert_id`. The client's dashboard
> then lists every case that contact has, and the expert's lists every case assigned to
> them — which is exactly what the delivered screens draw.

What this buys, in one column plus a nullable `case_id`:

- **No new auth surface.** No password storage, no reset flow, no lockout, no session
  store, no second credential to rotate. The 256-bit token, the SHA-256-at-rest, the
  single-live-token partial index (`V23`), the absolute expiry and the one
  indistinguishable 401 all carry over untouched.
- **It fixes a problem Unit 15 already had**: the CM hand-sends a link *per case*. One
  link per expert is one hand-send per expert, ever.
- **It closes P2 without touching P1's security posture.** The token is still the scope;
  `ScopePredicate` is still not involved; a portal caller is still not a staff caller.

What it costs, stated rather than buried: **a party-scoped token is a wider credential
than a case-scoped one.** A forwarded link exposes every case that contact has, not one.
The mitigations are the ones already built — absolute expiry, one live token at a time,
re-mint retires the previous — plus a shorter default expiry for party scope than the
current 30 days.

**The alternative, if the business wants self-service signup**: a third Spring Security
chain with real accounts. That is a genuinely larger thing — credential storage,
rotation, reset, lockout — and **reset requires a mail channel EvalOS does not have**
(invariant 14). It is a business decision with an infrastructure bill attached, and it
reverses the "passwordless via a GHL-delivered link" line in four documents. Do not drift
into it; take it in writing.

### D2 · The intake funnel — **recommend: cut it**

Seven screens (Welcome → Choose Service → Purpose → About You → Questionnaire →
Documents → Review), a schema-driven questionnaire engine, a service catalog. It is good
work and it is **front of house**, which EvalOS does not do. Wiring it would create a case
outside `opportunity.won`.

Cut from the wired build. The two honest futures for it: it becomes a **GHL form** (front
of house, where it belongs), or a case that already exists gains a *supplementary
questionnaire* the client fills in — which is a different feature, keyed to a real case,
and would be specced on its own. `serviceCatalog.ts` and `questionGroups.ts` are worth
keeping either way; they are data, not workflow.

### D3 · Payments and Invoices — **recommend: cut, link out**

Invariant 2. Replace both pages with a single outbound link to the GHL invoice on the
case. EvalOS holds `deal_value` and `paid` and neither is a client-facing statement.

### D4 · Messages and Tickets — **recommend: cut for v1**

The client's conversation already lives in GHL and that is where the front of house is.
Revisit only if the standing open question — *who delivers client-facing messages* — is
answered "EvalOS", which would reverse invariant 14.

**What replaces them is better than either:** the portal makes T1–T8 in
`process-automation.md` visible **without any message being sent at all.** A checklist
item outstanding, a draft waiting for review, an expert asking for evidence — these become
*states the client sees when they open the portal*. `architecture.md` invariant 14 already
named this as the natural home and called it a product decision still to be taken. The
arrival of a real portal is what makes it takeable. **It does not close the email
question** — a client who never opens the portal is not notified — but it downgrades it
from blocking to a reach problem.

### D5 · Lifecycle vocabulary — **DECIDED 2026-09-04: one vocabulary, EvalOS's, projected.** Built in Unit 35

Delete `RequestStatus`, `SigningStatus`, `DocumentStatus` and `IntakeDocumentStatus` from
the app. EvalOS serves the client's step and the expert's step **in the portal payload**,
derived from Unit 31's 12 stages by a mapping that lives in EvalOS and nowhere else.

The projections the target asks for:

| EvalOS stage (Unit 31) | Client sees | Expert sees |
|---|---|---|
| 01 Document Collection | Upload — *action required* | — |
| 02 PM Review & Assignment | In progress | — |
| 03 Draft In Progress | In progress | — |
| 04 Draft Review | In progress | — |
| 05 Ready to Send | In progress | — |
| 06 Client Review | **Review — action required** | — |
| 07 Client Approval | In progress | — |
| 08 Expert Signing | In progress | **Sign — action required** |
| 09 Final QC | In progress | Submitted |
| 10 Ready to Deliver | In progress | Submitted |
| 11 Delivered | **Ready to download** | Submitted |
| 12 Closed | Complete | Submitted |

**One home per fact.** The SPA renders the label it is given; it never derives a stage,
never maps one, and never holds a second enum for a fact `CaseTransitions` owns. That rule
is the whole content of "no duplicate workflow" — enforce it in code review, because a
convenience constant in the SPA is how it erodes.

### D6 · Expert payout visibility — **DECIDED 2026-09-04: yes, rows only, never a payment detail.** Built in Unit 35

An expert may read `payout_ledger` rows for their **own** `expert_id`: case reference,
amount, status, and the settlement date when one exists. Never `payment_detail` — that
field has no read path anywhere in EvalOS, not even for the ENM who typed it (invariant
4), and this does not become the first one. New whitelist, so it needs the same written
sign-off Unit 14's client whitelist got.

### D7 · Multi-brand — **recommend: brand comes from the payload**

The portal payload already names the case; it gains the brand's display name and palette,
and the SPA reads its tokens from there with IE as the default. Anything else means a
deployment per brand, which is two frontends to keep in step.

### D8 · Analytics — **DECIDED 2026-09-04: off.** `VITE_GTM_ID`, `VITE_GA4_ID` and `utils/analytics.ts` are deleted in Unit 35 — not stubbed, because a no-op module is one somebody re-points at a provider

No third-party tag on a page that shows a client's identity documents until somebody owns
that decision. `src/utils/analytics.ts` already warns against sending sensitive properties;
the safer default is not sending anything.

---

## 5. Build slices

34a and 34c are built. **34b and 34d do not start before D1 and D5 are answered** — 34b needs to
know where a draft screen lives, and 34d *is* D1.

- **34a · The seam — BUILT.** `services/apiClient.ts` is a real portal client: `withCredentials`
  deleted (the chain sets `allowCredentials(false)`, so it refused every cross-origin call), an
  `X-Portal-Token` interceptor, the token held in a module variable and **never persisted**, the
  `ApiResponse` envelope unwrapped in one place, and no default `Content-Type` so a `FormData` body
  keeps the browser's multipart boundary. `lib/portal.ts` holds the pure wire types and display
  rules. **Vitest installed** (`npm run test`), 10 tests. `mock/simulateUpload.ts` is where the
  intake wizard's fake upload went — a mock beside real calls in one module is how somebody ships
  the mock.
- **34b · Client draft review.** The three built endpoints get their screens: read,
  approve, request revisions. **This is the highest-value slice left in the unit** — it is the
  one thing the backend already does and the app cannot. **Unblocked by D1** (2026-09-04): it now
  knows which case it is showing, because the party read names them.
- **34c · Client documents — BUILT.** `pages/documents/Documents.tsx` is the app's first real
  screen: the checklist (outstanding first), an upload per item with a real progress bar, and a
  per-click presigned download. Routed at `/documents` **outside `AuthenticatedRoute`** and removed
  from the sidebar — the credential is a scoped link, not this shell's session, and putting it
  behind the account guard would have answered D1 sideways. The orphaned mock surface went with it:
  `MOCK_DOCUMENTS`, `ClientDocument`, `DocumentStatus` and `DocumentStatusBadge` are deleted.
- **34d · The case list + projection.** D1's party-scoped token, D5's stage projection,
  the dashboard and requests screens against real cases. **Unblocked 2026-09-04** — both
  decisions taken; the backend half is Unit 35.
- **34e · Expert portal — BUILT 2026-09-03**, against Unit 15's six routes.
  `expert/src/pages/portal/ExpertCasePortal.tsx` at `/case#<token>`, **outside**
  `ExpertAuthenticatedRoute` for the reason 34c stayed outside `AuthenticatedRoute`: the credential
  names one case, not an account, and mounting it behind the shell would answer D1 sideways. One
  column — goal, the letter, the evidence it rests on, then the answers — plus the two-step sign
  panel (open the letter → sign it in your own tool → upload the signed PDF) with the attestation
  **as part of the upload**: the dropzone is disabled until the tick, and the API refuses it absent
  regardless. `expert/src/services/expertPortalService.ts` is the app's second real service module;
  `expert/src/lib/expertCase.ts` holds the wire types and display rules, 12 tests.
  **Still mock**: the assignments list (needs D1), login, payments (needs D6) and profile.
  **Two seam changes it forced**: `apiClient`'s base is now `/api/portal`, each service naming its
  own half (`/client/…`, `/expert/…`); and EvalOS mints expert links at a **separate origin**
  (`evalos.portal.expert-base-url`, path `/case#<token>`) because the two portals are two
  deployments — a link to the wrong host reads to its holder exactly like a revoked token.
  **Review fix (2026-09-03):** both apps opened documents with `window.open` *after* an `await`,
  which Safari and Firefox block — the URL was minted and audited and nothing opened, with no
  error. Both now open the tab synchronously and navigate it once the URL arrives, which is the
  pattern `frontend/src/features/case/DocumentList.tsx` already documented. The client's
  `/documents` had the same bug from 34c and was fixed with it: one root cause, two call sites.

---

## 6. Invariant impact

- **1 (brand isolation)** — unchanged. A portal caller's scope is their token; D7 adds a
  brand *label* to a payload, never a brand predicate a caller can name.
- **2 (one custody, no invoicing/sales)** — **protected by D2 and D3.** Both cut features
  that would have breached it.
- **4 (`payment_detail`)** — **unchanged and load-bearing in D6.** Ledger rows yes, the
  field never.
- **7 (`ghl_contact_id` is canonical)** — **D1 rests on it.** A party-scoped client token
  names the GHL contact id and nothing else. No portal-minted client identifier, ever.
- **8 (only `opportunity.won` creates a case)** — **protected by D2.**
- **13 (append-only audit)** — every portal action already writes through
  `AuditService.recordPortalEvent` with `actor_type` `CLIENT`/`EXPERT`. Wiring adds
  callers, not writers.
- **14 (no files, no email)** — unchanged. The SPA holds no AWS credential and uploads
  through EvalOS, which streams. D4 sends nothing.
- **15 (no AI)** — unchanged, and worth restating here because a client-facing surface is
  where "helpful summary" pressure arrives.

---

## 7. Acceptance criteria

1. A client opens a portal link, sees their draft, and **approves it** — and the case in
   EvalOS moves to expert signing with an audit row naming **the client**.
2. The same client requests revisions with a reason, and the Case Manager sees the reason
   on the case.
3. A client uploads a document against a checklist item; the Coordinator sees it in
   EvalOS against that item, and the object is under the case's brand-first S3 prefix.
4. Every status word the client reads is a string EvalOS sent. A search for a lifecycle
   enum in `client/src` finds none.
5. No screen in the wired build creates a case, shows an invoice, or sends a message.
6. A cross-origin call from the deployed portal origin succeeds; one from any other
   origin is refused at the preflight.
7. `npm run build` passes in `client/` with no TypeScript error, and the new test suite
   is green.
8. **(34e)** An expert opens their link, downloads the draft, uploads it signed, and the
   case reaches Final QC with an `EXPERT` audit row and the hash pair recorded.

---

## 8. Open questions

- ~~**(a) Party-scoped token expiry.**~~ **ANSWERED 2026-09-04: 7 days**, against the case
  token's unchanged 30 — a party token opens every case that party has, so it must not live four
  times as long as the narrower one. Two properties, `evalos.portal.link-ttl` and
  `evalos.portal.party-link-ttl`. See `35-party-scoped-portal-access.md` §2.
- **(b) Where the portal is deployed, and how the origin reaches `EVALOS_PORTAL_ORIGINS`.**
  A missing value fails the prod boot by design — so this is a deployment prerequisite,
  not a runtime discovery.
- **(c) Does a client see a case before the draft exists?** D5's table says yes ("In
  progress" from stage 01), which means the portal shows a case with nothing to read on
  it. That is honest and it is also a support call. A "nothing to do yet" state needs
  copy that pre-empts it.
- **(d) The intake funnel's seven screens.** Cut, per D2 — but *deleted* or *parked*? They
  are ~2k lines of working code with a real future as a GHL form or a supplementary
  questionnaire. Recommend parking them behind a route that is not registered, with a
  dated note, rather than deleting work whose owner has not been asked.
- **(e) Reference numbers.** The app mints its own `IE-{year}-{6 digits}`; EvalOS has
  `case_code`. Whichever the client is told is the one support has to be able to look up.
  Recommend `case_code`, and that the portal never mints an identifier.

---

## 9. What building 34a + 34c found

Recorded because each was invisible from either side alone.

1. **The upload was uncallable.** `POST /api/portal/client/documents` needs a `checklistItemId`
   and no portal route revealed one. A backend-only review sees a working, tested endpoint; a
   frontend-only review sees a parameter it cannot source. **Two new reads close it** (§2).
2. **`withCredentials: true` against `allowCredentials(false)`** — predicted in P8 and confirmed
   in the config on both sides. Every cross-origin portal call would have failed at the preflight
   with a message pointing at the token.
3. **The kind filter is authorization, not tidiness.** The obvious presign route — "any document
   on the token's case" — hands a client their own draft outside the approval flow and, once Unit
   15 lands, the expert's signed letter. Filtering to `CLIENT_UPLOAD` is one line and one test
   (`theDraftAndTheSignedLetterAreNotReachableThroughTheDocumentRoute`), which also asserts
   **nothing is minted on a refusal** — a presigned URL created ahead of a check has already leaked.
4. **`MISSING` and `INCORRECT` reaching the client is a feature, and it is D4 working.** These are
   the Coordinator's "you did not send this" and "what you sent will not do" — touchpoint **T4**,
   arriving as a state the client can see rather than a message EvalOS has no channel to send.
   Unit 10's vocabulary is passed through unmapped; the SPA holds no lifecycle enum, only a
   label-and-colour table for the five values the server can send, with a test that fails if a
   sixth is added.
5. **`draft_link` is still a free-text link the CM pastes, not an S3 key.** Only `CLIENT_UPLOAD`
   rows have an `object_key`; a `DRAFT` `CaseDocument` is created with none. So **S3 holds client
   uploads and nothing else today.** Unit 30's "the draft is an object key" describes the intent,
   not the code — worth knowing before 34b assumes it can presign a draft.
6. **A page reaching past `services/` is the thing to reject in review.** That isolation is what
   made this slice a rewrite of two modules instead of a rewrite of the app.

### Owed after this slice

- **No content sniffing on upload** — the declared content type is recorded, not trusted (Unit 30's
  own open item, unchanged). The client-side `validateFile` check is a courtesy, not a control.
- **The two `Link to="/documents"` buttons** on the mock Dashboard and Request Detail pages now
  lead to a page that says it needs the emailed link. Honest, but they are links from mock screens
  that D2/D5 propose removing; they go with those screens rather than being patched now.
- **`frontend/src/features/client-portal/portalRules.ts` still declares `expertProfile` and
  `expertReference`**, removed from the server with Unit 13. Harmless (they arrive undefined) and
  stale — a staff-app cleanup, not this unit's.
