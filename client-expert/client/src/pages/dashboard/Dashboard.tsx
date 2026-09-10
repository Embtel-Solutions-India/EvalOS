import { useQuery } from '@tanstack/react-query'
import { FileCheck2, FileText, Receipt } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '@shared/components/ui/badge'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { failureMessage, NO_TOKEN, type ClientCaseSummary } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { listCases } from '@/services/draftService'

/**
 * Where the client lands: what needs them, then everything else (34d).
 *
 * **Real cases, and no greeting by name.** This screen used to open with "Good morning,
 * {firstName}" from a mock account session. There is no account: the credential is a scoped
 * portal link, and EvalOS deliberately does not hand the portal a client's first name for
 * decoration. A greeting that needed one was a greeting built on a login that is not coming.
 *
 * **Action first.** `actionRequired` is the server's flag, from `PortalStageProjection` — the
 * one thing a client opening this page wants to know is whether anything is waiting on them,
 * and the server is the only thing entitled to answer that.
 */
export default function Dashboard() {
  const tokenPresent = usePortalToken()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'cases'],
    queryFn: ({ signal }) => listCases(signal),
    enabled: tokenPresent,
    retry: false,
  })

  if (!tokenPresent) {
    return <PageHeader title="Your cases" description={NO_TOKEN} />
  }

  const needsYou = (data ?? []).filter((item) => item.actionRequired)
  const running = (data ?? []).filter((item) => !item.actionRequired)

  return (
    <div className="space-y-6">
      <PageHeader
        title="Your cases"
        description={
          needsYou.length > 0
            ? `${needsYou.length} of your cases ${needsYou.length === 1 ? 'needs' : 'need'} something from you.`
            : 'Nothing is waiting on you right now.'
        }
      />

      {isLoading && <ListSkeleton />}

      {isError && (
        <ErrorState
          description={
            statusOf(error) === 403
              ? 'This link opens a single case rather than your account. Use the link we sent for that case.'
              : failureMessage(statusOf(error))
          }
          onRetry={() => void refetch()}
        />
      )}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState
          icon={FileCheck2}
          title="Nothing here yet"
          description="When a case of yours is opened, it will appear here."
        />
      )}

      {needsYou.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-foreground">Needs you</h2>
          {needsYou.map((item) => <CaseRow key={item.caseId} item={item} highlight />)}
        </section>
      )}

      {running.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-foreground">In progress</h2>
          {running.map((item) => <CaseRow key={item.caseId} item={item} />)}
        </section>
      )}

      {data && data.length > 0 && <Shortcuts />}
    </div>
  )
}

function CaseRow({ item, highlight }: { item: ClientCaseSummary; highlight?: boolean }) {
  return (
    <Link to={`/draft/${item.caseId}`} className="block">
      <Card
        className={`flex flex-wrap items-center justify-between gap-2 p-4 hover:bg-accent ${
          highlight ? 'border-primary' : ''
        }`}
      >
        <div>
          <p className="text-sm font-medium text-foreground">{item.caseReference}</p>
          <p className="text-xs text-muted-foreground">{item.step}</p>
        </div>
        {item.actionRequired && <Badge>Needs you</Badge>}
      </Card>
    </Link>
  )
}

/**
 * The other three real screens.
 *
 * <p>Shown here rather than only in the sidebar because the sidebar is easy to miss on a phone,
 * and these are the whole app: send documents, read the draft, see the bill.
 */
function Shortcuts() {
  const links = [
    { to: '/documents', label: 'Your documents', icon: FileText },
    { to: '/invoices', label: 'Your invoices', icon: Receipt },
  ]
  return (
    <section className="grid gap-3 sm:grid-cols-2">
      {links.map(({ to, label, icon: Icon }) => (
        <Link key={to} to={to}>
          <Card className="flex items-center gap-3 p-4 hover:bg-accent">
            <Icon className="h-5 w-5 text-muted-foreground" />
            <span className="text-sm font-medium">{label}</span>
          </Card>
        </Link>
      ))}
    </section>
  )
}
