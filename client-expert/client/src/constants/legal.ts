/**
 * The three legal pages and the contact block they share (2026-09-25).
 *
 * One place for the paths, so the footer, sign-up, the uploader and the send step cannot drift
 * from the routes in `App.tsx`. The contact is the one the policies themselves name — `info@`,
 * not the portal's `SUPPORT_EMAIL` — because a policy's "contact us" is the business's legal
 * address, not the help desk.
 */
export const LEGAL = {
  privacy: { to: '/privacy', label: 'Privacy Policy' },
  disclaimer: { to: '/disclaimer', label: 'Disclaimer' },
  retention: { to: '/document-retention', label: 'Document Retention Policy' },
} as const

export const LEGAL_UPDATED = 'September 25, 2026'

export const COMPANY = {
  name: 'International Evaluations',
  street: '39159 Paseo Padre Pkwy STE 119',
  city: 'Fremont, CA 94538, United States',
  phone: '+1 (510) 876-0900',
  email: 'info@internationalevaluations.com',
} as const
