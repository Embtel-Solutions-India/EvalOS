import { useEffect, useRef } from 'react'
import { describeChange, type LogEntry } from './registerRules'

/**
 * One offer's money history, oldest first (Unit 65 rule 8): fee set and changed, the expert's
 * answer, payout opened, transfer recorded, receipt confirmed. Read-only — the log is append-only.
 *
 * A panel over the right edge rather than a page, so the register behind it keeps its filters and
 * scroll position. Escape and the close button both close it; focus moves into it on open.
 */
export default function OfferLogPanel({
  title,
  entries,
  failure,
  onClose,
}: {
  title: string
  entries: readonly LogEntry[] | null
  failure?: string | null
  onClose: () => void
}) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div aria-hidden className="fixed inset-0 z-30" style={{ background: 'rgb(0 0 0 / 0.18)' }} onClick={onClose} />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="fixed inset-y-0 right-0 z-40 flex w-full flex-col overflow-y-auto border-l sm:w-[26rem]"
        style={{ background: 'var(--bg-raised)', borderColor: 'var(--border-default)', boxShadow: 'var(--shadow-pop)' }}
      >
        <header
          className="flex items-start justify-between gap-3 border-b px-5 py-4"
          style={{ borderColor: 'var(--border-default)' }}
        >
          <div>
            <h2 className="text-base font-semibold tracking-tight">{title}</h2>
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              Every change to this case's fee and payment, oldest first
            </p>
          </div>
          <button ref={closeRef} type="button" onClick={onClose} className="btn" aria-label="Close history">
            Close
          </button>
        </header>

        <div className="px-5 py-4">
          {failure ? (
            <p className="text-sm" style={{ color: 'var(--status-red)' }}>{failure}</p>
          ) : entries === null ? (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>
          ) : entries.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Nothing has been recorded for this offer yet.</p>
          ) : (
            <ol className="relative flex flex-col gap-4 border-l pl-5" style={{ borderColor: 'var(--border-default)' }}>
              {entries.map((entry, i) => {
                const detail = describeChange(entry.before, entry.after)
                return (
                  <li key={i} className="relative">
                    <span
                      aria-hidden
                      className="absolute top-1.5 -left-[25px] h-2 w-2 rounded-full"
                      style={{ background: 'var(--accent-primary)' }}
                    />
                    <p className="text-sm font-medium">{entry.what}</p>
                    <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                      {entry.who}, {new Date(entry.at).toLocaleString()}
                    </p>
                    {detail && <p className="font-num mt-0.5 text-xs tabular-nums">{detail}</p>}
                  </li>
                )
              })}
            </ol>
          )}
        </div>
      </aside>
    </>
  )
}
