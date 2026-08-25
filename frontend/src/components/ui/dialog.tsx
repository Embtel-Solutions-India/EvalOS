import { Dialog as Primitive } from 'radix-ui'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'

/**
 * Modal dialog and side sheet, both over Radix's Dialog.
 *
 * **One file for two exports on purpose.** A sheet *is* a dialog — same primitive, same focus
 * trap, same escape and outside-click behaviour — positioned against an edge instead of the
 * centre. Splitting them would duplicate the overlay, the close button and the animation hooks
 * so that two files could each own a `className`.
 *
 * Which to use is a question about consequence, not about looks:
 * - `Dialog` for a decision — changing a deadline, reassigning ownership, delivering. It
 *   interrupts, and it should.
 * - `Sheet` for *inspecting* a record without losing your place in a queue — a case preview,
 *   the strategy notes, an activity trail.
 *
 * Animation is CSS keyed on Radix's `data-state`, so there is no animation library here; the
 * keyframes and the `prefers-reduced-motion` opt-out live in `index.css`.
 */

const OVERLAY =
  'fixed inset-0 z-40 bg-black/25 data-[state=open]:animate-overlay-in data-[state=closed]:animate-overlay-out'

/**
 * Radix's Root, which is uncontrolled by default and accepts `open` / `onOpenChange` when a
 * caller needs to drive it — a dialog opened from a row action rather than from its own trigger.
 */
export const DialogRoot = Primitive.Root
export const DialogTrigger = Primitive.Trigger
export const DialogClose = Primitive.Close

type ContentProps = {
  title: string
  /** Radix requires a description or an explicit opt-out; stating one is nearly always better. */
  description?: string
  children: ReactNode
  footer?: ReactNode
}

export function DialogContent({ title, description, children, footer }: ContentProps) {
  return (
    <Primitive.Portal>
      <Primitive.Overlay className={OVERLAY} />
      <Primitive.Content
        className="fixed top-1/2 left-1/2 z-50 max-h-[85vh] w-[min(32rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto p-6 data-[state=closed]:animate-dialog-out data-[state=open]:animate-dialog-in"
        style={{
          background: 'var(--bg-surface)',
          borderRadius: 'var(--radius-xl)',
          boxShadow: 'var(--shadow-pop)',
        }}
      >
        <Primitive.Title className="text-base font-semibold tracking-tight">{title}</Primitive.Title>
        {description ? (
          <Primitive.Description className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
            {description}
          </Primitive.Description>
        ) : (
          <Primitive.Description className="sr-only">{title}</Primitive.Description>
        )}

        <div className="mt-4">{children}</div>
        {footer && <div className="mt-6 flex justify-end gap-2">{footer}</div>}

        <Primitive.Close
          aria-label="Close"
          className="absolute top-5 right-5 rounded-md p-1"
          style={{ color: 'var(--text-muted)' }}
        >
          <X className="h-4 w-4" />
        </Primitive.Close>
      </Primitive.Content>
    </Primitive.Portal>
  )
}

export const SheetRoot = Primitive.Root
export const SheetTrigger = Primitive.Trigger
export const SheetClose = Primitive.Close

export function SheetContent({ title, description, children, footer }: ContentProps) {
  return (
    <Primitive.Portal>
      <Primitive.Overlay className={OVERLAY} />
      <Primitive.Content
        className="fixed inset-y-0 right-0 z-50 flex w-[min(30rem,100vw)] flex-col overflow-y-auto p-6 data-[state=closed]:animate-sheet-out data-[state=open]:animate-sheet-in"
        style={{
          background: 'var(--bg-surface)',
          borderLeft: '1px solid var(--border-default)',
          boxShadow: 'var(--shadow-pop)',
        }}
      >
        <Primitive.Title className="text-base font-semibold tracking-tight">{title}</Primitive.Title>
        {description ? (
          <Primitive.Description className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
            {description}
          </Primitive.Description>
        ) : (
          <Primitive.Description className="sr-only">{title}</Primitive.Description>
        )}

        <div className="mt-4 flex-1">{children}</div>
        {footer && <div className="mt-6 flex justify-end gap-2">{footer}</div>}

        <Primitive.Close
          aria-label="Close"
          className="absolute top-5 right-5 rounded-md p-1"
          style={{ color: 'var(--text-muted)' }}
        >
          <X className="h-4 w-4" />
        </Primitive.Close>
      </Primitive.Content>
    </Primitive.Portal>
  )
}
