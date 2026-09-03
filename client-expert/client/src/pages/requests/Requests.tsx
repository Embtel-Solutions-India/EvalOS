import { useQuery } from '@tanstack/react-query'
import { FileCheck2, Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
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
        description="Every request you've submitted, and where it stands."
        actions={
          <Button asChild>
            <Link to="/start/service">
              <Plus className="h-4 w-4" />
              Start a New Request
            </Link>
          </Button>
        }
      />

      {isLoading && <ListSkeleton />}
      {isError && <ErrorState description="We couldn't load your requests." onRetry={() => void refetch()} />}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState
          icon={FileCheck2}
          title="No requests yet"
          description="Start a new request and we'll guide you through what's needed."
          action={
            <Button asChild>
              <Link to="/start/service">Start a New Request</Link>
            </Button>
          }
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
