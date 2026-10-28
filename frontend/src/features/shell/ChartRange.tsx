import { useState } from 'react'
import type { DateRange } from './filtersContext'

/**
 * A chart's own period, as a from–to date pair and nothing else (2026-10-28). The date filter left the top bar, and
 * a chart offers only the custom range: it opens on this month (the 1st to today) and applies once both dates are
 * set and in order — a half-typed range never reaches the server.
 */
export default function ChartRange({
  range,
  onChange,
}: {
  range: DateRange
  onChange: (range: DateRange) => void
}) {
  const [draft, setDraft] = useState(() => (range.kind === 'custom' ? { from: range.from, to: range.to } : thisMonth()))

  function set(next: { from: string; to: string }) {
    setDraft(next)
    if (next.from && next.to && next.from <= next.to) onChange({ kind: 'custom', ...next })
  }

  return (
    <span className="inline-flex items-center gap-1.5 text-xs" role="group" aria-label="Chart period">
      <input
        type="date"
        className="field h-8 px-2 text-xs"
        aria-label="From"
        value={draft.from}
        max={draft.to || undefined}
        onChange={(e) => set({ ...draft, from: e.target.value })}
      />
      <span style={{ color: 'var(--text-muted)' }}>to</span>
      <input
        type="date"
        className="field h-8 px-2 text-xs"
        aria-label="To"
        value={draft.to}
        min={draft.from || undefined}
        onChange={(e) => set({ ...draft, to: e.target.value })}
      />
    </span>
  )
}

/** The 1st of this month to today, as local yyyy-MM-dd. */
function thisMonth(): { from: string; to: string } {
  const today = new Date()
  const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { from: iso(new Date(today.getFullYear(), today.getMonth(), 1)), to: iso(today) }
}
