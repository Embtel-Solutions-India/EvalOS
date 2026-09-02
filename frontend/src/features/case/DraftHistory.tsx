import { useEffect, useState } from 'react'
import { fetchDraftVersions, type DraftVersion } from './caseApi'

/**
 * Every version of the draft, newest first, with the PM's ruling and comment on each.
 *
 * **This is the draft status board (Unit 32), and it lives on the case rather than in `/drafts`.**
 * That queue answers "what is waiting for me" and would stop being workable if it also listed
 * finished work; this answers "what happened to this letter", which is asked by somebody already
 * looking at the letter.
 *
 * **The comment belongs to the version, not to the case.** It is stamped on the row by the
 * transition that ruled on it, so "V2 was returned because…" cannot drift onto V3 — which is what
 * rendering this from the audit trail by timestamp would eventually do.
 */

const STATUS_TONE: Record<string, { label: string; color: string }> = {
  SUBMITTED: { label: 'Awaiting review', color: 'var(--status-amber)' },
  RETURNED: { label: 'Returned', color: 'var(--status-red)' },
  PM_APPROVED: { label: 'PM approved', color: 'var(--status-green)' },
  CLIENT_APPROVED: { label: 'Client approved', color: 'var(--status-green)' },
  SIGNED: { label: 'Signed', color: 'var(--status-green)' },
  SUPERSEDED: { label: 'Superseded', color: 'var(--text-muted)' },
}

export default function DraftHistory({ caseId }: { caseId: string }) {
  const [state, setState] = useState<
    { status: 'loading' } | { status: 'ready'; versions: DraftVersion[] } | { status: 'failed' }
  >({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchDraftVersions(caseId, controller.signal)
      .then((versions) => setState({ status: 'ready', versions }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: 'failed' })
      })
    return () => controller.abort()
  }, [caseId])

  if (state.status === 'loading') return null

  if (state.status === 'failed') {
    return (
      <p className="text-sm" style={{ color: 'var(--status-red)' }}>
        Could not load the draft history.
      </p>
    )
  }

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Draft history</h2>

      {state.versions.length === 0 ?
        // Operational copy, never "No data" — an empty history is a statement about the case.
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          No draft has been submitted yet.
        </p>
      : <ol className="mt-3 flex flex-col gap-3">
          {state.versions.map((version) => {
            const tone = STATUS_TONE[version.status] ?? {
              label: version.status,
              color: 'var(--text-muted)',
            }
            return (
              <li key={version.id} className="flex flex-col gap-1">
                <p className="flex flex-wrap items-baseline gap-2 text-sm">
                  <span className="font-num font-semibold tabular-nums">V{version.version}</span>
                  {/* Status is never carried by colour alone. */}
                  <span className="text-xs font-medium" style={{ color: tone.color }}>
                    {tone.label}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                    {version.uploadedByName ?? 'Unknown author'} ·{' '}
                    {new Date(version.uploadedAt).toLocaleString()}
                  </span>
                </p>
                {version.reviewComment && (
                  <p
                    className="rounded-md border-l-2 py-1 pl-3 text-sm"
                    style={{ borderColor: tone.color, color: 'var(--text-primary)' }}
                  >
                    {version.reviewComment}
                  </p>
                )}
              </li>
            )
          })}
        </ol>
      }
    </section>
  )
}
