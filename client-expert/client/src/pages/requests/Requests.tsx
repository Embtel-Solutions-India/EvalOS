import { useQuery } from '@tanstack/react-query'
import { FileCheck2, Plus } from 'lucide-react'
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
import { listApplications, type ClientApplication } from '@/services/applicationService'
import { listCases } from '@/services/draftService'
import { formatDateShort } from '@shared/utils/formatters'

/**
 * Everything the client has asked us for, and everything we are doing (34d, extended Unit 43).
 *
 * **Two lists, because they are two different things and the client experiences both.** A
 * *request* is what they sent us and Sales has not yet priced; a *case* is work in progress, born
 * of a won opportunity through Handoff A. Merging them into one list would need this app to
 * invent a combined status vocabulary spanning both, which is exactly what D5 forbids — every
 * word about state here is the server's.
 *
 * **A request does not become a case on this screen.** It disappears from the top list and a case
 * appears in the lower one, days later, when the client has paid. Nothing in the portal links the
 * two, because EvalOS itself does not: the case is created by the webhook from the opportunity,
 * and `client_application` holds the opportunity id, not a case id.
 *
 * **Needs a party-scoped link.** A case-scoped one names a single case and is refused (403); the
 * message says which link to use rather than showing a generic failure.
 */
export default function Requests() {
  const tokenPresent = usePortalToken()

  const cases = useQuery({
    queryKey: ['portal', 'cases'],
    queryFn: ({ signal }) => listCases(signal),
    enabled: tokenPresent,
    retry: false,
  })

  const applications = useQuery({
    queryKey: ['portal', 'applications'],
    queryFn: ({ signal }) => listApplications(signal),
    enabled: tokenPresent,
    retry: false,
  })

  if (!tokenPresent) {
    return (
      <div>
        <PageHeader title="My requests" description={NO_TOKEN} />
      </div>
    )
  }

  const draft = applications.data?.find((item) => item.status === 'DRAFT')
  const nothingAtAll =
    !cases.isLoading &&
    !applications.isLoading &&
    cases.data?.length === 0 &&
    applications.data?.length === 0

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <PageHeader title="My requests" description="Everything of yours, and where it stands." />
        <Button asChild>
          <Link to="/requests/new">
            <Plus className="mr-2 h-4 w-4" aria-hidden="true" />
            {/* The one in progress is resumed rather than duplicated — the server returns the
                open draft instead of opening a second, so this button is honest either way. */}
            {draft ? 'Continue your request' : 'Request a service'}
          </Link>
        </Button>
      </div>

      {(cases.isLoading || applications.isLoading) && <ListSkeleton />}

      {cases.isError && (
        <ErrorState
          description={
            // A case-scoped link cannot list cases — it names one. A different link fixes it,
            // so saying so beats sending the client to support.
            statusOf(cases.error) === 403
              ? 'This link opens a single case rather than your account. Use the link we sent for that case.'
              : failureMessage(statusOf(cases.error))
          }
          onRetry={() => void cases.refetch()}
        />
      )}

      {nothingAtAll && (
        <EmptyState
          icon={FileCheck2}
          title="Nothing here yet"
          description="Tell us what you need evaluated and we'll come back to you with a price."
        />
      )}

      {applications.data && applications.data.length > 0 && (
        <section>
          <h2 className="text-sm font-semibold text-foreground">Requests with us</h2>
          <div className="mt-3 grid gap-3">
            {applications.data.map((item) => (
              <ApplicationRow key={item.id} item={item} />
            ))}
          </div>
        </section>
      )}

      {cases.data && cases.data.length > 0 && (
        <section>
          <h2 className="text-sm font-semibold text-foreground">Work in progress</h2>
          <div className="mt-3 grid gap-3">
            {cases.data.map((item) => (
              <CaseRow key={item.caseId} item={item} />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

/**
 * A request, before it is a case.
 *
 * **Two words about state and both are the server's `status`**, mapped to a sentence here rather
 * than rendered raw, because `DRAFT` and `SUBMITTED` are EvalOS's vocabulary and not a client's.
 * That is the narrowest possible exception to D5 — a label for a two-valued enum this app itself
 * writes by calling submit — and it is not a lifecycle: no stage, no progress bar, no ordering.
 */
function ApplicationRow({ item }: { item: ClientApplication }) {
  const unfinished = item.status === 'DRAFT'
  const row = (
    <Card className="flex flex-wrap items-center justify-between gap-2 p-4">
      <div>
        <p className="text-sm font-medium text-foreground">{item.serviceName}</p>
        <p className="text-xs text-muted-foreground">
          {unfinished
            ? 'Not sent yet — pick up where you left off.'
            : `Sent ${formatDateShort(item.submittedAt ?? item.updatedAt)}. We'll be in touch.`}
        </p>
      </div>
      {unfinished && <Badge>Unfinished</Badge>}
    </Card>
  )

  return unfinished ? (
    <Link to="/requests/new" className="block [&>*]:hover:bg-accent">
      {row}
    </Link>
  ) : (
    row
  )
}

function CaseRow({ item }: { item: ClientCaseSummary }) {
  return (
    <Link to={`/draft/${item.caseId}`} className="block">
      <Card className="flex flex-wrap items-center justify-between gap-2 p-4 hover:bg-accent">
        <div>
          <p className="text-sm font-medium text-foreground">{item.caseReference}</p>
          {/*
            `step` is a sentence the server wrote. Mapping it to an icon or a progress bar here
            would mean this app deciding what the stages are, which is the thing D5 forbids.
          */}
          <p className="text-xs text-muted-foreground">{item.step}</p>
        </div>
        {item.actionRequired && <Badge>Needs you</Badge>}
      </Card>
    </Link>
  )
}
