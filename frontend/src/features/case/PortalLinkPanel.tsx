import { useCallback, useEffect, useState } from 'react'
import type { Role } from '../../lib/session'
import { fetchPortalLink, mintPortalLink, type CaseDetail, type MintedLink, type PortalLinkStatus } from './caseApi'
import { mayMintPortalLink } from '../client-portal/portalRules'

/**
 * The staff side of the client's draft-review link (Unit 14).
 *
 * **The link is shown exactly once, here, right after it is minted.** Nothing reads it back — there
 * is no endpoint that returns the URL of an existing link, because the server stores only its hash.
 * Losing it means minting a new one, which revokes the old one immediately; the panel says so
 * before the second press rather than after.
 *
 * This is also the stopgap the spec names: until GHL can send a client-facing message on an EvalOS
 * event (open question (b), still open), somebody copies this URL to the client by hand. Unit 18
 * dispatches it on `draft.ready_for_client` if the answer turns out to be yes, and nothing here
 * changes when it does.
 */
export default function PortalLinkPanel({ detail, role }: { detail: CaseDetail; role: Role }) {
  const caseId = detail.summary.id
  const [status, setStatus] = useState<PortalLinkStatus | null>(null)
  const [minted, setMinted] = useState<MintedLink | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  const mayMint = mayMintPortalLink(role)

  useEffect(() => {
    if (!mayMint) return
    const controller = new AbortController()
    fetchPortalLink(caseId, controller.signal)
      .then(setStatus)
      .catch(() => {
        if (!controller.signal.aborted) setFailure('Could not read the link status. Nothing was changed.')
      })
    return () => controller.abort()
  }, [caseId, mayMint])

  const mint = useCallback(async () => {
    setBusy(true)
    setFailure(null)
    setConfirming(false)
    try {
      const link = await mintPortalLink(caseId)
      setMinted(link)
      setCopied(false)
      setStatus({ live: true, expiresAt: link.expiresAt, openedAt: null })
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : 'The link could not be created')
    } finally {
      setBusy(false)
    }
  }, [caseId])

  // Not gated on `mayMint` alone by accident: a role that cannot mint has nothing to read either,
  // since the GET carries the same gate. Drawing an empty panel for them would say the feature is
  // broken rather than not theirs.
  if (!mayMint) return null

  const hasLive = status?.live === true

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">Client portal link</h2>
        <span
          className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
          style={
            hasLive
              ? { color: 'var(--status-green)', background: 'var(--status-green-bg)' }
              : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
          }
        >
          {status === null ? 'checking…' : hasLive ? 'live' : 'no live link'}
        </span>
      </div>

      <dl className="mt-3 space-y-2">
        <Row label="Expires">
          {status?.expiresAt ? <When at={status.expiresAt} /> : '—'}
        </Row>
        {/*
          "Last opened", not "opened": the value is the token's own last-seen and moves on every
          visit. Labelling it "Opened" would read as first-contact, which is the case's separate
          `client_portal_read_at` and is not shown here.
        */}
        <Row label="Last opened by the client">
          {/*
            "Never" is the answer that actually changes what a case manager does next, so it is
            drawn as a state rather than a dash.
          */}
          {status?.openedAt ? <When at={status.openedAt} /> : <span>never</span>}
        </Row>
      </dl>

      {minted && (
        <div
          className="mt-3 rounded-md border p-3"
          style={{ borderColor: 'var(--status-amber)', background: 'var(--status-amber-bg)' }}
        >
          <p className="text-xs font-semibold" style={{ color: 'var(--status-amber)' }}>
            Copy this now — it is shown once and cannot be retrieved.
          </p>
          <code
            className="font-mono mt-2 block break-all rounded px-2 py-1.5 text-xs"
            style={{ background: 'var(--bg-base)' }}
          >
            {minted.url}
          </code>
          <button
            type="button"
            onClick={() => {
              void navigator.clipboard?.writeText(minted.url).then(() => setCopied(true))
            }}
            className="mt-2 rounded-md px-2.5 py-1 text-xs font-semibold"
            style={{ background: 'var(--accent-primary)', color: '#fff' }}
          >
            {copied ? 'Copied' : 'Copy the link'}
          </button>
        </div>
      )}

      {confirming ? (
        <div className="mt-3">
          <p className="text-sm">
            {hasLive
              ? 'Create a new link? The one the client already has will stop working immediately.'
              : 'Create a link for this client?'}
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={() => void mint()}
              disabled={busy}
              className="rounded-md px-2.5 py-1.5 text-xs font-semibold disabled:opacity-50"
              style={{ background: 'var(--accent-primary)', color: '#fff' }}
            >
              {busy ? 'Creating…' : hasLive ? 'Yes, replace it' : 'Create the link'}
            </button>
            <button
              type="button"
              onClick={() => setConfirming(false)}
              className="rounded-md px-2.5 py-1.5 text-xs font-medium"
              style={{ background: 'var(--bg-raised)' }}
            >
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          disabled={busy}
          className="mt-3 rounded-md border px-2.5 py-1.5 text-xs font-semibold disabled:opacity-50"
          style={{ borderColor: 'var(--border-default)', color: 'var(--text-primary)' }}
        >
          {hasLive ? 'Replace the link' : 'Create a link'}
        </button>
      )}

      <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>
        Send it to the client yourself for now — EvalOS does not email, and whether GHL can deliver
        it on an event is still being confirmed.
      </p>

      {failure && (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
          {failure}
        </p>
      )}
    </section>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className="text-sm" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd className="font-num text-xs tabular-nums">{children}</dd>
    </div>
  )
}

function When({ at }: { at: string }) {
  return (
    <time dateTime={at}>{new Date(at).toLocaleString()}</time>
  )
}
