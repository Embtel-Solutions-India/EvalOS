import { LayoutGrid, List } from 'lucide-react'
import type { ViewMode } from './useViewMode'

/** Cards or list, as one segmented control. */
export default function ViewToggle({ mode, onChange }: { mode: ViewMode; onChange: (next: ViewMode) => void }) {
  const options = [
    { value: 'cards', label: 'Cards', Icon: LayoutGrid },
    { value: 'list', label: 'List', Icon: List },
  ] as const
  return (
    <div
      role="group"
      aria-label="View"
      className="inline-flex overflow-hidden rounded-lg border"
      style={{ borderColor: 'var(--border-default)' }}
    >
      {options.map(({ value, label, Icon }) => {
        const active = mode === value
        return (
          <button
            key={value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(value)}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 text-sm font-medium"
            style={{
              background: active ? 'var(--accent-soft)' : 'var(--bg-surface)',
              color: active ? 'var(--accent-primary)' : 'var(--text-muted)',
            }}
          >
            <Icon className="h-3.5 w-3.5" aria-hidden />
            {label}
          </button>
        )
      })}
    </div>
  )
}
