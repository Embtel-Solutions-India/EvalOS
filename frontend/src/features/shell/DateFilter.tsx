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
      className="flex items-center gap-0.5 rounded-md p-0.5"
      style={{ background: 'var(--bg-raised)' }}
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
            className="rounded-md px-2.5 py-1 text-sm font-medium"
            style={{
              background: active ? 'var(--bg-surface)' : 'transparent',
              color: active ? 'var(--text-primary)' : 'var(--text-muted)',
            }}
          >
            {range.label}
          </button>
        )
      })}
    </div>
  )
}
