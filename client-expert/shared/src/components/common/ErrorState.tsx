import { AlertTriangle } from 'lucide-react'
import { Button } from '@shared/components/ui/button'
import { cn } from '@shared/utils/cn'

interface ErrorStateProps {
  title?: string
  description?: string
  onRetry?: () => void
  className?: string
}

export function ErrorState({
  title = 'Something went wrong.',
  description = "We couldn't load this page. Please try again.",
  onRetry,
  className,
}: ErrorStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-xl border border-destructive/20 bg-destructive/5 px-6 py-14 text-center',
        className,
      )}
      role="alert"
    >
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-destructive/10 text-destructive">
        <AlertTriangle className="h-6 w-6" />
      </div>
      <h3 className="text-base font-semibold text-foreground">{title}</h3>
      <p className="mt-1.5 max-w-sm text-sm text-muted-foreground">{description}</p>
      {onRetry && (
        <Button variant="outline" className="mt-5" onClick={onRetry}>
          Try Again
        </Button>
      )}
    </div>
  )
}
