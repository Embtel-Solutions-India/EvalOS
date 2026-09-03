import { CheckCircle2, Pencil } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Checkbox } from '@shared/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@shared/components/ui/dialog'
import { IntakeDocumentStatusBadge } from '@/components/intake/IntakeDocumentStatusBadge'
import { IntakeProgress } from '@/components/intake/IntakeProgress'
import { EVALUATION_PURPOSE_OPTIONS } from '@/constants/evaluation'
import { getQuestionGroups } from '@/constants/questionGroups'
import { getService } from '@/constants/serviceCatalog'
import { useAuth } from '@/hooks/useAuth'
import { getDraft, submitRequest } from '@/services/intakeService'
import { getVisibleQuestions } from '@/lib/questionnaire'
import { formatFileSize } from '@shared/utils/formatters'
import type { ClientRequest } from '@/types/intake'

function SummaryRow({ label, value }: { label: string; value?: string }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:justify-between sm:gap-4">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span className="text-sm font-medium text-foreground">{value || '—'}</span>
    </div>
  )
}

function SectionCard({ title, editPath, children }: { title: string; editPath: string; children: ReactNode }) {
  const navigate = useNavigate()
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle>{title}</CardTitle>
        <Button type="button" variant="outline" size="sm" onClick={() => navigate(editPath)}>
          <Pencil className="h-3.5 w-3.5" />
          Edit
        </Button>
      </CardHeader>
      <CardContent className="space-y-2.5">{children}</CardContent>
    </Card>
  )
}

export default function IntakeReview() {
  const navigate = useNavigate()
  const { markProfileCompleted } = useAuth()
  // Captured once at mount: submitting calls clearDraft(), and re-reading
  // getDraft() on the re-render that follows would return the empty draft,
  // making `service` undefined and tripping the `!service` guard below —
  // wiping the page (and its confirmation dialog) right as it should show.
  const [draft] = useState(() => getDraft())
  const service = getService(draft.serviceId)
  const [confirmed, setConfirmed] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [submittedRequest, setSubmittedRequest] = useState<ClientRequest | null>(null)

  if (!service) return null

  const groups = getQuestionGroups(service.questionGroupIds)
  const answeredQuestions = groups.flatMap((group) => getVisibleQuestions(group, draft.answers))

  async function handleSubmit() {
    setIsSubmitting(true)
    try {
      const request = await submitRequest()
      markProfileCompleted()
      setSubmittedRequest(request)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div>
      <IntakeProgress currentIndex={3} />

      <div className="mt-6 space-y-6">
        <SectionCard title="Your Request" editPath="/start/service">
          <SummaryRow label="Service" value={service.name} />
          <SummaryRow
            label="Purpose"
            value={EVALUATION_PURPOSE_OPTIONS.find((option) => option.value === draft.purpose)?.label}
          />
        </SectionCard>

        <SectionCard title="Your Information" editPath="/start/about-you">
          <SummaryRow label="Name" value={draft.aboutYou?.fullName} />
          <SummaryRow label="Email" value={draft.aboutYou?.email} />
          <SummaryRow label="Phone" value={draft.aboutYou?.phone} />
          <SummaryRow label="Country" value={draft.aboutYou?.countryOfResidence} />
        </SectionCard>

        <SectionCard title="Your Answers" editPath="/start/questions">
          {answeredQuestions.map((question) => (
            <SummaryRow key={question.id} label={question.label} value={draft.answers[question.id]} />
          ))}
        </SectionCard>

        <SectionCard title="Documents" editPath="/start/documents">
          <div className="space-y-2">
            {draft.documents.map((document) => (
              <div key={document.id} className="flex items-center justify-between gap-3">
                <div>
                  <p className="text-sm text-foreground">{document.name}</p>
                  {document.file && (
                    <p className="text-xs text-muted-foreground">{formatFileSize(document.file.fileSizeBytes)}</p>
                  )}
                </div>
                <IntakeDocumentStatusBadge status={document.status} />
              </div>
            ))}
          </div>
        </SectionCard>

        <Card>
          <CardHeader>
            <CardTitle>Ready to submit?</CardTitle>
            <p className="text-sm text-muted-foreground">
              Review your information before sending your request to our team.
            </p>
          </CardHeader>
          <CardContent className="space-y-5">
            <label className="flex items-start gap-2.5 text-sm text-foreground">
              <Checkbox className="mt-0.5" checked={confirmed} onCheckedChange={(checked) => setConfirmed(checked === true)} />
              I confirm that the information I've provided is accurate.
            </label>

            <Button
              size="lg"
              className="w-full sm:w-auto sm:min-w-56"
              disabled={!confirmed}
              loading={isSubmitting}
              onClick={() => void handleSubmit()}
            >
              Submit Request
            </Button>
          </CardContent>
        </Card>
      </div>

      <Dialog open={Boolean(submittedRequest)} onOpenChange={() => undefined}>
        <DialogContent onInteractOutside={(event) => event.preventDefault()} onEscapeKeyDown={(event) => event.preventDefault()}>
          <DialogHeader>
            <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-success/10 text-success">
              <CheckCircle2 className="h-6 w-6" />
            </div>
            <DialogTitle className="text-center">You're all set.</DialogTitle>
            <DialogDescription className="text-center">
              Your request has been received. Our team will review the information and contact you with the next
              steps.
            </DialogDescription>
          </DialogHeader>
          {submittedRequest && (
            <div className="space-y-2 rounded-lg border bg-muted/30 p-4 text-center">
              <p className="text-xs text-muted-foreground">Request ID</p>
              <p className="text-sm font-semibold text-foreground">{submittedRequest.referenceNumber}</p>
              <p className="mt-2 text-xs text-muted-foreground">Current Status</p>
              <p className="text-sm font-semibold text-success">Received</p>
            </div>
          )}
          <Button className="mt-2" onClick={() => navigate(`/requests/${submittedRequest?.id}`, { replace: true })}>
            View Request Status
          </Button>
        </DialogContent>
      </Dialog>
    </div>
  )
}
