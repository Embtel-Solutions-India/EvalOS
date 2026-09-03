import type { ReactNode } from 'react'
import { Label } from '@shared/components/ui/label'
import { cn } from '@shared/utils/cn'

interface FormFieldProps {
  label: string
  htmlFor?: string
  required?: boolean
  error?: string
  hint?: string
  className?: string
  // Styles only the <Label> itself (e.g. a light color on a dark surface).
  // Kept separate from `className` because that applies to the whole
  // wrapper, and text color inherits down into the field's own input —
  // coloring the wrapper light would make typed text invisible against
  // the input's light background.
  labelClassName?: string
  children: ReactNode
}

export function FormField({ label, htmlFor, required, error, hint, className, labelClassName, children }: FormFieldProps) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label htmlFor={htmlFor} className={labelClassName}>
        {label}
        {required && <span className="ml-0.5 text-destructive">*</span>}
      </Label>
      {children}
      {hint && !error && <p className="text-xs text-muted-foreground">{hint}</p>}
      {error && (
        <p className="text-xs font-medium text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
