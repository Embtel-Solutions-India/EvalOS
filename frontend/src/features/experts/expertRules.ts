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
  // Unit 33. The list above was drawn for credential-evaluation degree fields; these are the
  // disciplines an expert-opinion-letter roster recruits into. Mirrors `domain/FieldTag` and
  // V35's widened CHECK — all three move together.
  'AEROSPACE_ENGINEERING',
  'ARTIFICIAL_INTELLIGENCE',
  'BIOMEDICAL_ENGINEERING',
  'BIOTECHNOLOGY',
  'CYBERSECURITY',
  'ENVIRONMENTAL_ENGINEERING',
  'MATERIALS_SCIENCE',
  'NEUROSCIENCE',
  'PHARMACOLOGY',
  'RENEWABLE_ENERGY_ENGINEERING',
  'SOFTWARE_ENGINEERING',
] as const

export const LETTER_TYPES = [
  'CREDENTIAL_EVALUATION',
  'EXPERT_OPINION_LETTER',
  'RFE_RESPONSE',
  'PERM_LETTER',
  'TRANSLATION_CERTIFICATION',
  'RECOMMENDATION_LETTER',
  'WAGE_LEVEL_LETTER',
] as const

export const AVAILABILITIES = ['AVAILABLE', 'AT_CAPACITY', 'ON_LEAVE', 'INACTIVE'] as const

export const TIERS = ['TIER_1', 'TIER_2', 'TIER_3'] as const

/** Unit 33. Mirrors `domain/AffiliationType` and V35's `expert_affiliation_type_known`. */
export const AFFILIATION_TYPES = [
  'UNIVERSITY',
  'INDUSTRY',
  'NATIONAL_LAB',
  'GOVERNMENT',
  'INDEPENDENT',
] as const

/**
 * Unit 33. Mirrors `domain/VisaCategory`. **Not the same list as LETTER_TYPES** — a letter
 * type is what the expert signs, a visa category is the petition it supports.
 */
export const VISA_CATEGORIES = [
  'H1B',
  'EB1A',
  'EB2_NIW',
  'O1',
  'TN',
  'PERM',
  'L1A',
  'EDUCATION',
  'EMPLOYMENT',
  'ADMISSION',
  'OTHER',
] as const

export type AffiliationType = (typeof AFFILIATION_TYPES)[number]
export type VisaCategory = (typeof VISA_CATEGORIES)[number]

/**
 * Everything the roster sheet carries and the roster list deliberately does not show
 * (Unit 33): the standing an expert opinion letter rests on, and what an ENM actually
 * chooses between two experts on. Read on the profile and nowhere else.
 */
export type Dossier = {
  expertCode: string | null
  subSpecialization: string | null
  highestDegree: string | null
  degreeField: string | null
  degreeInstitution: string | null
  currentPosition: string | null
  affiliationType: AffiliationType | null
  country: string | null
  stateRegion: string | null
  yearsExperience: number | null
  linkedinUrl: string | null
  visaCategories: VisaCategory[]
  publications: number | null
  citations: number | null
  hIndex: number | null
  patents: number | null
  notableAwards: string | null
  professionalMemberships: string | null
  editorialRoles: string | null
  languages: string | null
  rushAvailable: boolean
  avgTurnaroundDays: number | null
}

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
  dossier: Dossier
  /**
   * Last approached, derived from the offer table rather than stored. **Null means never
   * offered a case, which is not dormancy** — render it as "never", never as a date.
   */
  lastActiveAt: string | null
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
  // Unit 33's dossier, flat on the form because that is the shape the API takes and the shape
  // a sheet column maps onto. It is grouped only on the way to the screen.
  expertCode: string | null
  subSpecialization: string | null
  highestDegree: string | null
  degreeField: string | null
  degreeInstitution: string | null
  currentPosition: string | null
  affiliationType: AffiliationType | null
  country: string | null
  stateRegion: string | null
  yearsExperience: number | null
  linkedinUrl: string | null
  visaCategories: VisaCategory[]
  publications: number | null
  citations: number | null
  hIndex: number | null
  patents: number | null
  notableAwards: string | null
  professionalMemberships: string | null
  editorialRoles: string | null
  languages: string | null
  rushAvailable: boolean
  avgTurnaroundDays: number | null
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
 * Honorifics, dropped before initials are taken. A roster of academics is mostly
 * `Dr.` and `Prof.`, and initialling the honorific gives half the network the same
 * two letters — `DS` for Dr. Smith and `DJ` for Dr. Jones is a column of D's.
 */
const HONORIFICS = new Set(['dr', 'prof', 'professor', 'mr', 'mrs', 'ms', 'mx', 'sir'])

/**
 * Two letters for the avatar placeholder: first and last word of the name, honorifics
 * dropped. `Dr. John Smith` -> `JS`, `Cher` -> `CH`, nothing on file -> an em dash.
 *
 * A placeholder and only ever a placeholder — the roster has no image column and is not
 * getting one, so this is what stands where a photo would. Deliberately *not* coloured per
 * expert: a hashed avatar colour is decoration, and the only palette it could draw from is
 * the RAG one, which means capacity here.
 */
export function initials(fullName: string | null): string {
  const words = (fullName ?? '')
    .trim()
    .split(/\s+/)
    .filter((word) => word && !HONORIFICS.has(word.toLowerCase().replace(/\./g, '')))
  if (words.length === 0) return '—'
  const first = words[0]
  const last = words[words.length - 1]
  return (words.length === 1 ? first.slice(0, 2) : first[0] + last[0]).toUpperCase()
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
  // Unit 33 — every dossier field is mappable, which is what lets a real roster sheet import
  // with no column left over.
  { field: 'expertCode', label: 'Expert ID' },
  { field: 'subSpecialization', label: 'Sub-specialisation' },
  { field: 'highestDegree', label: 'Highest degree' },
  { field: 'degreeField', label: 'Degree field' },
  { field: 'degreeInstitution', label: 'Degree institution' },
  { field: 'currentPosition', label: 'Current position' },
  { field: 'affiliationType', label: 'Affiliation type' },
  { field: 'country', label: 'Country' },
  { field: 'stateRegion', label: 'State / region' },
  { field: 'yearsExperience', label: 'Years of experience' },
  { field: 'linkedinUrl', label: 'LinkedIn' },
  { field: 'visaCategories', label: 'Visa categories supported' },
  { field: 'publications', label: 'Publications' },
  { field: 'citations', label: 'Citations' },
  { field: 'hIndex', label: 'h-index' },
  { field: 'patents', label: 'Patents' },
  { field: 'notableAwards', label: 'Notable awards' },
  { field: 'professionalMemberships', label: 'Professional memberships' },
  { field: 'editorialRoles', label: 'Editorial roles' },
  { field: 'languages', label: 'Languages' },
  { field: 'rushAvailable', label: 'Rush available' },
  { field: 'avgTurnaroundDays', label: 'Average turnaround (days)' },
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
  // Unit 33. The spellings a real roster sheet uses, so a 36-column workbook maps itself.
  { field: 'expertCode', hints: ['expertid', 'expertcode', 'evaluatorid'] },
  { field: 'subSpecialization', hints: ['subspecialization', 'subspecialisation', 'niche', 'subfield'] },
  { field: 'highestDegree', hints: ['highestdegree', 'degree', 'terminaldegree', 'qualification'] },
  { field: 'degreeField', hints: ['degreefield', 'fieldofdegree'] },
  { field: 'degreeInstitution', hints: ['degreeinstitution', 'almamater', 'degreeuniversity'] },
  { field: 'currentPosition', hints: ['currentposition', 'jobtitletoday'] },
  { field: 'affiliationType', hints: ['affiliationtype', 'employertype', 'sector'] },
  { field: 'country', hints: ['country', 'nation'] },
  { field: 'stateRegion', hints: ['stateregion', 'state', 'region', 'province'] },
  { field: 'yearsExperience', hints: ['yearsexperience', 'yearsofexperience', 'experience', 'years'] },
  { field: 'linkedinUrl', hints: ['linkedinurl', 'linkedin', 'profileurl'] },
  { field: 'visaCategories', hints: ['visacategoriessupported', 'visacategories', 'visas', 'petitions', 'visatypes'] },
  { field: 'publications', hints: ['publications', 'papers', 'pubs'] },
  { field: 'citations', hints: ['citations', 'citationcount'] },
  { field: 'hIndex', hints: ['hindex'] },
  { field: 'patents', hints: ['patents', 'patentcount'] },
  { field: 'notableAwards', hints: ['notableawards', 'awards', 'honours', 'honors'] },
  { field: 'professionalMemberships', hints: ['professionalmemberships', 'memberships', 'societies'] },
  { field: 'editorialRoles', hints: ['editorialroles', 'editorships', 'reviewerroles'] },
  { field: 'languages', hints: ['languages', 'language', 'spokenlanguages'] },
  { field: 'rushAvailable', hints: ['rushavailable', 'rush', 'rushcapable'] },
  { field: 'avgTurnaroundDays', hints: ['avgturnarounddays', 'turnaround', 'turnarounddays', 'avgturnaround'] },
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
