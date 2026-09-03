// ---------------------------------------------------------------------------
// Client acquisition / guided intake data model.
//
// This is a schema-driven questionnaire engine: a Service points at a list
// of QuestionGroup ids and DocumentTemplate ids, so a new service can be
// added to the catalog (constants/serviceCatalog.ts) without touching any
// page or component — ChooseService, Questionnaire and IntakeDocuments all
// just render whatever the catalog says.
// ---------------------------------------------------------------------------

import type { LucideIcon } from 'lucide-react'

export type ServiceCategoryId = 'credential_evaluations' | 'expert_opinion_letters' | 'other_professional_services'

export interface ServiceCategory {
  id: ServiceCategoryId
  name: string
  description: string
}

// A generic, human-readable "what is this for" — only asked when a service
// doesn't already imply it (see ServiceDefinition.impliedPurpose).
export type RequestPurpose =
  | 'immigration'
  | 'employment'
  | 'education'
  | 'university_admission'
  | 'professional_licensing'
  | 'government'
  | 'other'

export interface ServiceDefinition {
  id: string
  categoryId: ServiceCategoryId
  name: string
  shortDescription: string
  bestFor?: string
  icon: LucideIcon
  /** If set, the Purpose step is skipped — this service already implies it. */
  impliedPurpose?: RequestPurpose
  /** Ordered list of reusable question group ids composed for this service. */
  questionGroupIds: string[]
  /** Ordered list of reusable document template ids required for this service. */
  documentTemplateIds: string[]
}

export type ClientType = 'individual' | 'employer' | 'attorney' | 'organization' | 'other'

export interface AboutYouInfo {
  fullName: string
  email: string
  phone: string
  countryOfResidence: string
  currentLocation?: string
  preferredContactMethod: 'email' | 'phone' | 'whatsapp'
  clientType: ClientType
}

export type QuestionType = 'text' | 'textarea' | 'select' | 'date' | 'radio' | 'number'

export interface QuestionOption {
  value: string
  label: string
}

export interface QuestionDefinition {
  id: string
  label: string
  type: QuestionType
  required: boolean
  placeholder?: string
  helpText?: string
  options?: QuestionOption[]
  /** Answers accumulated so far, keyed by question id across all groups. */
  showIf?: (answers: RequestAnswers) => boolean
}

export interface QuestionGroup {
  id: string
  title: string
  description: string
  questions: QuestionDefinition[]
  /** Whole group is skipped unless this returns true (e.g. "only if working with an attorney"). */
  showIf?: (answers: RequestAnswers) => boolean
}

export type RequestAnswers = Record<string, string>

// ---------------------------------------------------------------------------
// Documents
// ---------------------------------------------------------------------------

export type IntakeDocumentStatus = 'required' | 'uploaded' | 'processing' | 'needs_attention' | 'accepted'

export interface IntakeUploadedFile {
  fileName: string
  fileSizeBytes: number
  fileType: string
  uploadedAt: string
}

export interface DocumentTemplate {
  id: string
  name: string
  description: string
  required: boolean
  acceptedTypes: string[]
  maxSizeMb: number
}

export interface IntakeDocument extends DocumentTemplate {
  status: IntakeDocumentStatus
  file?: IntakeUploadedFile
}

// ---------------------------------------------------------------------------
// The request itself, and its simplified client-facing lifecycle
// ---------------------------------------------------------------------------

export type RequestStatus =
  | 'received'
  | 'information_review'
  | 'documents_review'
  | 'in_progress'
  | 'final_review'
  | 'completed'

export interface RequestStatusEvent {
  status: RequestStatus
  occurredAt: string
}

export interface ClientRequest {
  id: string
  referenceNumber: string
  serviceId: string
  purpose: RequestPurpose
  status: RequestStatus
  actionRequired?: string
  createdAt: string
  updatedAt: string
  history: RequestStatusEvent[]
  answers: RequestAnswers
  documents: IntakeDocument[]
}

// In-progress intake state persisted before a request is finalized — this
// is what survives across the public (pre-account) steps and account
// creation, per the "never lose progress" requirement.
export interface IntakeDraft {
  serviceId?: string
  purpose?: RequestPurpose
  aboutYou?: AboutYouInfo
  answers: RequestAnswers
  documents: IntakeDocument[]
}
