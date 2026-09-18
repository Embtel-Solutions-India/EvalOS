import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Check } from 'lucide-react'
import { useMemo, useState } from 'react'
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
import { QuestionField } from '@/components/intake/QuestionField'
import { ServiceCategorySection } from '@/components/intake/ServiceCategorySection'
import { SERVICE_CATEGORIES, getService, getServicesByCategory } from '@/constants/serviceCatalog'
import { getVisibleGroups, getVisibleQuestions, isGroupComplete } from '@/lib/questionnaire'
import {
  parseAnswers,
  saveApplication,
  startApplication,
  submitApplication,
  type ClientApplication,
} from '@/services/applicationService'
import type { RequestAnswers, RequestPurpose } from '@/types/intake'

/**
 * Asking us for a service (Unit 43) — choose one, answer what it needs, send it.
 *
 * **One screen with three steps, not seven routes.** The funnel deleted in `f9f1165` was a
 * public, pre-account journey, so each step needed its own URL to be linkable and resumable
 * across a sign-up. The client is signed in before this screen opens now, and resumption is a
 * server row rather than a browser, so the routes bought nothing and cost a route table, seven
 * files and a redirect guard on each of them.
 *
 * **The questionnaire is conditional and no part of this file knows how.** `getVisibleGroups`
 * and `getVisibleQuestions` answer "what should this person be asked, given what they have said
 * so far" from the catalog. Adding a service is an entry in `serviceCatalog.ts`; nothing here
 * changes, and there is no per-service component anywhere.
 *
 * **Documents are deliberately not a step**, which reverses `43` §5 and is recorded there. There
 * is nowhere to put a file before a case exists: every upload route EvalOS has takes a checklist
 * item on a case. Sales chases the documents after the call, which §5 already accepts as the
 * posture ("a missing document is a thing Sales chases, not a wall the funnel puts in front of a
 * lead"), and the client's Documents screen is where they land once there is a case.
 *
 * **Picking the service is what reaches Sales**, not submitting. The server opens the GHL
 * opportunity on `startApplication`, so a client who abandons the questionnaire — the longest
 * part, and therefore where people stop — is still somebody a salesperson can ring. Where that
 * opportunity then sits and who picks it up are GHL's automation's business, not this app's.
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

type Step = 'service' | 'questions' | 'review'

const STEP_INDEX: Record<Step, number> = { service: 0, questions: 1, review: 2 }

export default function NewRequest() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const tokenPresent = usePortalToken()

  const [step, setStep] = useState<Step>('service')
  const [serviceId, setServiceId] = useState<string>()
  const [purpose, setPurpose] = useState<RequestPurpose>()
  const [answers, setAnswers] = useState<RequestAnswers>({})
  const [application, setApplication] = useState<ClientApplication>()

  const service = getService(serviceId)
  // Recomputed on every answer, which is the point: answering "yes, I'm working with an attorney"
  // is what makes the attorney group appear.
  const groups = useMemo(() => getVisibleGroups(serviceId, answers), [serviceId, answers])
  const needsPurpose = Boolean(service && !service.impliedPurpose)
  const incomplete = groups.filter((group) => !isGroupComplete(group, answers))

  const start = useMutation({
    mutationFn: () => startApplication(serviceId!, service!.name, purpose ?? service!.impliedPurpose),
    onSuccess: (started) => {
      setApplication(started)
      // A draft the client abandoned earlier comes back with its answers, because the server
      // returns the one in progress rather than opening a second.
      setAnswers(parseAnswers(started.answers))
      if (started.serviceId !== serviceId) {
        setServiceId(started.serviceId)
        toast.info(`Carrying on with your ${started.serviceName} request.`)
      }
      setStep('questions')
    },
    onError: (error) => toast.error(failed(error, 'We could not start your request.')),
  })

  // **Labelled on the way out, flat on the way in.** The staff app has no catalog, so an answer
  // travels with the question it answered; the form and the conditional engine want the flat map.
  const labelled = () =>
    groups.flatMap((group) =>
      getVisibleQuestions(group, answers)
        .filter((question) => answers[question.id]?.trim())
        .map((question) => ({ id: question.id, label: question.label, value: answers[question.id] })),
    )

  const save = useMutation({
    mutationFn: () => saveApplication(application!.id, labelled(), purpose),
    onError: (error) => toast.error(failed(error, 'We could not save your answers.')),
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
        description="Tell us what you need and a little about it. We'll price it and come back to you."
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

      {step === 'questions' && service && (
        <div className="space-y-6">
          {groups.map((group) => (
            <Card key={group.id} className="space-y-4 p-5">
              <div>
                <h2 className="text-base font-semibold text-foreground">{group.title}</h2>
                <p className="mt-1 text-sm text-muted-foreground">{group.description}</p>
              </div>
              {getVisibleQuestions(group, answers).map((question) => (
                <QuestionField
                  key={question.id}
                  question={question}
                  value={answers[question.id] ?? ''}
                  onChange={(value) => setAnswers((current) => ({ ...current, [question.id]: value }))}
                />
              ))}
            </Card>
          ))}

          <div className="flex gap-3">
            <Button variant="outline" onClick={() => setStep('service')}>
              <ArrowLeft className="mr-2 h-4 w-4" aria-hidden="true" />
              Back
            </Button>
            <Button
              className="flex-1"
              loading={save.isPending}
              // **Saved before the step changes, not after.** Review reads from this component's
              // state either way; saving here is what makes closing the tab on the review screen
              // keep the answers rather than lose them.
              onClick={() => save.mutateAsync().then(() => setStep('review'))}
            >
              Review
              <ArrowRight className="ml-2 h-4 w-4" aria-hidden="true" />
            </Button>
          </div>
          {incomplete.length > 0 && (
            <p className="text-sm text-muted-foreground">
              You can send this with gaps — we'll ask about anything missing when we call. Still
              open: {incomplete.map((group) => group.title).join(', ')}.
            </p>
          )}
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

            {groups.map((group) => {
              const asked = getVisibleQuestions(group, answers).filter((q) => answers[q.id]?.trim())
              if (asked.length === 0) return null
              return (
                <div key={group.id} className="border-t border-border pt-4">
                  <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    {group.title}
                  </p>
                  <dl className="mt-2 space-y-2">
                    {asked.map((question) => (
                      <div key={question.id} className="grid gap-0.5 sm:grid-cols-[1fr_1.4fr] sm:gap-4">
                        <dt className="text-sm text-muted-foreground">{question.label}</dt>
                        <dd className="text-sm text-foreground">{answers[question.id]}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              )
            })}
          </Card>

          {/*
            **The DOCUMENT SUBMISSION step, here at last** (Unit 53, D33). It sits on review rather
            than as a fourth wizard step: the client has just read back what they are sending, and
            the transcript that evidences it belongs in the same glance. Removal is offered while
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
            <Button variant="outline" onClick={() => setStep('questions')}>
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
    ? "We couldn't reach our systems just now. Your answers are saved — please try again in a few minutes."
    : fallback
}
