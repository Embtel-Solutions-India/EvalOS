import { Bar, BarChart, Cell, LabelList, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

const ROW = 28

/** One horizontal bar per row, value labelled at the end. Colour per row, else the accent. */
export function HBars({ rows }: { rows: { label: string; value: number; color?: string }[] }) {
  return (
    <div style={{ height: rows.length * ROW + 8 }} className="w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} layout="vertical" margin={{ top: 0, right: 28, bottom: 0, left: 0 }}>
          <XAxis type="number" hide allowDecimals={false} />
          <YAxis type="category" dataKey="label" width={132} tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
          <Tooltip cursor={{ fill: 'var(--bg-raised)' }} />
          <Bar dataKey="value" name="Count" radius={4} barSize={14}>
            {rows.map((row) => (
              <Cell key={row.label} fill={row.color ?? 'var(--accent-primary)'} />
            ))}
            <LabelList dataKey="value" position="right" fontSize={12} />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

export type FieldBar = { label: string; available: number; atCapacity: number; inactive: number; gap: boolean }

/** The bench per field, stacked by availability. A field short of cover is labelled in red. */
export function AvailabilityBars({ rows }: { rows: FieldBar[] }) {
  return (
    <div style={{ height: rows.length * ROW + 40 }} className="w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} layout="vertical" margin={{ top: 0, right: 12, bottom: 0, left: 0 }}>
          <XAxis type="number" hide allowDecimals={false} />
          <YAxis
            type="category"
            dataKey="label"
            width={132}
            axisLine={false}
            tickLine={false}
            tick={({ x, y, payload }: { x?: string | number; y?: string | number; payload?: { value?: string } }) => (
              <text
                x={x}
                y={y}
                dy={4}
                textAnchor="end"
                fontSize={12}
                fill={rows.find((row) => row.label === payload?.value)?.gap ? 'var(--status-red)' : 'currentColor'}
              >
                {payload?.value}
              </text>
            )}
          />
          <Tooltip cursor={{ fill: 'var(--bg-raised)' }} />
          <Legend verticalAlign="bottom" height={24} iconType="circle" wrapperStyle={{ fontSize: 12 }} />
          <Bar dataKey="available" name="Available" stackId="bench" fill="var(--status-green)" barSize={14} />
          <Bar dataKey="atCapacity" name="At capacity" stackId="bench" fill="var(--status-amber)" barSize={14} />
          <Bar dataKey="inactive" name="Inactive" stackId="bench" fill="var(--text-muted)" barSize={14} radius={[0, 4, 4, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/** Pending / processing / paid as one bar, widths in proportion to the amounts. Empty when nothing is owed. */
export function PayoutBar({ pending, processing, paid }: { pending: number; processing: number; paid: number }) {
  const total = pending + processing + paid
  const parts = [
    ['Pending', pending, 'var(--status-amber)'],
    ['Processing', processing, 'var(--accent-primary)'],
    ['Paid', paid, 'var(--status-green)'],
  ] as const
  return (
    <div className="flex h-2.5 w-full overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
      {total > 0 &&
        parts.map(([label, amount, color]) => (
          <span key={label} title={`${label}: ${amount}`} style={{ width: `${(amount / total) * 100}%`, background: color }} />
        ))}
    </div>
  )
}
