import { useState } from 'react'
import { tokenFromFragment } from '@shared/lib/portal'
import { hasPortalToken, setPortalToken } from '@shared/services/apiClient'

/**
 * Takes the scoped portal token out of the URL fragment, once, before anything queries.
 *
 * **A lazy `useState` initializer and not an effect.** The token has to be on the client before
 * the first query fires, and an effect runs after render — which would send one unauthenticated
 * request on every page load. Re-running the initializer (StrictMode does) sets the same value
 * twice, which is a no-op.
 *
 * **Extracted in 34d, when this became the fifth copy.** `/documents`, `/invoices` and `/draft`
 * each carried it, and the case list and dashboard were about to. Five copies of the one line
 * that decides whether a screen is authenticated is five places for one to drift.
 *
 * **The fragment, deliberately, not the query string.** A fragment is not sent to the server and
 * does not land in access logs or `Referer` headers — the same reasoning that put the token in a
 * header rather than a query parameter on the wire.
 *
 * @returns whether a token is now available, which is what a screen gates its query on
 */
export function usePortalToken(): boolean {
  const [present] = useState(liftFragmentToken)
  return present
}

/**
 * Takes a token out of the fragment and **out of the address bar** (Unit 75, D73). With the
 * sign-in now kept across reloads, a fragment left in place would be read again on every load: an
 * expired link would 401, the session-ended handler would reload, and the same dead token would
 * come back — a reload loop. Dropping it also keeps the credential out of browser history. The
 * router's own history state is kept, so nothing else about the entry changes.
 */
export function liftFragmentToken(): boolean {
  const token = tokenFromFragment(window.location.hash)
  if (token) {
    setPortalToken(token)
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
  }
  return hasPortalToken()
}
