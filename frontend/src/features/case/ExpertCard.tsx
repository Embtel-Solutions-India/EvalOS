import { useState } from 'react'
import { useMe } from '../../lib/authContext'
import { mintPortalLink, type CaseDetail } from './caseApi'
import DocumentList from './DocumentList'

/**
 * Who is signing, where their signature stands, and the link that reaches them.
 *
 * Name and tier only. The rest of the roster record — quality score, response times,
 * payment detail — belongs to the expert database (Unit 11) and to the payout ledger
 * (Unit 16), and `payment_detail` is encrypted and never leaves its entity at all.
 *
 * **The link is how the expert is reached, and there is no other way (Unit 15).** No signature
 * provider sends anything and EvalOS sends no mail (invariant 14), so a Case Manager mints the
 * link here and passes it on. It is shown once: nothing reads a token back, and a lost link is
 * re-minted, which revokes the previous one.
 */

const SIGN_TONE: Record<string, { fg: string; bg: string; label: string }> = {
  PENDING: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'awaiting signature' },
  SIGNED: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'signed' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'overdue' },
  REASSIGNED: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)', label: 'reassigned' },
}

/** Who may put a link in front of an expert — the same list the server gates the mint with. */
const MAY_MINT = ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'CASE_MANAGER']

export default function ExpertCard({ detail }: { detail: CaseDetail }) {
  const me = useMe()
  const status = detail.summary.expertSignStatus
  const tone = status ? SIGN_TONE[status] : null
  const [link, setLink] = useState<string | null>(null)
  const [minting, setMinting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mint = async () => {
    setMinting(true)
    setError(null)
    try {
      const minted = await mintPortalLink(detail.summary.id, 'EXPERT')
      setLink(minted.url)
    } catch {
      setError('That link could not be minted. The case may have no expert on it yet.')
    } finally {
      setMinting(false)
    }
  }

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

          {/*
            The read receipt, and it is worth its line: "they have not opened it" and "they opened
            it and have not signed" are different problems with different next moves — one is a
            delivery failure, the other is a chase.
          */}
          <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            {detail.expertPortalReadAt
              ? `Opened their link ${new Date(detail.expertPortalReadAt).toLocaleDateString()}`
              : 'Has not opened their link yet'}
          </p>

          <h3 className="mt-3 text-xs font-semibold tracking-tight">Signed letter</h3>
          <DocumentList
            caseId={detail.summary.id}
            maySee={detail.maySeeCaseContent}
            kind="SIGNED_LETTER"
            emptyMessage="Nothing signed has come back yet."
          />

          {MAY_MINT.includes(me.role) && (
            <div className="mt-3">
              <button
                type="button"
                onClick={() => void mint()}
                disabled={minting}
                className="rounded-md border px-2 py-1 text-xs font-semibold"
                style={{ borderColor: 'var(--border-default)', color: 'var(--accent-primary)' }}
              >
                {link ? 'Re-send link' : 'Send link to expert'}
              </button>
              {link && (
                <>
                  {/*
                    Shown once and never stored. Re-minting revokes the previous token, so a second
                    click is the fix for "the link doesn't work" rather than a second live credential.
                  */}
                  <p className="mt-2 break-all text-xs" style={{ color: 'var(--text-primary)' }}>
                    {link}
                  </p>
                  <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                    Copy it now — it is shown once, and re-minting revokes it.
                  </p>
                </>
              )}
              {error && (
                <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
                  {error}
                </p>
              )}
            </div>
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
