import { Check } from 'lucide-react'
import { cn } from '@shared/utils/cn'

export interface StepperStep {
  key: string
  label: string
}

interface StepperProps {
  steps: StepperStep[]
  currentKey: string
  completedKeys: string[]
  onStepClick?: (key: string) => void
  className?: string
}

export function Stepper({ steps, currentKey, completedKeys, onStepClick, className }: StepperProps) {
  const currentIndex = steps.findIndex((step) => step.key === currentKey)

  return (
    <nav aria-label="Onboarding progress" className={cn('w-full', className)}>
      <ol className="flex items-center gap-1 overflow-x-auto no-scrollbar sm:gap-2">
        {steps.map((step, index) => {
          const isCompleted = completedKeys.includes(step.key)
          const isCurrent = step.key === currentKey
          const isReachable = isCompleted || isCurrent || index <= currentIndex
          return (
            <li key={step.key} className="flex flex-1 items-center last:flex-none">
              <button
                type="button"
                disabled={!isReachable || !onStepClick}
                onClick={() => onStepClick?.(step.key)}
                aria-current={isCurrent ? 'step' : undefined}
                className={cn(
                  'flex shrink-0 items-center gap-2 rounded-full py-1.5 pl-1.5 pr-3 text-sm font-medium transition-colors disabled:cursor-default',
                  isCurrent && 'bg-primary/10 text-primary',
                  !isCurrent && isCompleted && 'text-foreground hover:bg-muted',
                  !isCurrent && !isCompleted && 'text-muted-foreground',
                )}
              >
                <span
                  className={cn(
                    'flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-semibold',
                    isCurrent && 'border-primary bg-primary text-primary-foreground',
                    !isCurrent && isCompleted && 'border-success bg-success text-success-foreground',
                    !isCurrent && !isCompleted && 'border-border bg-background text-muted-foreground',
                  )}
                >
                  {isCompleted && !isCurrent ? <Check className="h-3.5 w-3.5" /> : index + 1}
                </span>
                <span className="hidden sm:inline">{step.label}</span>
              </button>
              {index < steps.length - 1 && (
                <div className={cn('mx-1 hidden h-px flex-1 sm:block', isCompleted ? 'bg-success' : 'bg-border')} />
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
