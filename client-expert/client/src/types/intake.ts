// ---------------------------------------------------------------------------
// The client's request for a service: the catalog it is chosen from.
//
// A new service is one entry in constants/serviceCatalog.ts and no component
// changes. There is no questionnaire (Unit 55, 2026-09-25): the request is the
// service, the purpose and the documents; Sales asks the rest on the call.
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
  /** Ordered list of reusable document template ids required for this service. */
  documentTemplateIds: string[]
}
