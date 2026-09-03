import { CalendarDays } from 'lucide-react'
import * as React from 'react'
import { cn } from '@shared/utils/cn'

export interface DatePickerProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  invalid?: boolean
}

// A native date input styled to match the rest of the form kit. Using the
// platform's own date picker keeps this dependency-free while still giving
// every device (including mobile) its native, accessible date UI.
const DatePicker = React.forwardRef<HTMLInputElement, DatePickerProps>(
  ({ className, invalid, ...props }, ref) => {
    return (
      <div className="relative">
        <input
          type="date"
          className={cn(
            'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 pr-9 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 [&::-webkit-calendar-picker-indicator]:absolute [&::-webkit-calendar-picker-indicator]:inset-0 [&::-webkit-calendar-picker-indicator]:h-full [&::-webkit-calendar-picker-indicator]:w-full [&::-webkit-calendar-picker-indicator]:cursor-pointer [&::-webkit-calendar-picker-indicator]:opacity-0',
            invalid && 'border-destructive focus-visible:ring-destructive',
            className,
          )}
          aria-invalid={invalid || undefined}
          ref={ref}
          {...props}
        />
        <CalendarDays className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
      </div>
    )
  },
)
DatePicker.displayName = 'DatePicker'

export { DatePicker }
