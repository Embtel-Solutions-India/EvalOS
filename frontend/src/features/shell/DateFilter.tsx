import { useFilters, type DateRange } from './filtersContext'

const RANGES: readonly { value: DateRange; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'Week' },
  { value: 'month', label: 'Month' },
  { value: 'year', label: 'Year' },
]

/**
 * The shell-wide period. Nothing consumes it yet — the dashboards that will are Unit
 * 17 — so this sets state and stops there, which is what the spec asks for.
 */
export default function DateFilter() {
  const { dateRange, setDateRange } = useFilters()

  return (
    <div
      className="flex h-9 items-center gap-0.5 p-1"
      style={{ background: 'var(--bg-surface)', borderRadius: 'var(--radius-xl)', boxShadow: 'var(--shadow-card)' }}
      role="group"
      aria-label="Date range"
    >
      {RANGES.map((range) => {
        const active = range.value === dateRange
        return (
          <button
            key={range.value}
            type="button"
            onClick={() => setDateRange(range.value)}
            aria-pressed={active}
            className="px-3 py-1 text-sm font-medium transition-colors"
            style={{
              borderRadius: 'var(--radius-xl)',
              background: active ? 'var(--accent-soft)' : 'transparent',
              color: active ? 'var(--accent-primary)' : 'var(--text-muted)',
            }}
          >
            {range.label}
          </button>
        )
      })}
    </div>
  )
}
