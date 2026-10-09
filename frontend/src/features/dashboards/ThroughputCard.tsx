import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, type CardState } from '../../components/ui/card'
import type { ThroughputPoint } from './pmMetricsApi'

const label = (iso: string) =>
  new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })

/** Cases delivered per day (up to a month) or per week, over the period the header names. */
export function ThroughputCard({ points, state, period, className }: { points?: ThroughputPoint[]; state: CardState; period: string; className?: string }) {
  return (
    <Card title="Delivered over time" note={`Cases delivered, ${period}.`} state={state} className={className}>
      <div className="h-56 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points ?? []}>
            <CartesianGrid stroke="var(--border-default)" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={label} tick={{ fontSize: 12 }} />
            <YAxis allowDecimals={false} tick={{ fontSize: 12 }} width={28} />
            <Tooltip labelFormatter={(value) => label(String(value))} formatter={(value) => [value, 'Delivered']} />
            <Line dataKey="delivered" stroke="var(--accent-primary)" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </Card>
  )
}
