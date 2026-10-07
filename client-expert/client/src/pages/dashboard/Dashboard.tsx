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
import { getMe, listCases } from '@/services/caseService'

/**
 * Where the client lands: what needs them, then everything else (34d).
 *
 * **Real cases, and a greeting by time of day plus the client's first name (2026-10-07).** The
 * name once came from a mock session and was dropped; it is back because the business asked, and now
 * from `GET /portal/client/me` — the token's own `client_account` in the token's brand, first name
 * only. A token with no account (or no first name) greets by time of day alone.
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

  // The first name for the greeting; without one (or while it loads) the greeting stands alone.
  const { data: me } = useQuery({ queryKey: ['portal', 'me'], queryFn: ({ signal }) => getMe(signal), enabled: tokenPresent, retry: false, staleTime: Infinity })

  if (!tokenPresent) {
    return <PageHeader title="Your cases" description={NO_TOKEN} />
  }

  const active = (data ?? []).filter((item) => item.stepIndex < DELIVERED_STEP)
  const needsYou = active.filter((item) => item.actionRequired)
  const delivered = (data ?? []).filter((item) => item.stepIndex >= DELIVERED_STEP)
  const byAction = [...needsYou, ...active.filter((item) => !item.actionRequired)]

  return (
    <div className="space-y-6">
      <p className="font-serif text-2xl font-normal tracking-tight text-foreground sm:text-3xl">{me?.firstName ? `${greeting()}, ${me.firstName}!` : `${greeting()}!`}</p>

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

/** By the client's own clock. */
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
        {item.actionRequired && <Badge variant="destructive" className="rounded-full bg-destructive px-3 py-1 text-sm text-destructive-foreground">Needs you</Badge>}
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
