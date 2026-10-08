import type { CSSProperties, ReactNode } from 'react'
import { Area, AreaChart, CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ChevronRight } from 'lucide-react'
import { Card, type CardState } from '../../components/ui/card'
import { Avatar, Donut } from '../../components/ui/widgets'
import { formatCount, formatMoney } from '../../lib/money'
import type { Audience, Journey, JourneyDesk } from './journeyApi'
import { achievementPct, changePct, pulse, remaining, sum } from './journeyMath'

const MUTED: CSSProperties = { color: 'var(--text-muted)' }
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** Colour here means "which channel", so the compare view is capped at five and the rest stay in the list. */
export const SERIES = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)']

const compactMoney = (value: number) => (value >= 1000 ? `$${Math.round(value / 100) / 10}k` : formatMoney(value))

/** A signed, coloured change. Null renders nothing: "no previous figure" is not "no change". */
function Change({ pct, label }: { pct: number | null; label: string }) {
  if (pct === null) return null
  const color = pct > 0 ? 'var(--status-green)' : pct < 0 ? 'var(--status-red)' : 'var(--text-muted)'
  return (
    <span className="font-num text-xs font-medium tabular-nums" style={{ color }}>
      {pct > 0 ? '▲' : pct < 0 ? '▼' : '•'} {Math.abs(pct)}% <span style={MUTED}>{label}</span>
    </span>
  )
}

/**
 * The first screen's answer to "how are we performing?": four tiles, the Sketch's KPI row.
 * Won (or leads) this month with its change, the target with the percentage achieved, then the
 * year's leads and their combined value. Target and remaining ride on the target tile.
 */
export function KpiRow({ data, state, audience, canSetTarget, source }: { data: Journey | null; state: CardState; audience: Audience; canSetTarget: boolean; source: string | null }) {
  const money = audience === 'sales'
  const show = (n: number) => (money ? formatMoney(Math.round(n)) : formatCount(Math.round(n)))
  const monthName = data ? new Date(`${data.target.month}T00:00:00Z`).toLocaleString('en-US', { month: 'long', timeZone: 'UTC' }) : ''
  const progress = data?.target.progress ?? 0
  const target = data?.target.target ?? null
  const hasTarget = target !== null && target > 0
  const pct = achievementPct(progress, target)
  const { current, previous } = data ? pulse(data) : { current: undefined, previous: undefined }
  const was = previous ? (money ? previous.wonValue : previous.leads) : undefined
  const leads = data ? sum(data.months, (m) => m.leads) : 0
  const value = data ? sum(data.months, (m) => m.leadValue) : 0
  const year = data?.year ?? ''

  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Card variant="tile" title={`${money ? 'Won' : 'Leads'} · ${monthName || 'this month'}`} state={state}>
        <Figure>{show(progress)}</Figure>
        {current && <p className="mt-1.5"><Change pct={changePct(progress, was)} label="vs last month" /></p>}
      </Card>

      <Card variant="tile" title={`Target · ${monthName || 'this month'}`} state={state}>
        {hasTarget ? (
          <>
            <Figure>{show(target)}</Figure>
            <div
              className="mt-2 h-1.5 w-full overflow-hidden rounded-full"
              style={{ background: 'var(--bg-raised)' }}
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.min(pct ?? 0, 100)}
              aria-label={`${pct ?? 0}% of the ${monthName} target`}
            >
              <div className="h-full rounded-full" style={{ width: `${Math.min(pct ?? 0, 100)}%`, background: (pct ?? 0) >= 100 ? 'var(--status-green)' : 'var(--accent-primary)' }} />
            </div>
            <p className="font-num mt-1.5 text-xs tabular-nums" style={MUTED}>
              <span className="font-medium" style={{ color: 'var(--text-primary)' }}>{pct}%</span> achieved · {show(remaining(progress, target) ?? 0)} left
            </p>
          </>
        ) : (
          <>
            <Figure>Not set</Figure>
            <p className="mt-1.5 text-xs" style={MUTED}>{canSetTarget ? 'Set one under By desk on the main dashboard.' : `No target for ${monthName}.`}</p>
          </>
        )}
      </Card>

      <Card variant="tile" title={`Leads in ${year}${source ? ` · ${source}` : ''}`} state={state}>
        <Figure>{formatCount(leads)}</Figure>
        {current && (
          <p className="font-num mt-1.5 flex flex-wrap items-baseline gap-x-1.5 text-xs tabular-nums">
            <span className="font-medium">{formatCount(current.leads)}</span>
            <span style={MUTED}>this month</span>
            <Change pct={changePct(current.leads, previous?.leads)} label="" />
          </p>
        )}
      </Card>

      <Card variant="tile" title={`Lead value · ${year}`} state={state}>
        <Figure>{compactMoney(Math.round(value))}</Figure>
        {current && (
          <p className="font-num mt-1.5 flex flex-wrap items-baseline gap-x-1.5 text-xs tabular-nums">
            <span className="font-medium">{compactMoney(Math.round(current.leadValue))}</span>
            <span style={MUTED}>this month</span>
            <Change pct={changePct(current.leadValue, previous?.leadValue)} label="" />
          </p>
        )}
      </Card>
    </div>
  )
}

function Figure({ children }: { children: ReactNode }) {
  return <p className="font-num text-2xl font-semibold leading-none tracking-tight tabular-nums">{children}</p>
}

/** The yearly lead journey: one line for the filtered total, or one per channel when comparing. */
export function JourneyChart({
  data, state, metric, compare, thisMonth, controls,
}: { data: Journey | null; state: CardState; metric: 'leads' | 'value'; compare: boolean; thisMonth: number | null; controls?: ReactNode }) {
  const top = (data?.sources ?? []).slice(0, SERIES.length)
  const rows = (data?.months ?? []).map((m) => {
    // Months that have not happened are gaps, not a collapse to zero.
    const future = thisMonth !== null && m.month > thisMonth
    const row: Record<string, number | string | null> = { name: MONTHS[m.month - 1], total: future ? null : metric === 'leads' ? m.leads : m.leadValue }
    top.forEach((s) => { row[s.source] = future ? null : s.monthlyLeads[m.month - 1] })
    return row
  })
  const format = metric === 'leads' ? formatCount : (n: number) => formatMoney(Math.round(n))
  const common = { data: rows, margin: { top: 8, right: 8, bottom: 0, left: 0 } }
  const axes = (
    <>
      <CartesianGrid vertical={false} stroke="var(--border-default)" />
      <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'var(--text-muted)' }} />
      <YAxis width={44} allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'var(--text-muted)' }} tickFormatter={(n: number) => (metric === 'value' ? compactMoney(n) : String(n))} />
      <Tooltip formatter={(n) => format(Number(n))} contentStyle={{ borderRadius: 12, border: '1px solid var(--border-tint)', boxShadow: 'var(--shadow-pop)', fontSize: 12 }} />
      {thisMonth !== null && <ReferenceLine x={MONTHS[thisMonth - 1]} stroke="var(--border-default)" strokeDasharray="4 4" />}
    </>
  )
  const empty = data !== null && data.months.every((m) => m.leads === 0)
  const comparing = compare && metric === 'leads'

  return (
    <Card
      title="Yearly lead journey"
      action={controls ? <div className="flex flex-wrap items-center justify-end gap-2">{controls}</div> : undefined}
      state={state.kind === 'ok' && empty ? { kind: 'empty', note: `No leads were opened in ${data?.year} for this selection.` } : state}
    >
      <div className="h-64 w-full" role="img" aria-label={`Leads by month in ${data?.year}: ${rows.filter((r) => r.total !== null).map((r) => `${r.name} ${r.total}`).join(', ')}`}>
        <ResponsiveContainer width="100%" height="100%">
          {comparing ? (
            <LineChart {...common}>
              {axes}
              {top.map((s, i) => (
                <Line key={s.source} type="monotone" dataKey={s.source} stroke={SERIES[i]} strokeWidth={2} dot={false} />
              ))}
            </LineChart>
          ) : (
            <AreaChart {...common}>
              <defs>
                <linearGradient id="journeyFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--accent-primary)" stopOpacity={0.22} />
                  <stop offset="100%" stopColor="var(--accent-primary)" stopOpacity={0} />
                </linearGradient>
              </defs>
              {axes}
              <Area type="monotone" dataKey="total" name={metric === 'leads' ? 'Leads' : 'Lead value'} stroke="var(--accent-primary)" strokeWidth={2.5} fill="url(#journeyFill)" />
            </AreaChart>
          )}
        </ResponsiveContainer>
      </div>
      {comparing && (
        <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs">
          {top.map((s, i) => (
            <li key={s.source} className="flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-full" style={{ background: SERIES[i] }} aria-hidden />
              {s.source}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function Bar({ share, color = 'var(--accent-primary)' }: { share: number; color?: string }) {
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full" style={{ background: 'var(--bg-raised)' }} aria-hidden>
      <div className="h-full rounded-full" style={{ width: `${Math.max(share, 2)}%`, background: color }} />
    </div>
  )
}

/** Open deals by stage, in GHL's order. The ring shows share of open deals; the list keeps every stage with its count and value. */
export function StageCard({ data, state, to }: { data: Journey | null; state: CardState; to?: string }) {
  // Pipelines that share stage names (Lead, Warm, Hot, Cold, Lost) read as duplicates, so they add up by name, in first-seen order.
  const byName = new Map<string, { name: string; deals: number; value: number }>()
  for (const s of data?.stages ?? []) {
    const row = byName.get(s.name.toLowerCase()) ?? { name: s.name, deals: 0, value: 0 }
    row.deals += s.deals
    row.value += s.value
    byName.set(s.name.toLowerCase(), row)
  }
  const stages = [...byName.values()]
  const max = Math.max(1, ...stages.map((s) => s.deals))
  const total = stages.reduce((n, s) => n + s.deals, 0)
  const value = stages.reduce((n, s) => n + s.value, 0)
  // Five colours, then grey: more than five categorical hues stops being readable (tokens.css).
  const ranked = [...stages].sort((a, b) => b.deals - a.deals)
  const slices = [
    ...ranked.slice(0, SERIES.length).map((s, i) => ({ name: s.name, value: s.deals, color: SERIES[i] })),
    { name: 'Other stages', value: ranked.slice(SERIES.length).reduce((n, s) => n + s.deals, 0), color: 'var(--text-muted)' },
  ]
  return (
    <Card
      title="Pipeline by stage"
      state={state.kind === 'ok' && stages.length === 0 ? { kind: 'empty', note: 'Nothing is open on this pipeline right now.' } : state}
      to={to}
    >
      <div className="flex flex-wrap items-center gap-x-6 gap-y-4">
        <Donut slices={slices} centre={formatCount(total)} caption="open deals" />
        <div className="min-w-0 max-w-sm flex-1">
          <p className="text-sm" style={MUTED}>{formatMoney(Math.round(value))} open right now</p>
          <ul className="mt-2 space-y-1.5 text-sm">
            {slices.filter((s) => s.value > 0).map((s) => (
              <li key={s.name} className="flex items-center gap-2">
                <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: s.color }} aria-hidden />
                <span className="min-w-0 flex-1 truncate">{s.name}</span>
                <span className="font-num tabular-nums">{formatCount(s.value)}</span>
                <span className="font-num w-9 text-right text-xs tabular-nums" style={MUTED}>{total ? Math.round((s.value / total) * 100) : 0}%</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
      <ul className="mt-5 grid gap-x-8 gap-y-3 border-t pt-4 sm:grid-cols-2" style={{ borderColor: 'var(--border-default)' }}>
        {stages.map((s) => (
          <li key={s.name}>
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span className="truncate">{s.name}</span>
              <span className="font-num shrink-0 tabular-nums">
                {formatCount(s.deals)} <span className="ml-1 text-xs" style={MUTED}>{formatMoney(Math.round(s.value))}</span>
              </span>
            </div>
            <div className="mt-1"><Bar share={(s.deals / max) * 100} /></div>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** Where the year's leads came from. A row is also the source filter. */
export function SourceCard({ data, state, selected, onSelect, colored }: { data: Journey | null; state: CardState; selected: string | null; onSelect: (source: string | null) => void; /** Source colours key the compare chart; otherwise every bar is the one accent. */ colored: boolean }) {
  const sources = data?.sources ?? []
  const total = sources.reduce((n, s) => n + s.leads, 0)
  return (
    <Card
      title="Where leads come from"
      state={state.kind === 'ok' && sources.length === 0 ? { kind: 'empty', note: `No leads were opened in ${data?.year}.` } : state}
    >
      <ul className="max-h-80 space-y-1 overflow-y-auto pr-1">
        {sources.map((s, i) => {
          const active = selected?.toLowerCase() === s.source.toLowerCase()
          return (
            <li key={s.source}>
              <button
                type="button"
                onClick={() => onSelect(active ? null : s.source)}
                aria-pressed={active}
                className="block w-full rounded-md px-2 py-1.5 text-left"
                style={{ background: active ? 'var(--accent-soft)' : undefined }}
              >
                <span className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="min-w-0 flex-1 truncate" title={`${s.source} · ${formatMoney(Math.round(s.value))}`}>{s.source}</span>
                  <span className="font-num shrink-0 tabular-nums">
                    {formatCount(s.leads)} <span className="ml-1 text-xs" style={MUTED}>{total ? Math.round((s.leads / total) * 100) : 0}%</span>
                  </span>
                </span>
                <span className="mt-1 block"><Bar share={total ? (s.leads / total) * 100 : 0} color={colored ? (i < SERIES.length ? SERIES[i] : 'var(--text-muted)') : undefined} /></span>
              </button>
            </li>
          )
        })}
      </ul>
      <p className="mt-3 text-xs" style={MUTED}>Select a source to filter the whole page.</p>
    </Card>
  )
}

/** The GM's team view as the Sketch's member list: who is behind the numbers. A row opens the same dashboard scoped to that person. */
export function DeskCard({ desks, state, audience, onOpen }: { desks: JourneyDesk[]; state: CardState; audience: Audience; onOpen: (memberId: string) => void }) {
  const money = audience === 'sales'
  const rows = [...desks].sort((a, b) => (money ? b.wonValue - a.wonValue : b.leads - a.leads))
  return (
    <Card
      title={money ? 'Sales team' : 'Marketing team'}
      state={state.kind === 'ok' && rows.length === 0 ? { kind: 'empty', note: 'No active member has a pipeline assigned yet.' } : state}
    >
      <ul className="-m-2 divide-y divide-[color:var(--border-default)]">
        {rows.map((d) => (
          <li key={d.memberId}>
            <button type="button" onClick={() => onOpen(d.memberId)} className="flex w-full items-center gap-3 rounded-lg p-2 text-left hover:bg-[var(--bg-raised)]">
              <Avatar name={d.name} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{d.name}</span>
                <span className="font-num block text-xs tabular-nums" style={MUTED}>
                  {formatCount(d.leads)} leads{money ? ` · ${compactMoney(Math.round(d.wonValue))} won` : ''} · {formatCount(d.open)} open
                </span>
              </span>
              <ChevronRight className="h-4 w-4 shrink-0" style={MUTED} aria-hidden />
            </button>
          </li>
        ))}
      </ul>
    </Card>
  )
}
