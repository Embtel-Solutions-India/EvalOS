import { api, unwrap } from '../../lib/api'
import type {
  Availability,
  ExpertForm,
  ExpertProfile,
  FieldTag,
  ImportReport,
  RosterFilters,
  RosterPage,
  AvailabilityColumn,
} from './expertRules'
import type { ShortlistView } from './shortlistRules'

/**
 * Everything the expert database sends over the wire.
 *
 * **There is no read of the payment detail here, and there is no endpoint for one.** It is
 * written with `putPaymentDetail` and never read back — the roster and the profile carry
 * `paymentDetailOnFile` and nothing else about it.
 *
 * `brandId` is the shell's brand switcher. On reads it can only narrow. On the two writes
 * that create rows (a new expert, an import) it is the brand the row lands in, which a GM
 * has to name because they have no brand of their own — the server refuses anybody else who
 * names one that is not theirs.
 */

export async function fetchRoster(
  brandId: string | null,
  filters: RosterFilters,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<RosterPage> {
  const params: Record<string, string | number> = { page, size }
  if (brandId) params.brandId = brandId
  if (filters.search.trim()) params.search = filters.search.trim()
  if (filters.fieldTag) params.fieldTag = filters.fieldTag
  if (filters.letterType) params.letterType = filters.letterType
  if (filters.availability) params.availability = filters.availability
  if (filters.tier) params.tier = filters.tier
  return unwrap<RosterPage>(api.get('/experts/roster', { params, signal }))
}

export async function fetchAvailabilityBoard(
  brandId: string | null,
  signal?: AbortSignal,
): Promise<AvailabilityColumn[]> {
  const params = brandId ? { brandId } : {}
  return unwrap<AvailabilityColumn[]>(api.get('/experts/availability-board', { params, signal }))
}

export async function fetchExpert(id: string, signal?: AbortSignal): Promise<ExpertProfile> {
  return unwrap<ExpertProfile>(api.get(`/experts/${id}`, { signal }))
}

/**
 * The top-3 ranked experts for one case, with the per-factor breakdown.
 *
 * A read that sits *beside* the picker, never in front of it: `/experts` still lists everybody
 * available, and `assign-cm` does not care whether the expert it is given was on this list.
 *
 * `fieldTag` is required and is the PM's answer, not the case's — nothing stores which discipline
 * a case needs, because the PM who just read the documents is the only one who knows.
 */
export async function fetchShortlist(
  caseId: string,
  fieldTag: FieldTag,
  signal?: AbortSignal,
): Promise<ShortlistView> {
  return unwrap<ShortlistView>(
    api.get(`/cases/${caseId}/expert-shortlist`, { params: { fieldTag }, signal }),
  )
}

export async function createExpert(brandId: string | null, form: ExpertForm): Promise<ExpertProfile> {
  const params = brandId ? { brandId } : {}
  return unwrap<ExpertProfile>(api.post('/experts', form, { params }))
}

export async function updateExpert(id: string, form: ExpertForm): Promise<ExpertProfile> {
  return unwrap<ExpertProfile>(api.patch(`/experts/${id}`, form))
}

/** Its own call as well as a form field: it is the one change made from a list. */
export async function setAvailability(id: string, availability: Availability): Promise<ExpertProfile> {
  return unwrap<ExpertProfile>(api.patch(`/experts/${id}/availability`, { availability }))
}

/**
 * Write-only. The response is the refreshed profile, where the only trace of this call is
 * `paymentDetailOnFile` turning true — there is deliberately no GET counterpart, so an ENM
 * correcting an account number types the whole value again.
 */
export async function putPaymentDetail(id: string, paymentDetail: string): Promise<ExpertProfile> {
  return unwrap<ExpertProfile>(api.put(`/experts/${id}/payment-detail`, { paymentDetail }))
}

/**
 * The sheet and its column mapping, as one multipart request.
 *
 * The mapping goes as a JSON *part* rather than a form field, so Spring binds it as an
 * object instead of a string that something then has to parse. `Content-Type` is left to
 * the browser: it has to set the multipart boundary, and the shared axios instance's
 * `application/json` default would otherwise override it.
 */
async function upload(path: string, brandId: string | null, file: File, mapping: Record<string, string>) {
  const body = new FormData()
  body.append('file', file)
  body.append(
    'mapping',
    new Blob([JSON.stringify({ columns: mapping })], { type: 'application/json' }),
  )
  return unwrap<ImportReport>(
    api.post(path, body, {
      params: brandId ? { brandId } : {},
      headers: { 'Content-Type': undefined },
    }),
  )
}

/** Dry run: parses and checks every row, writes nothing. */
export async function validateSheet(
  brandId: string | null,
  file: File,
  mapping: Record<string, string>,
): Promise<ImportReport> {
  return upload('/experts/import/validate', brandId, file, mapping)
}

/**
 * The real import, all-or-nothing.
 *
 * Answers 200 even when the sheet was rejected — the report is the answer, and `imported`
 * is what says whether anything was written. Read that, not the status code.
 */
export async function importSheet(
  brandId: string | null,
  file: File,
  mapping: Record<string, string>,
): Promise<ImportReport> {
  return upload('/experts/import', brandId, file, mapping)
}
