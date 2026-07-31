import { useCallback, useEffect, useState } from 'react'
import { useMe } from '../../lib/authContext'
import { useFilters } from '../shell/filtersContext'
import AvailabilityBoard from './AvailabilityBoard'
import ExpertProfile from './ExpertProfile'
import SheetUpload from './SheetUpload'
import { fetchRoster } from './expertApi'
import {
  AVAILABILITIES,
  AVAILABILITY_TOKEN,
  FIELD_TAGS,
  LETTER_TYPES,
  NO_FILTERS,
  TIERS,
  label,
  type RosterFilters,
  type RosterPage,
  type RosterRow,
} from './expertRules'

/**
 * The expert database: the roster that replaces the ENM's Google Sheet, the availability
 * board beside it, and the sheet upload that maintains both.
 *
 * A table rather than cards. What varies between experts is fields, tiers, scores and case
 * loads — mostly numbers, and numbers read better in aligned columns; the production board's
 * cards exist because a case's *stage* is what matters about it, which is not true here.
 *
 * Three tabs, one screen, because they are three views of one roster: an ENM who has just
 * imported fifty experts is going to look at them, and a separate route per view would make
 * that a navigation exercise.
 *
 * Writes are hidden from a Project Manager, who reads the roster to know who they are
 * picking from. The server refuses them either way — this only avoids offering a button that
 * answers 403.
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; page: RosterPage }
  | { status: 'failed'; message: string }

const PAGE_SIZE = 25

export default function ExpertRoster() {
  const me = useMe()
  const { activeBrandId } = useFilters()
  const [tab, setTab] = useState<'roster' | 'availability' | 'import'>('roster')
  const [filters, setFilters] = useState<RosterFilters>(NO_FILTERS)
  const [page, setPage] = useState(0)
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [open, setOpen] = useState<string | 'new' | null>(null)

  const mayWrite = me.role !== 'PROJECT_MANAGER'

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState({
          status: 'ready',
          page: await fetchRoster(activeBrandId, filters, page, PAGE_SIZE, signal),
        })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load the roster',
        })
      }
    },
    [activeBrandId, filters, page],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  // A filter change re-pages from the start: page 3 of an unfiltered roster is rarely page 3
  // of a filtered one, and an empty screen with rows behind it reads as "nothing matched".
  const narrow = useCallback((change: Partial<RosterFilters>) => {
    setPage(0)
    setFilters((current) => ({ ...current, ...change }))
  }, [])

  const openExpert = useCallback((expertId: string) => {
    setTab('roster')
    setOpen(expertId)
  }, [])

  return (
    <div className="flex flex-col gap-5">
      <header>
        <p className="text-[11px] font-semibold tracking-[0.08em] uppercase" style={{ color: 'var(--text-muted)' }}>
          {me.role === 'GM' ? (activeBrandId ? 'One brand' : 'All brands') : 'Your brand'} · expert network
        </p>
        <h1 className="mt-1 text-xl font-semibold tracking-tight">Expert database</h1>
        {/* The count belongs to the roster's filtered read, so it is only shown there. Left
            standing over the availability board it claimed to describe a screen it had not
            counted — the same failure as a header contradicting the instrument beside it. */}
        {tab === 'roster' && (
          <p className="font-num mt-1 text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {state.status === 'ready'
              ? `${state.page.total} ${state.page.total === 1 ? 'expert' : 'experts'}${
                  filters === NO_FILTERS ? '' : ' matching'
                }`
              : 'Loading…'}
          </p>
        )}
      </header>

      <nav className="flex gap-1.5" aria-label="Expert database views">
        <Tab current={tab} value="roster" onSelect={setTab}>
          Roster
        </Tab>
        <Tab current={tab} value="availability" onSelect={setTab}>
          Availability
        </Tab>
        {mayWrite && (
          <Tab current={tab} value="import" onSelect={setTab}>
            Sheet upload
          </Tab>
        )}
      </nav>

      {tab === 'availability' && <AvailabilityBoard onOpen={openExpert} />}

      {tab === 'import' && mayWrite && (
        <div className="rounded-lg border p-4" style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}>
          <SheetUpload
            onImported={() => {
              setPage(0)
              void load()
            }}
          />
        </div>
      )}

      {tab === 'roster' && (
        <>
          <section className="flex flex-wrap items-end gap-3">
            <div className="min-w-56 flex-1">
              <label className="block text-xs font-medium" htmlFor="expert-search">
                Search
              </label>
              <input
                id="expert-search"
                type="search"
                value={filters.search}
                placeholder="name or institution"
                onChange={(event) => narrow({ search: event.target.value })}
                className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
                style={INPUT_STYLE}
              />
            </div>
            <Filter
              label="Field tag"
              options={FIELD_TAGS}
              value={filters.fieldTag}
              onChange={(value) => narrow({ fieldTag: value as RosterFilters['fieldTag'] })}
            />
            <Filter
              label="Letter type"
              options={LETTER_TYPES}
              value={filters.letterType}
              onChange={(value) => narrow({ letterType: value as RosterFilters['letterType'] })}
            />
            <Filter
              label="Availability"
              options={AVAILABILITIES}
              value={filters.availability}
              onChange={(value) => narrow({ availability: value as RosterFilters['availability'] })}
            />
            <Filter
              label="Tier"
              options={TIERS}
              value={filters.tier}
              onChange={(value) => narrow({ tier: value as RosterFilters['tier'] })}
            />
            {mayWrite && (
              <button
                type="button"
                onClick={() => setOpen('new')}
                className="rounded-md px-3 py-1.5 text-sm font-medium text-white"
                style={{ background: 'var(--accent-primary)' }}
              >
                Add an expert
              </button>
            )}
          </section>

          {state.status === 'failed' && (
            <div
              className="rounded-lg border p-4"
              style={{ background: 'var(--status-red-bg)', borderColor: 'var(--border-default)' }}
            >
              <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
                {state.message}
              </p>
              <button
                type="button"
                onClick={() => void load()}
                className="mt-3 rounded-md px-3 py-1.5 text-sm font-medium text-white"
                style={{ background: 'var(--accent-primary)' }}
              >
                Try again
              </button>
            </div>
          )}

          {state.status === 'ready' && state.page.rows.length === 0 && (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              {filters === NO_FILTERS
                ? 'No experts on this roster yet. Upload your sheet, or add one by hand.'
                : 'No expert matches those filters.'}
            </p>
          )}

          {state.status === 'ready' && state.page.rows.length > 0 && (
            <>
              <div
                className="overflow-x-auto rounded-lg border"
                style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
              >
                <table className="w-full text-sm">
                  <thead>
                    <tr style={{ color: 'var(--text-muted)' }}>
                      <Th>Expert</Th>
                      <Th>Fields</Th>
                      <Th>Letters</Th>
                      <Th>Tier</Th>
                      <Th>Availability</Th>
                      <Th numeric>Quality</Th>
                      <Th numeric>Load</Th>
                      <Th numeric>Fee</Th>
                      <Th>Payment</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.page.rows.map((expert) => (
                      <Row key={expert.id} expert={expert} onOpen={() => setOpen(expert.id)} />
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="font-num flex items-center gap-3 text-sm tabular-nums">
                <button
                  type="button"
                  disabled={page === 0}
                  onClick={() => setPage((current) => Math.max(current - 1, 0))}
                  className="rounded-md px-2.5 py-1 font-medium disabled:opacity-50"
                  style={{ background: 'var(--bg-raised)' }}
                >
                  Previous
                </button>
                <span style={{ color: 'var(--text-muted)' }}>
                  {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, state.page.total)} of{' '}
                  {state.page.total}
                </span>
                <button
                  type="button"
                  disabled={(page + 1) * PAGE_SIZE >= state.page.total}
                  onClick={() => setPage((current) => current + 1)}
                  className="rounded-md px-2.5 py-1 font-medium disabled:opacity-50"
                  style={{ background: 'var(--bg-raised)' }}
                >
                  Next
                </button>
              </div>
            </>
          )}

          {open && (
            <ExpertProfile
              expertId={open}
              onSaved={() => void load()}
              onClose={() => setOpen(null)}
            />
          )}
        </>
      )}
    </div>
  )
}

const INPUT_STYLE = { background: 'var(--bg-base)', borderColor: 'var(--border-default)' }

/**
 * One narrowing dropdown over a closed vocabulary. Empty means "any" — an absent filter is
 * not sent at all, so no combination of these can widen what the caller may read.
 */
function Filter({
  label: text,
  options,
  value,
  onChange,
}: {
  label: string
  options: readonly string[]
  value: string
  onChange: (value: string) => void
}) {
  const id = `expert-filter-${text.toLowerCase().replace(/[^a-z]/g, '-')}`
  return (
    <div>
      <label className="block text-xs font-medium" htmlFor={id}>
        {text}
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-1 rounded-md border px-2.5 py-1.5 text-sm"
        style={INPUT_STYLE}
      >
        <option value="">Any</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {label(option)}
          </option>
        ))}
      </select>
    </div>
  )
}

function Row({ expert, onOpen }: { expert: RosterRow; onOpen: () => void }) {
  const availability = expert.availability
  return (
    <tr className="border-t" style={{ borderColor: 'var(--border-default)' }}>
      <td className="px-3 py-2">
        <button type="button" onClick={onOpen} className="text-left">
          <span className="block font-semibold underline-offset-2 hover:underline">
            {expert.fullName ?? 'Unnamed expert'}
          </span>
          <span className="block text-xs" style={{ color: 'var(--text-muted)' }}>
            {[expert.title, expert.institution].filter(Boolean).join(' · ') || 'No institution on file'}
          </span>
        </button>
      </td>
      <td className="px-3 py-2">
        <Tags values={expert.primaryFields} muted={expert.secondaryFields} />
      </td>
      <td className="px-3 py-2">
        <Tags values={expert.letterTypes} />
      </td>
      <td className="px-3 py-2 text-xs">{label(expert.tier)}</td>
      <td className="px-3 py-2">
        {availability ? (
          <span
            className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold"
            style={{
              color: AVAILABILITY_TOKEN[availability].fg,
              background: AVAILABILITY_TOKEN[availability].bg,
            }}
          >
            {label(availability)}
          </span>
        ) : (
          <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
            Not set
          </span>
        )}
      </td>
      <td className="font-num px-3 py-2 text-right tabular-nums">{expert.qualityScore ?? '—'}</td>
      {/* The derived count. `expert.current_active_count` is always 0 and is never read. */}
      <td className="font-num px-3 py-2 text-right tabular-nums" title="Open cases · completed">
        {expert.activeLoad}
        <span style={{ color: 'var(--text-muted)' }}> / {expert.completedCases}</span>
      </td>
      <td className="font-num px-3 py-2 text-right tabular-nums">
        {expert.standardFee == null
          ? '—'
          : expert.standardFee.toLocaleString(undefined, { minimumFractionDigits: 2 })}
      </td>
      <td className="px-3 py-2 text-xs" style={{ color: 'var(--text-muted)' }}>
        {/* Whether one is on file, which is all an ENM needs and all the API will ever say. */}
        {expert.paymentDetailOnFile ? 'On file' : '—'}
      </td>
    </tr>
  )
}

function Tags({ values, muted }: { values: readonly string[]; muted?: readonly string[] }) {
  if (values.length === 0 && !muted?.length) {
    return (
      <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
        None
      </span>
    )
  }
  return (
    <span className="flex flex-wrap gap-1">
      {values.map((value) => (
        <span
          key={value}
          className="rounded-md px-1.5 py-0.5 text-[11px] font-medium"
          style={{ background: 'var(--bg-raised)' }}
        >
          {label(value)}
        </span>
      ))}
      {muted?.map((value) => (
        <span
          key={value}
          className="rounded-md px-1.5 py-0.5 text-[11px]"
          style={{ color: 'var(--text-muted)', background: 'var(--bg-base)' }}
          title="Secondary field"
        >
          {label(value)}
        </span>
      ))}
    </span>
  )
}

function Th({ children, numeric }: { children: React.ReactNode; numeric?: boolean }) {
  return (
    <th
      scope="col"
      className={`px-3 py-2 text-[11px] font-semibold tracking-[0.06em] uppercase ${
        numeric ? 'text-right' : 'text-left'
      }`}
    >
      {children}
    </th>
  )
}

function Tab({
  current,
  value,
  onSelect,
  children,
}: {
  current: string
  value: 'roster' | 'availability' | 'import'
  onSelect: (value: 'roster' | 'availability' | 'import') => void
  children: React.ReactNode
}) {
  const active = current === value
  return (
    <button
      type="button"
      aria-current={active ? 'page' : undefined}
      onClick={() => onSelect(value)}
      className={`rounded-md px-3 py-1.5 text-sm font-medium ${active ? 'text-white' : ''}`}
      style={{ background: active ? 'var(--accent-primary)' : 'var(--bg-raised)' }}
    >
      {children}
    </button>
  )
}
