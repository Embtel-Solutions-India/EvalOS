import { useQuery } from '@tanstack/react-query'
import { Link, Navigate } from 'react-router-dom'
import { Badge } from '@shared/components/ui/badge'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { hasPortalToken, statusOf } from '@shared/services/apiClient'
import { FileText } from 'lucide-react'
import { expertFailureMessage } from '@/lib/expertCase'
import { listCases } from '@/services/expertPortalService'

/** A signed-in expert's cases (Unit 59); each opens `/case?caseId=`. The token is memory-only, so a reload signs out. */
export default function Cases() {
  const signedIn = hasPortalToken()
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'cases'],
    queryFn: listCases,
    enabled: signedIn,
    retry: false,
  })

  if (!signedIn) return <Navigate to="/" replace />

  return (
    <div className="mx-auto max-w-3xl space-y-4 p-6">
      <PageHeader title="Your cases" description="The letters you have been offered or are working on." />
      {isLoading && <ListSkeleton />}
      {isError && <ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} />}
      {data?.length === 0 && (
        <EmptyState icon={FileText} title="No cases yet" description="A case appears here as soon as you are offered one." />
      )}
      {data?.map((item) => (
        <Link key={item.caseId} to={`/case?caseId=${item.caseId}`} className="block">
          <Card className={`flex items-center justify-between gap-2 p-4 hover:bg-accent ${item.actionRequired ? 'border-primary' : ''}`}>
            <div>
              <p className="text-sm font-medium text-foreground">{item.caseReference ?? 'Case'}</p>
              {item.step && <p className="text-xs text-muted-foreground">{item.step}</p>}
            </div>
            {item.actionRequired && <Badge>Needs you</Badge>}
          </Card>
        </Link>
      ))}
    </div>
  )
}
