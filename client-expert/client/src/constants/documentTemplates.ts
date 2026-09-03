import { DOCUMENT_ACCEPTED_EXTENSIONS, DEFAULT_MAX_FILE_SIZE_MB } from '@shared/constants/upload'
import type { DocumentTemplate } from '@/types/intake'

// Reusable document requirements, composed per service in
// serviceCatalog.ts — the same "add to the catalog, not the components"
// principle as question groups.

function doc(id: string, name: string, description: string, required: boolean): DocumentTemplate {
  return {
    id,
    name,
    description,
    required,
    acceptedTypes: DOCUMENT_ACCEPTED_EXTENSIONS,
    maxSizeMb: DEFAULT_MAX_FILE_SIZE_MB,
  }
}

export const DOCUMENT_TEMPLATES: Record<string, DocumentTemplate> = {
  passport: doc('passport', 'Passport / Government ID', 'A copy of your passport photo page or another accepted form of ID.', true),
  degreeCertificate: doc('degreeCertificate', 'Degree Certificate', 'Your official degree or diploma certificate.', true),
  transcript: doc('transcript', 'Academic Transcript', 'Your official transcript, issued by your institution.', true),
  resume: doc('resume', 'Resume / CV', 'Your current resume or curriculum vitae.', false),
  employmentDocuments: doc('employmentDocuments', 'Employment Documents', 'Offer letters, pay stubs, or employment verification letters.', false),
  supportingDocuments: doc('supportingDocuments', 'Supporting Documents', 'Anything else relevant to your request.', false),
  employmentVerificationLetters: doc(
    'employmentVerificationLetters',
    'Employment Verification Letters',
    'Letters confirming your job duties and dates of employment.',
    true,
  ),
  recommendationLetters: doc('recommendationLetters', 'Recommendation Letters', 'Letters from colleagues, supervisors, or industry experts.', false),
  sourceDocument: doc('sourceDocument', 'Original Document', 'The document you need translated.', true),
  rfeNotice: doc('rfeNotice', 'RFE Notice', 'The Request for Evidence notice you received from USCIS.', true),
  originalPetition: doc('originalPetition', 'Original Petition Documents', 'The petition package originally filed.', false),
  jobPostingDraft: doc('jobPostingDraft', 'Job Description / Posting Draft', 'The role you plan to recruit for.', true),
  businessFinancials: doc('businessFinancials', 'Business Financials', 'Financial statements, projections, or bank records, if available.', false),
  priorFilings: doc('priorFilings', 'Prior Filing Documents', 'Any previously filed petitions or applications relevant to this review.', false),
}

export function getDocumentTemplates(ids: string[]): DocumentTemplate[] {
  return ids.map((id) => DOCUMENT_TEMPLATES[id]).filter((doc): doc is DocumentTemplate => Boolean(doc))
}
