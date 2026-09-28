import { useQuery } from '@tanstack/react-query'
import { ArrowRight, FileCheck2, MessagesSquare, Receipt } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { DELIVERED_STEP, failureMessage, NO_TOKEN, serviceLabel, type ClientCaseSummary } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { listApplications } from '@/services/applicationService'
import { listCases } from '@/services/caseService'

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
 * **Active cases, then delivered ones (Unit 58 §4), and within active, action first.**
 * `actionRequired` and `stepIndex` are the server's, from `PortalStageProjection` — the one thing
 * a client opening this page wants to know is whether anything is waiting on them, and the server
 * is the only thing entitled to answer that.
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

  const active = (data ?? []).filter((item) => item.stepIndex < DELIVERED_STEP)
  const needsYou = active.filter((item) => item.actionRequired)
  const delivered = (data ?? []).filter((item) => item.stepIndex >= DELIVERED_STEP)
  const byAction = [...needsYou, ...active.filter((item) => !item.actionRequired)]

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

      {byAction.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-foreground">Active cases</h2>
          {byAction.map((item) => <CaseRow key={item.caseId} item={item} />)}
        </section>
      )}

      {delivered.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-foreground">Delivered cases</h2>
          {delivered.map((item) => <CaseRow key={item.caseId} item={item} />)}
        </section>
      )}

      {data && data.length > 0 && <Shortcuts />}
    </div>
  )
}

function CaseRow({ item }: { item: ClientCaseSummary }) {
  return (
    <Link to={`/cases/${item.caseId}`} className="block">
      <Card
        className={`flex flex-wrap items-center justify-between gap-2 p-4 hover:bg-accent ${
          item.actionRequired ? 'border-primary' : ''
        }`}
      >
        <div>
          <p className="text-sm font-medium text-foreground">{item.caseReference}</p>
          <p className="text-xs text-muted-foreground">
            {serviceLabel(item.serviceType)} · {item.step}
          </p>
        </div>
        {item.actionRequired && <Badge>Needs you</Badge>}
      </Card>
    </Link>
  )
}

/** Shown here as well as in the sidebar, which is easy to miss on a phone. */
function Shortcuts() {
  const links = [
    { to: '/conversations', label: 'Conversations', icon: MessagesSquare },
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
