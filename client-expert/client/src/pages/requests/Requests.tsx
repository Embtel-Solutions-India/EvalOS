import { useQuery } from '@tanstack/react-query'
import { FileCheck2 } from 'lucide-react'
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
 * Every case behind the client's link, and where each one stands (34d).
 *
 * **Real cases, against `GET /api/portal/client/cases`.** This screen read a mock
 * `intakeService.listRequests` until 34d; the read it needed did not exist until Unit 35's D1
 * made a portal credential name a *party* rather than a case.
 *
 * **Every word about state is the server's.** `step` is a phrase EvalOS renders from
 * `PortalStageProjection`, and `actionRequired` is its flag. This app holds **no lifecycle
 * enum** — that was D5's rule, and it is why the twelve production stages do not appear here in
 * any form.
 *
 * **Needs a party-scoped link.** A case-scoped one names a single case and is refused (403); the
 * message says which link to use rather than showing a generic failure.
 */
export default function Requests() {
  const tokenPresent = usePortalToken()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'cases'],
    queryFn: ({ signal }) => listCases(signal),
    enabled: tokenPresent,
    retry: false,
  })

  if (!tokenPresent) {
    return (
      <div>
        <PageHeader title="My cases" description={NO_TOKEN} />
      </div>
    )
  }

  return (
    <div>
      <PageHeader title="My cases" description="Everything of yours, and where it stands." />

      {isLoading && <ListSkeleton />}

      {isError && (
        <ErrorState
          description={
            // A case-scoped link cannot list cases — it names one. A different link fixes it,
            // so saying so beats sending the client to support.
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

      {data && data.length > 0 && (
        <div className="mt-4 grid gap-3">
          {data.map((item) => <CaseRow key={item.caseId} item={item} />)}
        </div>
      )}
    </div>
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
