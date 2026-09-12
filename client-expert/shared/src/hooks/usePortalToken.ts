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
  const [present] = useState(() => {
    const token = tokenFromFragment(window.location.hash)
    if (token) setPortalToken(token)
    return hasPortalToken()
  })
  return present
}
