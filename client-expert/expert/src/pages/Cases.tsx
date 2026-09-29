import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useState } from 'react'
import { ChevronRight, FileText, Search } from 'lucide-react'
import { Badge } from '@shared/components/ui/badge'
import { Card } from '@shared/components/ui/card'
import { Input } from '@shared/components/ui/input'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { statusOf } from '@shared/services/apiClient'
import { cn } from '@shared/utils/cn'
import { expertFailureMessage, humanize, SIGN_STATUS, type ExpertCaseSummary } from '@/lib/expertCase'
import { listCases } from '@/services/expertPortalService'

/**
 * The tiles are the filters: each one counts a slice of the list and clicking it shows that slice.
 * Every slice reads a value the server sent — `actionRequired` or `signStatus` — nothing is derived.
 */
const FILTERS = [
  { key: 'all', label: 'All cases', hint: 'Everything you are on', match: () => true, tone: '' },
  { key: 'needs', label: 'Needs you', hint: 'Waiting on your answer', match: (c: ExpertCaseSummary) => c.actionRequired, tone: 'text-destructive' },
  { key: 'pending', label: 'Awaiting signature', hint: 'Accepted, not yet signed', match: (c: ExpertCaseSummary) => c.signStatus === 'PENDING', tone: 'text-warning' },
  { key: 'overdue', label: 'Overdue', hint: 'Past the signing window', match: (c: ExpertCaseSummary) => c.signStatus === 'OVERDUE', tone: 'text-destructive' },
  { key: 'signed', label: 'Signed', hint: 'Letter delivered', match: (c: ExpertCaseSummary) => c.signStatus === 'SIGNED', tone: 'text-success' },
] as const

type FilterKey = (typeof FILTERS)[number]['key']

/** A signed-in expert's cases (Unit 59); each opens `/case?caseId=`. The shell guards the token. */
export default function Cases() {
  const [filter, setFilter] = useState<FilterKey>('all')
  const [query, setQuery] = useState('')
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'cases'],
    queryFn: listCases,
    retry: false,
  })

  const cases = data ?? []
  const active = FILTERS.find((f) => f.key === filter)!
  const needle = query.trim().toLowerCase()
  const shown = cases.filter(
    (c) => active.match(c) && (!needle || `${c.caseReference ?? ''} ${humanize(c.serviceType)}`.toLowerCase().includes(needle)),
  )

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader title="Your cases" description="Letters you have been offered, are signing, or have delivered." />

      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5" role="group" aria-label="Filter cases">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            aria-pressed={filter === f.key}
            onClick={() => setFilter(f.key)}
            className={cn(
              'rounded-lg border bg-card p-4 text-left transition-colors hover:border-primary/40',
              filter === f.key && 'border-primary ring-1 ring-primary',
            )}
          >
            <span className="block text-sm text-muted-foreground">{f.label}</span>
            <span className={cn('mt-1 block text-3xl font-semibold tabular-nums text-foreground', f.tone)}>
              {isLoading ? '–' : cases.filter(f.match).length}
            </span>
            <span className="mt-1 block text-xs text-muted-foreground">{f.hint}</span>
          </button>
        ))}
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_20rem]">
        <Card className="overflow-hidden">
          <div className="flex flex-col gap-3 border-b p-4 sm:flex-row sm:items-center sm:justify-between">
            <h2 className="font-semibold text-foreground">{active.label}</h2>
            <div className="relative sm:w-72">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search by case or service"
                aria-label="Search cases"
                className="pl-9"
              />
            </div>
          </div>

          {isLoading && <div className="p-4"><ListSkeleton /></div>}
          {isError && <div className="p-4"><ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} /></div>}
          {data && cases.length === 0 && (
            <EmptyState icon={FileText} title="No cases yet" description="A case appears here as soon as you are offered one." />
          )}
          {data && cases.length > 0 && shown.length === 0 && (
            <p className="p-6 text-sm text-muted-foreground">No cases match. Pick another tile or clear the search.</p>
          )}

          {shown.length > 0 && (
            <div className="hidden grid-cols-[9rem_minmax(0,1fr)_minmax(0,1fr)_11rem_1.5rem] gap-4 border-b bg-muted/50 px-4 py-2 text-xs font-medium text-muted-foreground md:grid">
              <span>Case</span>
              <span>Service</span>
              <span>Where it stands</span>
              <span>Signature</span>
              <span />
            </div>
          )}
          <ul className="divide-y">
            {shown.map((item) => (
              <li key={item.caseId}>
                <CaseRow item={item} />
              </li>
            ))}
          </ul>
        </Card>

        <aside className="space-y-6">
          <StatusWidget cases={cases} />
          <NeedsYouWidget cases={cases.filter((c) => c.actionRequired)} />
        </aside>
      </div>
    </div>
  )
}

function CaseRow({ item }: { item: ExpertCaseSummary }) {
  const status = item.signStatus ? SIGN_STATUS[item.signStatus] : null
  return (
    <Link
      to={`/case?caseId=${item.caseId}`}
      className={cn(
        'grid grid-cols-[minmax(0,1fr)_1.5rem] items-center gap-x-4 gap-y-1 px-4 py-3 hover:bg-accent/60 md:grid-cols-[9rem_minmax(0,1fr)_minmax(0,1fr)_11rem_1.5rem]',
        item.actionRequired && 'shadow-[inset_3px_0_0_hsl(var(--destructive))]',
      )}
    >
      <span className="font-medium text-primary">{item.caseReference ?? 'Case'}</span>
      <ChevronRight className="h-4 w-4 text-muted-foreground md:order-last" />
      <span className="col-span-2 truncate text-sm text-foreground md:col-span-1">{humanize(item.serviceType) || '—'}</span>
      <span className="col-span-2 text-sm text-muted-foreground md:col-span-1">{item.step ?? 'Not with you yet'}</span>
      <span className="col-span-2 flex flex-wrap gap-1.5 md:col-span-1">
        {item.actionRequired && <Badge variant="destructive">Needs you</Badge>}
        {status && <Badge variant={status.variant}>{status.label}</Badge>}
      </span>
    </Link>
  )
}

/** One bar split by the server's sign statuses. Open offers have their own page, New cases. */
const SEGMENTS = [
  { label: 'Not at signing yet', match: (c: ExpertCaseSummary) => c.signStatus === null, color: 'bg-info' },
  { label: 'Awaiting signature', match: (c: ExpertCaseSummary) => c.signStatus === 'PENDING', color: 'bg-warning' },
  { label: 'Overdue', match: (c: ExpertCaseSummary) => c.signStatus === 'OVERDUE', color: 'bg-destructive' },
  { label: 'Signed', match: (c: ExpertCaseSummary) => c.signStatus === 'SIGNED', color: 'bg-success' },
  { label: 'Reassigned', match: (c: ExpertCaseSummary) => c.signStatus === 'REASSIGNED', color: 'bg-muted-foreground/40' },
]

function StatusWidget({ cases }: { cases: ExpertCaseSummary[] }) {
  const counts = SEGMENTS.map((s) => ({ ...s, count: cases.filter(s.match).length }))
  return (
    <Card className="p-4">
      <h2 className="font-semibold text-foreground">Where your cases stand</h2>
      {cases.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">Nothing to show until you are offered a case.</p>
      ) : (
        <>
          <div className="mt-4 flex h-3 overflow-hidden rounded-full bg-muted" aria-hidden>
            {counts.map((s) => s.count > 0 && <div key={s.label} className={s.color} style={{ width: `${(s.count / cases.length) * 100}%` }} />)}
          </div>
          <ul className="mt-4 space-y-2 text-sm">
            {counts.map((s) => (
              <li key={s.label} className="flex items-center gap-2">
                <span className={cn('h-2.5 w-2.5 rounded-full', s.color)} />
                <span className="flex-1 text-muted-foreground">{s.label}</span>
                <span className="font-medium tabular-nums text-foreground">{s.count}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </Card>
  )
}

function NeedsYouWidget({ cases }: { cases: ExpertCaseSummary[] }) {
  return (
    <Card className="p-4">
      <h2 className="font-semibold text-foreground">Needs your answer</h2>
      {cases.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">You are all caught up.</p>
      ) : (
        <ul className="mt-3 divide-y">
          {cases.map((c) => (
            <li key={c.caseId}>
              <Link to={`/case?caseId=${c.caseId}`} className="flex items-center justify-between gap-2 py-2 text-sm hover:text-primary">
                <span>
                  <span className="block font-medium">{c.caseReference ?? 'Case'}</span>
                  {c.step && <span className="block text-xs text-muted-foreground">{c.step}</span>}
                </span>
                <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
