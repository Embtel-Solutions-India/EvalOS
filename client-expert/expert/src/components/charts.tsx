import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * Two small SVG charts for the expert dashboard and payouts page — the portals carry no chart
 * library, and these two shapes are all they need. Colours are theme tokens, so dark mode follows.
 */

export type Slice = { label: string; value: number; color: string; detail?: string }

/**
 * A donut with the total in the middle and a legend beside it: every slice is named with its
 * value, so identity never rests on colour alone. 2px gaps separate the slices.
 */
export function Donut({ slices, center, caption }: { slices: Slice[]; center: ReactNode; caption: string }) {
  const [hover, setHover] = useState<number | null>(null)
  const total = slices.reduce((s, x) => s + x.value, 0)
  const r = 52
  const c = 2 * Math.PI * r
  const gap = total > 0 && slices.filter((s) => s.value > 0).length > 1 ? 2 : 0
  let offset = 0

  return (
    <div className="flex flex-col items-center gap-5 sm:flex-row sm:gap-8">
      <div className="relative h-40 w-40 shrink-0">
        <svg viewBox="0 0 128 128" className="h-full w-full -rotate-90" role="img" aria-label={caption}>
          <circle cx="64" cy="64" r={r} fill="none" stroke="hsl(var(--muted))" strokeWidth="14" />
          {total > 0 &&
            slices.map((s, i) => {
              const len = (s.value / total) * c
              const dash = Math.max(len - gap, 0)
              const el = (
                <circle
                  key={s.label}
                  cx="64"
                  cy="64"
                  r={r}
                  fill="none"
                  stroke={s.color}
                  strokeWidth={hover === i ? 17 : 14}
                  strokeDasharray={`${dash} ${c - dash}`}
                  strokeDashoffset={-offset}
                  onMouseEnter={() => setHover(i)}
                  onMouseLeave={() => setHover(null)}
                  className="transition-[stroke-width]"
                >
                  <title>{`${s.label}: ${s.detail ?? s.value}`}</title>
                </circle>
              )
              offset += len
              return el
            })}
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          {center}
        </div>
      </div>
      <ul className="w-full space-y-3 text-sm">
        {slices.map((s, i) => (
          <li
            key={s.label}
            className={`flex items-center gap-3 rounded-md px-1 ${hover === i ? 'bg-muted' : ''}`}
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover(null)}
          >
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: s.color }} aria-hidden="true" />
            <span className="flex-1 text-foreground">{s.label}</span>
            <span className="font-semibold tabular-nums text-foreground">{s.detail ?? s.value}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * One bar per month, the current month in full colour and the rest tinted. A hover (or focus) shows
 * the month's exact value; with `axis`, gridlines and a y scale are drawn.
 */
export function MonthBars({
  labels,
  values,
  color,
  format,
  axis = false,
  caption,
}: {
  labels: string[]
  values: number[]
  color: string
  format(v: number): string
  axis?: boolean
  caption: string
}) {
  const [hover, setHover] = useState<number | null>(null)
  // Drawn at the container's real width, so text stays at its font size instead of scaling with it.
  const box = useRef<HTMLDivElement>(null)
  const [W, setW] = useState(320)
  useEffect(() => {
    const el = box.current
    if (!el) return
    const observer = new ResizeObserver(([entry]) => setW(Math.max(200, Math.round(entry!.contentRect.width))))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])
  const max = niceMax(Math.max(0, ...values))
  const ticks = axis ? [0, max / 3, (2 * max) / 3, max] : []
  const H = axis ? 190 : 110
  const left = axis ? 48 : 4
  const bottom = 20
  const plotW = W - left - 4
  const plotH = H - bottom - 8
  const slot = plotW / Math.max(values.length, 1)
  const barW = Math.min(slot * 0.55, 44)

  return (
    <div className="relative" ref={box}>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} className="block max-w-full" role="img" aria-label={caption}>
        {ticks.map((t) => {
          const y = 8 + plotH - (t / max) * plotH
          return (
            <g key={t}>
              <line x1={left} x2={W - 4} y1={y} y2={y} stroke="hsl(var(--border))" strokeDasharray={t === 0 ? undefined : '3 3'} />
              <text x={left - 6} y={y + 3} textAnchor="end" className="fill-muted-foreground text-[11px]">
                {format(t)}
              </text>
            </g>
          )
        })}
        {!axis && <line x1={left} x2={W - 4} y1={8 + plotH} y2={8 + plotH} stroke="hsl(var(--border))" />}
        {values.map((v, i) => {
          const h = max > 0 ? Math.max((v / max) * plotH, v > 0 ? 3 : 0) : 0
          const x = left + i * slot + (slot - barW) / 2
          const y = 8 + plotH - h
          const current = i === values.length - 1
          return (
            <g
              key={labels[i]}
              onMouseEnter={() => setHover(i)}
              onMouseLeave={() => setHover(null)}
              onFocus={() => setHover(i)}
              onBlur={() => setHover(null)}
              tabIndex={0}
              aria-label={`${labels[i]}: ${format(v)}`}
              className="outline-none"
            >
              {/* The hit target is the whole column, bigger than the bar. */}
              <rect x={left + i * slot} y={8} width={slot} height={plotH} fill="transparent" />
              {h > 0 && (
                <path
                  d={roundedTop(x, y, barW, h, Math.min(4, h))}
                  fill={color}
                  opacity={current || hover === i ? 1 : 0.4}
                />
              )}
              <text x={left + i * slot + slot / 2} y={H - 5} textAnchor="middle" className="fill-muted-foreground text-[11px]">
                {labels[i]}
              </text>
            </g>
          )
        })}
      </svg>
      {hover !== null && (
        <div
          className="pointer-events-none absolute -top-2 rounded-md border bg-popover px-2 py-1 text-xs shadow-md"
          style={{ left: `${((left + hover * slot + slot / 2) / W) * 100}%`, transform: 'translate(-50%, -100%)' }}
        >
          <span className="text-muted-foreground">{labels[hover]} · </span>
          <span className="font-semibold tabular-nums text-foreground">{format(values[hover]!)}</span>
        </div>
      )}
    </div>
  )
}

/** A bar with its top two corners rounded and its base square on the axis. */
function roundedTop(x: number, y: number, w: number, h: number, r: number): string {
  return `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h} Z`
}

/** The smallest of 1.5, 3, 6 or 12 × a power of ten that is at least `v` — thirds make clean ticks. */
function niceMax(v: number): number {
  if (v <= 0) return 3
  const p = 10 ** Math.floor(Math.log10(v))
  for (const m of [1.5, 3, 6, 12]) if (m * p >= v) return m * p
  return 15 * p
}
