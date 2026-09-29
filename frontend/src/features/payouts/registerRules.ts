import type { Role } from '../../lib/session'

/**
 * The Payouts module's types (Unit 65), member for member with `PayoutRegisterService` and
 * `OfferFeeService`. Pure, like `payoutRules.ts`: this is the part vitest covers.
 *
 * **The status is derived on the server**, once (`PayoutRegisterService.status`): the offer's
 * answer until a payout exists, then the payout's state — stored `PAID` arrives as `PROCESSING`
 * and `CONFIRMED` as `PAID`. Nothing here re-derives it.
 */

export type RegisterStatus =
  | 'OFFERED'
  | 'ACCEPTED'
  | 'DECLINED'
  | 'TIMED_OUT'
  | 'SUPERSEDED'
  | 'PENDING'
  | 'PROCESSING'
  | 'PAID'

export const REGISTER_STATUS_LABEL: Record<RegisterStatus, string> = {
  OFFERED: 'Offered',
  ACCEPTED: 'Accepted',
  DECLINED: 'Declined',
  TIMED_OUT: 'Timed out',
  SUPERSEDED: 'Superseded',
  PENDING: 'Pending',
  PROCESSING: 'Processing',
  PAID: 'Paid',
}

/** One case's fee and where it stands. `offerId` is null for a payout with no offer on record. */
export type RegisterRow = {
  offerId: string | null
  payoutId: string | null
  caseId: string
  caseCode: string | null
  expertId: string
  expertName: string | null
  amount: number | null
  currency: string | null
  status: RegisterStatus
  feeSetByName: string | null
  feeSetAt: string | null
  offeredAt: string | null
  dueDate: string | null
  sentAt: string | null
  confirmedAt: string | null
  done: boolean
}

export type RegisterFilter = {
  status?: RegisterStatus
  expertId?: string
  from?: string
  to?: string
  q?: string
}

export type ExpertTotals = {
  expertId: string
  expertName: string | null
  currency: string | null
  committed: number
  pending: number
  processing: number
  paid: number
  oldestPendingDue: string | null
}

export type Tile = { count: number; amount: number }

/** One currency's position; a GM across brands gets one per currency, never a sum across them. */
export type Overview = {
  currency: string
  committed: Tile
  pending: Tile
  processing: Tile
  paid: Tile
  attention: RegisterRow[]
}

export type LogEntry = { at: string; who: string; what: string; before: string | null; after: string | null }

export type OfferOutcome = 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'TIMED_OUT' | 'SUPERSEDED'

/** The case's current offer (`OfferFeeService.OfferView`). */
export type OfferView = {
  offerId: string
  expertId: string
  fee: number | null
  currency: string | null
  outcome: OfferOutcome
  feeSetByName: string | null
  feeSetAt: string | null
  log: LogEntry[]
}

/** Mirrors `OfferFeeService.MAY_SET_FEE`: who sees Edit on an open offer. The CM is absent on purpose. */
export const MAY_SET_FEE: readonly Role[] = ['GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'EXPERT_NETWORK_MANAGER']

/** Snapshot keys that are ids — the offer's own row already names the case and the expert. */
const HIDDEN_KEYS = new Set(['caseId', 'expertId', 'payoutIds'])

function parse(snapshot: string | null): Record<string, unknown> {
  if (!snapshot) return {}
  try {
    const value: unknown = JSON.parse(snapshot)
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function word(value: unknown): string {
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (typeof value === 'string') return /^[A-Z_]+$/.test(value) ? value.toLowerCase().replace(/_/g, ' ') : value
  return JSON.stringify(value)
}

/** One log line's detail: `key: old → new` for each key the after snapshot carries. */
export function describeChange(before: string | null, after: string | null): string {
  const b = parse(before)
  const a = parse(after)
  return Object.keys(a)
    .filter((key) => !HIDDEN_KEYS.has(key) && a[key] !== null && a[key] !== undefined)
    .map((key) =>
      key in b && b[key] !== null && b[key] !== a[key]
        ? `${key}: ${word(b[key])} → ${word(a[key])}`
        : `${key}: ${word(a[key])}`,
    )
    .join(' · ')
}

/** The filter as query params, empty values dropped so a shared URL stays readable. */
export function registerParams(filter: RegisterFilter): Record<string, string> {
  const params: Record<string, string> = {}
  for (const [key, value] of Object.entries(filter)) {
    if (typeof value === 'string' && value.trim()) params[key] = value.trim()
  }
  return params
}

/** The colour a status is drawn in. Answered-but-unpaid outcomes stay muted: nothing is owed. */
export const STATUS_TONE: Record<RegisterStatus, string> = {
  OFFERED: 'var(--text-muted)',
  ACCEPTED: 'var(--chart-4)',
  DECLINED: 'var(--text-muted)',
  TIMED_OUT: 'var(--text-muted)',
  SUPERSEDED: 'var(--text-muted)',
  PENDING: 'var(--status-amber)',
  PROCESSING: 'var(--accent-primary)',
  PAID: 'var(--status-green)',
}

/** Hands the browser a file to save — the same way `PayoutSummary` saves its exports. */
export function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

/** A calendar date for a table cell, or a dash. */
export function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}
