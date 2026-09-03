import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, FastForward, TriangleAlert } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Skeleton } from '@shared/components/ui/skeleton'
import { ErrorState } from '@shared/components/common/ErrorState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { Timeline } from '@/components/common/Timeline'
import { IntakeDocumentStatusBadge } from '@/components/intake/IntakeDocumentStatusBadge'
import { RequestStatusBadge } from '@/components/intake/RequestStatusBadge'
import { getService } from '@/constants/serviceCatalog'
import {
  REQUEST_STATUS_DESCRIPTIONS,
  REQUEST_STATUS_LABELS,
  advanceRequestStatus,
  getRequest,
} from '@/services/intakeService'
import type { RequestStatus } from '@/types/intake'
import { formatDate } from '@shared/utils/formatters'

const LIFECYCLE: RequestStatus[] = ['received', 'information_review', 'documents_review', 'in_progress', 'final_review', 'completed']

export default function RequestDetail() {
  const { id } = useParams<{ id: string }>()
  const queryClient = useQueryClient()
  const { data: request, isLoading, isError, refetch } = useQuery({
    queryKey: ['requests', id],
    queryFn: () => getRequest(id as string),
    enabled: Boolean(id),
  })

  const service = request ? getService(request.serviceId) : undefined

  return (
    <div>
      <Button asChild variant="ghost" size="sm" className="mb-3 -ml-2">
        <Link to="/requests">
          <ArrowLeft className="h-4 w-4" />
          Back to My Requests
        </Link>
      </Button>

      {isLoading && <Skeleton className="h-64 w-full rounded-xl" />}
      {isError && <ErrorState description="We couldn't load this request." onRetry={() => void refetch()} />}
      {!isLoading && !isError && !request && (
        <ErrorState title="Request not found" description="This request does not exist or is unavailable." />
      )}

      {!isLoading && request && (
        <>
          <PageHeader
            title={service?.name ?? 'Request'}
            description={`${request.referenceNumber} · Submitted ${formatDate(request.createdAt)}`}
            actions={<RequestStatusBadge status={request.status} />}
          />

          {request.actionRequired && (
            <Card className="mb-6 border-warning/30 bg-warning/5">
              <CardContent className="flex flex-col gap-3 pt-6 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex items-start gap-3">
                  <TriangleAlert className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
                  <div>
                    <p className="text-sm font-semibold text-foreground">Action Required</p>
                    <p className="text-sm text-muted-foreground">{request.actionRequired}</p>
                  </div>
                </div>
                <Button asChild size="sm" className="shrink-0">
                  <Link to="/documents">Upload Document</Link>
                </Button>
              </CardContent>
            </Card>
          )}

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
            <Card className="lg:col-span-2">
              <CardHeader>
                <CardTitle>Status</CardTitle>
                <p className="text-sm text-muted-foreground">{REQUEST_STATUS_DESCRIPTIONS[request.status]}</p>
              </CardHeader>
              <CardContent>
                <Timeline
                  steps={LIFECYCLE.map((status) => {
                    const event = request.history.find((item) => item.status === status)
                    return {
                      key: status,
                      label: REQUEST_STATUS_LABELS[status],
                      completedAt: event?.occurredAt,
                      isCurrent: status === request.status,
                    }
                  })}
                />
              </CardContent>
            </Card>

            <div className="space-y-6">
              <Card>
                <CardHeader>
                  <CardTitle>Documents</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  {request.documents.map((document) => (
                    <div key={document.id} className="flex items-center justify-between gap-3">
                      <span className="text-sm text-foreground">{document.name}</span>
                      <IntakeDocumentStatusBadge status={document.status} />
                    </div>
                  ))}
                </CardContent>
              </Card>

              {request.status !== 'completed' && (
                <Card>
                  <CardHeader>
                    <CardTitle>Demo</CardTitle>
                    <p className="text-sm text-muted-foreground">
                      There's no live case management system behind this portal yet — use this to preview what
                      the next status looks like.
                    </p>
                  </CardHeader>
                  <CardContent>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        advanceRequestStatus(request.id)
                        void queryClient.invalidateQueries({ queryKey: ['requests'] })
                      }}
                    >
                      <FastForward className="h-3.5 w-3.5" />
                      Simulate Next Status
                    </Button>
                  </CardContent>
                </Card>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
