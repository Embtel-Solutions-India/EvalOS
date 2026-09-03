import { Check, Circle } from 'lucide-react'
import { formatDateShort } from '@shared/utils/formatters'
import { cn } from '@shared/utils/cn'

export interface TimelineStep {
  key: string
  label: string
  completedAt?: string
  isCurrent?: boolean
}

interface TimelineProps {
  steps: TimelineStep[]
  className?: string
}

export function Timeline({ steps, className }: TimelineProps) {
  return (
    <ol className={cn('space-y-0', className)}>
      {steps.map((step, index) => {
        const isDone = Boolean(step.completedAt) && !step.isCurrent
        const isLast = index === steps.length - 1
        return (
          <li key={step.key} className="relative flex gap-4 pb-8 last:pb-0">
            {!isLast && (
              <span
                className={cn(
                  'absolute left-[13px] top-7 h-[calc(100%-1.25rem)] w-px',
                  isDone ? 'bg-success' : 'bg-border',
                )}
                aria-hidden="true"
              />
            )}
            <span
              className={cn(
                'relative z-10 flex h-7 w-7 shrink-0 items-center justify-center rounded-full border-2',
                isDone && 'border-success bg-success text-success-foreground',
                step.isCurrent && 'border-primary bg-primary text-primary-foreground',
                !isDone && !step.isCurrent && 'border-border bg-background text-muted-foreground',
              )}
            >
              {isDone ? <Check className="h-4 w-4" /> : <Circle className="h-2.5 w-2.5 fill-current" />}
            </span>
            <div className="pt-0.5">
              <p className={cn('text-sm font-medium', step.isCurrent ? 'text-primary' : 'text-foreground')}>
                {step.label}
              </p>
              {step.completedAt && (
                <p className="text-xs text-muted-foreground">{formatDateShort(step.completedAt)}</p>
              )}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
