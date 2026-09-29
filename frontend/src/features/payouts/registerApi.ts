import { api, unwrap } from '../../lib/api'
import type { ExpertTotals, LogEntry, OfferView, Overview, RegisterFilter, RegisterRow } from './registerRules'
import { registerParams } from './registerRules'

/**
 * The Payouts module's reads (Unit 65) and the offer-fee edit. EvalOS still moves no money: these
 * describe what the ENM recorded and what the expert agreed to.
 */

export async function fetchRegister(filter: RegisterFilter, signal?: AbortSignal): Promise<RegisterRow[]> {
  return unwrap<RegisterRow[]>(api.get('/payouts/cases', { params: registerParams(filter), signal }))
}

/** The register exactly as filtered, as the server's CSV. */
export async function exportRegister(filter: RegisterFilter): Promise<Blob> {
  const response = await api.get('/payouts/cases/export', { params: registerParams(filter), responseType: 'blob' })
  return response.data as Blob
}

export async function fetchOfferHistory(offerId: string, signal?: AbortSignal): Promise<LogEntry[]> {
  return unwrap<LogEntry[]>(api.get(`/payouts/cases/${offerId}/history`, { signal }))
}

export async function fetchExpertTotals(signal?: AbortSignal): Promise<ExpertTotals[]> {
  return unwrap<ExpertTotals[]>(api.get('/payouts/experts', { signal }))
}

export async function fetchOverview(from: string | null, to: string | null, signal?: AbortSignal): Promise<Overview[]> {
  const params: Record<string, string> = {}
  if (from) params.from = from
  if (to) params.to = to
  return unwrap<Overview[]>(api.get('/payouts/overview', { params, signal }))
}

/** The case's current offer, or null when nobody has been offered it yet. Every production role. */
export async function fetchCaseOffer(caseId: string, signal?: AbortSignal): Promise<OfferView | null> {
  return unwrap<OfferView | null>(api.get(`/cases/${caseId}/expert/offer`, { signal }))
}

/** Changes an open offer's fee (GM / PM / PC / ENM). 409 once the expert has answered. */
export async function editOfferFee(caseId: string, fee: number): Promise<OfferView> {
  return unwrap<OfferView>(api.patch(`/cases/${caseId}/expert/offer/fee`, { fee }))
}
