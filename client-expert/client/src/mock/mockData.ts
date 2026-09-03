import type {
  Application,
  ApplicationStatus,
  ApplicationTimelineStep,
  Conversation,
  Invoice,
  Message,
  Payment,
  Report,
  SupportTicket,
} from '@/types'

// Fictional demo data only — never real applicant information.

const TIMELINE_LABELS: Record<ApplicationStatus, string> = {
  submitted: 'Application Submitted',
  documents_received: 'Documents Received',
  documents_under_review: 'Documents Under Review',
  evaluation_in_progress: 'Evaluation In Progress',
  final_review: 'Final Review',
  completed: 'Evaluation Completed',
  report_ready: 'Report Ready',
}

const STATUS_ORDER: ApplicationStatus[] = [
  'submitted',
  'documents_received',
  'documents_under_review',
  'evaluation_in_progress',
  'final_review',
  'completed',
  'report_ready',
]

function buildTimeline(currentStatus: ApplicationStatus, startedAt: string): ApplicationTimelineStep[] {
  const currentIndex = STATUS_ORDER.indexOf(currentStatus)
  const start = new Date(startedAt).getTime()
  return STATUS_ORDER.map((key, index) => ({
    key,
    label: TIMELINE_LABELS[key],
    completedAt: index <= currentIndex ? new Date(start + index * 4 * 24 * 60 * 60 * 1000).toISOString() : undefined,
  }))
}

export const MOCK_APPLICATIONS: Application[] = [
  {
    id: 'app-001',
    referenceNumber: 'IE-2026-000001',
    evaluationType: 'course_by_course',
    destinationCountry: 'United States',
    status: 'evaluation_in_progress',
    createdAt: '2026-06-02T09:15:00.000Z',
    updatedAt: '2026-08-18T14:30:00.000Z',
    timeline: buildTimeline('evaluation_in_progress', '2026-06-02T09:15:00.000Z'),
  },
  {
    id: 'app-002',
    referenceNumber: 'IE-2026-000042',
    evaluationType: 'general',
    destinationCountry: 'Canada',
    status: 'report_ready',
    createdAt: '2026-03-11T11:00:00.000Z',
    updatedAt: '2026-05-02T10:00:00.000Z',
    timeline: buildTimeline('report_ready', '2026-03-11T11:00:00.000Z'),
  },
]

export const MOCK_PAYMENTS: Payment[] = [
  {
    id: 'pay-001',
    applicationId: 'app-001',
    applicationReference: 'IE-2026-000001',
    amount: 225,
    currency: 'USD',
    date: '2026-06-02T09:20:00.000Z',
    status: 'paid',
    invoiceId: 'inv-001',
  },
  {
    id: 'pay-002',
    applicationId: 'app-002',
    applicationReference: 'IE-2026-000042',
    amount: 150,
    currency: 'USD',
    date: '2026-03-11T11:05:00.000Z',
    status: 'paid',
    invoiceId: 'inv-002',
  },
  {
    id: 'pay-003',
    applicationId: 'app-001',
    applicationReference: 'IE-2026-000001',
    amount: 45,
    currency: 'USD',
    date: '2026-08-20T00:00:00.000Z',
    status: 'pending',
    invoiceId: 'inv-003',
  },
]

export const MOCK_INVOICES: Invoice[] = [
  {
    id: 'inv-001',
    invoiceNumber: 'INV-2026-1001',
    applicationId: 'app-001',
    applicationReference: 'IE-2026-000001',
    date: '2026-06-02T09:20:00.000Z',
    dueDate: '2026-06-09T00:00:00.000Z',
    amount: 225,
    currency: 'USD',
    status: 'paid',
  },
  {
    id: 'inv-002',
    invoiceNumber: 'INV-2026-1002',
    applicationId: 'app-002',
    applicationReference: 'IE-2026-000042',
    date: '2026-03-11T11:05:00.000Z',
    dueDate: '2026-03-18T00:00:00.000Z',
    amount: 150,
    currency: 'USD',
    status: 'paid',
  },
  {
    id: 'inv-003',
    invoiceNumber: 'INV-2026-1003',
    applicationId: 'app-001',
    applicationReference: 'IE-2026-000001',
    date: '2026-08-20T00:00:00.000Z',
    dueDate: '2026-08-27T00:00:00.000Z',
    amount: 45,
    currency: 'USD',
    status: 'unpaid',
  },
]

export const MOCK_REPORTS: Report[] = [
  {
    id: 'rep-001',
    reportNumber: 'IE-2026-000042',
    applicationId: 'app-002',
    applicationReference: 'IE-2026-000042',
    evaluationType: 'general',
    issueDate: '2026-05-02T10:00:00.000Z',
    status: 'ready',
  },
  {
    id: 'rep-002',
    reportNumber: 'IE-2026-000001',
    applicationId: 'app-001',
    applicationReference: 'IE-2026-000001',
    evaluationType: 'course_by_course',
    status: 'not_ready',
  },
]

export const MOCK_CONVERSATIONS: Conversation[] = [
  {
    id: 'conv-001',
    subject: 'Application #IE-2026-000001',
    applicationReference: 'IE-2026-000001',
    lastMessagePreview: 'Your documents are currently under review.',
    lastMessageAt: '2026-08-19T15:40:00.000Z',
    unreadCount: 1,
  },
  {
    id: 'conv-002',
    subject: 'General Support',
    lastMessagePreview: "Thanks for reaching out — happy to help.",
    lastMessageAt: '2026-08-10T12:05:00.000Z',
    unreadCount: 0,
  },
  {
    id: 'conv-003',
    subject: 'Payment Question',
    lastMessagePreview: 'Your receipt has been emailed to you.',
    lastMessageAt: '2026-07-28T09:12:00.000Z',
    unreadCount: 0,
  },
]

export const MOCK_MESSAGES: Record<string, Message[]> = {
  'conv-001': [
    {
      id: 'msg-001',
      conversationId: 'conv-001',
      sender: 'support',
      senderName: 'International Evaluations Support',
      body: 'Hello John, thank you for submitting your documents.',
      createdAt: '2026-06-05T09:00:00.000Z',
    },
    {
      id: 'msg-002',
      conversationId: 'conv-001',
      sender: 'support',
      senderName: 'International Evaluations Support',
      body: 'Your documents are currently under review.',
      createdAt: '2026-08-19T15:40:00.000Z',
    },
    {
      id: 'msg-003',
      conversationId: 'conv-001',
      sender: 'client',
      senderName: 'You',
      body: 'Thank you for the update.',
      createdAt: '2026-08-19T16:02:00.000Z',
    },
  ],
  'conv-002': [
    {
      id: 'msg-004',
      conversationId: 'conv-002',
      sender: 'client',
      senderName: 'You',
      body: 'Hi, I had a question about the evaluation timeline.',
      createdAt: '2026-07-10T08:00:00.000Z',
    },
    {
      id: 'msg-005',
      conversationId: 'conv-002',
      sender: 'support',
      senderName: 'International Evaluations Support',
      body: "Thanks for reaching out — happy to help.",
      createdAt: '2026-07-10T12:05:00.000Z',
    },
  ],
  'conv-003': [
    {
      id: 'msg-006',
      conversationId: 'conv-003',
      sender: 'support',
      senderName: 'International Evaluations Support',
      body: 'Your receipt has been emailed to you.',
      createdAt: '2026-07-28T09:12:00.000Z',
    },
  ],
}

export const MOCK_TICKETS: SupportTicket[] = [
  {
    id: 'tix-001',
    ticketNumber: 'TCK-10021',
    subject: 'Question about document requirements',
    applicationReference: 'IE-2026-000001',
    status: 'waiting_for_client',
    createdAt: '2026-08-15T09:00:00.000Z',
    updatedAt: '2026-08-19T10:30:00.000Z',
    description: 'I would like to confirm which translation is required for my transcript.',
  },
  {
    id: 'tix-002',
    ticketNumber: 'TCK-09988',
    subject: 'Invoice copy request',
    applicationReference: 'IE-2026-000042',
    status: 'resolved',
    createdAt: '2026-04-02T14:00:00.000Z',
    updatedAt: '2026-04-03T09:00:00.000Z',
    description: 'Could you resend a copy of my paid invoice for my records?',
  },
]
