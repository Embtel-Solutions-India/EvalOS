import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Check } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { PageHeader } from '@shared/components/common/PageHeader'
import { RadioGroup, RadioGroupItem } from '@shared/components/ui/radio-group'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { NO_TOKEN } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { IntakeProgress } from '@/components/intake/IntakeProgress'
import RequestDocuments from '@/components/intake/RequestDocuments'
import { ServiceCategorySection } from '@/components/intake/ServiceCategorySection'
import { SERVICE_CATEGORIES, getService, getServicesByCategory } from '@/constants/serviceCatalog'
import { startApplication, submitApplication, type ClientApplication } from '@/services/applicationService'
import type { RequestPurpose } from '@/types/intake'

/**
 * Asking us for a service (Unit 43) — choose one, attach documents, send it.
 *
 * **Two steps, and no questionnaire** (Unit 55, 2026-09-25). The request is the service, the
 * purpose and the documents; everything else Sales asks on the call. Adding a service is an entry
 * in `serviceCatalog.ts` and nothing here changes.
 *
 * **Choosing a service opens the draft, submitting opens the deal** (D10). The draft is a server
 * row, so the documents have somewhere to attach before the request is sent.
 */

/** The generic "what is this for", asked only when the service does not already imply one. */
const PURPOSES: { value: RequestPurpose; label: string }[] = [
  { value: 'immigration', label: 'Immigration' },
  { value: 'employment', label: 'Employment' },
  { value: 'education', label: 'Education' },
  { value: 'university_admission', label: 'University admission' },
  { value: 'professional_licensing', label: 'Professional licensing' },
  { value: 'government', label: 'Government' },
  { value: 'other', label: 'Something else' },
]

type Step = 'service' | 'review'

const STEP_INDEX: Record<Step, number> = { service: 0, review: 1 }

export default function NewRequest() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const tokenPresent = usePortalToken()

  const [step, setStep] = useState<Step>('service')
  const [serviceId, setServiceId] = useState<string>()
  const [purpose, setPurpose] = useState<RequestPurpose>()
  const [application, setApplication] = useState<ClientApplication>()

  const service = getService(serviceId)
  const needsPurpose = Boolean(service && !service.impliedPurpose)

  const start = useMutation({
    mutationFn: () => startApplication(serviceId!, service!.name, purpose ?? service!.impliedPurpose),
    onSuccess: (started) => {
      // A draft the client left earlier comes back rather than a second opening: the server
      // returns the one in progress.
      setApplication(started)
      if (started.serviceId !== serviceId) {
        setServiceId(started.serviceId)
        toast.info(`Carrying on with your ${started.serviceName} request.`)
      }
      setStep('review')
    },
    onError: (error) => toast.error(failed(error, 'We could not start your request.')),
  })

  const send = useMutation({
    mutationFn: () => submitApplication(application!.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['portal', 'applications'] })
      toast.success("Sent. We'll be in touch shortly.")
      navigate('/requests')
    },
    onError: (error) => toast.error(failed(error, 'We could not send your request.')),
  })

  if (!tokenPresent) {
    return <PageHeader title="Request a service" description={NO_TOKEN} />
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <PageHeader
        title="Request a service"
        description="Tell us what you need and send any documents you have. We'll price it and come back to you."
      />
      <IntakeProgress currentIndex={STEP_INDEX[step]} />

      {step === 'service' && (
        <div className="space-y-8">
          {SERVICE_CATEGORIES.map((category) => (
            <ServiceCategorySection
              key={category.id}
              category={category}
              services={getServicesByCategory(category.id)}
              selectedServiceId={serviceId}
              onSelect={setServiceId}
            />
          ))}

          {needsPurpose && (
            <Card className="space-y-3 p-5">
              <h2 className="text-base font-semibold text-foreground">What are you using this for?</h2>
              <RadioGroup
                value={purpose ?? ''}
                onValueChange={(value) => setPurpose(value as RequestPurpose)}
                className="grid gap-2 sm:grid-cols-2"
              >
                {PURPOSES.map((option) => (
                  <label
                    key={option.value}
                    className="flex cursor-pointer items-center gap-2 rounded-md border border-border p-3 text-sm hover:bg-accent"
                  >
                    <RadioGroupItem value={option.value} />
                    {option.label}
                  </label>
                ))}
              </RadioGroup>
            </Card>
          )}

          <Button
            className="w-full"
            disabled={!serviceId || (needsPurpose && !purpose)}
            loading={start.isPending}
            onClick={() => start.mutate()}
          >
            Continue
            <ArrowRight className="ml-2 h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      )}

      {step === 'review' && service && application && (
        <div className="space-y-6">
          <Card className="space-y-4 p-5">
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                What you're asking for
              </p>
              <p className="mt-1 text-sm font-semibold text-foreground">{service.name}</p>
              <p className="text-sm text-muted-foreground">{service.shortDescription}</p>
            </div>
          </Card>

          {/*
            **The DOCUMENT SUBMISSION step** (Unit 53, D33). It sits on review rather than as a
            step of its own: the client has just read back what they are asking for, and the
            transcript that evidences it belongs in the same glance. Removal is offered while
            the request is still a draft, which on this screen it always is — `send` navigates away.
          */}
          <RequestDocuments applicationId={application.id} canRemove />

          <p className="text-sm text-muted-foreground">
            {/*
              This said "you can send us your documents once your case is open", which was true
              while there was nowhere to put them and is not any more. It is edited rather than
              left beside the uploader contradicting it.
            */}
            Sending this doesn't commit you to anything. We'll read it, price the work and come
            back to you.
          </p>

          <div className="flex gap-3">
            <Button variant="outline" onClick={() => setStep('service')}>
              <ArrowLeft className="mr-2 h-4 w-4" aria-hidden="true" />
              Back
            </Button>
            <Button className="flex-1" loading={send.isPending} onClick={() => send.mutate()}>
              <Check className="mr-2 h-4 w-4" aria-hidden="true" />
              Send my request
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * A 502 here means GHL refused or is unreachable, and the server deliberately does not pretend a
 * request was sent when nobody can see it. Retrying is the right advice; "something went wrong"
 * is not.
 */
function failed(error: unknown, fallback: string): string {
  return statusOf(error) === 502
    ? "We couldn't reach our systems just now. Your request is saved — please try again in a few minutes."
    : fallback
}
