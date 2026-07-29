import { useMe } from '../../lib/authContext'
import { useFilters } from '../shell/filtersContext'
import type { Role } from '../../lib/session'

/**
 * One dashboard per role, driven by a table rather than six near-identical files. The
 * largest tile is the role's PRIMARY KPI, per `ui-context.md`'s dashboard pattern.
 *
 * **The KPI names below are slot labels, not agreed metrics.** Unit 17 owns the real
 * ones; these exist so each role's landing page says what will be here and so the tile
 * hierarchy is testable now. Every value is a skeleton — no tile shows a number,
 * because a plausible fake number on an operations dashboard is worse than a blank one.
 */
type RoleDashboardSpec = {
  primary: string
  primaryNote: string
  secondary: readonly string[]
}

const DASHBOARDS: Record<Role, RoleDashboardSpec> = {
  GM: {
    primary: 'Money in vs delivered',
    primaryNote: 'Aggregated across every brand. Collected-but-undelivered shows as open liability.',
    secondary: ['Cycle time by stage', 'Cases at risk', 'Expert acceptance rate', 'Review capture'],
  },
  BRAND_MANAGER: {
    primary: 'Money in vs delivered',
    primaryNote: 'Your brand only.',
    secondary: ['Cases in pool', 'Cycle time by stage', 'Cases at risk', 'Review capture'],
  },
  PROJECT_MANAGER: {
    primary: 'Cases at risk in your team',
    primaryNote: 'Recomputed on read — a case sitting past its budget is overdue even if nothing wrote to it.',
    secondary: ['Awaiting your draft review', 'Awaiting expert signature', 'Team workload'],
  },
  PROJECT_COORDINATOR: {
    primary: 'Documents outstanding',
    primaryNote: 'Cases held at Doc Collection waiting on the client.',
    secondary: ['Ready to send to client', 'Ready to deliver', 'On hold'],
  },
  CASE_MANAGER: {
    primary: 'Your cases by deadline',
    primaryNote: 'Only cases assigned to you.',
    secondary: ['Drafts to submit', 'Revisions requested'],
  },
  EXPERT_NETWORK_MANAGER: {
    primary: 'Expert utilisation',
    primaryNote: 'Supply-side only — the field a case needs, never client identity or content.',
    secondary: ['Acceptance rate', 'Cases needing an expert', 'Payouts pending'],
  },
}

export default function RoleDashboard() {
  const me = useMe()
  const { dateRange, activeBrandId } = useFilters()
  const spec = DASHBOARDS[me.role]

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-lg font-semibold tracking-tight">Dashboard</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {dateRange} · {me.role === 'GM' ? (activeBrandId ? 'one brand' : 'all brands') : 'your brand'}
        </p>
      </header>

      <div className="mt-4 grid gap-4 md:grid-cols-3">
        <Tile title={spec.primary} note={spec.primaryNote} primary />
        {spec.secondary.map((title) => (
          <Tile key={title} title={title} />
        ))}
      </div>

      <p className="mt-6 text-sm" style={{ color: 'var(--text-muted)' }}>
        Live figures arrive with Unit 17. Nothing here is reading real data yet.
      </p>
    </section>
  )
}

/**
 * A skeleton tile. The bar is a placeholder for a number, not a zero — an operations
 * dashboard that shows "0 cases at risk" when it simply has not loaded is how a real
 * problem gets missed.
 */
function Tile({ title, note, primary = false }: { title: string; note?: string; primary?: boolean }) {
  return (
    <article
      className={`rounded-lg border p-5 ${primary ? 'md:col-span-2 md:row-span-2' : ''}`}
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className={primary ? 'text-base font-semibold' : 'text-sm font-medium'}>{title}</h2>
      <div
        className={`mt-4 rounded-md ${primary ? 'h-10 w-40' : 'h-7 w-24'}`}
        style={{ background: 'var(--bg-raised)' }}
        aria-label="No data yet"
      />
      {note && (
        <p className="mt-3 max-w-prose text-sm" style={{ color: 'var(--text-muted)' }}>
          {note}
        </p>
      )}
    </article>
  )
}
