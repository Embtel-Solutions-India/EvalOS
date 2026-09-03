import { CheckCircle2, CircleDot, Clock, TriangleAlert } from 'lucide-react'
import { Badge, type BadgeProps } from '@shared/components/ui/badge'
import { isOverdue } from '@/services/expertCaseService'
import type { ExpertCase } from '@/types/expert'

const STATUS_META: Record<ExpertCase['signingStatus'], { label: string; variant: NonNullable<BadgeProps['variant']>; icon: typeof CircleDot }> = {
  pending: { label: 'Pending', variant: 'muted', icon: CircleDot },
  viewed: { label: 'Viewed', variant: 'info', icon: Clock },
  signed: { label: 'Signed', variant: 'success', icon: CheckCircle2 },
}

export function SigningStatusBadge({ expertCase, className }: { expertCase: ExpertCase; className?: string }) {
  if (isOverdue(expertCase)) {
    return (
      <Badge variant="destructive" className={className}>
        <TriangleAlert className="h-3 w-3" />
        Overdue
      </Badge>
    )
  }
  const meta = STATUS_META[expertCase.signingStatus]
  const Icon = meta.icon
  return (
    <Badge variant={meta.variant} className={className}>
      <Icon className="h-3 w-3" />
      {meta.label}
    </Badge>
  )
}
