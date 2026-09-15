import { useQuery } from '@tanstack/react-query'
import { ArrowRight, FileCheck2, FileText, Receipt } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { failureMessage, NO_TOKEN, type ClientCaseSummary } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { listApplications } from '@/services/applicationService'
import { listCases } from '@/services/draftService'

/**
 * Where the client lands: what needs them, then everything else (34d).
 *
 * **Real cases, and no greeting by name.** This screen used to open with "Good morning,
 * {firstName}" from a mock account session over mock data. **There is an account now** — Unit 42
 * brought one back and 2026-09-15 let a client create their own — and `client_account` even holds
 * a first name. The greeting still does not come back: EvalOS does not hand the portal a client's
 * name for decoration, and the objection that killed it was never only that the session was fake.
 * What the credential is has also changed: a scoped portal link *or* a token minted by signing in,
 * and this screen cannot tell which, by design.
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

  // **The one state `43` §4 asked this screen to gain.** A client with an unfinished request and
  // no case used to land on "nothing here yet", which is both false and a dead end — the thing
  // they were in the middle of was invisible. Its own query rather than a field on the case list:
  // a request is not a case, and a failure to load one must not blank the other.
  const applications = useQuery({
    queryKey: ['portal', 'applications'],
    queryFn: ({ signal }) => listApplications(signal),
    enabled: tokenPresent,
    retry: false,
  })
  const unfinished = applications.data?.find((item) => item.status === 'DRAFT')

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

      {unfinished && (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
          <div>
            <p className="text-sm font-medium text-foreground">
              Your {unfinished.serviceName} request isn&rsquo;t finished
            </p>
            <p className="text-xs text-muted-foreground">
              Pick up where you left off — your answers are saved.
            </p>
          </div>
          <Button asChild>
            <Link to="/requests/new">
              Continue
              <ArrowRight className="ml-2 h-4 w-4" aria-hidden="true" />
            </Link>
          </Button>
        </Card>
      )}

      {!isLoading && !isError && data && data.length === 0 && !unfinished && (
        <EmptyState
          icon={FileCheck2}
          title="Nothing here yet"
          description="Tell us what you need evaluated and we'll come back to you with a price."
          action={
            <Button asChild>
              <Link to="/requests/new">Request a service</Link>
            </Button>
          }
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
