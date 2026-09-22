import { Popover } from 'radix-ui'
import type { ReactNode } from 'react'

/**
 * A popover of content: a raised card on the surface colour with the pop shadow.
 *
 * The behaviour is Radix's and is the part worth not writing — outside-click dismissal, focus
 * return to the trigger, and escape, none of which a hand-rolled panel gets right.
 */

const surfaceStyle = {
  background: 'var(--bg-surface)',
  border: '1px solid var(--border-default)',
  borderRadius: 'var(--radius-lg)',
  boxShadow: 'var(--shadow-pop)',
} as const

export const PopoverRoot = Popover.Root
export const PopoverTrigger = Popover.Trigger

export function PopoverContent({ children, label }: { children: ReactNode; label: string }) {
  return (
    <Popover.Portal>
      <Popover.Content
        aria-label={label}
        sideOffset={4}
        align="end"
        className="z-50 w-72 p-3 data-[state=closed]:animate-pop-out data-[state=open]:animate-pop-in"
        style={surfaceStyle}
      >
        {children}
      </Popover.Content>
    </Popover.Portal>
  )
}
