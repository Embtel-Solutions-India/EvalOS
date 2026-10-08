import type { CSSProperties, ReactNode } from 'react'
import { Area, AreaChart, CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, type CardState } from '../../components/ui/card'
import { formatCount, formatMoney } from '../../lib/money'
import type { Audience, Journey, JourneyDesk } from './journeyApi'
import { achievementPct, changePct, pulse, remaining, sum } from './journeyMath'

const MUTED: CSSProperties = { color: 'var(--text-muted)' }
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** Colour here means "which channel", so the compare view is capped at five and the rest stay in the list. */
export const SERIES = ['var(--accent-primary)', '#0d9488', '#d97706', '#7c3aed', '#64748b']

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

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <dt className="text-xs" style={MUTED}>{label}</dt>
      <dd className="font-num mt-0.5 text-lg font-semibold tabular-nums">{value}</dd>
    </div>
  )
}

/** Sales vs target (won value) or Marketing's leads vs target — the question the page opens with. */
export function TargetCard({ data, state, audience, canSetTarget }: { data: Journey | null; state: CardState; audience: Audience; canSetTarget: boolean }) {
  const money = audience === 'sales'
  const show = (n: number) => (money ? formatMoney(Math.round(n)) : formatCount(Math.round(n)))
  const monthName = data ? new Date(`${data.target.month}T00:00:00Z`).toLocaleString('en-US', { month: 'long', timeZone: 'UTC' }) : ''
  const progress = data?.target.progress ?? 0
  const target = data?.target.target ?? null
  const pct = achievementPct(progress, target)
  const { current, previous } = data ? pulse(data) : { current: undefined, previous: undefined }
  const was = previous ? (money ? previous.wonValue : previous.leads) : undefined

  return (
    <Card title={`${money ? 'Sales' : 'Leads'} vs target${monthName ? ` · ${monthName}` : ''}`} state={state}>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="font-num text-4xl font-semibold leading-none tracking-tight tabular-nums">{show(progress)}</span>
        <span className="text-sm" style={MUTED}>{money ? 'won this month' : 'leads this month'}</span>
        {current && <Change pct={changePct(progress, was)} label="vs last month" />}
      </div>

      {target === null ? (
        <p className="mt-5 text-sm" style={MUTED}>
          No target is set for {monthName}.{canSetTarget && ' Set one under By desk on the main dashboard.'}
        </p>
      ) : (
        <>
          <div
            className="mt-5 h-2.5 w-full overflow-hidden rounded-full"
            style={{ background: 'var(--bg-raised)' }}
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.min(pct ?? 0, 100)}
            aria-label={`${pct ?? 0}% of the ${monthName} target`}
          >
            <div
              className="h-full rounded-full"
              style={{ width: `${Math.min(pct ?? 0, 100)}%`, background: (pct ?? 0) >= 100 ? 'var(--status-green)' : 'var(--accent-primary)' }}
            />
          </div>
          <dl className="mt-4 grid grid-cols-3 gap-4">
            <Stat label="Target" value={show(target)} />
            <Stat label="Remaining" value={show(remaining(progress, target) ?? 0)} />
            <Stat label="Achieved" value={`${pct}%`} />
          </dl>
        </>
      )}
    </Card>
  )
}

/** Total leads and their value for the year, with this month's pulse underneath. */
export function LeadsCard({ data, state, source }: { data: Journey | null; state: CardState; source: string | null }) {
  const leads = data ? sum(data.months, (m) => m.leads) : 0
  const value = data ? sum(data.months, (m) => m.leadValue) : 0
  const { current, previous } = data ? pulse(data) : { current: undefined, previous: undefined }
  return (
    <Card title={`Leads${data ? ` in ${data.year}` : ''}${source ? ` · ${source}` : ''}`} state={state}>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <p className="font-num text-4xl font-semibold leading-none tracking-tight tabular-nums">{formatCount(leads)}</p>
          <p className="mt-1.5 text-sm" style={MUTED}>leads opened</p>
        </div>
        <div>
          <p className="font-num text-4xl font-semibold leading-none tracking-tight tabular-nums">{compactMoney(Math.round(value))}</p>
          <p className="mt-1.5 text-sm" style={MUTED}>combined value</p>
        </div>
      </div>
      {current && (
        <p className="mt-5 flex flex-wrap items-baseline gap-x-2 text-sm">
          <span className="font-num font-medium tabular-nums">{formatCount(current.leads)}</span>
          <span style={MUTED}>this month</span>
          <Change pct={changePct(current.leads, previous?.leads)} label="vs last month" />
        </p>
      )}
    </Card>
  )
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
      <Tooltip formatter={(n) => format(Number(n))} contentStyle={{ borderRadius: 8, border: '1px solid var(--border-default)', fontSize: 12 }} />
      {thisMonth !== null && <ReferenceLine x={MONTHS[thisMonth - 1]} stroke="var(--border-default)" strokeDasharray="4 4" />}
    </>
  )
  const empty = data !== null && data.months.every((m) => m.leads === 0)
  const comparing = compare && metric === 'leads'

  return (
    <Card
      title="Yearly lead journey"
      state={state.kind === 'ok' && empty ? { kind: 'empty', note: `No leads were opened in ${data?.year} for this selection.` } : state}
    >
      {controls && <div className="mb-3 flex flex-wrap items-center justify-end gap-2">{controls}</div>}
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

/** Open deals by stage, in GHL's order — bar length is the count, the figures carry the value. */
export function StageCard({ data, state, to }: { data: Journey | null; state: CardState; to?: string }) {
  const stages = data?.stages ?? []
  const max = Math.max(1, ...stages.map((s) => s.deals))
  const total = stages.reduce((n, s) => n + s.deals, 0)
  const value = stages.reduce((n, s) => n + s.value, 0)
  return (
    <Card
      title="Pipeline by stage"
      state={state.kind === 'ok' && stages.length === 0 ? { kind: 'empty', note: 'Nothing is open on this pipeline right now.' } : state}
      to={to}
    >
      <p className="mb-3 text-sm" style={MUTED}>
        <span className="font-num font-medium tabular-nums" style={{ color: 'var(--text-primary)' }}>{formatCount(total)}</span> open · {formatMoney(Math.round(value))} right now
      </p>
      <ul className="space-y-3">
        {stages.map((s) => (
          <li key={s.stageId}>
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
      <ul className="space-y-1">
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
                  <span className="truncate">{s.source}</span>
                  <span className="font-num shrink-0 tabular-nums">
                    {formatCount(s.leads)} <span className="ml-1 text-xs" style={MUTED}>{total ? Math.round((s.leads / total) * 100) : 0}% · {formatMoney(Math.round(s.value))}</span>
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

/** The GM's team view: who is behind the numbers. A row opens the same dashboard scoped to that person. */
export function DeskCard({ desks, state, audience, onOpen }: { desks: JourneyDesk[]; state: CardState; audience: Audience; onOpen: (memberId: string) => void }) {
  const money = audience === 'sales'
  const rows = [...desks].sort((a, b) => (money ? b.wonValue - a.wonValue : b.leads - a.leads))
  return (
    <Card
      title={money ? 'Sales team' : 'Marketing team'}
      state={state.kind === 'ok' && rows.length === 0 ? { kind: 'empty', note: 'No active member has a pipeline assigned yet.' } : state}
    >
      <table className="w-full text-sm">
        <thead>
          <tr style={MUTED}>
            <th className="pb-1.5 text-left text-xs font-medium">Member</th>
            <th className="pb-1.5 text-right text-xs font-medium">Leads</th>
            {money && <th className="pb-1.5 text-right text-xs font-medium">Won</th>}
            <th className="pb-1.5 text-right text-xs font-medium">Open now</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((d) => (
            <tr key={d.memberId} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
              <td className="py-1">
                <button type="button" onClick={() => onOpen(d.memberId)} className="rounded-md py-1 text-left font-medium hover:underline" style={{ color: 'var(--accent-primary)' }}>
                  {d.name}
                </button>
              </td>
              <td className="font-num py-1 text-right tabular-nums">{formatCount(d.leads)}</td>
              {money && (
                <td className="font-num py-1 text-right tabular-nums">
                  {formatMoney(Math.round(d.wonValue))} <span className="text-xs" style={MUTED}>({d.won})</span>
                </td>
              )}
              <td className="font-num py-1 text-right tabular-nums">{formatCount(d.open)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}
