import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Inbox } from 'lucide-react'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { statusOf } from '@shared/services/apiClient'
import { Answers } from '@/components/Answers'
import { expertFailureMessage, humanize } from '@/lib/expertCase'
import { listCases } from '@/services/expertPortalService'

/**
 * Cases offered to this expert that they have not answered yet (`ExpertCaseSummary.offered`, an
 * open `expert_case_offer`). Each one can be answered here, or opened to read the letter first.
 */
export default function NewCases() {
  const queryClient = useQueryClient()
  const [answering, setAnswering] = useState<string | null>(null)
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'cases'],
    queryFn: listCases,
    retry: false,
  })

  const offered = data?.filter((c) => c.offered) ?? []
  // An answered offer leaves this list, so the list and any open case are re-read.
  const onAnswered = () => {
    setAnswering(null)
    void queryClient.invalidateQueries({ queryKey: ['expert-portal'] })
  }

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title="New cases" description="Cases offered to you. Accept, ask for more evidence, or decline." />

      {isLoading && <ListSkeleton />}
      {isError && <ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} />}
      {data && offered.length === 0 && (
        <EmptyState icon={Inbox} title="No new cases" description="A case appears here as soon as you are offered one." />
      )}

      <ul className="space-y-4">
        {offered.map((c) => (
          <li key={c.caseId} className="space-y-3">
            <Card className="flex flex-col gap-3 p-4 shadow-[inset_3px_0_0_hsl(var(--info))] sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p className="font-medium text-foreground">{c.caseReference ?? 'Case'}</p>
                <p className="text-sm text-muted-foreground">
                  {humanize(c.serviceType) || 'Expert opinion letter'}
                  {c.step ? ` · ${c.step}` : ''}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" asChild>
                  <Link to={`/case?caseId=${c.caseId}`}>Open case</Link>
                </Button>
                <Button
                  aria-expanded={answering === c.caseId}
                  onClick={() => setAnswering(answering === c.caseId ? null : c.caseId)}
                >
                  {answering === c.caseId ? 'Close' : 'Your answer'}
                </Button>
              </div>
            </Card>
            {answering === c.caseId && <Answers caseId={c.caseId} onChanged={onAnswered} />}
          </li>
        ))}
      </ul>
    </div>
  )
}
