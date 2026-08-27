/**
 * The expert roster's vocabulary and the pure helpers over it.
 *
 * The tag lists below mirror `domain/FieldTag` / `domain/LetterType` and V18's CHECK
 * constraints, the same way `boardRules.ts` mirrors the backend's transitions. They are
 * duplicated here on purpose: the vocabulary is closed, so the ENM has to *pick* from it
 * rather than type into it — a free-text input is how "mechanical engg" gets pasted out of
 * the old spreadsheet and rejected by the API. **Widening the vocabulary is a migration,
 * an enum edit and this list, together.**
 */

export type FieldTag = (typeof FIELD_TAGS)[number]
export type LetterType = (typeof LETTER_TYPES)[number]
export type Availability = (typeof AVAILABILITIES)[number]
export type ExpertTier = (typeof TIERS)[number]

export const FIELD_TAGS = [
  'MECHANICAL_ENGINEERING',
  'ELECTRICAL_ENGINEERING',
  'CIVIL_ENGINEERING',
  'CHEMICAL_ENGINEERING',
  'COMPUTER_SCIENCE',
  'INFORMATION_TECHNOLOGY',
  'DATA_SCIENCE',
  'BUSINESS_ADMINISTRATION',
  'FINANCE',
  'ACCOUNTING',
  'MARKETING',
  'ECONOMICS',
  'NURSING',
  'MEDICINE',
  'PHARMACY',
  'PUBLIC_HEALTH',
  'EDUCATION',
  'LAW',
  'ARCHITECTURE',
  'BIOLOGY',
  'CHEMISTRY',
  'PHYSICS',
  'MATHEMATICS',
  'PSYCHOLOGY',
  'FINE_ARTS',
  'HOSPITALITY_MANAGEMENT',
  'SUPPLY_CHAIN',
  'HUMAN_RESOURCES',
] as const

export const LETTER_TYPES = [
  'CREDENTIAL_EVALUATION',
  'EXPERT_OPINION_LETTER',
  'RFE_RESPONSE',
  'PERM_LETTER',
  'TRANSLATION_CERTIFICATION',
] as const

export const AVAILABILITIES = ['AVAILABLE', 'AT_CAPACITY', 'ON_LEAVE', 'INACTIVE'] as const

export const TIERS = ['TIER_1', 'TIER_2', 'TIER_3'] as const

export type RosterRow = {
  id: string
  brandId: string
  fullName: string | null
  title: string | null
  institution: string | null
  email: string | null
  phone: string | null
  primaryFields: FieldTag[]
  secondaryFields: FieldTag[]
  letterTypes: LetterType[]
  tier: ExpertTier | null
  availability: Availability | null
  qualityScore: number | null
  standardFee: number | null
  /** Derived from the cases, not `expert.current_active_count` — which is always 0. */
  activeLoad: number
  completedCases: number
  /** Whether a payment detail exists. The value itself is never sent to any client. */
  paymentDetailOnFile: boolean
  /**
   * What this expert is owed on pending payouts (Unit 16b).
   *
   * Derived from the ledger, never read from `expert.total_payments_pending` — that column
   * is `NOT NULL DEFAULT 0` and nothing has ever written it.
   */
  pendingTotal: number | null
}

export type RosterPage = { rows: RosterRow[]; page: number; size: number; total: number }

export type ExpertProfile = {
  expert: RosterRow
  notes: string | null
  recruitmentSource: string | null
  dateOnboarded: string | null
  avgResponseHours: number | null
  agreementStatus: string | null
  paymentStatus: string | null
  createdAt: string | null
}

export type AvailabilityColumn = { availability: Availability; count: number; experts: RosterRow[] }

/** What the create/edit form sends. Mirrors `ExpertService.ExpertForm`. */
export type ExpertForm = {
  fullName: string
  email: string | null
  phone: string | null
  title: string | null
  institution: string | null
  primaryFields: FieldTag[]
  secondaryFields: FieldTag[]
  letterTypes: LetterType[]
  tier: ExpertTier | null
  availability: Availability | null
  qualityScore: number | null
  standardFee: number | null
  recruitmentSource: string | null
  dateOnboarded: string | null
  notes: string | null
}

export type RowProblem = { row: number; column: string; reason: string }

export type ImportReport = {
  file: string
  rows: number
  created: number
  updated: number
  problems: RowProblem[]
  imported: boolean
}

export type RosterFilters = {
  search: string
  fieldTag: FieldTag | ''
  letterType: LetterType | ''
  availability: Availability | ''
  tier: ExpertTier | ''
}

export const NO_FILTERS: RosterFilters = {
  search: '',
  fieldTag: '',
  letterType: '',
  availability: '',
  tier: '',
}

/**
 * Whether anything is actually narrowing the roster.
 *
 * By value, not by identity. `filters === NO_FILTERS` was true only until the first
 * keystroke: every change makes a new object, so typing a character into the search box and
 * deleting it again left the screen saying "N experts matching" and, on an empty result,
 * "No expert matches those filters" — permanently, with no filter applied. A blank search
 * that is only spaces is no filter either, since the server trims it before searching.
 */
export function hasFilters(filters: RosterFilters): boolean {
  return Boolean(
    filters.search.trim() || filters.fieldTag || filters.letterType || filters.availability || filters.tier,
  )
}

/**
 * Acronyms the sentence-casing below would otherwise mangle. `RFE_RESPONSE` read as
 * "Rfe response", which is a term of art spelled wrong on the screen an ENM works in.
 */
const ACRONYMS: Record<string, string> = { rfe: 'RFE', perm: 'PERM', cv: 'CV' }

/** `MECHANICAL_ENGINEERING` → `Mechanical engineering`; `RFE_RESPONSE` → `RFE response`. */
export function label(value: string | null): string {
  if (!value) return '—'
  const words = value
    .replaceAll('_', ' ')
    .toLowerCase()
    .split(' ')
    .map((word, index) => ACRONYMS[word] ?? (index === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
  return words.join(' ')
}

/**
 * Availability drawn as capacity, which `ui-context.md` lists as a legitimate RAG use —
 * this is not decoration. `ON_LEAVE` and `INACTIVE` are deliberately *not* red: an expert
 * on sabbatical is not a problem, they are simply not available, and spending the red band
 * on them would leave nothing to say about a roster with nobody free.
 */
export const AVAILABILITY_TOKEN: Record<Availability, { fg: string; bg: string }> = {
  AVAILABLE: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  AT_CAPACITY: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  ON_LEAVE: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
  INACTIVE: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
}

/** The expert fields a sheet column may be mapped onto, in the order the mapper offers them. */
export const MAPPABLE_FIELDS: readonly { field: keyof ExpertForm; label: string }[] = [
  { field: 'fullName', label: 'Full name' },
  { field: 'email', label: 'Email' },
  { field: 'phone', label: 'Phone' },
  { field: 'title', label: 'Title' },
  { field: 'institution', label: 'Institution' },
  { field: 'primaryFields', label: 'Primary field tags' },
  { field: 'secondaryFields', label: 'Secondary field tags' },
  { field: 'letterTypes', label: 'Letter types' },
  { field: 'tier', label: 'Tier' },
  { field: 'availability', label: 'Availability' },
  { field: 'qualityScore', label: 'Quality score' },
  { field: 'standardFee', label: 'Standard fee' },
  { field: 'recruitmentSource', label: 'Recruitment source' },
  { field: 'dateOnboarded', label: 'Date onboarded' },
  { field: 'notes', label: 'Notes' },
]

/** Header spellings that mean a given field, matched after stripping everything but letters. */
const HEADER_HINTS: readonly { field: keyof ExpertForm; hints: readonly string[] }[] = [
  { field: 'fullName', hints: ['fullname', 'name', 'expert', 'expertname'] },
  { field: 'email', hints: ['email', 'emailaddress', 'mail'] },
  { field: 'phone', hints: ['phone', 'mobile', 'telephone', 'contactnumber'] },
  { field: 'title', hints: ['title', 'position', 'jobtitle', 'rank'] },
  { field: 'institution', hints: ['institution', 'university', 'employer', 'affiliation', 'college'] },
  { field: 'primaryFields', hints: ['primaryfields', 'primaryfield', 'fields', 'field', 'discipline', 'speciality', 'specialty'] },
  { field: 'secondaryFields', hints: ['secondaryfields', 'secondaryfield', 'otherfields'] },
  { field: 'letterTypes', hints: ['lettertypes', 'lettertype', 'letters', 'deliverables', 'signs'] },
  { field: 'tier', hints: ['tier', 'grade'] },
  { field: 'availability', hints: ['availability', 'available', 'status'] },
  { field: 'qualityScore', hints: ['qualityscore', 'quality', 'score', 'rating'] },
  { field: 'standardFee', hints: ['standardfee', 'fee', 'rate', 'cost', 'price'] },
  { field: 'recruitmentSource', hints: ['recruitmentsource', 'source', 'howfound'] },
  { field: 'dateOnboarded', hints: ['dateonboarded', 'onboarded', 'startdate', 'joined'] },
  { field: 'notes', hints: ['notes', 'note', 'comments', 'remarks'] },
]

/**
 * A first guess at the column mapping, so the ENM confirms fifteen dropdowns instead of
 * filling them in.
 *
 * A guess and nothing more: the mapping is shown, editable, and sent explicitly — the
 * server never infers it. Only exact hint matches count, because a wrong guess that looks
 * right is worse than a blank the user has to fill: it would silently import phone numbers
 * as titles. First header wins a field, so a sheet with both "Name" and "Expert Name" maps
 * one of them and leaves the other unmapped rather than fighting over it.
 */
export function guessMapping(headers: readonly string[]): Record<string, keyof ExpertForm> {
  const guessed: Record<string, keyof ExpertForm> = {}
  const taken = new Set<keyof ExpertForm>()
  for (const header of headers) {
    const normalized = header.toLowerCase().replace(/[^a-z]/g, '')
    if (!normalized) continue
    const match = HEADER_HINTS.find(
      (candidate) => !taken.has(candidate.field) && candidate.hints.includes(normalized),
    )
    if (match) {
      guessed[header] = match.field
      taken.add(match.field)
    }
  }
  return guessed
}

/**
 * The header row of a CSV, read in the browser so the mapping screen can be filled in
 * before anything is uploaded.
 *
 * Split on the first line and on commas only. This is not a CSV parser and does not try to
 * be one — a quoted header containing a comma comes out wrong here, and the consequence is
 * a dropdown the ENM sets by hand. The real parse happens on the server, which is where a
 * mismatch is reported.
 */
export function csvHeaders(firstLine: string): string[] {
  return firstLine
    .replace(/^﻿/, '')
    .split(',')
    .map((header) => header.trim().replace(/^"|"$/g, ''))
    .filter((header) => header.length > 0)
}
