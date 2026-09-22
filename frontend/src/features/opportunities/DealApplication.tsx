import { useEffect, useState } from 'react'
import { ChevronDown, ChevronRight, FileText } from 'lucide-react'

import { Panel } from '../../components/ui/panel'
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
 * **A table of one row, and it stays a table.** The design this follows showed several
 * questionnaires per deal — a "Client Intake Questionnaire" and an "Additional Information Form".
 * There is one `client_application` per opportunity and no second form exists anywhere in the
 * portal, so inventing a row would be a screen promising a document nobody can submit. The table
 * shape is kept because it is the right shape for what this *is* — a submission with a status and
 * a date — and because a second form would land in it without a rewrite.
 *
 * **Every label is the client portal's, stored with the answer.** This app is a separate build
 * and cannot import that catalog, and looking a question up by id would break the day somebody
 * rewords it — the wording shown here is the wording the client was asked.
 */
export default function DealApplication({ opportunityId }: { opportunityId: string }) {
  const [application, setApplication] = useState<ClientApplication | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState(false)

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
    return (
      <p className="text-xs" style={{ color: 'var(--status-red)' }}>
        {error}
      </p>
    )
  }
  // Null covers both "still loading" and "not a portal deal". Neither deserves a spinner on a
  // panel that is empty for most cards.
  if (!application) return null

  const answers = parseAnswers(application.answers)
  const submitted = application.status === 'SUBMITTED'

  return (
    <Panel title="Submitted questionnaires" icon={<FileText />}>
      <table className="tbl">
        <thead>
          <tr>
            <th className="num">#</th>
            <th>Questionnaire</th>
            <th>Submitted on</th>
            <th>Status</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td className="num">1</td>
            <td className="font-medium">
              {application.serviceName}
              {application.purpose && (
                <span className="block text-xs font-normal" style={{ color: 'var(--text-muted)' }}>
                  For: {application.purpose.replace(/_/g, ' ')}
                </span>
              )}
            </td>
            <td className="whitespace-nowrap" style={{ color: 'var(--text-muted)' }}>
              {/* An em dash while it is still a draft: there is no submission date yet, and
                  showing the created date under a "Submitted on" heading would be a wrong
                  answer that looks right. */}
              {application.submittedAt
                ? new Date(application.submittedAt).toLocaleDateString()
                : '—'}
            </td>
            <td className="whitespace-nowrap">
              {/*
                The two-value status matters to a salesperson: an unfinished request means the
                client stopped mid-questionnaire, which is a reason to ring them rather than to
                wait. The accent chip marks the one that needs an action.
              */}
              <span className={submitted ? 'chip' : 'chip chip-accent'}>
                {submitted ? 'Submitted' : 'Still filling it in'}
              </span>
            </td>
            <td className="text-right">
              <button
                type="button"
                onClick={() => setOpen((was) => !was)}
                className="inline-flex items-center gap-1 font-medium"
                style={{ color: 'var(--accent-primary)' }}
                aria-expanded={open}
              >
                {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                {open ? 'Hide' : 'View'}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      {/* Where it came from, said once. The client fills this in their own portal and Sales
          reads it here; there is no path from this screen back into the answers, by design —
          the questionnaire is the client's statement of what they want, and a desk editing it
          would make it EvalOS's. */}
      <p
        className="mt-3 border-t pt-3 text-xs"
        style={{ borderColor: 'var(--border-default)', color: 'var(--text-muted)' }}
      >
        Submitted by the client in their portal. Read-only here.
      </p>

      {/* The answers themselves, in place rather than behind a dialog. This panel exists because
          Unit 43 §6c's review step needs somewhere to READ the thing being reviewed; a modal would
          put the answers and the documents that evidence them on two different screens. */}
      {open && (
        <div
          className="mt-3 rounded-md p-4"
          style={{ background: 'var(--bg-raised)' }}
        >
          {answers.length === 0 ? (
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              No answers yet.
            </p>
          ) : (
            <dl className="space-y-2">
              {answers.map((answer) => (
                <div key={answer.id} className="grid gap-0.5 sm:grid-cols-[1fr_1.3fr] sm:gap-4">
                  <dt className="text-xs" style={{ color: 'var(--text-muted)' }}>
                    {answer.label}
                  </dt>
                  <dd className="text-xs">{answer.value}</dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      )}
    </Panel>
  )
}
