import { useQuery } from '@tanstack/react-query'
import { FileCheck2 } from 'lucide-react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@shared/components/ui/tabs'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { ExpertCaseCard } from '@/components/expert/ExpertCaseCard'
import { useExpertAuth } from '@/hooks/useExpertAuth'
import { listCases } from '@/services/expertCaseService'
import type { ExpertCase } from '@/types/expert'

function CaseList({ cases, emptyLabel }: { cases: ExpertCase[]; emptyLabel: string }) {
  if (cases.length === 0) {
    return <EmptyState className="mt-4" icon={FileCheck2} title={emptyLabel} />
  }
  return (
    <div className="mt-4 space-y-3">
      {cases.map((expertCase) => (
        <ExpertCaseCard key={expertCase.id} expertCase={expertCase} />
      ))}
    </div>
  )
}

export default function ExpertDashboard() {
  const { expert } = useExpertAuth()
  const { data, isLoading } = useQuery({ queryKey: ['expert-cases'], queryFn: listCases })

  const cases = data ?? []
  const pendingReview = cases.filter((c) => c.signingStatus === 'pending')
  const pendingSignature = cases.filter((c) => c.signingStatus === 'viewed')
  const signed = cases.filter((c) => c.signingStatus === 'signed')

  return (
    <div>
      <PageHeader title={`Welcome, ${expert?.firstName ?? ''}`} description="Your assigned cases, at a glance." />

      {isLoading && <ListSkeleton />}

      {!isLoading && cases.length === 0 && (
        <EmptyState icon={FileCheck2} title="No cases assigned yet" description="Assigned cases will show up here as soon as they're ready for your review." />
      )}

      {!isLoading && cases.length > 0 && (
        <Tabs defaultValue="all">
          <TabsList>
            <TabsTrigger value="all">All ({cases.length})</TabsTrigger>
            <TabsTrigger value="pending-review">Pending Review ({pendingReview.length})</TabsTrigger>
            <TabsTrigger value="pending-signature">Pending Signature ({pendingSignature.length})</TabsTrigger>
            <TabsTrigger value="signed">Signed ({signed.length})</TabsTrigger>
          </TabsList>

          <TabsContent value="all">
            <CaseList cases={cases} emptyLabel="No cases yet" />
          </TabsContent>
          <TabsContent value="pending-review">
            <CaseList cases={pendingReview} emptyLabel="Nothing pending review" />
          </TabsContent>
          <TabsContent value="pending-signature">
            <CaseList cases={pendingSignature} emptyLabel="Nothing awaiting signature" />
          </TabsContent>
          <TabsContent value="signed">
            <CaseList cases={signed} emptyLabel="No signed cases yet" />
          </TabsContent>
        </Tabs>
      )}
    </div>
  )
}
