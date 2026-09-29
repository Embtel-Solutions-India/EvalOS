import { useQuery } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import type { ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { Card } from '@shared/components/ui/card'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { CLIENT_STEPS, DELIVERED_STEP, failureMessage, serviceLabel, type Milestone } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { cn } from '@shared/utils/cn'
import { formatDateShort } from '@shared/utils/formatters'
import { CaseDelivered } from '@/components/case/CaseDelivered'
import { CaseChecklist, CaseDocuments } from '@/components/case/CaseDocuments'
import { CaseDraft } from '@/components/case/CaseDraft'
import { readCase } from '@/services/caseService'

/**
 * One case, everything about it (Unit 58 §4): uploads, delivered files and history on the left; the
 * document checklist with the draft under it on the right. The case's conversation is not on this
 * page — it lives in Conversations (2026-09-30).
 *
 * **Every step, label and flag is the server's.** `stepIndex` places the stepper, `milestones` are
 * already in client words, and which draft version is answerable is `inReview`.
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
      <Stepper index={data.stepIndex} />

      <div className="grid items-start gap-6 lg:grid-cols-[1fr_24rem]">
        <div className="space-y-6">
          <Section id="documents" title="Documents">
            <CaseDocuments caseId={caseId} />
          </Section>
          {data.stepIndex === DELIVERED_STEP && (
            <Section title="Delivered">
              <CaseDelivered caseId={caseId} />
            </Section>
          )}
          <Section title="History">
            <History milestones={data.milestones} />
          </Section>
        </div>

        <div className="order-first space-y-6 lg:order-none">
          <Section title="Document checklist">
            <CaseChecklist caseId={caseId} />
          </Section>
          <Section title="Draft">
            <CaseDraft caseId={caseId} legacyLink={data.draftLink} />
          </Section>
        </div>
      </div>
    </div>
  )
}

function Section({ id, title, children }: { id?: string; title: string; children: ReactNode }) {
  return (
    <Card id={id} className="scroll-mt-20 space-y-3 p-5">
      <h2 className="text-sm font-semibold text-foreground">{title}</h2>
      {children}
    </Card>
  )
}

function Stepper({ index }: { index: number }) {
  return (
    <ol className="grid grid-cols-4 gap-2" aria-label="Case progress">
      {CLIENT_STEPS.map((label, i) => (
        <li key={label} aria-current={i === index ? 'step' : undefined} className="space-y-1">
          <div className={cn('h-1.5 rounded-full', i <= index ? 'bg-primary' : 'bg-muted')} />
          <p className={cn('flex items-center gap-1 text-xs', i === index ? 'font-semibold text-foreground' : 'text-muted-foreground')}>
            {i < index && <Check className="h-3 w-3" aria-hidden="true" />}
            {label}
          </p>
        </li>
      ))}
    </ol>
  )
}

function History({ milestones }: { milestones: Milestone[] }) {
  if (milestones.length === 0) return <p className="text-sm text-muted-foreground">Nothing yet.</p>
  return (
    <ol className="space-y-2 border-l pl-4">
      {milestones.map((m, i) => (
        <li key={`${m.label}-${i}`} className="text-sm">
          <span className="font-medium text-foreground">{m.label}</span>
          <span className="ml-2 text-xs text-muted-foreground">{formatDateShort(m.at)}</span>
        </li>
      ))}
    </ol>
  )
}
