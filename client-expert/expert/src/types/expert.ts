// The Expert Portal is a separate area of the app for outside experts who
// review and sign letters/evaluations for International Evaluations. It
// intentionally shares no account, session, or data model with the client
// portal — an expert only ever sees their own assigned cases and payments,
// never client production data, other experts' cases, or internal notes.

export interface ExpertUser {
  id: string
  firstName: string
  lastName: string
  email: string
  title: string
  expertiseAreas: string[]
  createdAt: string
}

export interface ExpertDocumentFile {
  fileName: string
  fileSizeBytes: number
  uploadedAt: string
}

export interface ExpertDocumentVersion extends ExpertDocumentFile {
  version: number
}

// pending: assigned, not yet opened. viewed: opened, awaiting the signed
// copy. signed: the expert has uploaded their signed copy back — a terminal
// state from the expert's side (there's no staff portal in this build to
// drive a further "accepted/completed" transition, so "signed" already
// means "returned to International Evaluations"). "Overdue" isn't a stored
// state — it's derived from dueDate whenever status is still pending/viewed.
export type SigningStatus = 'pending' | 'viewed' | 'signed'

export interface ExpertCase {
  id: string
  caseReference: string
  serviceType: string
  assignedDate: string
  dueDate: string
  signingStatus: SigningStatus
  draftDocument: ExpertDocumentVersion
  previousVersions: ExpertDocumentVersion[]
  signedDocument?: ExpertDocumentFile
  amount: number
  paymentStatus: 'pending' | 'paid'
  paymentDate?: string
}
