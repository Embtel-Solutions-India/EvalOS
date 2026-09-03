import { CheckCircle2, CircleDot, CircleSlash, Clock, TriangleAlert } from 'lucide-react'
import { Badge, type BadgeProps } from '@shared/components/ui/badge'
import type {
  ApplicationStatus,
  InvoiceStatus,
  PaymentStatus,
  TicketStatus,
} from '@/types'

type StatusVariant = NonNullable<BadgeProps['variant']>

interface StatusMeta {
  label: string
  variant: StatusVariant
  icon: typeof CheckCircle2
}

const APPLICATION_STATUS_MAP: Record<ApplicationStatus, StatusMeta> = {
  submitted: { label: 'Application Submitted', variant: 'info', icon: CircleDot },
  documents_received: { label: 'Documents Received', variant: 'info', icon: CircleDot },
  documents_under_review: { label: 'Documents Under Review', variant: 'warning', icon: Clock },
  evaluation_in_progress: { label: 'Evaluation In Progress', variant: 'warning', icon: Clock },
  final_review: { label: 'Final Review', variant: 'warning', icon: Clock },
  completed: { label: 'Evaluation Completed', variant: 'success', icon: CheckCircle2 },
  report_ready: { label: 'Report Ready', variant: 'success', icon: CheckCircle2 },
}

const PAYMENT_STATUS_MAP: Record<PaymentStatus, StatusMeta> = {
  pending: { label: 'Pending', variant: 'warning', icon: Clock },
  paid: { label: 'Paid', variant: 'success', icon: CheckCircle2 },
  failed: { label: 'Failed', variant: 'destructive', icon: CircleSlash },
  refunded: { label: 'Refunded', variant: 'muted', icon: CircleDot },
}

const INVOICE_STATUS_MAP: Record<InvoiceStatus, StatusMeta> = {
  unpaid: { label: 'Unpaid', variant: 'warning', icon: Clock },
  paid: { label: 'Paid', variant: 'success', icon: CheckCircle2 },
  overdue: { label: 'Overdue', variant: 'destructive', icon: TriangleAlert },
  void: { label: 'Void', variant: 'muted', icon: CircleSlash },
}

const TICKET_STATUS_MAP: Record<TicketStatus, StatusMeta> = {
  open: { label: 'Open', variant: 'info', icon: CircleDot },
  in_progress: { label: 'In Progress', variant: 'warning', icon: Clock },
  waiting_for_client: { label: 'Waiting for Client', variant: 'warning', icon: TriangleAlert },
  resolved: { label: 'Resolved', variant: 'success', icon: CheckCircle2 },
  closed: { label: 'Closed', variant: 'muted', icon: CircleSlash },
}

function StatusBadgeRenderer({ meta, className }: { meta: StatusMeta; className?: string }) {
  const Icon = meta.icon
  return (
    <Badge variant={meta.variant} className={className}>
      <Icon className="h-3 w-3" />
      {meta.label}
    </Badge>
  )
}

export function ApplicationStatusBadge({ status, className }: { status: ApplicationStatus; className?: string }) {
  return <StatusBadgeRenderer meta={APPLICATION_STATUS_MAP[status]} className={className} />
}

export function PaymentStatusBadge({ status, className }: { status: PaymentStatus; className?: string }) {
  return <StatusBadgeRenderer meta={PAYMENT_STATUS_MAP[status]} className={className} />
}

export function InvoiceStatusBadge({ status, className }: { status: InvoiceStatus; className?: string }) {
  return <StatusBadgeRenderer meta={INVOICE_STATUS_MAP[status]} className={className} />
}

export function TicketStatusBadge({ status, className }: { status: TicketStatus; className?: string }) {
  return <StatusBadgeRenderer meta={TICKET_STATUS_MAP[status]} className={className} />
}
