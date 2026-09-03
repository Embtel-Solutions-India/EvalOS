import { STORAGE_KEYS } from '@shared/constants/storage'
import { getDocumentTemplates } from '@/constants/documentTemplates'
import { getService } from '@/constants/serviceCatalog'
import { mockDelay } from '@shared/mock/mockDelay'
import type {
  AboutYouInfo,
  ClientRequest,
  IntakeDocument,
  IntakeDraft,
  RequestAnswers,
  RequestPurpose,
  RequestStatus,
} from '@/types/intake'
import { readStorage, removeStorage, writeStorage } from '@shared/utils/storage'

// Draft state persisted across the public (pre-account) intake steps and
// account creation — nothing is lost when a visitor creates their account
// partway through.

const EMPTY_DRAFT: IntakeDraft = { answers: {}, documents: [] }

export function getDraft(): IntakeDraft {
  return readStorage<IntakeDraft>(STORAGE_KEYS.intakeDraft, EMPTY_DRAFT)
}

function saveDraft(draft: IntakeDraft): void {
  writeStorage(STORAGE_KEYS.intakeDraft, draft)
}

export function setDraftService(serviceId: string): void {
  saveDraft({ ...getDraft(), serviceId })
}

export function setDraftPurpose(purpose: RequestPurpose): void {
  saveDraft({ ...getDraft(), purpose })
}

export function setDraftAboutYou(aboutYou: AboutYouInfo): void {
  saveDraft({ ...getDraft(), aboutYou })
}

// Used when an already-onboarded client starts an additional request: their
// account already has this information, so the draft is seeded from it
// instead of asking them to fill out "About You" again.
export function seedAboutYouFromAccount(user: {
  firstName: string
  lastName: string
  email: string
  phone?: string
  countryOfResidence?: string
  preferredContactMethod?: AboutYouInfo['preferredContactMethod']
  clientType?: AboutYouInfo['clientType']
}): void {
  setDraftAboutYou({
    fullName: `${user.firstName} ${user.lastName}`.trim(),
    email: user.email,
    phone: user.phone ?? '',
    countryOfResidence: user.countryOfResidence ?? '',
    preferredContactMethod: user.preferredContactMethod ?? 'email',
    clientType: user.clientType ?? 'individual',
  })
}

export async function saveAnswers(answers: RequestAnswers): Promise<IntakeDraft> {
  await mockDelay(400)
  const draft = { ...getDraft(), answers: { ...getDraft().answers, ...answers } }
  saveDraft(draft)
  return draft
}

export function buildInitialDocuments(): IntakeDocument[] {
  const draft = getDraft()
  if (draft.documents.length > 0) return draft.documents
  const service = getService(draft.serviceId)
  if (!service) return []
  return getDocumentTemplates(service.documentTemplateIds).map((template) => ({
    ...template,
    status: 'required',
  }))
}

export async function saveDocuments(documents: IntakeDocument[]): Promise<IntakeDraft> {
  await mockDelay(300)
  const draft = { ...getDraft(), documents }
  saveDraft(draft)
  return draft
}

export function clearDraft(): void {
  removeStorage(STORAGE_KEYS.intakeDraft)
}

// ---------------------------------------------------------------------------
// Requests — created once the client submits from Review.
// ---------------------------------------------------------------------------

const STATUS_SEQUENCE: RequestStatus[] = [
  'received',
  'information_review',
  'documents_review',
  'in_progress',
  'final_review',
  'completed',
]

export const REQUEST_STATUS_LABELS: Record<RequestStatus, string> = {
  received: 'Request Received',
  information_review: 'Information Review',
  documents_review: 'Documents Review',
  in_progress: 'In Progress',
  final_review: 'Final Review',
  completed: 'Completed',
}

export const REQUEST_STATUS_DESCRIPTIONS: Record<RequestStatus, string> = {
  received: "We've received your request and will begin reviewing it shortly.",
  information_review: "Our team is reviewing the information you've provided.",
  documents_review: 'Our team is reviewing your uploaded documents.',
  in_progress: 'Your request is currently being worked on by our team.',
  final_review: 'Your request is in final review before completion.',
  completed: 'Your request is complete.',
}

function readRequests(): ClientRequest[] {
  return readStorage<ClientRequest[]>(STORAGE_KEYS.requests, [])
}

function writeRequests(requests: ClientRequest[]): void {
  writeStorage(STORAGE_KEYS.requests, requests)
}

function generateReferenceNumber(): string {
  const year = new Date().getFullYear()
  const digits = Math.floor(100000 + Math.random() * 900000)
  return `IE-${year}-${digits}`
}

export async function submitRequest(): Promise<ClientRequest> {
  await mockDelay(900)
  const draft = getDraft()
  if (!draft.serviceId || !draft.purpose) {
    throw new Error('Your request is missing required information.')
  }

  const now = new Date().toISOString()
  const request: ClientRequest = {
    id: `req-${Date.now()}`,
    referenceNumber: generateReferenceNumber(),
    serviceId: draft.serviceId,
    purpose: draft.purpose,
    status: 'received',
    createdAt: now,
    updatedAt: now,
    history: [{ status: 'received', occurredAt: now }],
    answers: draft.answers,
    documents: draft.documents,
  }

  writeRequests([request, ...readRequests()])
  clearDraft()
  return request
}

export async function listRequests(): Promise<ClientRequest[]> {
  await mockDelay()
  return readRequests()
}

export async function getRequest(id: string): Promise<ClientRequest | null> {
  await mockDelay()
  return readRequests().find((request) => request.id === id) ?? null
}

// Demo-only helper so a freshly submitted request doesn't sit at "Received"
// forever in a walkthrough — advances it one stage, simulating real
// progress. Never called automatically.
export function advanceRequestStatus(id: string): ClientRequest | null {
  const requests = readRequests()
  const index = requests.findIndex((request) => request.id === id)
  if (index === -1) return null
  const current = requests[index]
  const currentIndex = STATUS_SEQUENCE.indexOf(current.status)
  if (currentIndex === -1 || currentIndex === STATUS_SEQUENCE.length - 1) return current
  const nextStatus = STATUS_SEQUENCE[currentIndex + 1]
  const now = new Date().toISOString()
  const updated: ClientRequest = {
    ...current,
    status: nextStatus,
    updatedAt: now,
    history: [...current.history, { status: nextStatus, occurredAt: now }],
  }
  requests[index] = updated
  writeRequests(requests)
  return updated
}
