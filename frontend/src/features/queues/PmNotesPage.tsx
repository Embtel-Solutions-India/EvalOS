import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchPmNotes, type CaseNotes } from '../case/caseApi'
import { useFilters } from '../shell/filtersContext'

/**
 * What the PM asked for, on every case that is mine.
 *
 * **The notes are on the page, not behind a click.** This screen exists because they were only
 * reachable by opening a case one at a time, and then only by expanding a row — a "PM notes" screen
 * that still hid them would repeat the problem it was built to fix.
 *
 * **One request.** `/api/cases/pm-notes` reuses the board's own scoped read, so what a Case Manager
 * sees here is exactly the set they see on their board — no second scope rule to drift. The notes
 * are deliberately not a field on the board card: the board is the most-loaded screen in the app
 * and a paragraph per case would ride on every load of it to serve this one.
 *
 * Every case, not only the drafting ones: strategy is guidance for the whole case, and a CM reading
 * it before they start is the point.
 */
export default function PmNotesPage() {
  const { activeBrandId } = useFilters()
  // Unit 70a: a PM's edit on a case page shows here on the next focus, or at once in the same tab.
  const notes = useQuery<CaseNotes[]>({
    queryKey: ['pm-notes', activeBrandId],
    queryFn: ({ signal }) => fetchPmNotes(activeBrandId, signal),
  })
  const rows = notes.data

  if (!rows && !notes.isError) {
    return <div className="h-40 animate-pulse rounded-lg" style={{ background: 'var(--bg-raised)' }} />
  }

  if (!rows) {
    return (
      <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
        {notes.error?.message}
      </p>
    )
  }

  // Cases the PM has actually written for come first: the rest are on the screen so their absence
  // is visible — "no strategy yet" is a fact a CM needs before they start guessing.
  const written = rows.filter((row) => row.pmStrategyNotes)
  const awaiting = rows.filter((row) => !row.pmStrategyNotes)

  return (
    <section>
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">PM notes</h1>
        <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {`${written.length} of ${rows.length} written`}
        </p>
      </header>

      {rows.length === 0 && (
        <p
          className="mt-4 rounded-lg border p-6 text-sm"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-default)',
            color: 'var(--text-muted)',
          }}
        >
          No cases are assigned to you.
        </p>
      )}

      <ul className="mt-4 flex flex-col gap-2">
        {[...written, ...awaiting].map((row) => (
          <li
            key={row.id}
            className="rounded-lg border p-3"
            style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
          >
            <p className="flex flex-wrap items-baseline gap-2">
              <Link to={`/cases/${row.id}`} className="font-mono text-xs font-medium">
                {row.caseCode}
              </Link>
              <span className="text-sm font-semibold">{row.clientName ?? 'Unnamed contact'}</span>
              <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                {readable(row.serviceType)} · {readable(row.stage)}
              </span>
            </p>

            {/*
              Three states, and the middle one is the point — the same shape `StrategyNotes` uses.
              "Not yours to read" and "nobody has written it" must never look alike, because one is
              a permission and the other is a prompt to go and ask the PM.
            */}
            <p
              className="mt-1.5 text-sm whitespace-pre-wrap"
              style={{ color: row.pmStrategyNotes ? 'var(--text-primary)' : 'var(--text-muted)' }}
            >
              {!row.maySeeNotes ?
                'Not visible to your role.'
              : (row.pmStrategyNotes ?? 'The PM has not written strategy notes for this case yet.')}
            </p>
          </li>
        ))}
      </ul>
    </section>
  )
}

function readable(value: string | null): string {
  if (!value) return '—'
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')
}
