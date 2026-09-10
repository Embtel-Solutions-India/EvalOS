import { useQuery } from '@tanstack/react-query'
import { FileCheck2 } from 'lucide-react'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { RequestCard } from '@/components/intake/RequestCard'
import { listRequests } from '@/services/intakeService'

export default function Requests() {
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['requests'], queryFn: listRequests })

  return (
    <div>
      <PageHeader
        title="My Requests"
        description="Every request of yours, and where it stands."
      />

      {isLoading && <ListSkeleton />}
      {isError && <ErrorState description="We couldn't load your requests." onRetry={() => void refetch()} />}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState
          icon={FileCheck2}
          title="No requests yet"
          description="Nothing here yet. When a request of yours is opened, it will appear here."
        />
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="space-y-3">
          {data.map((request) => (
            <RequestCard key={request.id} request={request} />
          ))}
        </div>
      )}
    </div>
  )
}
