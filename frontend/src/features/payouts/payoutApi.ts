import { api, unwrap } from '../../lib/api'
import type {
  BatchView,
  LedgerRow,
  PaymentDetailView,
  PaymentEditRequest,
  PaymentRow,
  PayoutStatus,
  SettleRequest,
} from './payoutRules'

/**
 * Everything the payout feature sends over the wire.
 *
 * **EvalOS moves no money.** Nothing here talks to a bank or a payment platform; `settle`
 * records that a transfer already happened outside the system, and its `reference` is
 * whatever the person who sent it wrote down.
 *
 * **There is no read of an expert's payment detail here, and there is no endpoint for one.**
 * The bank details an ENM would need to send the transfer live outside EvalOS entirely — the
 * column is write-only with no read path anywhere in the system, deliberately.
 */

/** The brand's rows, filterable. The only read that answers "this expert's pending drafts". */
export async function fetchPayouts(
  filters: { status?: PayoutStatus; expertId?: string; weekOf?: string; overdue?: boolean },
  signal?: AbortSignal,
): Promise<LedgerRow[]> {
  const params: Record<string, string | boolean> = {}
  if (filters.status) params.status = filters.status
  if (filters.expertId) params.expertId = filters.expertId
  if (filters.weekOf) params.weekOf = filters.weekOf
  if (filters.overdue) params.overdue = true
  return unwrap<LedgerRow[]>(api.get('/payouts', { params, signal }))
}

/** One week's pending drafts grouped by expert. The screen somebody works down on payout day. */
export async function fetchBatch(weekOf: string | null, signal?: AbortSignal): Promise<BatchView> {
  const params: Record<string, string> = {}
  if (weekOf) params.weekOf = weekOf
  return unwrap<BatchView>(api.get('/payouts/batch', { params, signal }))
}

/** Correct what a draft is worth, before anything settles it. Refused once it is paid. */
export async function correctAmount(payoutId: string, amount: number): Promise<LedgerRow> {
  return unwrap<LedgerRow>(api.patch(`/payouts/${payoutId}`, { amount }))
}

/**
 * Record one transfer against several drafts.
 *
 * The server refuses this unless `amount` is exactly the sum of the drafts named, they are all
 * pending, and they all belong to one expert in one currency. The dialog mirrors those checks
 * so the button can explain itself, but this is the gate.
 */
export async function settle(request: SettleRequest): Promise<{ paymentId: string }> {
  return unwrap<{ paymentId: string }>(api.post('/payouts/settle', request))
}

/** Payment history, newest first. Narrowed to one expert when given one. */
export async function fetchPayments(expertId: string | null, signal?: AbortSignal): Promise<PaymentRow[]> {
  const params: Record<string, string> = {}
  if (expertId) params.expertId = expertId
  return unwrap<PaymentRow[]>(api.get('/payments', { params, signal }))
}

/** One transfer and every draft it settled. */
export async function fetchPayment(paymentId: string, signal?: AbortSignal): Promise<PaymentDetailView> {
  return unwrap<PaymentDetailView>(api.get(`/payments/${paymentId}`, { signal }))
}

/** Correct how a transfer was described. Frozen once the expert has confirmed it. */
export async function editPayment(paymentId: string, edit: PaymentEditRequest): Promise<PaymentRow> {
  return unwrap<PaymentRow>(api.patch(`/payments/${paymentId}`, edit))
}

/** The expert acknowledged the transfer. Cascades to every draft it settled; terminal. */
export async function confirmPayment(paymentId: string): Promise<PaymentRow> {
  return unwrap<PaymentRow>(api.post(`/payments/${paymentId}/confirm`, {}))
}
