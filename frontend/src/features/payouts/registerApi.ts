import { api, unwrap } from '../../lib/api'
import type { ExpertTotals, LogEntry, OfferView, Overview, RegisterFilter, RegisterRow } from './registerRules'
import { registerParams } from './registerRules'
import { rangeParams, type DateRange } from '../shell/filtersContext'

/** The shell's brand switcher, sent only when it names one; the server narrows within scope. */
function brand(brandId: string | null): Record<string, string> {
  return brandId ? { brandId } : {}
}

/**
 * The Payouts module's reads (Unit 65) and the offer-fee edit. EvalOS still moves no money: these
 * describe what the ENM recorded and what the expert agreed to.
 */

export async function fetchRegister(
  filter: RegisterFilter,
  brandId: string | null,
  signal?: AbortSignal,
): Promise<RegisterRow[]> {
  return unwrap<RegisterRow[]>(api.get('/payouts/cases', { params: { ...registerParams(filter), ...brand(brandId) }, signal }))
}

/** The register exactly as filtered, as the server's CSV. */
export async function exportRegister(filter: RegisterFilter, brandId: string | null): Promise<Blob> {
  const response = await api.get('/payouts/cases/export', {
    params: { ...registerParams(filter), ...brand(brandId) },
    responseType: 'blob',
  })
  return response.data as Blob
}

export async function fetchOfferHistory(offerId: string, signal?: AbortSignal): Promise<LogEntry[]> {
  return unwrap<LogEntry[]>(api.get(`/payouts/cases/${offerId}/history`, { signal }))
}

export async function fetchExpertTotals(brandId: string | null, signal?: AbortSignal): Promise<ExpertTotals[]> {
  return unwrap<ExpertTotals[]>(api.get('/payouts/experts', { params: brand(brandId), signal }))
}

/** The shell's period and brand, the same contract as the dashboards' metrics reads. */
export async function fetchOverview(range: DateRange, brandId: string | null, signal?: AbortSignal): Promise<Overview[]> {
  return unwrap<Overview[]>(api.get('/payouts/overview', { params: { ...rangeParams(range), ...brand(brandId) }, signal }))
}

/** The case's current offer, or null when nobody has been offered it yet. Every production role. */
export async function fetchCaseOffer(caseId: string, signal?: AbortSignal): Promise<OfferView | null> {
  return unwrap<OfferView | null>(api.get(`/cases/${caseId}/expert/offer`, { signal }))
}

/** Changes an open offer's fee (GM / PM / PC / ENM). 409 once the expert has answered. */
export async function editOfferFee(caseId: string, fee: number): Promise<OfferView> {
  return unwrap<OfferView>(api.patch(`/cases/${caseId}/expert/offer/fee`, { fee }))
}
