import type { ChangeEvent, ReactNode } from 'react'
import { ChevronDown } from 'lucide-react'
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'

/**
 * The Sketch's small pieces, shared: the 26px pill filter that sits in a card header, a segmented
 * toggle, a donut, and an initials avatar. Presentational only — none of them knows what it shows.
 */

const PILL = 'h-[1.625rem] rounded-lg border px-3 text-xs font-medium'
const PILL_STYLE = { borderColor: 'var(--border-default)', background: 'var(--bg-surface)', color: 'var(--text-primary)' } as const

/** A native `<select>` drawn as the Sketch's "Current Week ▾" pill — native so keyboard and mobile pickers keep working. */
export function PillSelect({ label, value, onChange, children }: { label: string; value: string; onChange: (e: ChangeEvent<HTMLSelectElement>) => void; children: ReactNode }) {
  return (
    <label className="relative inline-flex items-center">
      <span className="sr-only">{label}</span>
      <select value={value} onChange={onChange} className={`${PILL} appearance-none pr-7`} style={PILL_STYLE}>
        {children}
      </select>
      <ChevronDown className="pointer-events-none absolute right-2 h-3.5 w-3.5" style={{ color: 'var(--text-muted)' }} aria-hidden />
    </label>
  )
}

export function PillToggle<T extends string>({ value, onChange, options, label }: { value: T; onChange: (value: T) => void; options: [T, string][]; label: string }) {
  return (
    <div className="inline-flex h-[1.625rem] items-center gap-0.5 rounded-lg border p-0.5" style={PILL_STYLE} role="group" aria-label={label}>
      {options.map(([key, text]) => (
        <button
          key={key}
          type="button"
          onClick={() => onChange(key)}
          aria-pressed={value === key}
          className="h-full rounded-md px-2.5 text-xs font-medium"
          style={value === key ? { background: 'var(--accent-primary)', color: '#fff' } : { color: 'var(--text-muted)' }}
        >
          {text}
        </button>
      ))}
    </div>
  )
}

/** A filter-style button with the same footprint as the pills; `on` tints it with the accent. */
export function PillButton({ on, ...rest }: { on?: boolean } & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type="button"
      aria-pressed={on}
      {...rest}
      className={`${PILL} disabled:opacity-50`}
      style={{ ...PILL_STYLE, background: on ? 'var(--accent-soft)' : 'var(--bg-surface)', borderColor: on ? 'var(--accent-primary)' : 'var(--border-default)' }}
    />
  )
}

export type Slice = { name: string; value: number; color: string }

/** The Sketch's ring: slices around a hole that carries the total. Drawn once, so the legend lives with the caller. */
export function Donut({ slices, centre, caption, size = 144 }: { slices: Slice[]; centre: string; caption: string; size?: number }) {
  const live = slices.filter((s) => s.value > 0)
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }} role="img" aria-label={`${caption}: ${live.map((s) => `${s.name} ${s.value}`).join(', ')}`}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={live.length ? live : [{ name: 'none', value: 1, color: 'var(--bg-raised)' }]} dataKey="value" innerRadius="68%" outerRadius="100%" paddingAngle={live.length > 1 ? 2 : 0} stroke="none" startAngle={90} endAngle={-270}>
            {(live.length ? live : [{ color: 'var(--bg-raised)' }]).map((s, i) => <Cell key={i} fill={s.color} />)}
          </Pie>
        </PieChart>
      </ResponsiveContainer>
      <div className="pointer-events-none absolute inset-0 grid place-items-center text-center">
        <div>
          <p className="font-num text-xl font-semibold leading-none tabular-nums">{centre}</p>
          <p className="mt-1 text-[0.6875rem]" style={{ color: 'var(--text-muted)' }}>{caption}</p>
        </div>
      </div>
    </div>
  )
}

/** Initials in a soft accent disc — the Sketch's member avatar, without needing a photo EvalOS does not hold. */
export function Avatar({ name }: { name: string }) {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  const letters = (parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : (parts[0] ?? '?').slice(0, 2)).toUpperCase()
  return (
    <span aria-hidden className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-xs font-semibold" style={{ background: 'var(--accent-soft)', color: 'var(--accent-primary)' }}>
      {letters}
    </span>
  )
}
