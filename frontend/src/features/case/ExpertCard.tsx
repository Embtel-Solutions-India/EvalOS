import type { CaseDetail } from './caseApi'
import DocumentList from './DocumentList'
import OfferFee from '../payouts/OfferFee'

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

const SIGN_TONE: Record<string, { fg: string; bg: string; label: string }> = {
  PENDING: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'awaiting signature' },
  SIGNED: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'signed' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'overdue' },
  REASSIGNED: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)', label: 'reassigned' },
}

export default function ExpertCard({ detail, reloadKey }: { detail: CaseDetail; reloadKey: number }) {
  const status = detail.summary.expertSignStatus
  const tone = status ? SIGN_TONE[status] : null

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Expert &amp; offer</h2>

      {detail.expertName ? (
        <>
          <p className="mt-2 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
            {detail.expertName}
          </p>
          {detail.expertTier && (
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              {detail.expertTier.replace('_', ' ').toLowerCase()}
            </p>
          )}
          {tone && (
            <span
              className="mt-2 inline-block rounded-md px-1.5 py-0.5 text-xs font-semibold"
              style={{ color: tone.fg, background: tone.bg }}
            >
              {tone.label}
            </span>
          )}

          {/* Unit 65: what this case pays the expert, and whether they have agreed to it. */}
          <OfferFee caseId={detail.summary.id} reloadKey={reloadKey} />

          {/*
            The read receipt, and it is worth its line: "they have not opened it" and "they opened
            it and have not signed" are different problems with different next moves — one is a
            delivery failure, the other is a chase.
          */}
          <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            {detail.expertPortalReadAt
              ? `Opened the case ${new Date(detail.expertPortalReadAt).toLocaleDateString()}`
              : 'Has not opened the case yet'}
          </p>

          <h3 className="mt-3 text-xs font-semibold tracking-tight">Signed letter</h3>
          <DocumentList
            caseId={detail.summary.id}
            maySee={detail.maySeeCaseContent}
            kind="SIGNED_LETTER"
            emptyMessage="Nothing signed has come back yet."
          />

          {/* No link to send (Unit 59): the expert signs up and signs in with their roster email. */}
          <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>
            The expert reaches this case by signing in to the expert portal with the email on their
            expert record.
          </p>
        </>
      ) : (
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          No expert assigned yet — that happens with the case manager.
        </p>
      )}
    </section>
  )
}
