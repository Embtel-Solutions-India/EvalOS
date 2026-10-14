import { useQuery } from '@tanstack/react-query'
import { useFilters } from '../shell/filtersContext'
import { fetchAvailabilityBoard } from './expertApi'
import { Pager, usePaging } from '../../components/ui/pager'
import { AVAILABILITY_TOKEN, label, type AvailabilityColumn, type RosterRow } from './expertRules'

/**
 * Who is free, who is at capacity, who is on leave — with each expert's live case load.
 *
 * The load is the point of putting it beside the availability: "at capacity" is a claim, and
 * the case count is the evidence for or against it. An expert marked available carrying six
 * open cases, or one at capacity carrying none, is exactly what an ENM needs to see before a
 * PM finds nobody to assign.
 *
 * Every availability is a column, including the empty ones, so the board does not reshape
 * itself as the roster changes — and "nobody is available" is a visible state rather than a
 * missing one.
 */
export default function AvailabilityBoard({ onOpen }: { onOpen: (expertId: string) => void }) {
  const { activeBrandId } = useFilters()
  // Unit 70a phase 2: an offer accepted or an availability change elsewhere moves a card here.
  const query = useQuery({
    queryKey: ['experts', 'availability', activeBrandId],
    queryFn: ({ signal }) => fetchAvailabilityBoard(activeBrandId, signal),
  })
  const state:
    | { status: 'loading' }
    | { status: 'ready'; columns: AvailabilityColumn[] }
    | { status: 'failed'; message: string } = query.data
    ? { status: 'ready', columns: query.data }
    : query.isError
      ? { status: 'failed', message: query.error.message || 'Could not load the availability board' }
      : { status: 'loading' }

  if (state.status === 'loading') {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading the availability board…
      </p>
    )
  }

  if (state.status === 'failed') {
    return (
      <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
        {state.message}
      </p>
    )
  }

  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
      {state.columns.map((column) => (
        <Column key={column.availability} column={column} onOpen={onOpen} />
      ))}
    </div>
  )
}

/** Experts shown per column before it pages, so a busy column does not push the others off the screen. */
const PER_COLUMN = 10

function Column({ column, onOpen }: { column: AvailabilityColumn; onOpen: (expertId: string) => void }) {
  const paging = usePaging(column.experts, PER_COLUMN)
  return (
    <section className="rounded-lg border p-3" style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}>
      <h2 className="flex items-baseline justify-between text-sm font-semibold tracking-tight">
        <span
          className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold"
          style={{
            color: AVAILABILITY_TOKEN[column.availability].fg,
            background: AVAILABILITY_TOKEN[column.availability].bg,
          }}
        >
          {label(column.availability)}
        </span>
        <span className="font-num tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {column.count}
        </span>
      </h2>

      {column.experts.length === 0 && (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
          Nobody.
        </p>
      )}

      <ul className="mt-2 flex flex-col gap-1.5">
        {paging.slice.map((expert) => (
          <li key={expert.id}>
            <button
              type="button"
              onClick={() => onOpen(expert.id)}
              className="w-full rounded-md px-2 py-1.5 text-left transition-colors hover:bg-(--bg-raised)"
            >
              <span className="block text-sm font-medium">{expert.fullName ?? 'Unnamed expert'}</span>
              <span className="font-num block text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                {loadLine(expert)}
              </span>
            </button>
          </li>
        ))}
      </ul>
      <Pager paging={paging} noun="experts" />
    </section>
  )
}

/**
 * The derived case load, never `current_active_count` — that column has never been written
 * and would report everybody as free.
 */
function loadLine(expert: RosterRow): string {
  const open = `${expert.activeLoad} open`
  const done = expert.completedCases > 0 ? ` · ${expert.completedCases} done` : ''
  const tier = expert.tier ? ` · ${label(expert.tier)}` : ''
  return open + done + tier
}
