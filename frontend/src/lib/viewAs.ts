/**
 * The member whose dashboard the GM is looking at (2026-10-30), or null for the GM's own.
 *
 * Set by `MemberDashboardPage` for as long as it is open. The API client adds `?viewAs=` to exactly the
 * dashboard reads the server accepts it on (`JwtFilter.VIEW_AS_PATHS`), and `useMetrics` keys its cache on
 * it, so switching between two people never shows the first one's figures. Module state rather than
 * context because the fetch functions it reaches are plain functions.
 */
let viewingAs: string | null = null

export function setViewAs(memberId: string | null): void {
  viewingAs = memberId
}

export function viewAs(): string | null {
  return viewingAs
}

/** Must match `JwtFilter.VIEW_AS_PATHS`, without the `/api` prefix. */
export const VIEW_AS_PATHS: readonly string[] = [
  '/metrics/pm',
  '/metrics/pm/overview',
  '/metrics/coordinator',
  '/metrics/coordinator/work',
  '/metrics/case-manager',
  '/metrics/case-manager/work',
  '/metrics/expert-network',
  '/metrics/expert-network/work',
  '/opportunities/board',
  '/payouts/summary',
]
