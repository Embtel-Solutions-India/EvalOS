# Unit 72 — The portals' sign-in artwork, and policies accepted on first sign-in

**Decided 2026-10-01 by the business (D71).** **Status: BUILT 2026-10-01.**

## 0. Before this unit

- The client and expert sign-in screens were a centred card on a blank page.
- Nobody accepted anything. The client portal showed three policies in its footer (Privacy Policy,
  Disclaimer, Document Retention Policy); the expert portal had no legal pages or footer at all.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | What is accepted? | **The three existing policies**, on both portals: "I have read and accept the Privacy Policy, the Disclaimer and the Document Retention Policy." No new legal text was written; there is no Terms & Conditions page. |
| 2 | When? | **Once per account and policy version**, on the first sign-in (and again only when the policies change). A person who has accepted never sees it again, on any device. |
| 3 | Where is it recorded? | `client_account` / `expert_account`: `terms_accepted_at`, `terms_version` (`V83`), plus an append-only `TERMS_ACCEPTED` audit row with the version — the row is the evidence. |
| 4 | The version | `PortalTermsService.VERSION` = the policies' date (`2026-09-25`, `LEGAL_UPDATED` in `shared/src/legal/legal.ts`). Bump both when the policies change and every account is asked again. |
| 5 | Which accounts? | Party-scoped sign-ins only. A client token names the account by `client_account_id` **or** `ghl_contact_id` (V44), and both resolve. A pre-Unit 59 case-scoped link has no account: not asked, nothing recorded. |
| 6 | If the check fails | **Fail closed**: the portal is not shown, only a retry. |
| 7 | The screen | `shared/src/legal/TermsGate.tsx`, wrapping each portal's signed-in layout: logo, "Before you continue", the footer's three policy sentences (`PolicySummary`, word for word, links open a new tab), the checkbox, Accept (disabled until ticked) and Sign out; the artwork on the right half. |
| 8 | The sign-in screens | `AuthSplit` in each portal's `PublicLayout`: the form on the left, the artwork on the right half from `lg` up, hidden on phones. The artwork is `shared/src/assets/portal-signin.jpg` (1536×1024, 130 KB, re-encoded from a 2.4 MB PNG). |
| 9 | Shared legal pages | `LegalPage`, the three policies and the constants moved from `client/` to `shared/src/legal/`; the expert portal now routes `/privacy`, `/disclaimer`, `/document-retention`. |
| 10 | The footer | **Removed** (the business, same day): the policies are accepted once, so `SiteFooter` no longer repeats them under every screen. `PolicySummary` keeps the three sentences for the acceptance screen. |

## 2. Routes

`GET` / `POST /api/portal/client/terms` and `/api/portal/expert/terms` (`PortalTermsController`) →
`{ required, version }`. Each path demands its own audience; an expert token on the client route is 403.
`POST` is idempotent.

## 3. Verified

`PortalTermsServiceTest` (5). In Chrome, 2026-10-01: the client sign-in page with the artwork (the footer was removed after this check);
a first sign-in shows the acceptance screen, accepting lands on the dashboard and records version
`2026-09-25` plus one `TERMS_ACCEPTED` row; a second sign-in goes straight to the dashboard. The expert
sign-in page and `/privacy` render; the expert routes answer required → accepted → not required, a
repeat accept writes no second row.
