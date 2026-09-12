import * as React from 'react'
import { cn } from '@shared/utils/cn'

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean
}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, invalid, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
          invalid && 'border-destructive focus-visible:ring-destructive',
          className,
        )}
        aria-invalid={invalid || undefined}
        ref={ref}
        {...props}
      />
    )
  },
)
Input.displayName = 'Input'

export { Input }
