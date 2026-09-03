// ---------------------------------------------------------------------------
// Core client portal data models.
// These types mirror the future REST API response shapes so the service
// layer (src/services) can swap mock implementations for real HTTP calls
// without changing consuming components.
//
// The guided intake / questionnaire model lives in src/types/intake.ts —
// this file covers account identity and the secondary portal pages
// (payments, invoices, reports, messages, tickets) that sit alongside it.
// ---------------------------------------------------------------------------

import type { ClientType } from './intake'

export interface User {
  id: string
  firstName: string
  lastName: string
  email: string
  phone?: string
  countryOfResidence?: string
  clientType?: ClientType
  preferredContactMethod?: 'email' | 'phone' | 'whatsapp'
  emailVerified: boolean
  profileCompleted: boolean
  createdAt: string
}

export type EvaluationType =
  | 'general'
  | 'course_by_course'
  | 'credential'
  | 'document'
  | 'professional'
  | 'custom'

export type EvaluationPurpose =
  | 'immigration'
  | 'employment'
  | 'education'
  | 'university_admission'
  | 'professional_licensing'
  | 'government'
  | 'other'

export interface UploadedFileMeta {
  fileName: string
  fileSizeBytes: number
  fileType: string
  uploadedAt: string
}

export type ApplicationStatus =
  | 'submitted'
  | 'documents_received'
  | 'documents_under_review'
  | 'evaluation_in_progress'
  | 'final_review'
  | 'completed'
  | 'report_ready'

export interface ApplicationTimelineStep {
  key: ApplicationStatus
  label: string
  completedAt?: string
}

export interface Application {
  id: string
  referenceNumber: string
  evaluationType: EvaluationType
  destinationCountry: string
  status: ApplicationStatus
  createdAt: string
  updatedAt: string
  timeline: ApplicationTimelineStep[]
}

export type PaymentStatus = 'pending' | 'paid' | 'failed' | 'refunded'

export interface Payment {
  id: string
  applicationId: string
  applicationReference: string
  amount: number
  currency: string
  date: string
  status: PaymentStatus
  invoiceId?: string
}

export type InvoiceStatus = 'unpaid' | 'paid' | 'overdue' | 'void'

export interface Invoice {
  id: string
  invoiceNumber: string
  applicationId: string
  applicationReference: string
  date: string
  dueDate: string
  amount: number
  currency: string
  status: InvoiceStatus
}

export type ReportStatus = 'not_ready' | 'ready'

export interface Report {
  id: string
  reportNumber: string
  applicationId: string
  applicationReference: string
  evaluationType: EvaluationType
  issueDate?: string
  status: ReportStatus
}

export type NotificationCategory =
  | 'application'
  | 'document'
  | 'payment'
  | 'report'
  | 'support'

export interface AppNotification {
  id: string
  category: NotificationCategory
  title: string
  message: string
  createdAt: string
  read: boolean
}

export interface MessageAttachment {
  fileName: string
  fileSizeBytes: number
}

export interface Message {
  id: string
  conversationId: string
  sender: 'client' | 'support'
  senderName: string
  body: string
  createdAt: string
  attachments?: MessageAttachment[]
}

export interface Conversation {
  id: string
  subject: string
  applicationReference?: string
  lastMessagePreview: string
  lastMessageAt: string
  unreadCount: number
}

export type TicketStatus = 'open' | 'in_progress' | 'waiting_for_client' | 'resolved' | 'closed'

export interface SupportTicket {
  id: string
  ticketNumber: string
  subject: string
  applicationReference?: string
  status: TicketStatus
  createdAt: string
  updatedAt: string
  description: string
}

export interface NotificationPreferences {
  emailNotifications: boolean
  applicationUpdates: boolean
  documentUpdates: boolean
  paymentNotifications: boolean
  reportNotifications: boolean
  supportNotifications: boolean
}

export interface ApiResult<T> {
  data: T
}
