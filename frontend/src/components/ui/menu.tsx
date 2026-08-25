import { DropdownMenu, Popover, Tooltip } from 'radix-ui'
import type { ReactNode } from 'react'

/**
 * The three transient overlays: a dropdown of actions, a popover of content, a tooltip of words.
 *
 * Grouped because they share one visual treatment — a raised card on the surface colour with the
 * pop shadow — and splitting them would mean maintaining that treatment in three places. Their
 * *behaviour* is genuinely different and comes from Radix, which is the part worth not writing:
 * dropdowns are keyboard-navigable menus with roving focus, popovers are non-menu content, and
 * tooltips never receive focus at all.
 *
 * **A tooltip is never the only route to a fact.** It is supplementary by definition — it cannot
 * be reached by touch and is skipped by some assistive technology — so anything essential is on
 * the page, and this exists for exact chart values and icon labels.
 */

const SURFACE = 'z-50 p-1 data-[state=closed]:animate-pop-out data-[state=open]:animate-pop-in'

const surfaceStyle = {
  background: 'var(--bg-surface)',
  border: '1px solid var(--border-default)',
  borderRadius: 'var(--radius-lg)',
  boxShadow: 'var(--shadow-pop)',
} as const

export const MenuRoot = DropdownMenu.Root
export const MenuTrigger = DropdownMenu.Trigger

export function MenuContent({ children, label }: { children: ReactNode; label: string }) {
  return (
    <DropdownMenu.Portal>
      <DropdownMenu.Content
        aria-label={label}
        sideOffset={4}
        align="end"
        className={`${SURFACE} min-w-44`}
        style={surfaceStyle}
      >
        {children}
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  )
}

export function MenuItem({
  children,
  onSelect,
  disabled,
}: {
  children: ReactNode
  onSelect: () => void
  disabled?: boolean
}) {
  return (
    <DropdownMenu.Item
      disabled={disabled}
      onSelect={onSelect}
      className="flex cursor-default items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none data-[disabled]:opacity-40 data-[highlighted]:bg-[var(--bg-raised)]"
    >
      {children}
    </DropdownMenu.Item>
  )
}

export const PopoverRoot = Popover.Root
export const PopoverTrigger = Popover.Trigger

export function PopoverContent({ children, label }: { children: ReactNode; label: string }) {
  return (
    <Popover.Portal>
      <Popover.Content
        aria-label={label}
        sideOffset={4}
        align="end"
        className={`${SURFACE} w-72 p-3`}
        style={surfaceStyle}
      >
        {children}
      </Popover.Content>
    </Popover.Portal>
  )
}

/**
 * Wrap the app once so every tooltip shares a delay. Without a provider Radix uses its own
 * default per tooltip, which is how a dashboard ends up with some tips instant and some slow.
 */
export const TooltipProvider = Tooltip.Provider

export function InfoTip({ children, label }: { children: ReactNode; label: string }) {
  return (
    <Tooltip.Root>
      <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
      <Tooltip.Portal>
        <Tooltip.Content
          sideOffset={4}
          className="z-50 max-w-56 px-2 py-1 text-xs data-[state=closed]:animate-pop-out data-[state=delayed-open]:animate-pop-in"
          style={{
            background: 'var(--text-primary)',
            color: 'var(--bg-surface)',
            borderRadius: 'var(--radius-md)',
          }}
        >
          {label}
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  )
}
