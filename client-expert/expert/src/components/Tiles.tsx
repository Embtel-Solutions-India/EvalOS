import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
import { Card } from '@shared/components/ui/card'
import { cn } from '@shared/utils/cn'

const TONES = {
  violet: 'border-violet-200 bg-violet-50 dark:border-violet-900/50 dark:bg-violet-950/30 [--tone:theme(colors.violet.600)]',
  green: 'border-emerald-200 bg-emerald-50 dark:border-emerald-900/50 dark:bg-emerald-950/30 [--tone:theme(colors.emerald.600)]',
  blue: 'border-sky-200 bg-sky-50 dark:border-sky-900/50 dark:bg-sky-950/30 [--tone:theme(colors.sky.600)]',
  orange: 'border-orange-200 bg-orange-50 dark:border-orange-900/50 dark:bg-orange-950/30 [--tone:theme(colors.orange.600)]',
} as const

/** A tinted stat tile: icon, title, the figure, one line under it; the whole tile links on. */
export function Stat({ to, tone, icon, title, value, foot }: { to?: string; tone: keyof typeof TONES; icon: ReactNode; title: string; value: string; foot: ReactNode }) {
  const body = (
    <>
      <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-white/70 text-[var(--tone)] dark:bg-white/10">{icon}</span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium text-foreground">{title}</span>
        <span className="mt-1 block truncate text-2xl font-semibold tabular-nums text-foreground">{value}</span>
        <span className="mt-1 flex flex-wrap items-center gap-1 text-xs text-muted-foreground">{foot}</span>
      </span>
      {to && <ArrowRight className="h-4 w-4 shrink-0 text-[var(--tone)]" />}
    </>
  )
  const cls = cn('flex items-start gap-4 rounded-xl border p-4 transition-shadow', TONES[tone], to && 'hover:shadow-md')
  return to ? (
    <Link to={to} className={cls}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  )
}

/** A white panel with a titled header and an optional "View all" link. */
export function Panel({ title, icon, to, action, children }: { title: string; icon: ReactNode; to?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Card className="p-5">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-muted text-muted-foreground">{icon}</span>
          {title}
        </h2>
        {action}
        {to && (
          <Link to={to} className="flex items-center gap-1 text-sm font-medium text-info hover:underline">
            View all <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        )}
      </div>
      {children}
    </Card>
  )
}

