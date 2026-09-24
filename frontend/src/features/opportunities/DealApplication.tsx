import { useEffect, useState } from 'react'
import { FileText } from 'lucide-react'

import { Panel } from '../../components/ui/panel'
import { fetchApplication, type ClientApplication } from './opportunityApi'

/**
 * What the client asked for in their portal, on the deal (Unit 43 §6c).
 *
 * **The request, not a questionnaire.** There is no questionnaire since Unit 55 (2026-09-25): the
 * request is the service, the purpose and the documents (`DealDocuments`, beside this), and Sales
 * asks everything else on the call.
 *
 * **It renders nothing at all for a deal that did not come from the portal**, which is most of
 * the board: a lead Marketing opened or a deal Sales phoned in has no request and never will. An
 * empty panel saying "no request" on every card would be noise on the common case.
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
    return (
      <p className="text-xs" style={{ color: 'var(--status-red)' }}>
        {error}
      </p>
    )
  }
  // Null covers both "still loading" and "not a portal deal". Neither deserves a spinner on a
  // panel that is empty for most cards.
  if (!application) return null

  const submitted = application.status === 'SUBMITTED'

  return (
    <Panel title="Portal request" icon={<FileText />}>
      <table className="tbl">
        <thead>
          <tr>
            <th>Service</th>
            <th>Submitted on</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          <tr>
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
              {/* An unsent request means the client stopped before sending, which is a reason to
                  ring them rather than to wait. The accent chip marks the one that needs an
                  action. */}
              <span className={submitted ? 'chip' : 'chip chip-accent'}>
                {submitted ? 'Submitted' : 'Not sent yet'}
              </span>
            </td>
          </tr>
        </tbody>
      </table>

      <p
        className="mt-3 border-t pt-3 text-xs"
        style={{ borderColor: 'var(--border-default)', color: 'var(--text-muted)' }}
      >
        Sent by the client from their portal. Read-only here.
      </p>
    </Panel>
  )
}
