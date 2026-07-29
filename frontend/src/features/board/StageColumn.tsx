import type { ReactNode } from 'react'

/**
 * One column or lane. Always rendered, even empty — a column that disappears when it has
 * no work makes the pipeline look shorter than it is.
 */
export default function StageColumn({
  label,
  count,
  readOnly = false,
  children,
}: {
  label: string
  count: number
  /** This role watches the stage rather than working it — labelled, so the missing
   *  actions read as intentional instead of broken. */
  readOnly?: boolean
  children: ReactNode
}) {
  return (
    <section
      className="flex w-72 shrink-0 flex-col rounded-lg border"
      style={{ background: 'var(--bg-raised)', borderColor: 'var(--border-default)' }}
    >
      <header
        className="flex items-baseline justify-between border-b px-3 py-2"
        style={{ borderColor: 'var(--border-default)' }}
      >
        <h2 className="text-sm font-semibold tracking-tight">{label}</h2>
        <span className="font-num flex items-baseline gap-1.5 text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {readOnly && <span title="You watch this stage; another role works it">status</span>}
          {count}
        </span>
      </header>

      <div className="flex min-h-24 flex-col gap-2 overflow-y-auto p-2">
        {count === 0 ? (
          <p className="px-1 py-3 text-xs" style={{ color: 'var(--text-muted)' }}>
            Nothing here.
          </p>
        ) : (
          children
        )}
      </div>
    </section>
  )
}
