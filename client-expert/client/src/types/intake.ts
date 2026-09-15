// ---------------------------------------------------------------------------
// The client's guided intake: a schema-driven questionnaire engine.
//
// A Service points at a list of QuestionGroup ids and DocumentTemplate ids, so
// a new service is one entry in constants/serviceCatalog.ts and no component
// changes. NewRequest and QuestionField render whatever the catalog says.
//
// RECOVERED from f9f1165^ for Unit 43, and deliberately SHORTER than it was.
// Gone with the screens that used them:
//
//   AboutYouInfo / ClientType   — sign-up owns the client's details now, and
//                                 About You is not a funnel step any more.
//   RequestStatus / ClientRequest / RequestStatusEvent
//                               — the SPA holds no lifecycle enum (34d's D5).
//                                 Status is a word the server renders.
//   IntakeDraft                 — the draft is a `client_application` row on
//                                 the server, not localStorage on one browser.
//   IntakeDocument*             — the funnel does not upload. See NewRequest.
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
