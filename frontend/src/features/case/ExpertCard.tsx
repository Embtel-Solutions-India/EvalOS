import type { CaseDetail } from './caseApi'

/**
 * Who is signing, and where their signature stands.
 *
 * Name and tier only. The rest of the roster record — quality score, response times,
 * payment detail — belongs to the expert database (Unit 11) and to the payout ledger
 * (Unit 16), and `payment_detail` is encrypted and never leaves its entity at all.
 */

const SIGN_TONE: Record<string, { fg: string; bg: string; label: string }> = {
  PENDING: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'awaiting signature' },
  SIGNED: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'signed' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'overdue' },
  REASSIGNED: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)', label: 'reassigned' },
}

export default function ExpertCard({ detail }: { detail: CaseDetail }) {
  const status = detail.summary.expertSignStatus
  const tone = status ? SIGN_TONE[status] : null

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Expert</h2>

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
        </>
      ) : (
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          No expert assigned yet — that happens with the case manager.
        </p>
      )}
    </section>
  )
}
