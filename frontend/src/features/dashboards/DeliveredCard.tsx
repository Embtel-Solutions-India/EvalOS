import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, Delta, type CardState } from '../../components/ui/card'
import type { OnTimeDelivery, ThroughputPoint } from './pmMetricsApi'

const label = (iso: string) =>
  new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })

// Thresholds, not taste: 90% and above is healthy, below 75% is a production problem.
const toneOf = (pct: number) =>
  pct >= 90 ? 'var(--status-green)' : pct >= 75 ? 'var(--status-amber)' : 'var(--status-red)'

/**
 * Delivered on time and delivered over time, one card (2026-10-14): the on-time rate as the headline, and under it
 * the cases delivered per day (up to a month) or per week, each bar split into on time and late. Both read the same
 * delivered cases over the header's period, so the headline's "of N delivered" is the chart's total.
 */
export function DeliveredCard({
  onTime,
  points,
  state,
  period,
  className,
  action,
}: {
  onTime: OnTimeDelivery | undefined
  points: ThroughputPoint[] | undefined
  state: CardState
  period: string
  /** Grid placement from the dashboard, e.g. spanning two rows beside smaller cards. */
  className?: string
  /** The chart's own period picker. */
  action?: React.ReactNode
}) {
  const data = (points ?? []).map((p) => ({ bucket: p.bucket, onTime: p.delivered - p.late, late: p.late }))
  return (
    <Card
      title="Delivered on time"
      wide
      className={className}
      // No `to` (2026-10-28): a linked card is one <Link>, and the period picker inside it would navigate on every
      // click. The link went to `/inbox?view=delivered`, which is not an inbox view and fell back to All open.
      action={action}
      note={`Delivered on or before the date the client was promised, ${period}.`}
      state={state}
    >
      {onTime && onTime.ratePct !== null && (
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <span
            className="font-num text-4xl leading-none font-bold tabular-nums"
            style={{ color: toneOf(onTime.ratePct) }}
          >
            {onTime.ratePct}%
          </span>
          <span className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
            of {onTime.delivered} delivered
          </span>
          {onTime.deltaPoints !== null && <Delta value={onTime.deltaPoints} better="up" unit=" pts" />}
        </div>
      )}
      <div className="mt-3 h-44 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data}>
            <CartesianGrid stroke="var(--border-default)" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={label} tick={{ fontSize: 12 }} />
            <YAxis allowDecimals={false} tick={{ fontSize: 12 }} width={28} />
            <Tooltip labelFormatter={(value) => label(String(value))} />
            <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
            <Bar dataKey="onTime" name="On time" stackId="d" fill="var(--status-green)" />
            <Bar dataKey="late" name="Late" stackId="d" fill="var(--status-red)" radius={[3, 3, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </Card>
  )
}
