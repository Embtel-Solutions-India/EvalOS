import { Link, useParams } from 'react-router-dom'

import { useMe } from '../../lib/authContext'
import { useMetrics } from '../dashboards/useMetrics'
import DealActions from './DealActions'
import DealApplication from './DealApplication'
import DealNotes from './DealNotes'
import { fetchDealContact, fetchOpportunityBoard, type DealContact } from './opportunityApi'

/**
 * One opportunity, on its own screen.
 *
 * <p><strong>Clicking a card opens this</strong> (2026-09-17). The card used to expand in place,
 * which meant the answers, the notes and the actions all appeared inside a 16rem column between
 * two other cards — readable for a note, useless for a questionnaire.
 *
 * <p><strong>What is here, and what is not.</strong> The contact and the submitted questionnaire
 * are read from EvalOS's own mirror. <strong>Documents are not shown, because they do not exist
 * yet</strong>: request-stage documents are Unit 53 (D33), specced and unbuilt, and a panel that
 * said "no documents" would be indistinguishable from a client who sent none. The section appears
 * when the table does.
 */
export default function DealPage() {
  const { opportunityId = '' } = useParams()
  const role = useMe().role

  const { data: contact, state: contactState } = useMetrics<DealContact | null>(
    (signal) => fetchDealContact(opportunityId, signal),
    [opportunityId],
  )
  // The board is read for its stage list, which the actions need to offer a move. Same payload the
  // board itself draws from, so the two cannot disagree about which stages are "mine".
  const { data: board } = useMetrics((signal) => fetchOpportunityBoard(signal), [opportunityId])
  const stages = (board?.columns ?? []).map((c) => ({ stageId: c.stageId, stageName: c.stageName }))
  const deal = board?.columns.flatMap((c) => c.deals).find((d) => d.opportunityId === opportunityId)

  return (
    <section className="max-w-4xl space-y-4">
      <header className="space-y-1">
        <Link to="/opportunities/board" className="text-sm text-blue-600 hover:underline">
          ← My pipeline
        </Link>
        <h1 className="text-xl font-semibold text-slate-900">{deal?.name ?? 'Opportunity'}</h1>
        <p className="text-sm text-slate-500">
          In GoHighLevel. EvalOS shows it; GHL owns it.
        </p>
      </header>

      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-medium text-slate-900">Contact</h2>
        {contactState.kind === 'loading' && (
          <p className="mt-2 text-sm text-slate-500">Loading…</p>
        )}
        {contactState.kind !== 'loading' && !contact && (
          <p className="mt-2 text-sm text-slate-500">
            {/* Honest about which of the two it is: the deal may predate the contact sync rather
                than have no contact at all. */}
            No contact on this deal yet — it arrives with the next sync.
          </p>
        )}
        {contact && (
          <dl className="mt-2 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
            <Field label="Name" value={contact.name} />
            <Field label="Email" value={contact.email} />
            <Field label="Phone" value={contact.phone} />
            <Field label="Company" value={contact.company} />
          </dl>
        )}
      </div>

      {/* The questionnaire the client submitted. Renders nothing for a deal that did not come
          through the portal, which is most of them today. */}
      <DealApplication opportunityId={opportunityId} />

      {role === 'SALES' && deal && (
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <h2 className="mb-2 text-sm font-medium text-slate-900">Actions</h2>
          <DealActions
            opportunityId={opportunityId}
            contactId={deal.contactId}
            stages={stages}
            onChanged={() => window.location.reload()}
          />
        </div>
      )}

      <DealNotes opportunityId={opportunityId} />
    </section>
  )
}

function Field({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      {/* An em dash rather than an empty cell: "we do not hold this" is a fact, and a blank line
          reads as a rendering bug. */}
      <dd className="text-slate-900">{value?.trim() ? value : '—'}</dd>
    </div>
  )
}
