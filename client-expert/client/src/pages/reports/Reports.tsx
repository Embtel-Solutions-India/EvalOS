import { useQuery } from '@tanstack/react-query'
import { Download, FileCheck2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { CardGridSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { EVALUATION_TYPE_OPTIONS } from '@/constants/evaluation'
import { listReports } from '@/services/reportService'
import { formatDate } from '@shared/utils/formatters'

export default function Reports() {
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['reports'], queryFn: listReports })

  return (
    <div>
      <PageHeader title="Evaluation Reports" description="Access your completed credential evaluation reports." />

      {isLoading && <CardGridSkeleton count={2} />}
      {isError && <ErrorState description="We couldn't load your reports." onRetry={() => void refetch()} />}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState icon={FileCheck2} title="No Reports" description="Completed reports will appear here." />
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {data.map((report) => (
            <Card key={report.id}>
              <CardContent className="pt-6">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-semibold text-foreground">Evaluation Report</p>
                    <p className="text-xs text-muted-foreground">Report #{report.reportNumber}</p>
                  </div>
                  <Badge variant={report.status === 'ready' ? 'success' : 'muted'}>
                    {report.status === 'ready' ? 'Ready' : 'Not Ready'}
                  </Badge>
                </div>
                <p className="mt-4 text-sm text-muted-foreground">
                  {EVALUATION_TYPE_OPTIONS.find((option) => option.value === report.evaluationType)?.label}
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  Issued: {report.issueDate ? formatDate(report.issueDate) : 'Report not yet available.'}
                </p>
                <div className="mt-5 flex gap-2">
                  {report.status === 'ready' ? (
                    <>
                      <Button asChild size="sm">
                        <Link to={`/reports/${report.id}`}>View Report</Link>
                      </Button>
                      <Button variant="outline" size="sm" onClick={() => toast.success('Report download started.')}>
                        <Download className="h-3.5 w-3.5" />
                        Download
                      </Button>
                    </>
                  ) : (
                    <Button size="sm" variant="outline" disabled>
                      Report not yet available
                    </Button>
                  )}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
