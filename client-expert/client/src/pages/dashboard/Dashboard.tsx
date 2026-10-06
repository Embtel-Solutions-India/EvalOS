import { useQuery } from '@tanstack/react-query'
import { FileCheck2, MessagesSquare, Receipt } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '@shared/components/ui/badge'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PushCard } from '@evalos/chat'
import { PageHeader } from '@shared/components/common/PageHeader'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { DELIVERED_STEP, failureMessage, NO_TOKEN, serviceLabel, type ClientCaseSummary } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { listCases } from '@/services/caseService'

/**
 * Where the client lands: what needs them, then everything else (34d).
 *
 * **Real cases, and a greeting by time of day only.** This screen used to open with "Good morning,
 * {firstName}" from a mock account session over mock data. **There is an account now** — Unit 42
 * brought one back, and since Unit 64 it is opened when the client's case is — and `client_account` even holds
 * a first name. The name still does not come back: EvalOS does not hand the portal a client's
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

  if (!tokenPresent) {
    return <PageHeader title="Your cases" description={NO_TOKEN} />
  }

  const active = (data ?? []).filter((item) => item.stepIndex < DELIVERED_STEP)
  const needsYou = active.filter((item) => item.actionRequired)
  const delivered = (data ?? []).filter((item) => item.stepIndex >= DELIVERED_STEP)
  const byAction = [...needsYou, ...active.filter((item) => !item.actionRequired)]

  return (
    <div className="space-y-6">
      <p className="text-2xl font-bold tracking-tight text-foreground sm:text-3xl">{greeting()}!</p>

      {/* The opt-in for messages while the portal is closed. It used to live only on Conversations, which
          most clients never open, so nobody was subscribed to be notified. */}
      <PushCard workerUrl="/sw.js" />

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
          title="No cases yet"
          description="Your cases appear here once our team starts one."
        />
      )}

      {byAction.length > 0 && (
        <section className="-mt-3 space-y-3">
          {byAction.map((item) => <CaseRow key={item.caseId} item={item} />)}
        </section>
      )}

      {delivered.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold text-foreground">Delivered cases</h2>
          {delivered.map((item) => <CaseRow key={item.caseId} item={item} />)}
        </section>
      )}

      {data && data.length > 0 && <Shortcuts />}
    </div>
  )
}

/** By the client's own clock — the greeting names nobody (see above). */
function greeting(hour = new Date().getHours()): string {
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

function CaseRow({ item }: { item: ClientCaseSummary }) {
  return (
    <Link to={`/cases/${item.caseId}`} className="block">
      <Card
        className={`flex flex-wrap items-center justify-between gap-2 border-l-4 px-6 py-5 shadow-sm transition hover:bg-accent ${
          item.actionRequired ? 'border-l-primary' : 'border-l-transparent'
        }`}
      >
        <div className="space-y-1">
          <p className="text-lg font-medium text-foreground">{item.caseReference}</p>
          <p className="text-sm text-muted-foreground">
            {serviceLabel(item.serviceType)} · {item.step}
          </p>
        </div>
        {item.actionRequired && <Badge className="rounded-full px-3 py-1 text-sm">Needs you</Badge>}
      </Card>
    </Link>
  )
}

/** For a phone, where the sidebar is a drawer and easy to miss; the desktop sidebar is always there. */
function Shortcuts() {
  const links = [
    { to: '/conversations', label: 'Conversations', icon: MessagesSquare },
    { to: '/invoices', label: 'Your invoices', icon: Receipt },
  ]
  return (
    <section className="grid gap-3 sm:grid-cols-2 lg:hidden">
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
