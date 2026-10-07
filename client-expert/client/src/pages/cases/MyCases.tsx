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
import { listCases } from '@/services/caseService'

/**
 * Every case of the client's, and where each stands (was "My requests" until Unit 64 removed the
 * request, 2026-09-29). A case is born of a won opportunity through Handoff A; nothing here starts
 * one. Every word about state is the server's (D5).
 *
 * **Needs a party-scoped link.** A case-scoped one names a single case and is refused (403); the
 * message says which link to use rather than showing a generic failure.
 */
export default function MyCases() {
  const tokenPresent = usePortalToken()

  const cases = useQuery({
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
    <div className="space-y-6">
      <PageHeader title="My cases" description="Every case of yours, and where it stands." />

      {cases.isLoading && <ListSkeleton />}

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

      {cases.data?.length === 0 && (
        <EmptyState icon={FileCheck2} title="No cases yet" description="Your cases appear here once our team starts one." />
      )}

      {cases.data && cases.data.length > 0 && (
        <div className="grid gap-3">
          {cases.data.map((item) => (
            <CaseRow key={item.caseId} item={item} />
          ))}
        </div>
      )}
    </div>
  )
}

function CaseRow({ item }: { item: ClientCaseSummary }) {
  return (
    <Link to={`/cases/${item.caseId}`} className="block">
      <Card className="flex flex-wrap items-center justify-between gap-2 p-4 hover:bg-accent">
        <div>
          <p className="text-sm font-medium text-foreground">{item.caseReference}</p>
          {/*
            `step` is a sentence the server wrote. Mapping it to an icon or a progress bar here
            would mean this app deciding what the stages are, which is the thing D5 forbids.
          */}
          <p className="text-xs text-muted-foreground">{item.step}</p>
        </div>
        {item.actionRequired && <Badge variant="destructive" className="bg-destructive text-destructive-foreground">Needs you</Badge>}
      </Card>
    </Link>
  )
}
