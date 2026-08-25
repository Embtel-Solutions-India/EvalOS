import { Tabs as Primitive } from 'radix-ui'
import type { ReactNode } from 'react'

/**
 * Tabbed sections, over Radix's Tabs.
 *
 * Worth not hand-rolling for the keyboard contract alone: arrow keys move between tabs, Home and
 * End jump to the ends, and only the active tab is in the page's tab order — so a case detail
 * with five sections costs one Tab keypress to leave, not five.
 *
 * Panels are unmounted when inactive by default, which is what keeps a heavy tab (an activity
 * trail, a document list) from loading for somebody who never opens it.
 */

export const TabsRoot = Primitive.Root

export function TabsBar({ children, label }: { children: ReactNode; label: string }) {
  return (
    <Primitive.List
      aria-label={label}
      className="flex gap-1 overflow-x-auto"
      style={{ borderBottom: '1px solid var(--border-default)' }}
    >
      {children}
    </Primitive.List>
  )
}

export function Tab({ value, children }: { value: string; children: ReactNode }) {
  return (
    <Primitive.Trigger
      value={value}
      // -1px bottom margin so the active tab's underline sits on the list's border rather than
      // above it, which is what makes the pair read as one edge.
      className="-mb-px cursor-default border-b-2 border-transparent px-3 py-2 text-sm whitespace-nowrap data-[state=active]:border-[var(--accent-primary)] data-[state=active]:font-medium data-[state=active]:text-[var(--accent-primary)]"
      style={{ color: 'var(--text-muted)' }}
    >
      {children}
    </Primitive.Trigger>
  )
}

export function TabPanel({ value, children }: { value: string; children: ReactNode }) {
  return (
    <Primitive.Content value={value} className="pt-4 outline-none">
      {children}
    </Primitive.Content>
  )
}
