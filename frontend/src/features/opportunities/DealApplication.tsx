import { useEffect, useState } from 'react'
import { fetchApplication, parseAnswers, type ClientApplication } from './opportunityApi'

/**
 * What the client actually asked for, on the deal (Unit 43 §6c).
 *
 * **Required by the flow, not an extra.** "Sales reviews the answers and contacts the client" is
 * a step in the middle of the funnel, and a review step with nowhere to read the thing being
 * reviewed does not exist. Without this panel the questionnaire would be written by the client,
 * stored by EvalOS and read by nobody.
 *
 * **It renders nothing at all for a deal that did not come from the portal**, which is most of
 * the board: a lead Marketing opened or a deal Sales phoned in has no application and never will.
 * An empty panel saying "no request" on every card would be noise on the common case.
 *
 * **Every label is the client portal's, stored with the answer.** This app is a separate build
 * and cannot import that catalog, and looking a question up by id would break the day somebody
 * rewords it — the wording shown here is the wording the client was asked.
 */
export default function DealApplication({ opportunityId }: { opportunityId: string }) {
  const [application, setApplication] = useState<ClientApplication | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchApplication(opportunityId, controller.signal)
      .then(setApplication)
      .catch((failure) => {
        // An aborted request is the effect cleaning up after itself, not a failure to report.
        if (!controller.signal.aborted) {
          setError(failure instanceof Error ? failure.message : 'Could not load the request')
        }
      })
    return () => controller.abort()
  }, [opportunityId])

  if (error) {
    return <p className="border-t border-slate-200 pt-2 text-xs text-rose-700">{error}</p>
  }
  // Null covers both "still loading" and "not a portal deal". Neither deserves a spinner on a
  // panel that is empty for most cards.
  if (!application) return null

  const answers = parseAnswers(application.answers)

  return (
    <section className="space-y-2 border-t border-slate-200 pt-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          Client&rsquo;s request
        </p>
        {/*
          The two-value status matters to a salesperson: an unfinished request means the client
          stopped mid-questionnaire, which is a reason to ring them rather than to wait.
        */}
        {application.status === 'DRAFT' && (
          <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-900">
            Still filling it in
          </span>
        )}
      </div>

      <p className="text-sm font-medium text-slate-900">{application.serviceName}</p>
      {application.purpose && (
        <p className="text-xs text-slate-500">For: {application.purpose.replace(/_/g, ' ')}</p>
      )}

      {answers.length === 0 ? (
        <p className="text-xs text-slate-500">No answers yet.</p>
      ) : (
        <dl className="space-y-1">
          {answers.map((answer) => (
            <div key={answer.id} className="grid gap-0.5 sm:grid-cols-[1fr_1.3fr] sm:gap-3">
              <dt className="text-xs text-slate-500">{answer.label}</dt>
              <dd className="text-xs text-slate-900">{answer.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  )
}
