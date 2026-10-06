# Unit 75 — The portal session survives a reload

**Decided 2026-10-02 by the business (D73)**: keep the client's and the expert's sign-in in
`sessionStorage`. **Edited 2026-10-07 (D73): the token is now in `localStorage`, not `sessionStorage`** — so rows 1 and 2 of the table below no longer hold: it survives a closed browser and a new tab, ending only at the server's 7-day expiry, Sign out or a 401. **Status: BUILT 2026-10-02** (branch `feature/unit-74-view-and-download`; see
`implementation-status.md`).

## 0. Today, before this unit

- The portal token is a module variable in `shared/src/services/apiClient.ts` and nothing else.
  A reload, a bookmark or a new tab loses it, and the layouts send the user to sign-in
  (`PortalLayout`, `ExpertLayout`). `PortalLayout` called this "memory-only, by design".
- **A reload is how the client signs out**: the client portal has no Sign out except on the policy
  screen, and the expert portal's Sign out is `window.location.assign('/')`, which works only
  because the reload drops the token.
- **Nothing ends the session on the server.** A signed-in token lives seven days
  (`evalos.portal.party-link-ttl`); leaving the page only forgets it in the browser.
- **A 401 is shown, not handled.** Each screen renders "This link is no longer valid… ask whoever
  sent it", which is wrong for someone who signed in with a password, and nothing returns them to
  sign-in.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | Where does the token live? | **`sessionStorage`, one key per portal origin**, plus the module variable as a cache. It survives a reload in the same tab and is gone when the tab closes. Every read and write is wrapped in try/catch, so a browser that refuses storage behaves as before (memory only). |
| 2 | A new tab? | Still signed out. `sessionStorage` is per tab; that is the point of choosing it over `localStorage`. |
| 3 | Sign out | **A Sign out control in both portals** (client: sidebar and mobile drawer; expert: the existing two) that **revokes the token on the server** (`POST /api/portal/sign-out`), clears storage and goes to the sign-in page. A failed revoke still signs the browser out. |
| 4 | A 401 while signed in | **Clear the token and return to sign-in**, which says "Your session has ended. Sign in again." The portal auth routes are excluded, so a wrong password still shows its own message. |
| 5 | Server | `PortalAccessService.revoke(presented)` sets `revoked_at` on the presented token's row; unknown or already-revoked tokens are a no-op. The route sits on the portal chain as an authenticated `POST`, not in the `permitAll` list. |
| 6 | Audit | A sign-out writes `PORTAL_SIGNED_OUT` (object `PORTAL_ACCESS`), as sign-in writes `CLIENT_SIGNED_IN` / `EXPERT_SIGNED_IN`. |
| 7 | A token in the link (`#token`) | Lifted once and **removed from the address bar** (`liftFragmentToken`, `history.replaceState`). Otherwise a reload would read it again, and an expired one would loop through the 401 handler. |
| 8 | A signed-in user on the sign-in page | Sent to `/dashboard`. |

## 2. Not in this unit

- Cookies (HttpOnly). Larger change: credentialed CORS, CSRF, cross-subdomain cookie scope.
- Shortening the seven-day token or adding idle expiry.
- A push notification opened with no portal tab still lands on sign-in (a new tab has no session).
