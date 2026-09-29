import { useQuery } from '@tanstack/react-query'
import { Button } from '@shared/components/ui/button'
import { ErrorState } from '@shared/components/common/ErrorState'
import { failureMessage } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { openFile } from '@/lib/openFile'
import { deliveredUrl, listDelivered } from '@/services/caseService'

const LABEL = { SIGNED_LETTER: 'Signed letter', APPROVED_DRAFT: 'Approved draft' } as const

/** The signed letter and the approved draft. Mounted only once delivered — the server refuses before. */
export function CaseDelivered({ caseId }: { caseId: string }) {
  const { data, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'case', caseId, 'delivered'],
    queryFn: ({ signal }) => listDelivered(caseId, signal),
    retry: false,
  })

  if (isError) return <ErrorState description={failureMessage(statusOf(error))} onRetry={() => void refetch()} />
  return (
    <div className="space-y-2">
      {data?.map((file) => (
        <div key={file.id} className="flex items-center justify-between gap-2 text-sm">
          <span className="min-w-0 truncate">
            <strong>{LABEL[file.kind]}</strong>
            {file.filename && <span className="ml-2 text-muted-foreground">{file.filename}</span>}
          </span>
          <Button variant="outline" size="sm" onClick={() => void openFile(() => deliveredUrl(caseId, file.id))}>
            Download
          </Button>
        </div>
      ))}
    </div>
  )
}
