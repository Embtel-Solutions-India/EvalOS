import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Download, FileCheck2, RefreshCw } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent } from '@shared/components/ui/card'
import { Skeleton } from '@shared/components/ui/skeleton'
import { ErrorState } from '@shared/components/common/ErrorState'
import { EVALUATION_TYPE_OPTIONS } from '@/constants/evaluation'
import { getReport } from '@/services/reportService'
import { formatDate } from '@shared/utils/formatters'

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>()
  const { data: report, isLoading, isError, refetch } = useQuery({
    queryKey: ['reports', id],
    queryFn: () => getReport(id as string),
    enabled: Boolean(id),
  })

  return (
    <div>
      <Button asChild variant="ghost" size="sm" className="mb-3 -ml-2">
        <Link to="/reports">
          <ArrowLeft className="h-4 w-4" />
          Back to Reports
        </Link>
      </Button>

      {isLoading && <Skeleton className="h-64 w-full rounded-xl" />}
      {isError && <ErrorState description="We couldn't load this report." onRetry={() => void refetch()} />}
      {!isLoading && !isError && !report && (
        <ErrorState title="Report not found" description="This report does not exist or is unavailable." />
      )}

      {!isLoading && report && (
        <Card className="mx-auto max-w-2xl">
          <CardContent className="pt-6">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-3">
                <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <FileCheck2 className="h-5 w-5" />
                </span>
                <div>
                  <p className="text-base font-semibold text-foreground">Evaluation Report</p>
                  <p className="text-sm text-muted-foreground">Report #{report.reportNumber}</p>
                </div>
              </div>
              <Badge variant={report.status === 'ready' ? 'success' : 'muted'}>
                {report.status === 'ready' ? 'Ready' : 'Not Ready'}
              </Badge>
            </div>

            <dl className="mt-6 space-y-3 border-t pt-6 text-sm">
              <div className="flex justify-between">
                <dt className="text-muted-foreground">Application</dt>
                <dd className="font-medium text-foreground">{report.applicationReference}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">Evaluation Type</dt>
                <dd className="font-medium text-foreground">
                  {EVALUATION_TYPE_OPTIONS.find((option) => option.value === report.evaluationType)?.label}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">Issue Date</dt>
                <dd className="font-medium text-foreground">
                  {report.issueDate ? formatDate(report.issueDate) : 'Report not yet available.'}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">Status</dt>
                <dd className="font-medium text-foreground">{report.status === 'ready' ? 'Ready' : 'Not Ready'}</dd>
              </div>
            </dl>

            <div className="mt-6 flex flex-wrap gap-2 border-t pt-6">
              <Button disabled={report.status !== 'ready'} onClick={() => toast.info('Report preview is a mock in this development phase.')}>
                View
              </Button>
              <Button
                variant="outline"
                disabled={report.status !== 'ready'}
                onClick={() => toast.success('Report download started.')}
              >
                <Download className="h-3.5 w-3.5" />
                Download
              </Button>
              <Button
                variant="outline"
                disabled={report.status !== 'ready'}
                onClick={() => toast.success('Additional copy request submitted.')}
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Request Additional Copy
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
