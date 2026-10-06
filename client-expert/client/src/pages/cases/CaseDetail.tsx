import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { Card } from '@shared/components/ui/card'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { DELIVERED_STEP, failureMessage, serviceLabel, type StatusEntry } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { formatDate } from '@shared/utils/formatters'
import { CaseDelivered } from '@/components/case/CaseDelivered'
import { CaseChecklist, CaseDocuments } from '@/components/case/CaseDocuments'
import { CaseDraft } from '@/components/case/CaseDraft'
import { readCase } from '@/services/caseService'

/**
 * One case, everything about it (Unit 58 §4): uploads, delivered files and history on the left; the
 * document checklist with the draft under it on the right. The case's conversation is not on this
 * page — it lives in Conversations (2026-09-30).
 *
 * **Every status, label and remark is the server's** (D74): the current status card and the dated
 * history are `status` and `history`, already in client words, and which draft version is answerable
 * is `inReview`. A hold shows its reason as the remark.
 */
export default function CaseDetail() {
  const { caseId = '' } = useParams<{ caseId: string }>()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'case', caseId],
    queryFn: ({ signal }) => readCase(caseId, signal),
    retry: false,
  })

  if (isLoading) return <ListSkeleton />
  if (isError) {
    return (
      <ErrorState
        description={statusOf(error) === 403 ? 'This case is not on your account.' : failureMessage(statusOf(error))}
        onRetry={() => void refetch()}
      />
    )
  }
  if (!data) return null

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <PageHeader title={`Case #${data.caseReference}`} description={serviceLabel(data.serviceType)} />
      {data.status && <StatusCard status={data.status} />}

      <div className="grid grid-cols-[minmax(0,1fr)] items-start gap-6 lg:grid-cols-[minmax(0,1fr)_24rem]">
        <div className="min-w-0 space-y-6">
          <Section id="documents" title="Documents">
            <CaseDocuments caseId={caseId} />
          </Section>
          {data.stepIndex === DELIVERED_STEP && (
            <Section title="Delivered">
              <CaseDelivered caseId={caseId} />
            </Section>
          )}
        </div>

        <div className="order-first min-w-0 space-y-6 lg:order-none">
          <Section title="Document checklist">
            <CaseChecklist caseId={caseId} />
          </Section>
          <Section title="Draft">
            <CaseDraft caseId={caseId} legacyLink={data.draftLink} />
          </Section>
          <Section title="History">
            <History history={data.history} />
          </Section>
        </div>
      </div>
    </div>
  )
}

function Section({ id, title, children }: { id?: string; title: string; children: ReactNode }) {
  return (
    <Card id={id} className="min-w-0 scroll-mt-20 space-y-3 p-4 sm:p-5">
      <h2 className="text-sm font-semibold text-foreground">{title}</h2>
      {children}
    </Card>
  )
}

function StatusCard({ status }: { status: StatusEntry }) {
  return (
    <Card className="space-y-2 p-4 sm:p-5" aria-label="Current status">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Current status</p>
      <p className="text-lg font-semibold text-foreground">{status.label}</p>
      <p className="text-sm text-muted-foreground">{status.description}</p>
      <Remark entry={status} />
    </Card>
  )
}

/** The latest client-facing remark under a status, with its date; nothing when staff wrote none. */
function Remark({ entry }: { entry: StatusEntry }) {
  if (!entry.remark) return null
  return (
    <div className="rounded-md bg-muted/50 p-3 text-sm">
      <p className="whitespace-pre-wrap text-foreground">{entry.remark.body}</p>
      <p className="mt-1 text-xs text-muted-foreground">{formatDate(entry.remark.at, 'short')}</p>
    </div>
  )
}

function History({ history }: { history: StatusEntry[] }) {
  if (history.length === 0) return <p className="text-sm text-muted-foreground">Nothing yet.</p>
  return (
    <ol className="space-y-3 border-l pl-4">
      {history.map((entry, i) => (
        <li key={`${entry.key}-${i}`} className="space-y-1 text-sm">
          <span className="font-medium text-foreground">{entry.label}</span>
          <span className="ml-2 text-xs text-muted-foreground">{formatDate(entry.at, 'short')}</span>
          <Remark entry={entry} />
        </li>
      ))}
    </ol>
  )
}
