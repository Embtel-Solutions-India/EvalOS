import type { CaseDetail } from './caseApi'
import DocumentList from './DocumentList'
import OfferFee from '../payouts/OfferFee'
import { UserCheck } from 'lucide-react'
import { Panel } from '../../components/ui/panel'
import { sentence, type Tone } from './caseUi'
import { StatusPill } from './StatusPill'

/**
 * Who is signing and where their signature stands.
 *
 * Name and tier only. The rest of the roster record — quality score, response times,
 * payment detail — belongs to the expert database (Unit 11) and to the payout ledger
 * (Unit 16), and `payment_detail` is encrypted and never leaves its entity at all.
 *
 * **There is no link to send (Unit 59, 2026-09-28).** An expert is on the roster, signs up, sets a
 * password and signs in; the case appears in their list.
 */

const SIGN: Record<string, { tone: Tone; label: string }> = {
  PENDING: { tone: 'pending', label: 'Awaiting signature' },
  SIGNED: { tone: 'done', label: 'Signed' },
  OVERDUE: { tone: 'blocked', label: 'Signature overdue' },
  REASSIGNED: { tone: 'idle', label: 'Reassigned' },
}

export default function ExpertCard({ detail }: { detail: CaseDetail }) {
  const status = detail.summary.expertSignStatus
  const sign = status ? SIGN[status] : null

  return (
    <Panel
      title="Expert & offer"
      icon={<UserCheck />}
      action={sign && <StatusPill tone={sign.tone}>{sign.label}</StatusPill>}
    >
      {detail.expertName ? (
        <>
          <p className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
            {detail.expertName}
          </p>
          <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
            {[detail.expertTier ? sentence(detail.expertTier) : null,
              // The read receipt: "not opened" is a delivery problem, "opened, not signed" is a chase.
              detail.expertPortalReadAt
                ? `Opened the case ${new Date(detail.expertPortalReadAt).toLocaleDateString()}`
                : 'Has not opened the case yet']
              .filter(Boolean)
              .join('. ')}
          </p>

          {/* Unit 65: what this case pays the expert, and whether they have agreed to it. */}
          <OfferFee caseId={detail.summary.id} />

          <h3 className="mt-5 text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
            Signed letter
          </h3>
          <DocumentList
            caseId={detail.summary.id}
            maySee={detail.maySeeCaseContent}
            kind="SIGNED_LETTER"
            emptyMessage="Nothing signed has come back yet."
          />

          {/* No link to send (Unit 59): the expert signs up and signs in with their roster email. */}
          <p className="mt-4 text-xs" style={{ color: 'var(--text-muted)' }}>
            The expert reaches this case by signing in to the expert portal with the email on their
            expert record.
          </p>
        </>
      ) : (
        // Unit 73: the expert is offered once a draft exists, by the PM, CM, ENM or GM.
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          No expert yet. One is offered once the first draft is in.
        </p>
      )}
    </Panel>
  )
}
