import { useId, useState } from 'react'
import {
  useFilters,
  sameRange,
  type DateRange,
  type NamedRange,
} from './filtersContext'

/**
 * The periods that get their own button: the ones a reader picks constantly.
 *
 * Four, not three. `Today` is here rather than in the dropdown because it is the cheapest option
 * to keep and somebody uses it daily; the split is "picked often" versus "picked occasionally",
 * not "current" versus "past".
 */
const BUTTONS: readonly { value: NamedRange; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'This week' },
  { value: 'month', label: 'This month' },
  { value: 'year', label: 'This year' },
]

/** The occasional ones, behind a select so they cost no width until wanted. */
const MENU: readonly { value: NamedRange; label: string }[] = [
  { value: 'last-month', label: 'Last month' },
  { value: 'last-year', label: 'Last year' },
]

/**
 * The shell-wide period: four buttons, a dropdown for completed periods, and a date-to-date range.
 *
 * <p><strong>Read backwards by everything that consumes it.</strong> The production board used to
 * read this same value *forwards*, as a deadline cutoff, which is why it now owns its own control
 * — `last-month` cannot be a "due before" date, and a custom interval is not a cutoff at all.
 *
 * <p>Two native `<input type="date">` rather than a picker component, and a native `<select>`
 * rather than a menu: both are keyboard-accessible, localised and mobile-friendly for free, and
 * neither is worth a dependency here.
 */
export default function DateFilter() {
  const { dateRange, setDateRange } = useFilters()
  const fromId = useId()
  const toId = useId()

  // Open whenever a custom range is already active, so a reload or a shared state does not hide
  // the dates the figures are actually for.
  const [editing, setEditing] = useState(dateRange.kind === 'custom')
  // Held locally until BOTH edges are set. Committing on the first change would send an
  // `from`-only window the server rightly refuses, and would repaint the whole app mid-edit.
  const [draft, setDraft] = useState<{ from: string; to: string }>(
    dateRange.kind === 'custom' ? { from: dateRange.from, to: dateRange.to } : { from: '', to: '' },
  )

  const commit = (next: { from: string; to: string }) => {
    setDraft(next)
    if (next.from && next.to && next.from <= next.to) {
      setDateRange({ kind: 'custom', ...next })
    }
    // An incomplete or backwards draft simply is not committed — the previous period stays on
    // screen with its figures. Nothing is disabled and nothing shouts: the reader is mid-edit, and
    // an error message for a range they have not finished typing is noise.
  }

  const menuValue = MENU.some((option) => option.value === dateRange.kind)
    ? dateRange.kind
    : dateRange.kind === 'custom'
      ? 'custom'
      : ''

  return (
    // **No `flex-wrap`, deliberately.** `--header-height` is a stated contract in
    // `styles`: "The header stays 72px: enough for a 36px control row, and no more above the
    // fold." Wrapping would put the custom-range inputs on a second line, grow the bar past
    // 72px, and silently invalidate every sticky offset measured from it — the case detail
    // header sticks at exactly `var(--header-height)`. Overflowing sideways is recoverable
    // (the top bar's search is `min-w-0 flex-1` and gives up width first); growing taller is
    // not, because nothing below is told about it.
    <div className="flex items-center gap-2">
      <div
        className="flex h-9 items-center gap-0.5 p-1"
        style={{ background: 'var(--bg-surface)', borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-card)' }}
        role="group"
        aria-label="Date range"
      >
        {BUTTONS.map((option) => {
          const active = sameRange(dateRange, { kind: option.value })
          return (
            <button
              key={option.value}
              type="button"
              onClick={() => {
                setDateRange({ kind: option.value })
                setEditing(false)
              }}
              aria-pressed={active}
              className="px-3 py-1 text-sm font-medium transition-colors"
              style={{
                borderRadius: 'var(--radius-md)',
                background: active ? 'var(--accent-soft)' : 'transparent',
                color: active ? 'var(--accent-primary)' : 'var(--text-muted)',
              }}
            >
              {option.label}
            </button>
          )
        })}

        <select
          aria-label="More periods"
          value={menuValue}
          onChange={(event) => {
            const picked = event.target.value
            if (picked === 'custom') {
              setEditing(true)
              return
            }
            setEditing(false)
            if (picked) setDateRange({ kind: picked as NamedRange })
          }}
          className="h-7 px-2 text-sm font-medium"
          style={{
            borderRadius: 'var(--radius-md)',
            background: menuValue ? 'var(--accent-soft)' : 'transparent',
            color: menuValue ? 'var(--accent-primary)' : 'var(--text-muted)',
            border: 'none',
          }}
        >
          {/* The placeholder is not a period. Selecting it would have to mean *something*, and
              every candidate meaning is a period already on this control. */}
          <option value="">More…</option>
          {MENU.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
          <option value="custom">Custom range…</option>
        </select>
      </div>

      {editing && (
        <div
          className="flex h-9 items-center gap-2 px-2 text-sm"
          style={{ background: 'var(--bg-surface)', borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-card)' }}
        >
          <label htmlFor={fromId} style={{ color: 'var(--text-muted)' }}>
            From
          </label>
          <input
            id={fromId}
            type="date"
            value={draft.from}
            // `max` and `min` let the browser refuse a backwards range before it is committed,
            // which is the same rule the server enforces — stated in the control rather than only
            // discovered as a 400.
            max={draft.to || undefined}
            onChange={(event) => commit({ ...draft, from: event.target.value })}
            className="bg-transparent"
            style={{ color: 'var(--text-body)' }}
          />
          <label htmlFor={toId} style={{ color: 'var(--text-muted)' }}>
            to
          </label>
          <input
            id={toId}
            type="date"
            value={draft.to}
            min={draft.from || undefined}
            onChange={(event) => commit({ ...draft, to: event.target.value })}
            className="bg-transparent"
            style={{ color: 'var(--text-body)' }}
          />
        </div>
      )}
    </div>
  )
}

export type { DateRange }
