import type { ReactNode } from 'react'

/**
 * The plain card shell: one surface, one border, one shadow.
 *
 * **Not {@link Card} from `card.tsx`.** That one owns a state machine — loading skeleton, empty
 * note, error with retry — and is right for a tile whose whole job is to render one figure's
 * outcome. A panel holding a table or a form has its own states and does not want a second set
 * imposed on it from outside.
 */
export function Surface({ children }: { children: ReactNode }) {
  return (
    <section
      className="rounded-lg border p-5"
      style={{
        background: 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
        boxShadow: 'var(--shadow-card)',
      }}
    >
      {children}
    </section>
  )
}

/**
 * A {@link Surface} with a heading and an icon.
 *
 * **It lives here, and each panel's own component renders it, because a panel has to be able to
 * render nothing at all.** `DealApplication` and `DealDocuments` return null for a deal that did
 * not come through the portal — which is most of the board — and when the page owned the wrapper
 * instead, that null left a titled empty box behind. A heading with nothing under it is worse than
 * either outcome it was meant to distinguish: it reads as a panel that failed to load.
 */
export function Panel({
  title,
  icon,
  action,
  children,
}: {
  title: string
  icon: ReactNode
  /** Rendered at the far end of the heading row — an "Add note", an "Edit". */
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <Surface>
      <div className="mb-4 flex items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 text-sm font-medium">
          <span
            className="[&>svg]:h-4 [&>svg]:w-4"
            style={{ color: 'var(--text-muted)' }}
            aria-hidden
          >
            {icon}
          </span>
          {title}
        </h2>
        {action}
      </div>
      {children}
    </Surface>
  )
}
