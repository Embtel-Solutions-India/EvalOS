import { CheckCircle2, CircleDot, Clock, TriangleAlert } from 'lucide-react'
import { Badge, type BadgeProps } from '@shared/components/ui/badge'
import type { IntakeDocumentStatus } from '@/types/intake'

const STATUS_META: Record<IntakeDocumentStatus, { label: string; variant: NonNullable<BadgeProps['variant']>; icon: typeof CheckCircle2 }> = {
  required: { label: 'Required', variant: 'muted', icon: CircleDot },
  uploaded: { label: 'Uploaded', variant: 'info', icon: Clock },
  processing: { label: 'Processing', variant: 'warning', icon: Clock },
  needs_attention: { label: 'Needs Attention', variant: 'destructive', icon: TriangleAlert },
  accepted: { label: 'Accepted', variant: 'success', icon: CheckCircle2 },
}

export function IntakeDocumentStatusBadge({ status, className }: { status: IntakeDocumentStatus; className?: string }) {
  const meta = STATUS_META[status]
  const Icon = meta.icon
  return (
    <Badge variant={meta.variant} className={className}>
      <Icon className="h-3 w-3" />
      {meta.label}
    </Badge>
  )
}
