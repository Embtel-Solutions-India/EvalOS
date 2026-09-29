import { useEffect, useRef, useState } from 'react'
import { CalendarDays, Check, ChevronDown } from 'lucide-react'
import { cn } from '@shared/utils/cn'

/**
 * A small dropdown of named choices, shown as a button with the current label — the dashboard's
 * date range and the payouts trend's "Last 6 months". Closes on a pick, a click outside, or Escape.
 */
export function RangeMenu<K extends string>({
  value,
  options,
  onChange,
  label,
  calendar = false,
  ariaLabel,
}: {
  value: K
  options: { key: K; label: string }[]
  onChange(key: K): void
  /** What the button shows; defaults to the chosen option's label. */
  label?: string
  calendar?: boolean
  ariaLabel: string
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const outside = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex h-9 items-center gap-2 rounded-lg border bg-card px-3 text-sm font-medium text-foreground shadow-sm hover:bg-muted"
      >
        {calendar && <CalendarDays className="h-4 w-4 text-muted-foreground" />}
        <span className="whitespace-nowrap">{label ?? options.find((o) => o.key === value)?.label}</span>
        <ChevronDown className="h-4 w-4 text-muted-foreground" />
      </button>
      {open && (
        <ul role="listbox" aria-label={ariaLabel} className="absolute right-0 z-20 mt-1 min-w-full rounded-lg border bg-popover p-1 shadow-lg">
          {options.map((o) => (
            <li key={o.key}>
              <button
                type="button"
                role="option"
                aria-selected={o.key === value}
                onClick={() => {
                  onChange(o.key)
                  setOpen(false)
                }}
                className={cn(
                  'flex w-full items-center gap-2 whitespace-nowrap rounded-md px-3 py-1.5 text-left text-sm hover:bg-muted',
                  o.key === value ? 'font-semibold text-foreground' : 'text-muted-foreground',
                )}
              >
                <Check className={cn('h-3.5 w-3.5', o.key === value ? 'opacity-100' : 'opacity-0')} />
                {o.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
