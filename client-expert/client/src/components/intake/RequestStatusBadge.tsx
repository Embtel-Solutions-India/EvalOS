import { Badge, type BadgeProps } from '@shared/components/ui/badge'
import { REQUEST_STATUS_LABELS } from '@/services/intakeService'
import type { RequestStatus } from '@/types/intake'

const STATUS_VARIANT: Record<RequestStatus, NonNullable<BadgeProps['variant']>> = {
  received: 'info',
  information_review: 'warning',
  documents_review: 'warning',
  in_progress: 'warning',
  final_review: 'warning',
  completed: 'success',
}

export function RequestStatusBadge({ status, className }: { status: RequestStatus; className?: string }) {
  return (
    <Badge variant={STATUS_VARIANT[status]} className={className}>
      {REQUEST_STATUS_LABELS[status]}
    </Badge>
  )
}
